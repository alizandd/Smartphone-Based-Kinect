package dev.motionhub.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android TV shell for the shared web receiver (tv/). Adds what a browser page cannot do:
 * NSD discovery of the phone (_motionhub._tcp), the TV's own IP, low-latency Wi-Fi, and
 * remote-control keys mapped to receiver commands.
 */
class TvActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var nsd: NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val phones = CopyOnWriteArrayList<JSONObject>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var lastBack = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .setHttpAllowed(true)   // http origin so ws:// to the phone is not mixed content
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(Bridge(), "MotionHubHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
        }
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect for Phase 0 profiling
        web.loadUrl("http://appassets.androidplatform.net/assets/web/index.html")
        web.requestFocus()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("motionhub-nsd").apply { setReferenceCounted(false); acquire() }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "motionhub").apply { acquire() }
        }
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    override fun onDestroy() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        multicastLock?.release()
        wifiLock?.release()
        web.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_MENU -> { command("calibrate"); return true }
            KeyEvent.KEYCODE_BACK -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 1000) finish() else command("panel")
                lastBack = now
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun command(name: String) =
        web.evaluateJavascript("window.motionhubCommand && window.motionhubCommand('$name')", null)

    private fun pushPhones() {
        val json = JSONArray(phones).toString().replace("\\", "\\\\").replace("'", "\\'")
        runOnUiThread { web.evaluateJavascript("window.motionhubOnPhones && window.motionhubOnPhones('$json')", null) }
    }

    // ---------------------------------------------------------------- NSD

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { Log.i(TAG, "NSD discovery started") }
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "NSD start failed $code") }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
        override fun onServiceLost(info: NsdServiceInfo) {
            phones.removeAll { it.optString("name") == info.serviceName }
            pushPhones()
        }
    }

    // Pre-API-34 NsdManager resolves one service at a time.
    @Synchronized private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun resolveNext() {
        val next = resolveQueue.removeFirstOrNull() ?: run { resolving = false; return }
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve failed $code for ${info.serviceName}"); resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null && info.host is Inet4Address) {
                    phones.removeAll { it.optString("name") == info.serviceName }
                    phones.add(JSONObject().put("name", info.serviceName).put("host", host).put("port", info.port))
                    Log.i(TAG, "phone ${info.serviceName} at $host:${info.port}")
                    pushPhones()
                }
                resolveNext()
            }
        })
    }

    // ---------------------------------------------------------------- JS bridge

    inner class Bridge {
        @JavascriptInterface fun getPhones(): String = JSONArray(phones).toString()
        @JavascriptInterface fun platform(): String = "androidtv"
        @JavascriptInterface fun getLocalIp(): String {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
                ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: ""
        }
    }

    companion object {
        private const val TAG = "MotionHubTV"
        const val SERVICE_TYPE = "_motionhub._tcp."
    }
}
