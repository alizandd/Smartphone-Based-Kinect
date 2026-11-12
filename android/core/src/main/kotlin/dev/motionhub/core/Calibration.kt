package dev.motionhub.core

import kotlin.math.hypot
import kotlin.math.max

/** Result of the Phase 0 calibration (subset of docs/04 Stages 3–5). */
data class CalibrationResult(
    val statureM: Double,
    val cameraHeightM: Double,
    val distanceM: Double,
    /** Play Space origin expressed in the G frame (a floor point, so y = −cameraHeight). */
    val originG: Vec3,
    /** distance × torso length in pixels at calibration; distance = kScale / torsoPx later. */
    val kScale: Double?,
    val pitchDeg: Double,
    val rollDeg: Double,
)

object Anthropometry {
    /** Standing eye height ≈ 0.936 × stature (docs/04 Stage 4). */
    const val EYE_HEIGHT_RATIO = 0.936
    /** Shoulder-to-wrist length ≈ 0.332 × stature (upper arm 0.186 + forearm 0.146). */
    const val ARM_RATIO = 0.332
}

/** Pixel location of the foot–floor contact: mean of visible heel and toe landmarks. */
fun footContactPx(f: PoseFrame) =
    f.meanPx(BP.LEFT_HEEL, BP.RIGHT_HEEL, BP.LEFT_TOE, BP.RIGHT_TOE)
        ?: f.meanPx(BP.LEFT_ANKLE, BP.RIGHT_ANKLE)

fun torsoPx(f: PoseFrame): Double? {
    val sh = f.meanPx(BP.LEFT_SHOULDER, BP.RIGHT_SHOULDER) ?: return null
    val hp = f.meanPx(BP.LEFT_HIP, BP.RIGHT_HIP) ?: return null
    if (!(f.visible(BP.LEFT_SHOULDER) && f.visible(BP.RIGHT_SHOULDER) &&
            f.visible(BP.LEFT_HIP) && f.visible(BP.RIGHT_HIP))) return null
    return hypot(sh.first - hp.first, sh.second - hp.second)
}

/**
 * Solve horizontal player distance D and camera height h from one upright pose, given stature.
 *   tan(α_foot) = h / D,  tan(β_eye) = (E − h) / D  ⇒  D = E / (tan α + tan β),  h = D · tan α
 */
object StatureSolver {
    data class Solution(val distanceM: Double, val cameraHeightM: Double, val footG: Vec3)

    fun solve(cam: CameraModel, footU: Double, footV: Double, eyeU: Double, eyeV: Double, statureM: Double): Solution? {
        val rf = cam.rayG(footU, footV)
        val re = cam.rayG(eyeU, eyeV)
        val tanAlpha = -cam.tanElevation(rf)
        val tanBeta = cam.tanElevation(re)
        val denom = tanAlpha + tanBeta
        if (tanAlpha <= 0.01 || denom <= 0.01) return null
        val eye = Anthropometry.EYE_HEIGHT_RATIO * statureM
        val d = eye / denom
        val h = d * tanAlpha
        if (d !in 0.5..8.0 || h !in 0.05..3.0) return null
        val foot = cam.floorPoint(rf, h) ?: return null
        return Solution(d, h, foot)
    }
}

/**
 * Collects still, fully visible frames and solves D/h from their median.
 * Stage hints mirror docs/04 so the TV can show guidance.
 */
class Calibrator(private val cam: CameraModel, val statureM: Double, private val samplesNeeded: Int = 90) {
    enum class Stage { VISIBILITY, STAND_STILL, DONE, FAILED }

    data class Status(val stage: Stage, val progress: Double, val hint: String)

    private val dSamples = ArrayList<Double>()
    private val hSamples = ArrayList<Double>()
    private val xSamples = ArrayList<Double>()
    private val zSamples = ArrayList<Double>()
    private val torsoSamples = ArrayList<Double>()
    private val window = ArrayDeque<DoubleArray>()
    private var framesSeen = 0
    var result: CalibrationResult? = null; private set

    fun update(f: PoseFrame): Status {
        result?.let { return Status(Stage.DONE, 1.0, "done") }
        framesSeen++
        if (framesSeen > 60 * 60) return Status(Stage.FAILED, progress(), "timeout")

        val foot = footContactPx(f)
        val eye = f.meanPx(BP.LEFT_EYE, BP.RIGHT_EYE)
        val visibleKeys = f.keyJointsVisible()
        if (foot == null || eye == null || visibleKeys < BP.KEY_JOINTS.size - 1) {
            window.clear()
            val hint = when {
                foot == null || (foot.second > f.height * 0.97) -> "step_back"
                eye == null || eye.second < f.height * 0.03 -> "step_back"
                else -> "full_body_not_visible"
            }
            return Status(Stage.VISIBILITY, progress(), hint)
        }

        // Stillness: key points move less than 1.5% of body pixel height over a 20-frame window.
        val bodyPx = max(1.0, foot.second - eye.second)
        val probe = doubleArrayOf(
            f.u(BP.LEFT_WRIST), f.v(BP.LEFT_WRIST), f.u(BP.RIGHT_WRIST), f.v(BP.RIGHT_WRIST),
            foot.first, foot.second, eye.first, eye.second,
        )
        window.addLast(probe)
        if (window.size > 20) window.removeFirst()
        if (window.size < 20) return Status(Stage.STAND_STILL, progress(), "stand_still")
        var maxRange = 0.0
        for (k in probe.indices) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (s in window) { lo = minOf(lo, s[k]); hi = maxOf(hi, s[k]) }
            maxRange = max(maxRange, hi - lo)
        }
        if (maxRange > 0.015 * bodyPx) return Status(Stage.STAND_STILL, progress(), "stand_still")

        val sol = StatureSolver.solve(cam, foot.first, foot.second, eye.first, eye.second, statureM)
            ?: return Status(Stage.STAND_STILL, progress(), "check_phone_angle")
        dSamples += sol.distanceM; hSamples += sol.cameraHeightM
        xSamples += sol.footG.x; zSamples += sol.footG.z
        torsoPx(f)?.let { torsoSamples += it }

        if (dSamples.size >= samplesNeeded) {
            val d = median(dSamples)
            val h = median(hSamples)
            result = CalibrationResult(
                statureM = statureM,
                cameraHeightM = h,
                distanceM = d,
                originG = Vec3(median(xSamples), -h, median(zSamples)),
                kScale = if (torsoSamples.size > samplesNeeded / 2) d * median(torsoSamples) else null,
                pitchDeg = cam.pitchDeg,
                rollDeg = cam.rollDeg,
            )
            return Status(Stage.DONE, 1.0, "done")
        }
        return Status(Stage.STAND_STILL, progress(), "stand_still")
    }

    private fun progress() = dSamples.size.toDouble() / samplesNeeded
}
