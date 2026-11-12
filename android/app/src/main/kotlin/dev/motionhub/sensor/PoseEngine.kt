package dev.motionhub.sensor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import dev.motionhub.core.BP
import dev.motionhub.core.PoseFrame
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaPipe Pose Landmarker in LIVE_STREAM mode behind a tiny backend boundary.
 * Results are delivered on MediaPipe's callback thread, in order.
 */
class PoseEngine(
    private val context: Context,
    val model: String,                  // lite | full | heavy
    preferredDelegate: String,          // GPU | CPU
    private val onResult: (Result) -> Unit,
) : AutoCloseable {

    class Result(
        val frame: PoseFrame?,          // null when nobody was detected
        val zRel: FloatArray?,          // MediaPipe normalised z per landmark (for recording)
        val tCaptureUs: Long,
        val tArrivalUs: Long,
        val tResultUs: Long,
        /** Just before detectAsync() (after app-side preprocessing). */
        val tSubmitUs: Long,
        /** Time spent inside the synchronous detectAsync() call (image upload etc.), µs. */
        val detectCallUs: Long,
        val width: Int,
        val height: Int,
    )

    var delegate: String = preferredDelegate; private set
    private val pending = ConcurrentHashMap<Long, LongArray>()
    private var lastTsMs = -1L
    private val landmarker: PoseLandmarker =
        (if (preferredDelegate == "CPU") create(Delegate.CPU) else create(Delegate.GPU))
            ?: create(Delegate.CPU).also { delegate = "CPU" }
            ?: error("Could not create PoseLandmarker")

    private fun create(d: Delegate): PoseLandmarker? = try {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_$model.task").setDelegate(d).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .setResultListener { result, _ -> deliver(result) }
            .setErrorListener { e -> Log.e(TAG, "MediaPipe error", e) }
            .build()
        PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.w(TAG, "PoseLandmarker with $d failed", e); null
    }

    /** [bitmap] must not be reused by the caller: MediaPipe reads it asynchronously. */
    fun detect(bitmap: Bitmap, rotationDeg: Int, tCaptureUs: Long, tArrivalUs: Long) {
        var ts = tCaptureUs / 1000
        if (ts <= lastTsMs) ts = lastTsMs + 1           // MediaPipe requires strictly increasing ms
        lastTsMs = ts
        val upright = rotationDeg % 180 == 0
        val w = if (upright) bitmap.width else bitmap.height
        val h = if (upright) bitmap.height else bitmap.width
        val meta = longArrayOf(tCaptureUs, tArrivalUs, w.toLong(), h.toLong(), 0L, 0L)
        pending[ts] = meta
        val opts = ImageProcessingOptions.builder().setRotationDegrees(rotationDeg).build()
        val tSubmit = SystemClock.elapsedRealtimeNanos() / 1000
        meta[4] = tSubmit
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), opts, ts)
        meta[5] = SystemClock.elapsedRealtimeNanos() / 1000 - tSubmit
    }

    private fun deliver(r: PoseLandmarkerResult) {
        val meta = pending.remove(r.timestampMs()) ?: return
        // Drop stale entries for frames MediaPipe skipped (flow limiting).
        pending.keys.removeIf { it < r.timestampMs() }
        val tResult = SystemClock.elapsedRealtimeNanos() / 1000
        val w = meta[2].toInt(); val h = meta[3].toInt()
        val lms = r.landmarks()
        if (lms.isEmpty()) {
            onResult(Result(null, null, meta[0], meta[1], tResult, meta[4], meta[5], w, h)); return
        }
        val px = FloatArray(BP.COUNT * 2) { Float.NaN }
        val vis = FloatArray(BP.COUNT)
        val zRel = FloatArray(BP.COUNT)
        lms[0].forEachIndexed { i, l ->
            if (i < BP.COUNT) {
                px[2 * i] = l.x() * w; px[2 * i + 1] = l.y() * h
                zRel[i] = l.z()
                vis[i] = l.visibility().orElse(1f)
            }
        }
        val world = r.worldLandmarks().firstOrNull()?.let { wl ->
            FloatArray(BP.COUNT * 3).also { a ->
                wl.forEachIndexed { i, l -> if (i < BP.COUNT) { a[3 * i] = l.x(); a[3 * i + 1] = l.y(); a[3 * i + 2] = l.z() } }
            }
        }
        onResult(Result(PoseFrame(meta[0], w, h, px, vis, world), zRel, meta[0], meta[1], tResult, meta[4], meta[5], w, h))
    }

    override fun close() = landmarker.close()

    companion object { private const val TAG = "PoseEngine" }
}
