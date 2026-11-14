package dev.motionhub.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID

/**
 * Bluetooth fallback for networks where the TV cannot reach the phone over Wi-Fi (e.g. Wi-Fi
 * client isolation). Each Bluetooth RFCOMM connection from the TV is piped byte-for-byte to the
 * phone's own WebSocket server on 127.0.0.1, so the MSP protocol is unchanged end to end.
 *
 * Uses an *insecure* RFCOMM service (no pairing PIN). The TV finds the phone once via discovery
 * (the phone must be visible for that first time) and then remembers its address.
 */
class BtBridge(private val ctx: Context, private val localPort: Int) {
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var running = false
    @Volatile var sessions = 0; private set

    fun permitted() = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        if (!permitted() || !adapter.isEnabled) { Log.i(TAG, "Bluetooth bridge not started (permission or Bluetooth off)"); return false }
        server = runCatching { adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) }
            .onFailure { Log.w(TAG, "listen failed", it) }.getOrNull() ?: return false
        running = true
        Thread({
            while (running) {
                val bt = runCatching { server?.accept() }.getOrNull() ?: break
                Thread({ pipe(bt) }, "bt-session").start()
            }
            Log.i(TAG, "accept loop ended")
        }, "bt-accept").start()
        Log.i(TAG, "listening for TVs over Bluetooth")
        return true
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun pipe(bt: BluetoothSocket) {
        val tcp = runCatching { Socket("127.0.0.1", localPort).apply { tcpNoDelay = true } }.getOrElse {
            Log.w(TAG, "local server not reachable", it); runCatching { bt.close() }; return
        }
        sessions++
        Log.i(TAG, "TV connected over Bluetooth: ${runCatching { bt.remoteDevice.address }.getOrDefault("?")}")
        val closeBoth = { runCatching { bt.close() }; runCatching { tcp.close() }; Unit }
        val up = Thread({ copy(bt.inputStream, tcp.getOutputStream()); closeBoth() }, "bt-up")
        up.start()
        copy(tcp.getInputStream(), bt.outputStream)
        closeBoth()
        up.join(1000)
        sessions--
        Log.i(TAG, "Bluetooth session closed")
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
        private const val TAG = "BtBridge"
        const val SERVICE_NAME = "MotionHub"
        /** Must match the TV shell (TvActivity.BT_UUID). */
        val SERVICE_UUID: UUID = UUID.fromString("6f1d2c3a-8b7e-4f0a-9c55-4d6f6d48b001")
    }
}
