package dev.motionhub.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class GestureEvent(
    val id: String,
    val phase: String,          // start | peak | end | cancel
    val tUs: Long,
    val confidence: Double,
    val attributes: Map<String, Double> = emptyMap(),
)

/**
 * Phase 0 rule-based detectors (docs/05 §4): jump, squat, side step, lean, punch L/R.
 * Thresholds are starting values to be tuned on the recorded dataset; they are public so the
 * replay CLI and the app can experiment without code changes.
 */
class GestureEngine(var statureM: Double = 1.75) {
    var jumpVy = 0.8
    var jumpPelvisRise = 0.06
    var jumpAnkleRise = 0.04
    var squatDrop = 0.15
    var squatKneeDeg = 125.0
    var stepDistance = 0.25
    var leanEnterDeg = 15.0
    var leanExitDeg = 8.0
    var punchSpeed = 2.5
    var punchReachRatio = 0.6
    var punchElbowDeg = 110.0

    private class Sample(
        val t: Double, val pelvis: Vec3, val ankleY: Double,
        val wristRel: Array<Vec3>, val reach: DoubleArray,
    )

    private val history = ArrayDeque<Sample>()
    private var pelvisY0 = Double.NaN
    private var ankleY0 = Double.NaN
    private var anchorX = Double.NaN
    private var stillSince = Double.NaN

    private enum class Jump { IDLE, UP, DOWN }
    private var jump = Jump.IDLE
    private var jumpStart = 0.0
    private var jumpMaxRise = 0.0
    private var jumpRefractoryUntil = 0.0

    private var squatting = false
    private var squatMaxDrop = 0.0
    private var squatPeakSent = false

    private var stepping = false
    private var stepDir = 0
    private var stepSlowSince = Double.NaN

    private var leanDir = 0
    private var headDir = 0
    var headTurnEnterDeg = 30.0
    var headTurnExitDeg = 15.0

    private val punchActive = BooleanArray(2)
    private val punchStart = DoubleArray(2)
    private val punchPeakSpeed = DoubleArray(2)
    private val punchMaxReach = DoubleArray(2)
    private val punchRefractoryUntil = DoubleArray(2)

    fun reset() {
        history.clear(); pelvisY0 = Double.NaN; ankleY0 = Double.NaN; anchorX = Double.NaN
        jump = Jump.IDLE; squatting = false; stepping = false; leanDir = 0
        punchActive.fill(false)
    }

    fun update(s: Skeleton): List<GestureEvent> {
        if (s.trackingState == J.STATE_LOST) return emptyList()
        val out = ArrayList<GestureEvent>(2)
        val t = s.tUs / 1e6
        val pelvis = s[J.PELVIS]
        val ankleY = min(s[J.LEFT_ANKLE].y, s[J.RIGHT_ANKLE].y)
        val sides = intArrayOf(0, 1)
        val wristRel = Array(2) { i -> s[if (i == 0) J.LEFT_WRIST else J.RIGHT_WRIST] - pelvis }
        val reach = DoubleArray(2) { i ->
            val sh = s[if (i == 0) J.LEFT_SHOULDER else J.RIGHT_SHOULDER]
            val wr = s[if (i == 0) J.LEFT_WRIST else J.RIGHT_WRIST]
            (wr - sh).norm() / (Anthropometry.ARM_RATIO * statureM)
        }
        history.addLast(Sample(t, pelvis, ankleY, wristRel, reach))
        while (history.size > 12) history.removeFirst()
        if (history.size < 4) {
            if (pelvisY0.isNaN()) { pelvisY0 = pelvis.y; ankleY0 = ankleY; anchorX = pelvis.x }
            return out
        }
        val back = history[history.size - 4]
        val dt = max(1e-3, t - back.t)
        val v = (pelvis - back.pelvis) * (1.0 / dt)

        // ---- baseline: update while standing still with straight legs ----
        val kneeL = angleDeg(s[J.LEFT_HIP], s[J.LEFT_KNEE], s[J.LEFT_ANKLE])
        val kneeR = angleDeg(s[J.RIGHT_HIP], s[J.RIGHT_KNEE], s[J.RIGHT_ANKLE])
        val still = v.norm() < 0.1 && min(kneeL, kneeR) > 160 && jump == Jump.IDLE && !squatting
        if (still) {
            if (stillSince.isNaN()) stillSince = t
            if (t - stillSince > 0.5) {
                pelvisY0 = if (pelvisY0.isNaN()) pelvis.y else 0.95 * pelvisY0 + 0.05 * pelvis.y
                ankleY0 = if (ankleY0.isNaN()) ankleY else 0.95 * ankleY0 + 0.05 * ankleY
                if (!stepping) anchorX = 0.9 * anchorX + 0.1 * pelvis.x
            }
        } else stillSince = Double.NaN

        val rise = pelvis.y - pelvisY0
        val ankleRise = ankleY - ankleY0

        // ---- jump ----
        when (jump) {
            Jump.IDLE -> if (t >= jumpRefractoryUntil && !squatting && v.y > jumpVy &&
                rise > jumpPelvisRise && ankleRise > jumpAnkleRise
            ) {
                jump = Jump.UP; jumpStart = t; jumpMaxRise = rise
                out += GestureEvent("jump", "start", s.tUs, 0.8, mapOf("vy_mps" to v.y))
            }
            Jump.UP, Jump.DOWN -> {
                jumpMaxRise = max(jumpMaxRise, rise)
                if (jump == Jump.UP && v.y <= 0) {
                    jump = Jump.DOWN
                    out += GestureEvent("jump", "peak", s.tUs, 0.9, mapOf("height_m" to jumpMaxRise))
                }
                if (ankleRise < 0.025 && rise < 0.05) {
                    out += GestureEvent("jump", "end", s.tUs, 0.9,
                        mapOf("height_m" to jumpMaxRise, "airtime_s" to t - jumpStart))
                    jump = Jump.IDLE; jumpRefractoryUntil = t + 0.2
                } else if (t - jumpStart > 1.5) {
                    out += GestureEvent("jump", "cancel", s.tUs, 0.0)
                    jump = Jump.IDLE
                }
            }
        }

        // ---- squat ----
        val drop = pelvisY0 - pelvis.y
        if (!squatting) {
            // Frontal 2D views foreshorten the thigh, so the knee angle is only trusted with 3D landmarks.
            val kneeOk = s.zConfidence < 0.4 || min(kneeL, kneeR) < squatKneeDeg
            if (jump == Jump.IDLE && drop > squatDrop && kneeOk && ankleRise < jumpAnkleRise) {
                squatting = true; squatMaxDrop = drop; squatPeakSent = false
                out += GestureEvent("squat", "start", s.tUs, 0.85)
            }
        } else {
            if (drop > squatMaxDrop) squatMaxDrop = drop
            if (!squatPeakSent && drop < squatMaxDrop - 0.03) {
                squatPeakSent = true
                out += GestureEvent("squat", "peak", s.tUs, 0.9, mapOf("depth_m" to squatMaxDrop))
            }
            if (drop < 0.06) {
                squatting = false
                out += GestureEvent("squat", "end", s.tUs, 0.9, mapOf("depth_m" to squatMaxDrop))
            }
        }

        // ---- side step (+x is the player's right) ----
        val dx = pelvis.x - anchorX
        if (!stepping) {
            if (abs(dx) > stepDistance && abs(v.x) > 0.3) {
                stepping = true; stepDir = if (dx > 0) 1 else -1; stepSlowSince = Double.NaN
                out += GestureEvent(if (stepDir > 0) "step_r" else "step_l", "start", s.tUs, 0.85)
            }
        } else {
            if (abs(v.x) < 0.1) {
                if (stepSlowSince.isNaN()) stepSlowSince = t
                if (t - stepSlowSince > 0.3) {
                    out += GestureEvent(if (stepDir > 0) "step_r" else "step_l", "end", s.tUs, 0.9,
                        mapOf("distance_m" to abs(dx)))
                    stepping = false; anchorX = pelvis.x
                }
            } else stepSlowSince = Double.NaN
        }

        // ---- lean / dodge: trunk tilt in the frontal plane, feet planted ----
        val trunk = s[J.NECK] - pelvis
        val leanDeg = Math.toDegrees(atan2(trunk.x, trunk.y))
        if (leanDir == 0) {
            if (!stepping && jump == Jump.IDLE && abs(leanDeg) > leanEnterDeg) {
                leanDir = if (leanDeg > 0) 1 else -1
                out += GestureEvent(if (leanDir > 0) "lean_r" else "lean_l", "start", s.tUs, 0.8,
                    mapOf("angle_deg" to leanDeg))
            }
        } else if (abs(leanDeg) < leanExitDeg) {
            out += GestureEvent(if (leanDir > 0) "lean_r" else "lean_l", "end", s.tUs, 0.9)
            leanDir = 0
        }

        // ---- head turn (look left / right) ----
        s.head?.let { h ->
            if (headDir == 0) {
                if (h.confidence >= 0.4 && kotlin.math.abs(h.yawDeg - s.facingYawDeg) > headTurnEnterDeg) {
                    headDir = if (h.yawDeg - s.facingYawDeg > 0) -1 else 1     // +yaw = toward the player's left
                    out += GestureEvent(if (headDir > 0) "look_r" else "look_l", "start", s.tUs, h.confidence,
                        mapOf("yaw_deg" to h.yawDeg))
                }
            } else if (kotlin.math.abs(h.yawDeg - s.facingYawDeg) < headTurnExitDeg) {
                out += GestureEvent(if (headDir > 0) "look_r" else "look_l", "end", s.tUs, h.confidence)
                headDir = 0
            }
        }

        // ---- punches ----
        for (i in sides) {
            val elbow = angleDeg(
                s[if (i == 0) J.LEFT_SHOULDER else J.RIGHT_SHOULDER],
                s[if (i == 0) J.LEFT_ELBOW else J.RIGHT_ELBOW],
                s[if (i == 0) J.LEFT_WRIST else J.RIGHT_WRIST],
            )
            val vw = (wristRel[i] - back.wristRel[i]) * (1.0 / dt)
            val speed = vw.norm()
            val name = if (i == 0) "punch_l" else "punch_r"
            if (!punchActive[i]) {
                val extending = reach[i] > back.reach[i] + 0.05
                if (t >= punchRefractoryUntil[i] && jump == Jump.IDLE && speed > punchSpeed &&
                    extending && reach[i] > punchReachRatio && elbow > punchElbowDeg
                ) {
                    punchActive[i] = true; punchStart[i] = t
                    punchPeakSpeed[i] = speed; punchMaxReach[i] = reach[i]
                    val dir = vw.normalized()
                    out += GestureEvent(name, "start", s.tUs, 0.75, mapOf(
                        "speed_mps" to speed, "dir_x" to dir.x, "dir_y" to dir.y, "dir_z" to dir.z,
                    ))
                }
            } else {
                punchPeakSpeed[i] = max(punchPeakSpeed[i], speed)
                punchMaxReach[i] = max(punchMaxReach[i], reach[i])
                if (speed < 1.0 || t - punchStart[i] > 0.4) {
                    out += GestureEvent(name, "end", s.tUs, 0.85, mapOf(
                        "peak_speed_mps" to punchPeakSpeed[i], "reach_ratio" to punchMaxReach[i],
                    ))
                    punchActive[i] = false; punchRefractoryUntil[i] = t + 0.25
                }
            }
        }
        return out
    }
}

/**
 * Latency probe (docs/07 §5): fires when the mean luma of the image centre jumps, e.g. when a
 * phone flashlight or LED is switched on in front of the camera.
 */
class LumaProbe(var jump: Double = 60.0, var minLuma: Double = 170.0) {
    private var baseline = Double.NaN
    private var armed = true

    /** Returns true on the frame where a flash is first seen. */
    fun update(luma: Double): Boolean {
        if (baseline.isNaN()) { baseline = luma; return false }
        val fired = armed && luma - baseline > jump && luma > minLuma
        if (fired) armed = false
        if (!armed && luma < baseline + jump / 2) armed = true
        if (armed) baseline = 0.9 * baseline + 0.1 * luma
        return fired
    }
}
