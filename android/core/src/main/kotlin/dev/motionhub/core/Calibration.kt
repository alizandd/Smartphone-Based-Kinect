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
    /** True when calibrated from the upper body only (feet were not visible): less accurate. */
    val partial: Boolean = false,
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
 * Upper-body fallback when the feet are not visible (small room, phone on a desk): distance from
 * the vertical size of the torso (shoulder line → hip line ≈ 0.288 × stature), camera height from
 * the eye ray. Less accurate than [StatureSolver] (posture and torso proportions vary).
 */
object UpperBodySolver {
    const val TORSO_RATIO = 0.288

    fun solve(cam: CameraModel, f: PoseFrame, statureM: Double): StatureSolver.Solution? {
        val sh = f.meanPx(BP.LEFT_SHOULDER, BP.RIGHT_SHOULDER) ?: return null
        val hp = f.meanPx(BP.LEFT_HIP, BP.RIGHT_HIP) ?: return null
        val eye = f.meanPx(BP.LEFT_EYE, BP.RIGHT_EYE) ?: return null
        val rs = cam.rayG(sh.first, sh.second)
        val rh = cam.rayG(hp.first, hp.second)
        val re = cam.rayG(eye.first, eye.second)
        val dTan = cam.tanElevation(rs) - cam.tanElevation(rh)
        if (dTan <= 0.01) return null
        val d = TORSO_RATIO * statureM / dTan
        val h = Anthropometry.EYE_HEIGHT_RATIO * statureM - d * cam.tanElevation(re)
        if (d !in 0.4..8.0 || h !in 0.05..3.0) return null
        val hip = cam.atDepth(rh, d) ?: return null
        return StatureSolver.Solution(d, h, Vec3(hip.x, -h, hip.z))
    }
}

/**
 * Collects still frames and solves distance and camera height from their median (docs/04).
 * Hints say what to fix, in priority order: phone level/tilt, nobody in view, face the camera,
 * feet visible. With the upper body only, it calibrates anyway after [samplesNeeded] still frames
 * (result marked partial).
 */
class Calibrator(private val cam: CameraModel, val statureM: Double, private val samplesNeeded: Int = 60) {
    enum class Stage { VISIBILITY, STAND_STILL, DONE, FAILED }

    data class Status(
        val stage: Stage,
        val progress: Double,
        val hint: String,
        /** What the camera sees: head, shoulders, hips, knees, feet. */
        val parts: Map<String, Boolean> = emptyMap(),
    )

    private class Samples {
        val d = ArrayList<Double>(); val h = ArrayList<Double>(); val x = ArrayList<Double>(); val z = ArrayList<Double>()
        val torso = ArrayList<Double>()
        fun add(s: StatureSolver.Solution, torsoPx: Double?) { d += s.distanceM; h += s.cameraHeightM; x += s.footG.x; z += s.footG.z; torsoPx?.let { torso += it } }
    }

    private val full = Samples()
    private val upper = Samples()
    private val window = ArrayDeque<DoubleArray>()
    private var framesSeen = 0
    var result: CalibrationResult? = null; private set

    fun update(f: PoseFrame): Status {
        result?.let { return Status(Stage.DONE, 1.0, "done") }
        framesSeen++
        if (framesSeen > 60 * 60) return Status(Stage.FAILED, progress(), "timeout")

        val parts = linkedMapOf(
            "head" to (f.visible(BP.NOSE) || f.visible(BP.LEFT_EYE) || f.visible(BP.RIGHT_EYE)),
            "shoulders" to (f.visible(BP.LEFT_SHOULDER) && f.visible(BP.RIGHT_SHOULDER)),
            "hips" to (f.visible(BP.LEFT_HIP) && f.visible(BP.RIGHT_HIP)),
            "knees" to (f.visible(BP.LEFT_KNEE) || f.visible(BP.RIGHT_KNEE)),
            "feet" to (footContactPx(f) != null && (f.visible(BP.LEFT_ANKLE) || f.visible(BP.RIGHT_ANKLE))),
        )
        fun vis(hint: String) = Status(Stage.VISIBILITY, progress(), hint, parts).also { window.clear() }

        // 1. Phone placement (measured by the gravity sensor).
        if (kotlin.math.abs(cam.rollDeg) > 12) return vis("level_phone")
        if (cam.pitchDeg > 25) return vis("tilt_down")
        if (cam.pitchDeg < -40) return vis("tilt_up")
        // 2. Is someone there, facing the camera?
        if (f.keyJointsVisible() < 4) return vis("nobody_in_view")
        val eye = f.meanPx(BP.LEFT_EYE, BP.RIGHT_EYE)
        if (eye == null || !parts.getValue("shoulders")) return vis("face_camera")
        val fullBody = parts.getValue("feet") && f.keyJointsVisible() >= BP.KEY_JOINTS.size - 1
        val upperBody = parts.getValue("hips")
        if (!fullBody && !upperBody) return vis("step_back")

        // 3. Stillness: key points move less than 1.5% of the body's pixel size over 20 frames.
        val sh = f.meanPx(BP.LEFT_SHOULDER, BP.RIGHT_SHOULDER)!!
        val hp = f.meanPx(BP.LEFT_HIP, BP.RIGHT_HIP)!!
        val sizePx = max(1.0, kotlin.math.hypot(hp.first - sh.first, hp.second - sh.second) * (if (fullBody) 3.5 else 1.0))
        val probe = doubleArrayOf(f.u(BP.LEFT_WRIST), f.v(BP.LEFT_WRIST), f.u(BP.RIGHT_WRIST), f.v(BP.RIGHT_WRIST),
            sh.first, sh.second, hp.first, hp.second, eye.first, eye.second)
        window.addLast(probe)
        if (window.size > 20) window.removeFirst()
        val hintMoving = if (fullBody) "stand_still" else "feet_not_visible"
        if (window.size < 20) return Status(Stage.STAND_STILL, progress(), hintMoving, parts)
        var maxRange = 0.0
        for (k in probe.indices) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (s in window) { lo = minOf(lo, s[k]); hi = maxOf(hi, s[k]) }
            maxRange = max(maxRange, hi - lo)
        }
        if (maxRange > 0.015 * sizePx * (if (fullBody) 1.0 else 3.0)) return Status(Stage.STAND_STILL, progress(), hintMoving, parts)

        // 4. Solve and collect.
        if (fullBody) {
            val foot = footContactPx(f)!!
            val sol = StatureSolver.solve(cam, foot.first, foot.second, eye.first, eye.second, statureM)
                ?: return Status(Stage.STAND_STILL, progress(), "check_phone_angle", parts)
            full.add(sol, torsoPx(f))
        } else {
            val sol = UpperBodySolver.solve(cam, f, statureM)
                ?: return Status(Stage.STAND_STILL, progress(), "check_phone_angle", parts)
            upper.add(sol, torsoPx(f))
        }
        val done = when {
            full.d.size >= samplesNeeded -> full to false
            upper.d.size >= samplesNeeded * 3 / 2 -> upper to true      // needs longer: noisier
            else -> null
        }
        if (done != null) {
            val (s, partial) = done
            val d = median(s.d); val h = median(s.h)
            result = CalibrationResult(
                statureM = statureM, cameraHeightM = h, distanceM = d,
                originG = Vec3(median(s.x), -h, median(s.z)),
                kScale = if (s.torso.size > s.d.size / 2) d * median(s.torso) else null,
                pitchDeg = cam.pitchDeg, rollDeg = cam.rollDeg, partial = partial,
            )
            return Status(Stage.DONE, 1.0, if (partial) "done_without_feet" else "done", parts)
        }
        return Status(Stage.STAND_STILL, progress(), hintMoving, parts)
    }

    private fun progress() = max(full.d.size.toDouble() / samplesNeeded, upper.d.size.toDouble() / (samplesNeeded * 3 / 2)).coerceAtMost(1.0)
}
