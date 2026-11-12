package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Play Space: +x player's right, +y up, +z away from the camera (player faces −z). */
class BodyAnglesTest {
    private val stature = 1.75

    private fun standing(): Array<Vec3> {
        val p = Array(J.COUNT) { Vec3.ZERO }
        fun at(i: Int, x: Double, y: Double, z: Double = 0.0) { p[i] = Vec3(x * stature, y * stature, z) }
        at(J.PELVIS, 0.0, 0.53); at(J.SPINE_MID, 0.0, 0.67); at(J.NECK, 0.0, 0.818); at(J.HEAD, 0.0, 0.93); at(J.NOSE, 0.0, 0.925, -0.08)
        at(J.LEFT_SHOULDER, -0.13, 0.818); at(J.LEFT_ELBOW, -0.13, 0.63); at(J.LEFT_WRIST, -0.13, 0.485); at(J.LEFT_HAND, -0.13, 0.44)
        at(J.RIGHT_SHOULDER, 0.13, 0.818); at(J.RIGHT_ELBOW, 0.13, 0.63); at(J.RIGHT_WRIST, 0.13, 0.485); at(J.RIGHT_HAND, 0.13, 0.44)
        at(J.LEFT_HIP, -0.095, 0.53); at(J.LEFT_KNEE, -0.095, 0.285); at(J.LEFT_ANKLE, -0.095, 0.039); at(J.LEFT_HEEL, -0.095, 0.0); at(J.LEFT_TOE, -0.095, 0.0, -0.12)
        at(J.RIGHT_HIP, 0.095, 0.53); at(J.RIGHT_KNEE, 0.095, 0.285); at(J.RIGHT_ANKLE, 0.095, 0.039); at(J.RIGHT_HEEL, 0.095, 0.0); at(J.RIGHT_TOE, 0.095, 0.0, -0.12)
        at(J.LEFT_EYE, -0.018, 0.936, -0.07); at(J.RIGHT_EYE, 0.018, 0.936, -0.07)
        return p
    }

    private fun skel(p: Array<Vec3>, tUs: Long = 0) =
        Skeleton(tUs, p, FloatArray(J.COUNT) { 0.9f }, IntArray(J.COUNT) { J.STATE_TRACKED }, J.STATE_TRACKED, 0.9, 0.5, 0.0, 2.5)

    private fun angles(p: Array<Vec3>, prev: Skeleton? = null, tUs: Long = 0) = assertNotNull(BodyAnglesEstimator.compute(skel(p, tUs), prev)).angles

    @Test
    fun `neutral standing is near zero everywhere`() {
        val a = angles(standing())
        for (k in listOf("elbow_l", "elbow_r", "knee_l", "knee_r", "shoulder_raise_l", "shoulder_raise_r", "hip_raise_l", "trunk_forward", "trunk_side", "trunk_twist"))
            assertEquals(0.0, a.getValue(k), 3.0, k)
    }

    @Test
    fun `arm raises and elbow bend`() {
        val p = standing()
        val sh = p[J.RIGHT_SHOULDER]
        // right arm straight out to the side (horizontal, +x), left arm straight forward (−z)
        p[J.RIGHT_ELBOW] = sh + Vec3(0.3, 0.0, 0.0); p[J.RIGHT_WRIST] = sh + Vec3(0.55, 0.0, 0.0)
        val lsh = p[J.LEFT_SHOULDER]
        p[J.LEFT_ELBOW] = lsh + Vec3(0.0, 0.0, -0.3)
        p[J.LEFT_WRIST] = p[J.LEFT_ELBOW] + Vec3(0.0, 0.25, 0.0)        // forearm pointing up: elbow at 90°
        val a = angles(p)
        assertEquals(90.0, a.getValue("shoulder_raise_r"), 2.0)
        assertEquals(0.0, a.getValue("shoulder_plane_r"), 2.0)          // to the side
        assertEquals(0.0, a.getValue("elbow_r"), 2.0)
        assertEquals(90.0, a.getValue("shoulder_raise_l"), 2.0)
        assertEquals(90.0, a.getValue("shoulder_plane_l"), 2.0)         // forward
        assertEquals(90.0, a.getValue("elbow_l"), 2.0)
    }

    @Test
    fun `knee lift, trunk lean and twist`() {
        val p = standing()
        val hip = p[J.LEFT_HIP]
        p[J.LEFT_KNEE] = hip + Vec3(0.0, 0.0, -0.43)                     // thigh horizontal forward
        p[J.LEFT_ANKLE] = p[J.LEFT_KNEE] + Vec3(0.0, -0.43, 0.0)         // shin down: knee 90°
        var a = angles(p)
        assertEquals(90.0, a.getValue("hip_raise_l"), 2.0)
        assertEquals(90.0, a.getValue("hip_plane_l"), 2.0)
        assertEquals(90.0, a.getValue("knee_l"), 2.0)

        val q = standing()
        val t = Math.toRadians(30.0)
        val len = (q[J.NECK] - q[J.PELVIS]).norm()
        q[J.NECK] = q[J.PELVIS] + Vec3(0.0, len * cos(t), -len * sin(t))   // bend forward (toward −z)
        a = angles(q)
        assertEquals(30.0, a.getValue("trunk_forward"), 2.0)

        val r = standing()
        val tw = Math.toRadians(40.0)
        // rotate the shoulders about the vertical toward the player's left (+yaw): right shoulder comes forward (−z)
        val half = 0.13 * stature
        r[J.RIGHT_SHOULDER] = r[J.NECK] + Vec3(half * cos(tw), 0.0, -half * sin(tw))
        r[J.LEFT_SHOULDER] = r[J.NECK] + Vec3(-half * cos(tw), 0.0, half * sin(tw))
        a = angles(r)
        assertEquals(40.0, a.getValue("trunk_twist"), 2.0)
    }

    @Test
    fun `limb speeds`() {
        val p0 = standing()
        val p1 = standing().also { it[J.RIGHT_WRIST] = it[J.RIGHT_WRIST] + Vec3(0.0, 0.0, -0.1) }
        val b = assertNotNull(BodyAnglesEstimator.compute(skel(p1, 50_000), skel(p0, 0)))
        assertEquals(2.0, b.speeds.getValue("wrist_r"), 1e-6)
        assertEquals(0.0, b.speeds.getValue("wrist_l"), 1e-6)
    }
}
