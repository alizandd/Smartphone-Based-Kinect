package dev.motionhub.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Bluetooth fallback link to the phone sensor, for networks where Wi-Fi traffic between the TV
 * and the phone is blocked (client isolation).
 *
 * The web receiver keeps speaking WebSocket: it connects to ws://127.0.0.1:[LOCAL_PORT], and every
 * such local connection is piped byte-for-byte over a new insecure RFCOMM connection to the
 * phone, whose BtBridge pipes it into the phone's WebSocket server. No pairing PIN.
 *
 * Finding the phone: the last working address (remembered), then paired phones, then Bluetooth
 * discovery (the phone must be visible for that first time: "Pair TV (Bluetooth)" on the phone).
 */
@SuppressLint("MissingPermission")
class BtLink(
    private val ctx: Context,
    private val onReady: (deviceName: String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var address: String? = prefs.getString("bt_phone", null)
    @Volatile private var running = false
    @Volatile private var ready = false
    private var localServer: ServerSocket? = null
    private val tried = HashSet<String>()

    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val major = d.bluetoothClass?.majorDeviceClass
                    if (!ready && major == BluetoothClass.Device.Major.PHONE && tried.add(d.address)) worker.execute { tryDevice(d) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (running && !ready) worker.execute { discover() }
            }
        }
    }

    fun start() {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) { onStatus("Bluetooth is off on the TV"); return }
        running = true
        startLocalServer()
        ctx.registerReceiver(foundReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        worker.execute { find() }
    }

    fun stop() {
        running = false
        runCatching { ctx.unregisterReceiver(foundReceiver) }
        runCatching { adapter?.cancelDiscovery() }
        runCatching { localServer?.close() }
        worker.shutdownNow()
    }

    private fun find() {
        val a = adapter ?: return
        address?.let { addr ->
            onStatus("Connecting to the phone over Bluetooth…")
            if (tryDevice(a.getRemoteDevice(addr))) return
        }
        for (d in a.bondedDevices.orEmpty()) {
            if (ready) return
            if (d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE && tried.add(d.address)) tryDevice(d)
        }
        if (!ready) discover()
    }

    private fun discover() {
        if (!running || ready) return
        onStatus("Searching for the phone over Bluetooth… On the phone tap \"Pair TV (Bluetooth)\" once.")
        tried.clear()
        adapter?.startDiscovery()
    }

    /** Try an RFCOMM connection to our service; remember the device on success. */
    private fun tryDevice(d: BluetoothDevice): Boolean {
        if (ready || !running) return ready
        adapter?.cancelDiscovery()
        val s = connect(d) ?: run { if (running && !ready && adapter?.isDiscovering == false) discover(); return false }
        runCatching { s.close() }
        address = d.address
        prefs.edit().putString("bt_phone", d.address).apply()
        ready = true
        val name = runCatching { d.name }.getOrNull() ?: d.address
        Log.i(TAG, "phone reachable over Bluetooth: $name (${d.address})")
        onReady(name)
        return true
    }

    private fun connect(d: BluetoothDevice): BluetoothSocket? = runCatching {
        d.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() }
    }.onFailure { Log.d(TAG, "no MotionHub service on ${d.address}: ${it.message}") }.getOrNull()

    /** Local endpoint for the WebView: one RFCOMM connection per WebSocket connection. */
    private fun startLocalServer() {
        val server = ServerSocket(LOCAL_PORT, 4, InetAddress.getByName("127.0.0.1")).also { localServer = it }
        Thread({
            while (running) {
                val local = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ session(local) }, "bt-session").start()
            }
        }, "bt-local-accept").start()
    }

    private fun session(local: Socket) {
        val addr = address
        val bt = addr?.let { adapter?.getRemoteDevice(it) }?.let { connect(it) }
        if (bt == null) { runCatching { local.close() }; if (ready) { ready = false; worker.execute { find() } }; return }
        local.tcpNoDelay = true
        val closeBoth = { runCatching { bt.close() }; runCatching { local.close() }; Unit }
        val up = Thread({ copy(local.getInputStream(), bt.outputStream); closeBoth() }, "bt-up")
        up.start()
        copy(bt.inputStream, local.getOutputStream())
        closeBoth()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "MotionHubBT"
        const val LOCAL_PORT = 47811
        /** Must match the phone (BtBridge.SERVICE_UUID). */
        val SERVICE_UUID: UUID = UUID.fromString("6f1d2c3a-8b7e-4f0a-9c55-4d6f6d48b001")
    }
}
