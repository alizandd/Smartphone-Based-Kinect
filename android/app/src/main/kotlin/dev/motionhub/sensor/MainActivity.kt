package dev.motionhub.sensor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * A window onto [SensorService]: preview, skeleton overlay, HUD and controls. Tracking does not
 * depend on this activity — leave the app or turn the screen off and the service keeps streaming
 * to the TV (notification → Stop to end it).
 */
@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity(), EngineUi {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hud: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var engine: SensorEngine? = null
    private val buttons = LinkedHashMap<String, Button>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val e = (binder as SensorService.LocalBinder).engine
            engine = e
            pendingOverrides?.let { e.applyOverrides(it); pendingOverrides = null }
            e.ui = this@MainActivity
            e.attachPreview(previewView.surfaceProvider)
            refreshButtons()
        }
        override fun onServiceDisconnected(name: ComponentName) { engine = null }
    }

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finish() }
    }

    /** adb extras (--es res 640x480 …) applied once the engine is bound. */
    private var pendingOverrides: Map<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById<PreviewView>(R.id.preview).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = findViewById(R.id.overlay)
        hud = findViewById(R.id.hud)
        pendingOverrides = intent?.extras?.let { b -> b.keySet().associateWith { b.getString(it) ?: "" } }?.takeIf { it.isNotEmpty() }
        buildButtons()
        ContextCompat.registerReceiver(this, stoppedReceiver, IntentFilter(SensorService.ACTION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED)

        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startAndBind() else ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)

        ui.post(object : Runnable {
            override fun run() { engine?.let { hud.text = it.hudText() }; ui.postDelayed(this, 250) }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAndBind()
        else toast("Camera permission is required")
    }

    /** The service must be started while this activity is visible (Android's background-camera rule). */
    private fun startAndBind() {
        val i = Intent(this, SensorService::class.java)
        ContextCompat.startForegroundService(this, i)
        bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extras = intent.extras?.let { b -> b.keySet().associateWith { b.getString(it) ?: "" } }
        if (!extras.isNullOrEmpty()) { engine?.applyOverrides(extras); toast("Settings saved; restart the sensor to apply camera changes") }
    }

    override fun onStart() {
        super.onStart()
        engine?.let { it.ui = this; it.attachPreview(previewView.surfaceProvider) }
    }

    override fun onStop() {
        // Leaving the app: drop the preview, keep tracking in the service.
        engine?.let { it.ui = null; it.attachPreview(null) }
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        unregisterReceiver(stoppedReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ EngineUi

    override fun onPose(px: FloatArray?, vis: FloatArray?, w: Int, h: Int) = overlay.update(px, vis, w, h)
    override fun onHands(points: List<FloatArray>) = overlay.updateHands(points)
    override fun onFace(points: FloatArray?) = overlay.updateFace(points)
    override fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ controls

    private fun buildButtons() {
        val col = findViewById<LinearLayout>(R.id.buttons)
        fun add(key: String, onClick: (SensorEngine) -> Unit) {
            buttons[key] = Button(this).apply {
                isAllCaps = false; textSize = 12f
                setOnClickListener { engine?.let { e -> onClick(e); refreshButtons() } }
                col.addView(this)
            }
        }
        add("calibrate") { it.startCalibration() }
        add("record") { it.toggleRecording() }
        add("exposure") { it.cycleExposure() }
        add("model") { it.cycleModel() }
        add("res") { it.cycleResolution() }
        add("hands") { it.setHands(!it.handsOn) }
        add("face") { it.setFace(!it.faceOn) }
        add("delegate") { it.cycleDelegate() }
        add("height") { askHeight(it) }
        add("probe") { it.probeEnabled = !it.probeEnabled }
        add("heat") { e ->
            if (e.heatGuard) AlertDialog.Builder(this).setTitle("Turn heat guard off?")
                .setMessage("Hands and face will run at full speed even when the phone is hot. Android may then slow down everything or stop the camera. For short tests only.")
                .setPositiveButton("Turn off") { _, _ -> e.setHeatGuard(false); refreshButtons() }
                .setNegativeButton("Cancel", null).show()
            else e.setHeatGuard(true)
        }
        add("background") { moveTaskToBack(true) }
        add("stop") {
            startService(Intent(this, SensorService::class.java).setAction(SensorService.ACTION_STOP))
            finish()
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        val e = engine
        fun set(key: String, text: String) { buttons[key]?.text = text }
        set("calibrate", "Calibrate")
        set("record", if (e?.recording == true) "Record: ON" else "Record: off")
        set("exposure", "Exposure: ${e?.exposureLabel() ?: "…"}")
        set("model", "Model: ${e?.modelLabel() ?: "…"}")
        set("res", "Res: ${e?.resLabel() ?: "…"}")
        set("hands", "Hands: ${if (e?.handsOn == true) "ON" else "off"}")
        set("face", "Face: ${if (e?.faceOn == true) "ON" else "off"}")
        set("delegate", "Delegate: ${e?.delegateLabel() ?: "…"}")
        set("height", "Height: ${e?.statureCm ?: "…"} cm")
        set("probe", if (e?.probeEnabled == true) "Probe: ON" else "Probe: off")
        set("heat", if (e?.heatGuard == false) "Heat guard: OFF" else "Heat guard: on")
        set("background", "Run in background")
        set("stop", "Stop sensor")
    }

    private fun askHeight(e: SensorEngine) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(e.statureCm.toString()) }
        AlertDialog.Builder(this).setTitle("Player height (cm)").setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toIntOrNull()?.takeIf { it in 100..230 }?.let { e.setStature(it); refreshButtons() }
            }.show()
    }
}
