package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Image axes: +x = image right (player's left), +y down, +z away from the camera. */
class WristPoseTest {
    /** Right hand, palm down, fingers pointing at the camera; thumb side = +x (player's left). */
    private fun rightHandPalmDown(): Array<Vec3> {
        val pts = Array(HL.COUNT) { Vec3.ZERO }
        pts[HL.INDEX_MCP] = Vec3(30.0, 0.0, -80.0)
        pts[HL.MIDDLE_MCP] = Vec3(0.0, 0.0, -82.0)
        pts[HL.RING_MCP] = Vec3(-15.0, 0.0, -80.0)
        pts[HL.PINKY_MCP] = Vec3(-28.0, 0.0, -75.0)
        pts[HL.THUMB_MCP] = Vec3(35.0, 5.0, -30.0)
        for (i in 0 until HL.COUNT) if (pts[i] == Vec3.ZERO && i != HL.WRIST) pts[i] = Vec3(0.0, 0.0, -100.0)
        return pts
    }

    /** Right hand, palm toward the camera, fingers up; thumb side = +x. */
    private fun rightHandPalmCamera(): Array<Vec3> {
        val pts = Array(HL.COUNT) { Vec3.ZERO }
        pts[HL.INDEX_MCP] = Vec3(30.0, -80.0, 0.0)
        pts[HL.MIDDLE_MCP] = Vec3(0.0, -82.0, 0.0)
        pts[HL.RING_MCP] = Vec3(-15.0, -80.0, 0.0)
        pts[HL.PINKY_MCP] = Vec3(-28.0, -75.0, 0.0)
        for (i in 0 until HL.COUNT) if (pts[i] == Vec3.ZERO && i != HL.WRIST) pts[i] = Vec3(0.0, -100.0, 0.0)
        return pts
    }

    private fun rotX(p: Array<Vec3>, deg: Double) = p.map { v -> val t = Math.toRadians(deg); Vec3(v.x, v.y * cos(t) + v.z * sin(t), -v.y * sin(t) + v.z * cos(t)) }.toTypedArray()
    private fun rotY(p: Array<Vec3>, deg: Double) = p.map { v -> val t = Math.toRadians(deg); Vec3(v.x * cos(t) + v.z * sin(t), v.y, -v.x * sin(t) + v.z * cos(t)) }.toTypedArray()
    private fun lm(p: Array<Vec3>) = HandLandmarks(DoubleArray(HL.COUNT * 3) { i -> p[i / 3].let { v -> when (i % 3) { 0 -> v.x; 1 -> v.y; else -> v.z } } })
    private fun mirrorToLeft(p: Array<Vec3>) = p.map { Vec3(-it.x, it.y, it.z) }.toTypedArray()

    private val forward = Vec3(0.0, 0.0, -1.0)   // forearm pointing at the camera
    private val raised = Vec3(0.0, -1.0, 0.0)    // forearm pointing up

    @Test
    fun `straight wrist, palm down`() {
        val w = assertNotNull(WristPoseEstimator.estimate(forward, lm(rightHandPalmDown()), "r"))
        assertEquals("down", w.palmFacing)
        assertEquals(0.0, w.upDownDeg, 2.0)
        assertEquals(0.0, w.leftRightDeg, 2.0)
    }

    @Test
    fun `bending up and sideways, palm down`() {
        val up = WristPoseEstimator.estimate(forward, lm(rotX(rightHandPalmDown(), 40.0)), "r")!!
        assertEquals(40.0, up.upDownDeg, 2.0)
        assertEquals(-40.0, up.flexDeg, 2.0)            // toward the back of the hand = extension
        assertEquals(0.0, up.leftRightDeg, 2.0)

        val right = WristPoseEstimator.estimate(forward, lm(rotY(rightHandPalmDown(), 35.0)), "r")!!
        assertEquals(35.0, right.leftRightDeg, 2.0)     // toward the player's right
        assertEquals(-35.0, right.deviationDeg, 2.0)    // right hand palm down: right = little-finger side
        assertEquals(0.0, right.upDownDeg, 2.0)
    }

    @Test
    fun `left hand mirrors the right`() {
        val left = mirrorToLeft(rotY(rightHandPalmDown(), 35.0))   // bent toward the player's left
        val w = WristPoseEstimator.estimate(forward, lm(left), "l")!!
        assertEquals("down", w.palmFacing)
        assertEquals(-35.0, w.leftRightDeg, 2.0)
        assertEquals(-35.0, w.deviationDeg, 2.0)        // left hand palm down: left = little-finger side
    }

    @Test
    fun `raised hand, palm to camera, tilting back counts as up`() {
        val straight = WristPoseEstimator.estimate(raised, lm(rightHandPalmCamera()), "r")!!
        assertEquals("camera", straight.palmFacing)
        // tilt fingers away from the camera by 30° (rotX(+30) moves the −y fingers toward +z)
        val back = WristPoseEstimator.estimate(raised, lm(rotX(rightHandPalmCamera(), 30.0)), "r")!!
        assertEquals(30.0, back.upDownDeg, 2.0)
        assertEquals(-30.0, back.flexDeg, 2.0)
    }

    @Test
    fun `events with hysteresis`() {
        val t = WristEventTracker("r")
        fun pose(ud: Double, lr: Double) = WristPose(ud, lr, 0.0, 0.0, "down")
        assertEquals(listOf("wrist_up_r:start"), t.update(pose(35.0, 0.0), 0, 1.0).map { "${it.id}:${it.phase}" })
        assertEquals(emptyList(), t.update(pose(25.0, 0.0), 1, 1.0))
        assertEquals(listOf("wrist_up_r:end", "wrist_left_r:start"), t.update(pose(5.0, -40.0), 2, 1.0).map { "${it.id}:${it.phase}" })
        assertEquals(listOf("wrist_left_r:end"), t.update(null, 3, 0.0).map { "${it.id}:${it.phase}" })
    }
}
