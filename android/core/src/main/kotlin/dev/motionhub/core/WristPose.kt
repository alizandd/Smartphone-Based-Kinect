package dev.motionhub.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Wrist bend relative to the forearm, from the pose model's elbow→wrist direction and the hand
 * model's wrist→middle-knuckle direction.
 *
 * All vectors use IMAGE axes: +x image right (= the player's LEFT, camera faces the player),
 * +y image down, +z away from the camera. Scale does not matter (directions only), so pose world
 * landmarks (metres) and hand landmarks (pixels) can be combined.
 *
 * Two decompositions:
 *  - Screen/body terms (what games usually want): [upDownDeg] (+ = hand bent up) and
 *    [leftRightDeg] (+ = toward the player's right), measured in a frame attached to the forearm
 *    with "up" = world up. When the forearm itself points (nearly) up or down, "up" falls back to
 *    "away from the camera" (tilting a raised hand backwards counts as up).
 *  - Anatomical: [flexDeg] (+ = toward the palm, − = toward the back of the hand) and
 *    [deviationDeg] (+ = toward the thumb, − = toward the little finger).
 */
data class WristPose(
    val upDownDeg: Double,
    val leftRightDeg: Double,
    val flexDeg: Double,
    val deviationDeg: Double,
    /** Which way the palm faces: camera | away | up | down | left | right (player's left/right). */
    val palmFacing: String,
)

object WristPoseEstimator {
    private val UP = Vec3(0.0, -1.0, 0.0)
    private val AWAY = Vec3(0.0, 0.0, 1.0)

    fun estimate(forearm: Vec3, hand: HandLandmarks, side: String): WristPose? {
        val f = forearm.normalized()
        if (f.norm() < 0.5) return null
        fun p(i: Int) = Vec3(hand.x(i), hand.y(i), hand.z(i))
        val wrist = p(HL.WRIST)
        val a = (p(HL.MIDDLE_MCP) - wrist).normalized()
        val idx = p(HL.INDEX_MCP) - wrist
        val pky = p(HL.PINKY_MCP) - wrist
        // Palmar normal: right hand +(index × pinky), left hand −(index × pinky). (Right hand, palm
        // toward the camera, fingers up: the thumb is on the player's left = image +x, so
        // index×pinky points to −z, i.e. toward the camera.)
        val cross = idx cross pky
        val palm = (if (side == "r") cross else -cross).normalized()

        // ---- screen/body frame on the forearm ----
        val refUp = if (abs(f dot UP) > 0.8) AWAY else UP
        val u = (refUp - f * (refUp dot f)).normalized()
        val r = f cross u                         // points to the player's right for a forward forearm
        val upDown = Math.toDegrees(atan2(a dot u, a dot f))
        val leftRight = Math.toDegrees(atan2(a dot r, a dot f))

        // ---- anatomical frame: palmar axis orthogonal to the forearm ----
        val pPerp = (palm - f * (palm dot f)).normalized()
        val flex = Math.toDegrees(atan2(a dot pPerp, a dot f))
        val lateral = f cross pPerp
        val thumbSide = (idx - pky).normalized()
        val sign = if ((thumbSide dot lateral) >= 0) 1.0 else -1.0
        val deviation = sign * Math.toDegrees(atan2(a dot lateral, a dot f))

        return WristPose(upDown, leftRight, flex, deviation, facing(palm))
    }

    /** Dominant direction of the palm normal, named from the player's point of view. */
    fun facing(palm: Vec3): String {
        val ax = abs(palm.x); val ay = abs(palm.y); val az = abs(palm.z)
        return when {
            az >= ax && az >= ay -> if (palm.z < 0) "camera" else "away"
            ay >= ax -> if (palm.y < 0) "up" else "down"
            else -> if (palm.x < 0) "right" else "left"   // image −x = the player's right
        }
    }
}

/** Hysteresis + debounce for wrist bend events: wrist_{up,down,left,right}_{l,r}. */
class WristEventTracker(private val side: String, var enterDeg: Double = 30.0, var exitDeg: Double = 15.0) {
    private var vertical = 0      // +1 up, −1 down
    private var horizontal = 0    // +1 right, −1 left

    fun update(w: WristPose?, tUs: Long, confidence: Double): List<GestureEvent> {
        if (w == null) {
            val out = ArrayList<GestureEvent>()
            if (vertical != 0) out += end(if (vertical > 0) "up" else "down", tUs)
            if (horizontal != 0) out += end(if (horizontal > 0) "right" else "left", tUs)
            vertical = 0; horizontal = 0
            return out
        }
        val out = ArrayList<GestureEvent>(2)
        fun axis(state: Int, value: Double, pos: String, neg: String): Int = when {
            state == 0 && value > enterDeg -> { out += start(pos, tUs, confidence, value); 1 }
            state == 0 && value < -enterDeg -> { out += start(neg, tUs, confidence, value); -1 }
            state != 0 && abs(value) < exitDeg -> { out += end(if (state > 0) pos else neg, tUs); 0 }
            else -> state
        }
        vertical = axis(vertical, w.upDownDeg, "up", "down")
        horizontal = axis(horizontal, w.leftRightDeg, "right", "left")
        return out
    }

    private fun start(dir: String, tUs: Long, c: Double, deg: Double) =
        GestureEvent("wrist_${dir}_$side", "start", tUs, c, mapOf("angle_deg" to deg))
    private fun end(dir: String, tUs: Long) = GestureEvent("wrist_${dir}_$side", "end", tUs, 1.0)
}
