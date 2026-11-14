package dev.motionhub.sensor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * Phone-side MSP endpoint: ws://<phone>:47800/msp/v1 (docs/06). Phase 0 runs in open mode
 * (no pairing token). Frames use "latest wins": a client that still has unsent data skips
 * the new frame instead of queueing it, so a slow link drops frames rather than adding lag.
 */
class MspServer(
    port: Int,
    private val onWelcome: () -> String,
    private val onText: (WebSocket, String) -> Unit,
    private val onClients: (Int) -> Unit,
    /** Server-level failure (the selector thread is gone): the owner should replace this server. */
    private val onFatal: (Exception) -> Unit = {},
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isTcpNoDelay = true
        isReuseAddr = true
        connectionLostTimeout = 5
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (!handshake.resourceDescriptor.startsWith(PATH)) {
            conn.close(1008, "unknown path"); return
        }
        conn.send(onWelcome())
        onClients(connections.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) = onClients(connections.size)
    override fun onMessage(conn: WebSocket, message: String) = onText(conn, message)
    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "socket error (conn=${conn != null})", ex)
        if (conn == null) onFatal(ex)          // a null connection means the server itself failed
    }
    override fun onStart() { Log.i(TAG, "listening on ${address.port}") }

    fun broadcastFrame(bytes: ByteArray): Int {
        var sent = 0
        for (c in connections) if (c.isOpen && !c.hasBufferedData()) { c.send(bytes); sent++ }
        return sent
    }

    fun broadcastText(text: String) {
        for (c in connections) if (c.isOpen) c.send(text)
    }

    companion object {
        const val PORT = 47800
        const val PATH = "/msp/v1"
        const val NSD_TYPE = "_motionhub._tcp."
        private const val TAG = "MspServer"
    }
}

/** Advertises the sensor on the LAN so Android TV can find it without typing an IP. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "MotionHub ${Build.MODEL}"
            serviceType = MspServer.NSD_TYPE
            this.port = port
            setAttribute("path", MspServer.PATH)
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "registered ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "registration failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object { private const val TAG = "NsdAdvertiser" }
}
