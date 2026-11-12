package dev.motionhub.sensor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import dev.motionhub.core.BP
import dev.motionhub.core.HL
import dev.motionhub.core.HandLandmarks
import dev.motionhub.core.OneEuroFilter
import dev.motionhub.core.PoseFrame
import dev.motionhub.core.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

/**
 * Finger + wrist tracking at living-room distance (a hand is only ~30–70 px wide at 2.5 m).
 *
 * Per hand (player's left and right independently):
 *  1. Region of interest: the square around the hand's own landmarks from the previous frame
 *     (MediaPipe's own tracking strategy), or — when the hand was lost — a box from the pose
 *     model's wrist/knuckle points.
 *  2. The crop goes to that hand's own Hand Landmarker instance (VIDEO mode, 1 hand), so each
 *     hand keeps its temporal tracking.
 *  3. Sanity check: the detected wrist must lie near the pose wrist, otherwise it is the other
 *     hand (or a false positive) and is rejected.
 *  4. Points are One Euro filtered; 3D "world" points (metres) feed shape and wrist angles.
 *
 * Runs on its own thread; while busy, new frames are skipped (latest wins).
 */
class HandTracker(private val context: Context, private val onResult: (Result) -> Unit) : AutoCloseable {

    class Hand(
        val side: String,                 // "l" | "r" (the player's own left/right)
        val points: FloatArray,           // 21 × (x, y, z) full-frame pixels (smoothed), for drawing
        val box: FloatArray,              // crop square in full-frame pixels: left, top, size
        val confidence: Float,
        val forearm: Vec3?,               // elbow→wrist in image axes (+x right, +y down, +z away)
        /** Smoothed 3D points in metres (image-aligned axes), for shapes and wrist angles. */
        val shapeLandmarks: HandLandmarks,
    )

    class Result(val tCaptureUs: Long, val hands: List<Hand>, val latencyMs: Double, val imageW: Int, val imageH: Int)

    private class SideState(val side: String, val landmarker: HandLandmarker) {
        var lastPts: FloatArray? = null
        var lastSeenUs = 0L
        var lastTsMs = -1L
        val pxFilters = Array(HL.COUNT * 3) { OneEuroFilter(minCutoff = 1.5, beta = 0.05) }
        val worldFilters = Array(HL.COUNT * 3) { OneEuroFilter(minCutoff = 1.5, beta = 0.5) }
        fun resetFilters() { pxFilters.forEach { it.reset() }; worldFilters.forEach { it.reset() } }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var delegate = "GPU"; private set
    private val sides = arrayOf(SideState("l", createLandmarker()), SideState("r", createLandmarker()))

    private fun createLandmarker(): HandLandmarker =
        create(Delegate.GPU) ?: create(Delegate.CPU).also { delegate = "CPU" } ?: error("Could not create HandLandmarker")

    private fun create(d: Delegate): HandLandmarker? = try {
        HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(d).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build())
    } catch (e: Exception) {
        Log.w(TAG, "HandLandmarker with $d failed", e); null
    }

    /** Submit a frame with the pose result *of the same frame*. [bitmap] is upright (rotation 0). */
    fun submit(bitmap: Bitmap, pose: PoseFrame) {
        if (!busy.compareAndSet(false, true)) return
        // Build both crops now (cheap) so the caller's bitmap is not needed afterwards.
        val jobs = sides.mapNotNull { st ->
            val left = st.side == "l"
            val recent = pose.tUs - st.lastSeenUs < LOST_AFTER_US
            val box = (if (recent) st.lastPts?.let { landmarkBox(it, pose) } else null) ?: poseBox(pose, left) ?: return@mapNotNull null
            val crop = Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888)
            Canvas(crop).drawBitmap(bitmap, Rect(box[0].toInt(), box[1].toInt(), (box[0] + box[2]).toInt(), (box[1] + box[2]).toInt()),
                Rect(0, 0, CROP, CROP), paint)
            Job(st, box, crop, forearm(pose, left), wristPx(pose, left))
        }
        if (jobs.isEmpty()) { busy.set(false); return }
        val t0 = SystemClock.elapsedRealtimeNanos()
        executor.execute {
            try {
                val hands = ArrayList<Hand>(2)
                val tSec = pose.tUs / 1e6
                for (j in jobs) runCatching { detect(j, pose, tSec) }.onSuccess { it?.let(hands::add) }
                    .onFailure { Log.w(TAG, "hand inference failed", it) }
                onResult(Result(pose.tUs, hands, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, pose.width, pose.height))
            } finally {
                busy.set(false)
            }
        }
    }

    private class Job(val st: SideState, val box: FloatArray, val crop: Bitmap, val forearm: Vec3?, val poseWrist: FloatArray?)

    private fun detect(j: Job, pose: PoseFrame, tSec: Double): Hand? {
        val st = j.st
        var ts = pose.tUs / 1000
        if (ts <= st.lastTsMs) ts = st.lastTsMs + 1
        st.lastTsMs = ts
        val r = st.landmarker.detectForVideo(BitmapImageBuilder(j.crop).build(), ts)
        val lms = r.landmarks().firstOrNull()
        val world = r.worldLandmarks().firstOrNull()
        if (lms == null || lms.size < HL.COUNT || world == null) { st.lastPts = null; return null }
        val b = j.box
        val scale = b[2] / CROP
        val raw = FloatArray(HL.COUNT * 3)
        for (i in 0 until HL.COUNT) {
            raw[3 * i] = b[0] + lms[i].x() * CROP * scale
            raw[3 * i + 1] = b[1] + lms[i].y() * CROP * scale
            raw[3 * i + 2] = lms[i].z() * CROP * scale
        }
        // Reject the other hand / false positives: detected wrist must be near the pose wrist.
        j.poseWrist?.let { pw ->
            if (hypot((raw[0] - pw[0]).toDouble(), (raw[1] - pw[1]).toDouble()) > 0.6 * b[2]) { st.lastPts = null; return null }
        }
        if (pose.tUs - st.lastSeenUs > LOST_AFTER_US) st.resetFilters()
        st.lastSeenUs = pose.tUs
        st.lastPts = raw
        val pts = FloatArray(raw.size) { st.pxFilters[it].filter(raw[it].toDouble(), tSec).toFloat() }
        val w = DoubleArray(HL.COUNT * 3)
        for (i in 0 until HL.COUNT) {
            w[3 * i] = st.worldFilters[3 * i].filter(world[i].x().toDouble(), tSec)
            w[3 * i + 1] = st.worldFilters[3 * i + 1].filter(world[i].y().toDouble(), tSec)
            w[3 * i + 2] = st.worldFilters[3 * i + 2].filter(world[i].z().toDouble(), tSec)
        }
        val conf = r.handednesses().firstOrNull()?.firstOrNull()?.score() ?: 0.5f
        return Hand(st.side, pts, b, conf, j.forearm, HandLandmarks(w))
    }

    /** Square around the previous frame's hand landmarks, enlarged 2× (MediaPipe's tracking ROI). */
    private fun landmarkBox(p: FloatArray, f: PoseFrame): FloatArray? {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until HL.COUNT) { x0 = minOf(x0, p[3 * i]); x1 = maxOf(x1, p[3 * i]); y0 = minOf(y0, p[3 * i + 1]); y1 = maxOf(y1, p[3 * i + 1]) }
        val size = max(2.0 * max(x1 - x0, y1 - y0), 48.0)
        return square((x0 + x1) / 2.0, (y0 + y1) / 2.0, size, f)
    }

    /** First-detection box from the pose model's wrist and knuckle points. */
    private fun poseBox(f: PoseFrame, left: Boolean): FloatArray? {
        val wrist = if (left) BP.LEFT_WRIST else BP.RIGHT_WRIST
        val elbow = if (left) BP.LEFT_ELBOW else BP.RIGHT_ELBOW
        if (!f.visible(wrist, 0.3f)) return null
        val wx = f.u(wrist); val wy = f.v(wrist)
        val knuckles = f.meanPx(if (left) BP.LEFT_INDEX else BP.RIGHT_INDEX, if (left) BP.LEFT_PINKY else BP.RIGHT_PINKY, minVis = 0.2f)
        return if (knuckles != null) {
            val dx = knuckles.first - wx; val dy = knuckles.second - wy
            square(wx + 1.0 * dx, wy + 1.0 * dy, max(4.5 * hypot(dx, dy), 48.0), f)
        } else if (f.visible(elbow, 0.3f)) {
            val fx = wx - f.u(elbow); val fy = wy - f.v(elbow)
            square(wx + 0.25 * fx, wy + 0.25 * fy, max(0.8 * hypot(fx, fy), 48.0), f)
        } else null
    }

    private fun square(cx: Double, cy: Double, size: Double, f: PoseFrame): FloatArray {
        val s = size.coerceAtMost(minOf(f.width, f.height).toDouble())
        val x0 = (cx - s / 2).coerceIn(0.0, f.width - s)
        val y0 = (cy - s / 2).coerceIn(0.0, f.height - s)
        return floatArrayOf(x0.toFloat(), y0.toFloat(), s.toFloat())
    }

    private fun wristPx(f: PoseFrame, left: Boolean): FloatArray? {
        val w = if (left) BP.LEFT_WRIST else BP.RIGHT_WRIST
        return if (f.visible(w, 0.3f)) floatArrayOf(f.px[2 * w], f.px[2 * w + 1]) else null
    }

    /** Elbow→wrist direction; 3D from pose world landmarks when available (same axes as the image). */
    private fun forearm(f: PoseFrame, left: Boolean): Vec3? {
        val w = if (left) BP.LEFT_WRIST else BP.RIGHT_WRIST
        val e = if (left) BP.LEFT_ELBOW else BP.RIGHT_ELBOW
        if (!f.visible(w, 0.3f) || !f.visible(e, 0.3f)) return null
        val world = f.world
        return if (world != null) Vec3(
            (world[3 * w] - world[3 * e]).toDouble(), (world[3 * w + 1] - world[3 * e + 1]).toDouble(), (world[3 * w + 2] - world[3 * e + 2]).toDouble())
        else Vec3(f.u(w) - f.u(e), f.v(w) - f.v(e), 0.0)
    }

    override fun close() {
        executor.execute { sides.forEach { runCatching { it.landmarker.close() } } }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "HandTracker"
        const val CROP = 256
        private const val LOST_AFTER_US = 200_000L
    }
}
