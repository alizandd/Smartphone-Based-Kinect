package dev.motionhub.core

import kotlin.math.atan2

/**
 * Whole-body joint angles and limb speeds from a Play Space skeleton — the visible result of
 * muscle activity (a camera cannot see the muscles themselves).
 *
 * All angles in degrees, measured in the player's own body frame (so they do not change when the
 * player turns): up = +y, right = hip line, forward = up × right.
 *
 *  - elbow_l/r, knee_l/r:        flexion; 0 = straight, 90 = right angle
 *  - shoulder_raise_l/r:         0 = arm hanging, 90 = horizontal, 180 = straight up
 *  - shoulder_plane_l/r:         direction of the raise: 0 = to the side, 90 = forward, −90 = backward
 *  - hip_raise_l/r:              0 = leg straight down, 90 = thigh horizontal
 *  - hip_plane_l/r:              0 = to the side, 90 = forward (kick / knee lift), −90 = backward
 *  - trunk_forward:              forward bend of the torso (+ = leaning forward)
 *  - trunk_side:                 sideways bend (+ = toward the player's right)
 *  - trunk_twist:                shoulders vs hips around the vertical (+ = shoulders turned to the player's left)
 *
 * Speeds (m/s): wrist_l/r, elbow_l/r, ankle_l/r, knee_l/r, pelvis.
 */
class BodyAngles(val angles: Map<String, Double>, val speeds: Map<String, Double>)

object BodyAnglesEstimator {
    private val UP = Vec3.UNIT_Y

    fun compute(s: Skeleton, prev: Skeleton? = null): BodyAngles? {
        if (s.trackingState == J.STATE_LOST) return null
        val j = s.joints
        val pelvis = j[J.PELVIS]
        val neck = j[J.NECK]
        val hipLine = horizontal(j[J.RIGHT_HIP] - j[J.LEFT_HIP])
        val right = (if (hipLine.norm() > 1e-3) hipLine else horizontal(j[J.RIGHT_SHOULDER] - j[J.LEFT_SHOULDER])).normalized()
        val forward = (UP cross right).normalized()

        fun flexion(a: Int, b: Int, c: Int) = 180.0 - angleDeg(j[a], j[b], j[c])
        /** Raise (angle from straight down) and plane (side 0°, forward 90°) of a limb segment. */
        fun raiseAndPlane(from: Int, to: Int, sideSign: Double): Pair<Double, Double> {
            val d = (j[to] - j[from]).normalized()
            val raise = Math.toDegrees(kotlin.math.acos((-d dot UP).coerceIn(-1.0, 1.0)))
            val plane = Math.toDegrees(atan2(d dot forward, sideSign * (d dot right)))
            return raise to plane
        }

        val a = LinkedHashMap<String, Double>()
        a["elbow_l"] = flexion(J.LEFT_SHOULDER, J.LEFT_ELBOW, J.LEFT_WRIST)
        a["elbow_r"] = flexion(J.RIGHT_SHOULDER, J.RIGHT_ELBOW, J.RIGHT_WRIST)
        a["knee_l"] = flexion(J.LEFT_HIP, J.LEFT_KNEE, J.LEFT_ANKLE)
        a["knee_r"] = flexion(J.RIGHT_HIP, J.RIGHT_KNEE, J.RIGHT_ANKLE)
        raiseAndPlane(J.LEFT_SHOULDER, J.LEFT_ELBOW, -1.0).let { (r, p) -> a["shoulder_raise_l"] = r; a["shoulder_plane_l"] = p }
        raiseAndPlane(J.RIGHT_SHOULDER, J.RIGHT_ELBOW, 1.0).let { (r, p) -> a["shoulder_raise_r"] = r; a["shoulder_plane_r"] = p }
        raiseAndPlane(J.LEFT_HIP, J.LEFT_KNEE, -1.0).let { (r, p) -> a["hip_raise_l"] = r; a["hip_plane_l"] = p }
        raiseAndPlane(J.RIGHT_HIP, J.RIGHT_KNEE, 1.0).let { (r, p) -> a["hip_raise_r"] = r; a["hip_plane_r"] = p }
        val trunk = (neck - pelvis).normalized()
        a["trunk_forward"] = Math.toDegrees(atan2(trunk dot forward, trunk dot UP))
        a["trunk_side"] = Math.toDegrees(atan2(trunk dot right, trunk dot UP))
        val shoulderLine = horizontal(j[J.RIGHT_SHOULDER] - j[J.LEFT_SHOULDER]).normalized()
        a["trunk_twist"] = Math.toDegrees(atan2((right cross shoulderLine) dot UP, right dot shoulderLine))

        val sp = LinkedHashMap<String, Double>()
        if (prev != null && s.tUs > prev.tUs) {
            val dt = (s.tUs - prev.tUs) / 1e6
            fun v(i: Int) = (j[i] - prev.joints[i]).norm() / dt
            sp["wrist_l"] = v(J.LEFT_WRIST); sp["wrist_r"] = v(J.RIGHT_WRIST)
            sp["elbow_l"] = v(J.LEFT_ELBOW); sp["elbow_r"] = v(J.RIGHT_ELBOW)
            sp["ankle_l"] = v(J.LEFT_ANKLE); sp["ankle_r"] = v(J.RIGHT_ANKLE)
            sp["knee_l"] = v(J.LEFT_KNEE); sp["knee_r"] = v(J.RIGHT_KNEE)
            sp["pelvis"] = v(J.PELVIS)
        }
        return BodyAngles(a, sp)
    }

    private fun horizontal(v: Vec3) = Vec3(v.x, 0.0, v.z)

    /** True when the angle is meaningful for the joint states (all three joints at least inferred). */
    fun reliable(s: Skeleton, vararg joints: Int) = joints.all { s.state[it] != J.STATE_LOST }
}
