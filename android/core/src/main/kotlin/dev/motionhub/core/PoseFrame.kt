package dev.motionhub.core

/** BlazePose / MediaPipe 33-landmark indices. Other backends are mapped into this layout. */
object BP {
    const val COUNT = 33
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_TOE = 31
    const val RIGHT_TOE = 32

    /** Joints that must be visible for a "full body" detection (calibration Stage 3). */
    val KEY_JOINTS = intArrayOf(
        NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST,
        LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE,
    )
}

/**
 * Model-agnostic pose observation for one person in one camera frame.
 *
 * @param px 33×2 pixel coordinates (x right, y down) in the upright analysed image; NaN = missing.
 * @param vis 33 visibility/confidence values in 0..1.
 * @param world optional 33×3 hip-centred metric landmarks (MediaPipe "world landmarks":
 *   +x image-right, +y down, +z away from the camera). Null for 2D-only backends.
 */
class PoseFrame(
    val tUs: Long,
    val width: Int,
    val height: Int,
    val px: FloatArray,
    val vis: FloatArray,
    val world: FloatArray?,
) {
    init {
        require(px.size == BP.COUNT * 2 && vis.size == BP.COUNT)
        require(world == null || world.size == BP.COUNT * 3)
    }

    companion object {
        /** A frame with no person in it (the pose model found nobody). */
        fun empty(tUs: Long, width: Int, height: Int) =
            PoseFrame(tUs, width, height, FloatArray(BP.COUNT * 2) { Float.NaN }, FloatArray(BP.COUNT), null)
    }

    fun visible(i: Int, minVis: Float = 0.5f) = vis[i] >= minVis && !px[2 * i].isNaN()
    fun u(i: Int) = px[2 * i].toDouble()
    fun v(i: Int) = px[2 * i + 1].toDouble()
    fun worldZ(i: Int): Double = world?.get(3 * i + 2)?.toDouble() ?: 0.0

    /** Mean pixel position of the visible landmarks among [ids], or null if none is visible. */
    fun meanPx(vararg ids: Int, minVis: Float = 0.5f): Pair<Double, Double>? {
        var su = 0.0; var sv = 0.0; var n = 0
        for (i in ids) if (visible(i, minVis)) { su += u(i); sv += v(i); n++ }
        return if (n == 0) null else Pair(su / n, sv / n)
    }

    fun keyJointsVisible(minVis: Float = 0.5f) = BP.KEY_JOINTS.count { visible(it, minVis) }
}
