package dev.motionhub.sensor

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import dev.motionhub.core.BP
import dev.motionhub.core.Calibrator
import dev.motionhub.core.LandmarksJson
import dev.motionhub.core.LumaProbe
import dev.motionhub.core.MspCodec
import dev.motionhub.core.MspJson
import dev.motionhub.core.TrackingPipeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.java_websocket.WebSocket
import java.net.Inet4Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the engine tells a visible UI. All calls may come from worker threads. */
interface EngineUi {
    fun onPose(px: FloatArray?, vis: FloatArray?, w: Int, h: Int)
    fun onHands(points: List<FloatArray>)
    fun onFace(points: FloatArray?)
    fun toast(msg: String)
}

/**
 * Everything the sensor does, independent of any screen: camera → MediaPipe → tracking core →
 * WebSocket + NSD, plus hands/face, recording and logging. Owned by [SensorService] so it keeps
 * running in the background; the activity only attaches a preview and reads [hudText].
 */
@ExperimentalCamera2Interop
class SensorEngine(private val ctx: Context, private val owner: LifecycleOwner) : SensorEventListener {

    @Volatile var ui: EngineUi? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("poc", Context.MODE_PRIVATE)

    private lateinit var camera: CameraController
    private val logger = SessionLogger(ctx)
    @Volatile private lateinit var server: MspServer
    private val healthExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var healthFailures = 0

    private fun newServer() = MspServer(MspServer.PORT, { MspJson.welcome(sessionId, Build.MODEL, 60) }, ::onSocketText, { },
        onFatal = { e -> android.util.Log.w("SensorEngine", "server failed, restarting", e); healthExecutor.execute { restartServer("fatal: ${e.javaClass.simpleName}") } })

    /**
     * Self-check: open a WebSocket handshake to our own port. If the server's selector thread has
     * died (the OS still accepts connections but nobody answers), restart the server.
     */
    private fun checkServerHealth() {
        if (!started) return
        val ok = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", MspServer.PORT), 1000)
                s.soTimeout = 2000
                s.getOutputStream().write(("GET ${MspServer.PATH}?probe=1 HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
                s.getInputStream().bufferedReader().readLine()?.startsWith("HTTP/1.1 101") == true
            }
        }.getOrDefault(false)
        if (ok) { healthFailures = 0; return }
        if (++healthFailures >= 2) { healthFailures = 0; restartServer("health check failed") }
    }

    @Synchronized private fun restartServer(reason: String) {
        if (!started) return
        android.util.Log.w("SensorEngine", "restarting WebSocket server: $reason")
        healthExecutor.shutdownNow()
        runCatching { server.stop(500) }
        server = newServer()
        runCatching { server.start() }
        logger.event(MspJson.sensor("server_restarted", buildJsonObject { put("reason", reason) }))
    }
    private val nsd = NsdAdvertiser(ctx)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockScreenOff: WifiManager.WifiLock? = null

    // ---- thermal ladder (docs/07 §4): 0 normal, 1 warm, 2 hot, 3 critical ----
    @Volatile var thermalLevel = 0; private set
    /** Heat guard off = always full quality (testing only; the phone may throttle or shut the camera). */
    @Volatile var heatGuard = true; private set
    private var thermalTicks = 0
    private var coolSinceMs = 0L
    private var appliedResIndex = -1
    private var analyzeCounter = 0L
    private var poseCounter = 0L

    private val pipeline = TrackingPipeline()
    @Volatile private var engine: PoseEngine? = null
    private val probe = LumaProbe()
    @Volatile var probeEnabled = false
    @Volatile var recording = false; private set

    // ---- settings ----
    var statureCm = 175; private set
    private var modelIndex = 1
    private var resIndex = 1
    private var delegateIndex = 0
    private var exposureIndex = 0
    val models = listOf("lite", "full", "heavy")
    private val resolutions = listOf(android.util.Size(640, 480), android.util.Size(960, 720), android.util.Size(1280, 960), android.util.Size(1440, 1080))
    private val delegates = listOf("GPU", "CPU")
    private val exposures = listOf<Long?>(null, 8_000_000L, 4_000_000L, 2_000_000L)
    fun exposureLabel(i: Int = exposureIndex) = exposures[i]?.let { "${it / 1_000_000}ms" } ?: "AUTO"
    fun resLabel() = resolutions[resIndex].let { "${it.width}x${it.height}" }
    fun modelLabel() = models[modelIndex]
    fun delegateLabel() = delegates[delegateIndex]
    private fun newEngine() = PoseEngine(ctx, models[modelIndex], delegates[delegateIndex], ::onPose)

    // ---- hands / face ----
    @Volatile var handsOn = false; private set
    @Volatile private var handTracker: HandTracker? = null
    private val bitmapByCapture = ConcurrentHashMap<Long, Bitmap>()
    private val handShapes = mapOf("l" to dev.motionhub.core.HandShapeTracker("l"), "r" to dev.motionhub.core.HandShapeTracker("r"))
    private val wristTrackers = mapOf("l" to dev.motionhub.core.WristEventTracker("l"), "r" to dev.motionhub.core.WristEventTracker("r"))
    private val handFps = RateMeter()
    private val handMs = Rolling(60)
    @Volatile private var handSummary = "off"
    @Volatile var faceOn = false; private set
    @Volatile private var faceTracker: FaceTracker? = null
    private val faceEvents = dev.motionhub.core.FaceEventTracker()
    private val faceFps = RateMeter()
    private val faceMs = Rolling(60)
    @Volatile private var faceSummary = "off"

    // ---- live state ----
    @Volatile private var gravity = floatArrayOf(9.81f, 0f, 0f)
    @Volatile var thermalHeadroom = Float.NaN; private set
    @Volatile var thermalStatus = 0; private set
    @Volatile private var lastEvent = "-"
    @Volatile private var lastStatus = "not calibrated"
    @Volatile private var lastDistance = Double.NaN
    @Volatile private var personBox: FloatArray? = null
    @Volatile private var iso = 800
    private val frameSeq = AtomicLong(0)
    private val eventSeq = AtomicLong(0)
    private val camFps = RateMeter()
    private val poseFps = RateMeter()
    private val inferenceMs = Rolling(120)
    private val captureToResultMs = Rolling(120)
    private val lumaByCapture = ConcurrentHashMap<Long, FloatArray>()
    private var intrinsicsSet = false
    private var intrinsicsKey = ""
    private var lastStatusSentUs = 0L
    private var prevSkeleton: dev.motionhub.core.Skeleton? = null
    private var bodyFrame = 0
    private val sessionId = UUID.randomUUID().toString().take(8)
    private var started = false

    val clients: Int get() = if (::server.isInitialized) server.connections.size else 0

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (started) return
        started = true
        statureCm = prefs.getInt("stature_cm", 175)
        modelIndex = prefs.getInt("model", 1)
        exposureIndex = prefs.getInt("exposure", 0)
        resIndex = prefs.getInt("res", 1)
        delegateIndex = prefs.getInt("delegate", 0)
        heatGuard = prefs.getBoolean("heat_guard", true)
        pipeline.statureM = statureCm / 100.0

        server = newServer()
        server.start()
        healthExecutor.scheduleWithFixedDelay({ checkServerHealth() }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
        nsd.register(MspServer.PORT)
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "motionhub").apply { acquire() }
        // Low-latency mode only applies while the screen is on; keep Wi-Fi out of power-save when it is off.
        @Suppress("DEPRECATION")
        wifiLockScreenOff = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "motionhub-screenoff").apply { acquire() }

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Wake-up gravity sensor keeps delivering with the screen off (non-wake-up sensors may pause).
        (sm.getDefaultSensor(Sensor.TYPE_GRAVITY, true) ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY))
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        for (type in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE))
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        if (prefs.getBoolean("hands", false)) setHands(true)
        if (prefs.getBoolean("face", false)) setFace(true)
        engine = newEngine()
        camera = CameraController(ctx, owner, ::analyze)
        appliedResIndex = resIndex
        camera.analysisSize = resolutions[resIndex]
        camera.targetRotation = displayRotation()
        camera.start(withVideo = false) { toast(it) }
        // Keep CameraX's target rotation in sync with the display (portrait ↔ landscape, 180° flips).
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                val r = displayRotation()
                if (r != camera.targetRotation) camera.targetRotation = r
            }
        }, main)
        main.postDelayed({ applyExposure(); writeMeta() }, 1000)
        main.post(object : Runnable {
            override fun run() { pollThermal(); main.postDelayed(this, 1000) }
        })
    }

    fun stop() {
        if (!started) return
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (::camera.isInitialized) camera.shutdown()
        engine?.close()
        handTracker?.close()
        faceTracker?.close()
        nsd.unregister()
        runCatching { server.stop(500) }
        wifiLock?.release()
        wifiLockScreenOff?.release()
        logger.close()
        main.removeCallbacksAndMessages(null)
        started = false
    }

    /** A visible activity attaches its preview; detaching leaves analysis-only (background mode). */
    fun attachPreview(provider: Preview.SurfaceProvider?) {
        if (!::camera.isInitialized) return
        camera.surfaceProvider = provider
        camera.start(withVideo = camera.recordingEnabled) { toast(it) }
        main.postDelayed({ applyExposure() }, 800)
    }

    /** Settings from an adb intent: res, delegate, model, exposure, hands, face. */
    fun applyOverrides(extras: Map<String, String>) {
        extras["res"]?.let { v -> resolutions.indexOfFirst { "${it.width}x${it.height}" == v }.takeIf { it >= 0 }?.let { resIndex = it } }
        extras["delegate"]?.let { v -> delegates.indexOf(v.uppercase(Locale.ROOT)).takeIf { it >= 0 }?.let { delegateIndex = it } }
        extras["model"]?.let { v -> models.indexOf(v).takeIf { it >= 0 }?.let { modelIndex = it } }
        extras["exposure"]?.let { v -> (0 until exposures.size).firstOrNull { exposureLabel(it).equals(v, true) }?.let { exposureIndex = it } }
        val e = prefs.edit().putInt("res", resIndex).putInt("delegate", delegateIndex).putInt("model", modelIndex).putInt("exposure", exposureIndex)
        extras["hands"]?.let { e.putBoolean("hands", it == "on") }
        extras["face"]?.let { e.putBoolean("face", it == "on") }
        e.apply()
    }

    // ------------------------------------------------------------------ camera thread

    private fun analyze(image: ImageProxy) {
        if (thermalLevel >= 2 && (analyzeCounter++ % 2L == 1L)) { image.close(); return }   // hot: 15 fps
        val tArrival = SystemClock.elapsedRealtimeNanos() / 1000
        val tCapture = image.imageInfo.timestamp / 1000
        camFps.tick()
        try {
            val plane = image.planes[0]
            val bmp: Bitmap = if (plane.rowStride == image.width * 4) {
                Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(plane.buffer) }
            } else image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val center = regionLuma(bmp, 0.4f, 0.4f, 0.6f, 0.6f)
            val box = personBox
            val person = if (box != null && rotation == 0) regionLuma(bmp, box[0] / bmp.width, box[1] / bmp.height, box[2] / bmp.width, box[3] / bmp.height) else center
            lumaByCapture[tCapture] = floatArrayOf(center, person)
            if (lumaByCapture.size > 32) lumaByCapture.keys.removeIf { it < tCapture - 1_000_000 }
            if (probeEnabled && probe.update(center.toDouble())) {
                val msg = MspJson.probe(tCapture, center.toDouble())
                server.broadcastText(msg); logger.event(msg); lastEvent = "PROBE"
            }
            autoIso(person)
            val key = "${bmp.width}x${bmp.height}@$rotation"
            if (!intrinsicsSet || key != intrinsicsKey) { intrinsicsKey = key; setIntrinsics(bmp.width, bmp.height, rotation) }
            if ((handsOn || faceOn) && rotation == 0) {
                bitmapByCapture[tCapture] = bmp
                if (bitmapByCapture.size > 6) bitmapByCapture.keys.removeIf { it < tCapture - 300_000 }
            }
            engine?.detect(bmp, rotation, tCapture, tArrival)
        } finally {
            image.close()
        }
    }

    private var isoFrames = 0
    private fun autoIso(luma: Float) {
        if (exposures[exposureIndex] == null || ++isoFrames % 6 != 0) return
        val range = camera.info.isoRange ?: return
        val ratio = (110f / luma.coerceAtLeast(1f)).coerceIn(0.7f, 1.4f)
        val next = (iso * ratio).toInt().coerceIn(range.lower, range.upper)
        if (kotlin.math.abs(next - iso) > iso * 0.05) { iso = next; main.post { applyExposure() } }
    }

    private fun setIntrinsics(w: Int, h: Int, rotation: Int) {
        val (uw, uh) = if (rotation % 180 == 0) w to h else h to w
        val i = camera.info
        val f = dev.motionhub.core.CameraModel.focalPx(i.focalLengthMm, maxOf(i.sensorWidthMm, i.sensorHeightMm), uw)
        synchronized(pipeline) { pipeline.camera.setIntrinsics(f, f, uw / 2.0, uh / 2.0) }
        intrinsicsSet = true
        main.post { writeMeta() }
    }

    // ------------------------------------------------------------------ MediaPipe thread

    private fun onPose(r: PoseEngine.Result) {
        poseFps.tick()
        inferenceMs.add((r.tResultUs - r.tArrivalUs) / 1000.0)
        if (camera.info.timestampSourceRealtime) captureToResultMs.add((r.tResultUs - r.tCaptureUs) / 1000.0)
        val f = r.frame
        ui?.onPose(f?.px, f?.vis, r.width, r.height)
        if (recording) logger.landmarks(LandmarksJson.encode(f, r.tCaptureUs, r.width, r.height, "mediapipe_${engine?.model}", r.zRel))
        personBox = f?.let { bbox(it) }
        val crops = bitmapByCapture.remove(r.tCaptureUs)
        // Hands/face cost as much as the body model: run them on every 2nd frame (15 Hz), every
        // 3rd when the phone is warm, and pause them when it is hot.
        val every = handFaceEvery(thermalLevel)
        val sub = every > 0 && (poseCounter++ % every == 0L)
        if (sub && handsOn && f != null && crops != null) handTracker?.submit(crops, f)
        if (sub && faceOn && f != null && crops != null) faceTracker?.submit(crops, f)

        var sent = 0
        var tSend = 0L
        var tracking = 0
        val out = if (f == null) null else synchronized(pipeline) {
            val g = gravity
            pipeline.camera.setGravityDevice(g[0].toDouble(), g[1].toDouble(), g[2].toDouble())
            pipeline.process(f)
        }
        out?.skeleton?.let { s ->
            lastDistance = s.distanceM
            tracking = s.trackingState
            val flags = (if (pipeline.calibration == null) MspCodec.FLAG_UNCALIBRATED else 0) or
                (if (thermalLevel > 0) MspCodec.FLAG_DEGRADED else 0)
            tSend = SystemClock.elapsedRealtimeNanos() / 1000
            val bytes = MspCodec.encode(flags, frameSeq.incrementAndGet(), r.tCaptureUs, tSend - r.tCaptureUs,
                dev.motionhub.core.J.COUNT, listOf(MspCodec.playerFrom(s)))
            sent = server.broadcastFrame(bytes)
            // Whole-body joint angles + limb speeds, ~15 Hz (every 2nd frame at 30 fps).
            if (++bodyFrame % 2 == 0) dev.motionhub.core.BodyAnglesEstimator.compute(s, prevSkeleton)?.let { b ->
                server.broadcastText(buildJsonObject {
                    put("type", "body")
                    put("t_source_us", r.tCaptureUs)
                    putJsonObject("angles") { b.angles.forEach { (k, v) -> put(k, Math.round(v).toInt()) } }
                    putJsonObject("speeds") { b.speeds.forEach { (k, v) -> put(k, Math.round(v * 100) / 100.0) } }
                }.toString())
            }
            prevSkeleton = s
        }
        out?.events?.forEach { e -> emit(e) }
        out?.calibrationStatus?.let { st ->
            lastStatus = "${st.stage.name.lowercase()} ${(st.progress * 100).toInt()}% ${st.hint}"
            if (r.tCaptureUs - lastStatusSentUs > 200_000) {
                lastStatusSentUs = r.tCaptureUs
                server.broadcastText(MspJson.setupStatus(st))
            }
            if (st.stage == Calibrator.Stage.FAILED) toast("Calibration failed: ${st.hint}")
        }
        out?.calibrationDone?.let { c ->
            val msg = MspJson.calibrationDone(c)
            server.broadcastText(msg); logger.event(msg)
            lastStatus = String.format(Locale.ROOT, "calibrated D=%.2fm h=%.2fm", c.distanceM, c.cameraHeightM)
            main.post { writeMeta() }
            toast(lastStatus)
        }

        val lumas = lumaByCapture.remove(r.tCaptureUs)
        val meta = camera.metaFor(r.tCaptureUs)
        logger.frame(listOf(
            frameSeq.get(), r.tCaptureUs, r.tArrivalUs, r.tSubmitUs, r.tResultUs, tSend,
            fmt((r.tArrivalUs - r.tCaptureUs) / 1000.0), fmt((r.tResultUs - r.tArrivalUs) / 1000.0), fmt(r.detectCallUs / 1000.0),
            meta?.exposureNs?.div(1000) ?: -1, meta?.iso ?: -1, fmt(camFps.rate), fmt(poseFps.rate),
            fmt(thermalHeadroom.toDouble()), thermalStatus, fmt(lumas?.get(0)?.toDouble() ?: -1.0), fmt(lumas?.get(1)?.toDouble() ?: -1.0),
            f?.keyJointsVisible() ?: 0, tracking, fmt(lastDistance), if (pipeline.calibration != null) 1 else 0,
            engine?.model ?: "", engine?.delegate ?: "", exposureLabel(), sent,
            "${r.width}x${r.height}${if (ui == null) "-background" else ""}",
        ).joinToString(","))
    }

    private fun emit(e: dev.motionhub.core.GestureEvent) {
        val msg = MspJson.gesture(e, eventSeq.incrementAndGet())
        server.broadcastText(msg); logger.event(msg)
        if (e.phase == "start" && e.id != "neutral") lastEvent = e.id.uppercase(Locale.ROOT)
    }

    private fun bbox(f: dev.motionhub.core.PoseFrame): FloatArray? {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -1f; var y1 = -1f
        for (i in 0 until BP.COUNT) if (f.visible(i)) {
            x0 = minOf(x0, f.px[2 * i]); x1 = maxOf(x1, f.px[2 * i])
            y0 = minOf(y0, f.px[2 * i + 1]); y1 = maxOf(y1, f.px[2 * i + 1])
        }
        if (x1 < 0) return null
        return floatArrayOf(x0.coerceAtLeast(0f), y0.coerceAtLeast(0f), x1.coerceAtMost(f.width - 1f), y1.coerceAtMost(f.height - 1f))
    }

    // ------------------------------------------------------------------ network

    private fun onSocketText(conn: WebSocket, text: String) {
        val tRecv = SystemClock.elapsedRealtimeNanos() / 1000
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (o["type"]?.jsonPrimitive?.content) {
            "ping" -> conn.send(MspJson.pong(o["t0"]!!.jsonPrimitive.double, tRecv, SystemClock.elapsedRealtimeNanos() / 1000))
            "hello" -> logger.event(text)
            "tv_stats" -> {
                val clock = o["clock"]?.jsonObject
                val offset = clock?.get("offset_us")?.jsonPrimitive?.content ?: ""
                val rtt = clock?.get("rtt_us")?.jsonPrimitive?.content ?: ""
                o["samples"]?.jsonArray?.forEach { s ->
                    val a = s.jsonArray
                    logger.tvStat("${a[0].jsonPrimitive.long},${a[1].jsonPrimitive.long},${a[2].jsonPrimitive.long},${a[3].jsonPrimitive.long},$offset,$rtt")
                }
            }
            "calibrate" -> main.post { startCalibration() }
        }
    }

    // ------------------------------------------------------------------ controls (main thread)

    fun startCalibration() {
        synchronized(pipeline) { pipeline.startCalibration() }
        lastStatus = "calibrating…"
        toast("Stand still, full body in view")
    }

    fun cycleExposure() {
        exposureIndex = (exposureIndex + 1) % exposures.size
        prefs.edit().putInt("exposure", exposureIndex).apply()
        applyExposure(); writeMeta()
    }

    fun cycleModel() {
        modelIndex = (modelIndex + 1) % models.size
        prefs.edit().putInt("model", modelIndex).apply()
        swapPoseEngine()
    }

    fun cycleDelegate() {
        delegateIndex = (delegateIndex + 1) % delegates.size
        prefs.edit().putInt("delegate", delegateIndex).apply()
        swapPoseEngine()
    }

    private fun swapPoseEngine() {
        val old = engine
        engine = newEngine()
        main.postDelayed({ old?.close() }, 500)
        main.postDelayed({ writeMeta() }, 600)
    }

    fun cycleResolution() {
        resIndex = (resIndex + 1) % resolutions.size
        prefs.edit().putInt("res", resIndex).apply()
        appliedResIndex = minOf(resIndex, resCapFor(thermalLevel))
        camera.analysisSize = resolutions[appliedResIndex]
        camera.start(withVideo = camera.recordingEnabled) { toast(it) }
        main.postDelayed({ applyExposure(); writeMeta() }, 1000)
    }

    fun setStature(cm: Int) {
        statureCm = cm
        prefs.edit().putInt("stature_cm", cm).apply()
        synchronized(pipeline) { pipeline.statureM = cm / 100.0 }
        writeMeta()
    }

    fun toggleRecording() {
        if (!recording) {
            if (!camera.recordingEnabled) {
                camera.start(withVideo = true) { toast(it) }
                main.postDelayed({ applyExposure(); beginRecording() }, 1200)
            } else beginRecording()
        } else {
            recording = false
            camera.stopRecording()
            logger.event(marker("record_stop"))
            logger.stopRecording()
        }
    }

    private fun beginRecording() {
        val file = logger.startRecording()
        writeMeta()
        if (!camera.startRecording(file) { ev -> logger.event(marker(ev)) }) toast("Video not available; logging landmarks only")
        recording = true
        logger.event(marker("record_start"))
        toast("Recording → ${logger.recordingDir?.name}. Flash a light at the camera to sync.")
    }

    private fun marker(name: String) = buildJsonObject {
        put("type", "marker"); put("event", name); put("t_us", SystemClock.elapsedRealtimeNanos() / 1000)
        put("rec", logger.recordingDir?.name ?: "")
    }.toString()

    private fun applyExposure() {
        if (!::camera.isInitialized) return
        camera.setExposure(exposures[exposureIndex], iso)
    }

    fun setHands(on: Boolean) {
        if (on == handsOn) return
        if (on) {
            handTracker = runCatching { HandTracker(ctx, ::onHands) }.getOrElse { toast("Hand model unavailable: ${it.message}"); null }
            handsOn = handTracker != null
        } else {
            handsOn = false
            handTracker?.close(); handTracker = null
            bitmapByCapture.clear()
            ui?.onHands(emptyList())
            handSummary = "off"
        }
        prefs.edit().putBoolean("hands", handsOn).apply()
    }

    fun setFace(on: Boolean) {
        if (on == faceOn) return
        if (on) {
            faceTracker = runCatching { FaceTracker(ctx, ::onFace) }.getOrElse { toast("Face model unavailable: ${it.message}"); null }
            faceOn = faceTracker != null
        } else {
            faceOn = false
            faceTracker?.close(); faceTracker = null
            ui?.onFace(null)
            faceSummary = "off"
        }
        prefs.edit().putBoolean("face", faceOn).apply()
    }

    // ------------------------------------------------------------------ face / hands threads

    private fun onFace(r: FaceTracker.Result) {
        faceFps.tick(); faceMs.add(r.latencyMs)
        val res = r.blendshapes?.let { dev.motionhub.core.FaceExpressionClassifier.classify(it) }
        for (e in faceEvents.update(res, r.tCaptureUs)) emit(e)
        ui?.onFace(r.outline)
        faceSummary = if (res == null) "${fmt(faceFps.rate)} fps, face not found in crop" else
            "${fmt(faceFps.rate)} fps ${fmt(faceMs.p(0.5))} ms  ${faceEvents.expression.name.lowercase()}  " +
                "smile ${fmt(res.scores.getValue("smile"))} blink ${fmt(res.blinkLeft)}/${fmt(res.blinkRight)}"
        server.broadcastText(buildJsonObject {
            put("type", "face")
            put("t_source_us", r.tCaptureUs)
            put("found", res != null)
            put("expression", faceEvents.expression.name.lowercase())
            putJsonArray("box") { listOf(r.box[0] / r.imageW, r.box[1] / r.imageH, r.box[2] / r.imageW).forEach { add(JsonPrimitive(Math.round(it * 1000) / 1000.0)) } }
            if (res != null) putJsonObject("scores") { res.scores.forEach { (k, v) -> put(k, Math.round(v * 100) / 100.0) } }
        }.toString())
    }

    private fun onHands(r: HandTracker.Result) {
        handFps.tick(); handMs.add(r.latencyMs)
        val now = SystemClock.elapsedRealtimeNanos() / 1000
        val shapes = HashMap<String, dev.motionhub.core.HandShape>()
        val wrists = HashMap<String, dev.motionhub.core.WristPose>()
        for (h in r.hands) {
            shapes[h.side] = dev.motionhub.core.HandShapeClassifier.classify(h.shapeLandmarks).shape
            h.forearm?.let { fa -> dev.motionhub.core.WristPoseEstimator.estimate(fa, h.shapeLandmarks, h.side)?.let { wrists[h.side] = it } }
        }
        for ((side, tracker) in wristTrackers)
            tracker.update(wrists[side], r.tCaptureUs, r.hands.firstOrNull { it.side == side }?.confidence?.toDouble() ?: 0.0).forEach { emit(it) }
        for ((side, tracker) in handShapes)
            tracker.update(shapes[side], r.tCaptureUs, r.hands.firstOrNull { it.side == side }?.confidence?.toDouble() ?: 0.0)?.let { emit(it) }
        ui?.onHands(r.hands.map { it.points })
        handSummary = "${fmt(handFps.rate)} fps  ${fmt(handMs.p(0.5))} ms  " +
            handShapes.entries.joinToString("  ") { (side, t) ->
                val w = wrists[side]
                "$side:${t.current.name.lowercase()}" + (w?.let { " ↕${it.upDownDeg.toInt()}° ↔${it.leftRightDeg.toInt()}° palm ${it.palmFacing}" } ?: "")
            }
        server.broadcastText(buildJsonObject {
            put("type", "hands")
            put("t_source_us", r.tCaptureUs)
            put("age_us", now - r.tCaptureUs)
            putJsonArray("hands") {
                for (h in r.hands) add(buildJsonObject {
                    put("side", h.side)
                    put("shape", handShapes.getValue(h.side).current.name.lowercase())
                    put("raw_shape", shapes[h.side]?.name?.lowercase() ?: "unknown")
                    put("conf", h.confidence.toDouble())
                    wrists[h.side]?.let { w ->
                        putJsonObject("wrist") {
                            put("up_down_deg", Math.round(w.upDownDeg).toInt()); put("left_right_deg", Math.round(w.leftRightDeg).toInt())
                            put("flex_deg", Math.round(w.flexDeg).toInt()); put("deviation_deg", Math.round(w.deviationDeg).toInt())
                            put("palm", w.palmFacing)
                        }
                    }
                    putJsonArray("box") { listOf(h.box[0] / r.imageW, h.box[1] / r.imageH, h.box[2] / r.imageW).forEach { add(JsonPrimitive(Math.round(it * 1000) / 1000.0)) } }
                    putJsonArray("pts") {
                        for (i in 0 until dev.motionhub.core.HL.COUNT) {
                            add(JsonPrimitive(Math.round((h.points[3 * i] - h.box[0]) / h.box[2] * 1000) / 1000.0))
                            add(JsonPrimitive(Math.round((h.points[3 * i + 1] - h.box[1]) / h.box[2] * 1000) / 1000.0))
                        }
                    }
                })
            }
        }.toString())
    }

    // ------------------------------------------------------------------ status

    fun hudText(): String {
        val g = gravity
        synchronized(pipeline) { pipeline.camera.setGravityDevice(g[0].toDouble(), g[1].toDouble(), g[2].toDouble()) }
        val ip = localIp() ?: "no Wi-Fi"
        val cal = pipeline.calibration
        return buildString {
            appendLine("ws://$ip:${MspServer.PORT}${MspServer.PATH}   TV clients: $clients")
            appendLine("cam ${fmt(camFps.rate)} fps ($intrinsicsKey)   pose ${fmt(poseFps.rate)} fps   ${engine?.model}/${engine?.delegate}")
            appendLine("inference p50 ${fmt(inferenceMs.p(0.5))} p95 ${fmt(inferenceMs.p(0.95))} ms")
            appendLine("capture→result p50 ${fmt(captureToResultMs.p(0.5))} ms")
            appendLine("hands: ${if (handsOn && handSummary == "off") "on, no hands in view" else handSummary}")
            appendLine("face: ${if (faceOn && faceSummary == "off") "on, no face in view" else faceSummary}")
            appendLine("exposure ${exposureLabel()}  iso $iso  thermal ${fmt(thermalHeadroom.toDouble())} (status $thermalStatus) · " +
                if (!heatGuard) "HEAT GUARD OFF" else thermalLabel(thermalLevel))
            appendLine("pitch ${fmt(pipeline.camera.pitchDeg)}°  roll ${fmt(pipeline.camera.rollDeg)}°  distance ${fmt(lastDistance)} m")
            appendLine(if (cal != null) "calibrated: h=${fmt(cal.cameraHeightM)} m" else lastStatus)
            append("last event: $lastEvent   ${if (recording) "● REC" else ""}")
        }
    }

    /** One line for the notification. */
    fun statusLine(): String = "pose ${fmt(poseFps.rate)} fps · TV $clients · " +
        if (!heatGuard) "heat guard off" else thermalLabel(thermalLevel)

    private fun toast(msg: String) {
        val u = ui ?: return
        main.post { u.toast(msg) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 1

    private fun pollThermal() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        thermalHeadroom = pm.getThermalHeadroom(10)
        thermalStatus = pm.currentThermalStatus
        updateThermalLevel()
        // Repeat the state every 5 s so a TV that connects later knows why things are reduced.
        if (++thermalTicks % 5 == 0 && ::server.isInitialized) server.broadcastText(thermalMessage())
    }

    fun setHeatGuard(on: Boolean) {
        heatGuard = on
        prefs.edit().putBoolean("heat_guard", on).apply()
        if (!on) setThermalLevel(0) else updateThermalLevel()
    }

    /** Hands/face cadence: 15 Hz normal, 10 Hz warm, 5 Hz hot, paused only when critical. */
    private fun handFaceEvery(level: Int) = when (level) { 0 -> 2L; 1 -> 3L; 2 -> 6L; else -> 0L }
    private fun handFaceLabel(level: Int) = when (level) { 0 -> "15 Hz"; 1 -> "10 Hz"; 2 -> "5 Hz"; else -> "paused" }
    private fun thermalLabel(level: Int) = when (level) {
        0 -> "temperature ok"
        1 -> "WARM: hands/face 10 Hz, max 960x720"
        2 -> "HOT: 15 fps, hands/face 5 Hz, 640x480"
        else -> "CRITICAL: 15 fps, hands/face paused"
    }

    /** Max resolution index per thermal level: hot → 640×480, warm → 960×720, normal → any. */
    private fun resCapFor(level: Int) = when (level) { 0 -> resolutions.size - 1; 1 -> 1; else -> 0 }

    /** Step up immediately when heating, step down one level after 15 s of cooler readings. */
    private fun updateThermalLevel() {
        if (!heatGuard) return
        val h = thermalHeadroom
        val target = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> 3
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || (!h.isNaN() && h >= 0.95f) -> 2
            thermalStatus == PowerManager.THERMAL_STATUS_MODERATE || (!h.isNaN() && h >= 0.8f) -> 1
            else -> 0
        }
        val now = SystemClock.elapsedRealtime()
        when {
            target > thermalLevel -> { coolSinceMs = 0; setThermalLevel(target) }
            target < thermalLevel -> {
                if (coolSinceMs == 0L) coolSinceMs = now
                if (now - coolSinceMs > 15_000) { coolSinceMs = 0; setThermalLevel(thermalLevel - 1) }
            }
            else -> coolSinceMs = 0
        }
    }

    private fun setThermalLevel(level: Int) {
        thermalLevel = level
        val res = minOf(resIndex, resCapFor(level))
        if (::camera.isInitialized && res != appliedResIndex) {
            appliedResIndex = res
            camera.analysisSize = resolutions[res]
            camera.start(withVideo = camera.recordingEnabled) { toast(it) }
            main.postDelayed({ applyExposure() }, 800)
        }
        val msg = thermalMessage()
        if (::server.isInitialized) server.broadcastText(msg)
        logger.event(msg)
        toast(if (level == 0) "Temperature normal again" else "Phone is getting hot: ${thermalLabel(level)}")
    }

    private fun thermalMessage() = MspJson.sensor("thermal", buildJsonObject {
        put("level", thermalLevel); put("status", thermalStatus); put("headroom", Math.round(thermalHeadroom * 100) / 100.0)
        put("heat_guard", heatGuard)
        put("pose_fps_cap", if (thermalLevel >= 2) 15 else 30)
        put("hands_face", handFaceLabel(thermalLevel))
        put("resolution", resLabelFor(if (appliedResIndex >= 0) appliedResIndex else resIndex))
    })

    private fun resLabelFor(i: Int) = resolutions[i].let { "${it.width}x${it.height}" }

    private fun localIp(): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun writeMeta() {
        if (!::camera.isInitialized || !camera.isInfoReady()) return
        val i = camera.info
        val cal = pipeline.calibration
        val g = gravity
        logger.meta(buildJsonObject {
            put("app_version", "0.1-poc")
            put("device_model", Build.MODEL)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("session_id", sessionId)
            put("stature_m", statureCm / 100.0)
            put("model", models[modelIndex])
            put("analysis_resolution_requested", resLabel())
            put("analysis_resolution_actual", intrinsicsKey)
            put("delegate", engine?.delegate ?: "")
            put("exposure_mode", exposureLabel())
            putJsonObject("camera") {
                put("id", i.cameraId); put("focal_length_mm", i.focalLengthMm)
                put("sensor_width_mm", i.sensorWidthMm); put("sensor_height_mm", i.sensorHeightMm)
                put("timestamp_source_realtime", i.timestampSourceRealtime)
                put("iso_range", i.isoRange?.toString() ?: ""); put("exposure_range_ns", i.exposureRangeNs?.toString() ?: "")
                putJsonArray("fps_ranges") { i.fpsRanges.forEach { add(JsonPrimitive(it)) } }
                put("chosen_fps", i.chosenFps)
                put("intrinsic_calibration", i.intrinsicCalibration?.joinToString(",") ?: "")
                put("active_array", i.activeArray ?: "")
            }
            val cam = pipeline.camera
            putJsonObject("intrinsics") { put("fx", cam.fx); put("fy", cam.fy); put("cx", cam.cx); put("cy", cam.cy) }
            putJsonArray("gravity_device") { g.forEach { add(JsonPrimitive(it.toDouble())) } }
            if (cal != null) putJsonObject("calibration") {
                put("camera_height_m", cal.cameraHeightM); put("distance_m", cal.distanceM)
                putJsonArray("origin_g") { listOf(cal.originG.x, cal.originG.y, cal.originG.z).forEach { add(JsonPrimitive(it)) } }
                cal.kScale?.let { put("k_scale", it) }
                put("pitch_deg", cal.pitchDeg); put("roll_deg", cal.rollDeg)
            }
        }.toString())
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> gravity = floatArrayOf(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_ACCELEROMETER -> if (recording) logger.imu("${e.timestamp / 1000},acc,${e.values[0]},${e.values[1]},${e.values[2]}")
            Sensor.TYPE_GYROSCOPE -> if (recording) logger.imu("${e.timestamp / 1000},gyro,${e.values[0]},${e.values[1]},${e.values[2]}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun regionLuma(b: Bitmap, fx0: Float, fy0: Float, fx1: Float, fy1: Float): Float {
        val x0 = (fx0 * b.width).toInt().coerceIn(0, b.width - 1); val x1 = (fx1 * b.width).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (fy0 * b.height).toInt().coerceIn(0, b.height - 1); val y1 = (fy1 * b.height).toInt().coerceIn(y0 + 1, b.height)
        var sum = 0f; var n = 0
        val stepX = maxOf(1, (x1 - x0) / 24); val stepY = maxOf(1, (y1 - y0) / 24)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = b.getPixel(x, y)
                sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF); n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0f else sum / n
    }
}

internal fun fmt(v: Double) = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", v)

/** Events per second over the last second. */
internal class RateMeter {
    private val times = ArrayDeque<Long>()
    @Synchronized fun tick() {
        val now = SystemClock.elapsedRealtime()
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > 1000) times.removeFirst()
    }
    val rate: Double @Synchronized get() = times.size.toDouble()
}

/** Rolling window with percentiles. */
internal class Rolling(private val size: Int) {
    private val values = ArrayDeque<Double>()
    @Synchronized fun add(v: Double) { values.addLast(v); if (values.size > size) values.removeFirst() }
    @Synchronized fun p(q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return s[((s.size - 1) * q).toInt()]
    }
}
