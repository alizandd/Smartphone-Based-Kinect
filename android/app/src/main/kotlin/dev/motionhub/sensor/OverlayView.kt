package dev.motionhub.sensor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.motionhub.core.BP

/** Draws the latest 2D landmarks over a FIT_CENTER preview of the same 4:3 frame. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    @Volatile private var px: FloatArray? = null
    @Volatile private var vis: FloatArray? = null
    @Volatile private var imgW = 1
    @Volatile private var imgH = 1
    @Volatile private var hands: List<FloatArray> = emptyList()
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 184, 77); strokeWidth = 3f }

    fun updateHands(h: List<FloatArray>) { hands = h; postInvalidate() }

    @Volatile private var face: FloatArray? = null
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 120, 200) }
    fun updateFace(points: FloatArray?) { face = points; postInvalidate() }

    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 255, 160); strokeWidth = 6f }
    private val weak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 200, 60); strokeWidth = 4f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** The front-camera preview is mirrored on screen; mirror the drawing to match. */
    @Volatile var mirrored = false

    fun update(px: FloatArray?, vis: FloatArray?, w: Int, h: Int) {
        this.px = px; this.vis = vis; imgW = w; imgH = h
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = px ?: return
        val v = vis ?: return
        val scale = minOf(width.toFloat() / imgW, height.toFloat() / imgH)
        val ox = (width - imgW * scale) / 2
        val oy = (height - imgH * scale) / 2
        fun mx(v: Float) = if (mirrored) imgW - v else v
        fun x(i: Int) = ox + mx(p[2 * i]) * scale
        fun y(i: Int) = oy + p[2 * i + 1] * scale
        for ((a, b) in BONES) {
            if (p[2 * a].isNaN() || p[2 * b].isNaN()) continue
            canvas.drawLine(x(a), y(a), x(b), y(b), if (minOf(v[a], v[b]) >= 0.5f) bone else weak)
        }
        for (i in 0 until BP.COUNT) if (!p[2 * i].isNaN() && v[i] >= 0.5f) canvas.drawCircle(x(i), y(i), 5f, dot)
        face?.let { fp -> for (k in 0 until fp.size / 2) canvas.drawCircle(ox + mx(fp[2 * k]) * scale, oy + fp[2 * k + 1] * scale, 3f, facePaint) }
        for (h in hands) {
            fun hx(i: Int) = ox + mx(h[3 * i]) * scale
            fun hy(i: Int) = oy + h[3 * i + 1] * scale
            for ((a, b) in dev.motionhub.core.HL.BONES) canvas.drawLine(hx(a), hy(a), hx(b), hy(b), finger)
        }
    }

    companion object {
        val BONES = listOf(
            BP.LEFT_SHOULDER to BP.RIGHT_SHOULDER, BP.LEFT_HIP to BP.RIGHT_HIP,
            BP.LEFT_SHOULDER to BP.LEFT_HIP, BP.RIGHT_SHOULDER to BP.RIGHT_HIP,
            BP.LEFT_SHOULDER to BP.LEFT_ELBOW, BP.LEFT_ELBOW to BP.LEFT_WRIST,
            BP.RIGHT_SHOULDER to BP.RIGHT_ELBOW, BP.RIGHT_ELBOW to BP.RIGHT_WRIST,
            BP.LEFT_HIP to BP.LEFT_KNEE, BP.LEFT_KNEE to BP.LEFT_ANKLE,
            BP.RIGHT_HIP to BP.RIGHT_KNEE, BP.RIGHT_KNEE to BP.RIGHT_ANKLE,
            BP.LEFT_ANKLE to BP.LEFT_HEEL, BP.LEFT_HEEL to BP.LEFT_TOE, BP.LEFT_ANKLE to BP.LEFT_TOE,
            BP.RIGHT_ANKLE to BP.RIGHT_HEEL, BP.RIGHT_HEEL to BP.RIGHT_TOE, BP.RIGHT_ANKLE to BP.RIGHT_TOE,
            BP.NOSE to BP.LEFT_EYE, BP.NOSE to BP.RIGHT_EYE,
        )
    }
}
