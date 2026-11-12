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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import dev.motionhub.core.BP
import dev.motionhub.core.PoseFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

/**
 * Facial expressions at living-room distance: crop the face around the pose model's face points,
 * scale it to 256×256 and run MediaPipe Face Landmarker with blendshapes on its own thread
 * (latest frame wins, body tracking never waits). At 2.5 m the face is only ~50–70 px, so
 * expressions with large visual change (smile, open mouth) work best; winks and sadness are
 * better at 1–1.5 m or with a higher analysis resolution.
 */
class FaceTracker(private val context: Context, private val onResult: (Result) -> Unit) : AutoCloseable {

    class Result(
        val tCaptureUs: Long,
        val blendshapes: Map<String, Float>?,   // null = no face found in the crop
        val box: FloatArray,                    // crop square in full-frame pixels: left, top, size
        val outline: FloatArray?,               // a few landmark points (x, y) in full-frame pixels, for drawing
        val latencyMs: Double,
        val imageW: Int,
        val imageH: Int,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val crop = Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastTsMs = -1L
    var delegate = "GPU"; private set
    private val landmarker: FaceLandmarker = create(Delegate.GPU) ?: create(Delegate.CPU).also { delegate = "CPU" }
        ?: error("Could not create FaceLandmarker")

    private fun create(d: Delegate): FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(d).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.4f)
            .setMinFacePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setOutputFaceBlendshapes(true)
            .build())
    } catch (e: Exception) {
        Log.w(TAG, "FaceLandmarker with $d failed", e); null
    }

    fun submit(bitmap: Bitmap, pose: PoseFrame) {
        if (!busy.compareAndSet(false, true)) return
        val b = faceBox(pose) ?: run { busy.set(false); return }
        canvas.drawBitmap(bitmap, Rect(b[0].toInt(), b[1].toInt(), (b[0] + b[2]).toInt(), (b[1] + b[2]).toInt()),
            Rect(0, 0, CROP, CROP), paint)
        val image = BitmapImageBuilder(crop.copy(Bitmap.Config.ARGB_8888, false)).build()
        val t0 = SystemClock.elapsedRealtimeNanos()
        executor.execute {
            try {
                var ts = pose.tUs / 1000
                if (ts <= lastTsMs) ts = lastTsMs + 1
                lastTsMs = ts
                val r = landmarker.detectForVideo(image, ts)
                val shapes = r.faceBlendshapes().orElse(null)?.firstOrNull()?.associate { it.categoryName() to it.score() }
                val outline = r.faceLandmarks().firstOrNull()?.let { lms ->
                    val s = b[2] / CROP
                    FloatArray(OUTLINE.size * 2).also { o ->
                        OUTLINE.forEachIndexed { k, i ->
                            if (i < lms.size) { o[2 * k] = b[0] + lms[i].x() * CROP * s; o[2 * k + 1] = b[1] + lms[i].y() * CROP * s }
                        }
                    }
                }
                onResult(Result(pose.tUs, shapes, b, outline, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, pose.width, pose.height))
            } catch (e: Exception) {
                Log.w(TAG, "face inference failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Square around the face from the pose face points (eyes, ears, nose, mouth). */
    private fun faceBox(f: PoseFrame): FloatArray? {
        if (!f.visible(BP.NOSE, 0.4f)) return null
        val eyes = f.meanPx(BP.LEFT_EYE, BP.RIGHT_EYE, minVis = 0.3f) ?: return null
        val eyeDist = if (f.visible(BP.LEFT_EYE, 0.3f) && f.visible(BP.RIGHT_EYE, 0.3f))
            hypot(f.u(BP.LEFT_EYE) - f.u(BP.RIGHT_EYE), f.v(BP.LEFT_EYE) - f.v(BP.RIGHT_EYE)) else 0.0
        val earDist = if (f.visible(BP.LEFT_EAR, 0.2f) && f.visible(BP.RIGHT_EAR, 0.2f))
            hypot(f.u(BP.LEFT_EAR) - f.u(BP.RIGHT_EAR), f.v(BP.LEFT_EAR) - f.v(BP.RIGHT_EAR)) else 0.0
        val width = max(earDist, 2.3 * eyeDist)
        if (width < 8) return null
        // Centre between the eyes and the nose, slightly below the eyes.
        val cx = (eyes.first + f.u(BP.NOSE)) / 2
        val cy = (eyes.second + f.v(BP.NOSE)) / 2 + 0.1 * width
        val s = max(2.0 * width, 48.0).coerceAtMost(minOf(f.width, f.height).toDouble())
        val x0 = (cx - s / 2).coerceIn(0.0, f.width - s)
        val y0 = (cy - s / 2).coerceIn(0.0, f.height - s)
        return floatArrayOf(x0.toFloat(), y0.toFloat(), s.toFloat())
    }

    override fun close() {
        executor.execute { runCatching { landmarker.close() } }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "FaceTracker"
        const val CROP = 256
        /** Face-mesh indices for a light outline: eyes and mouth. */
        val OUTLINE = intArrayOf(33, 160, 158, 133, 153, 144, 362, 385, 387, 263, 373, 380, 61, 39, 0, 269, 291, 405, 17, 181)
    }
}
