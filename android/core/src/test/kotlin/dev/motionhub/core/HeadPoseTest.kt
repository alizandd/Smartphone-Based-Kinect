package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadPoseTest {
    private val stature = 1.75

    /** Rotate the head points (nose, eyes, ears) of a synthetic person. */
    private fun withHead(person: Map<Int, Vec3>, yawDeg: Double = 0.0, pitchDeg: Double = 0.0, rollDeg: Double = 0.0): Map<Int, Vec3> {
        val c = midpoint(person.getValue(BP.LEFT_EAR), person.getValue(BP.RIGHT_EAR))
        val y = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        fun rot(v: Vec3): Vec3 {
            var d = v - c
            // roll about z (positive = right ear up), pitch about x (positive = nose up), yaw about y (positive = toward the player's left)
            d = Vec3(d.x * cos(r) - d.y * sin(r), d.x * sin(r) + d.y * cos(r), d.z)
            d = Vec3(d.x, d.y * cos(p) - d.z * sin(p), d.y * sin(p) + d.z * cos(p))
            d = Vec3(d.x * cos(y) + d.z * sin(y), d.y, -d.x * sin(y) + d.z * cos(y))
            return c + d
        }
        val ids = setOf(BP.NOSE, BP.LEFT_EYE, BP.RIGHT_EYE, BP.LEFT_EAR, BP.RIGHT_EAR)
        return person.mapValues { (k, v) -> if (k in ids) rot(v) else v }
    }

    private fun estimate(points: Map<Int, Vec3>): HeadPose {
        val l = points.getValue(BP.LEFT_EAR); val r = points.getValue(BP.RIGHT_EAR); val n = points.getValue(BP.NOSE)
        return HeadPoseEstimator.fromPoints(l, r, n, 1.0)
    }

    @Test
    fun `yaw follows the play space convention`() {
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val neutralPitch = estimate(person).pitchDeg
        assertEquals(0.0, estimate(person).yawDeg, 1.0)
        assertEquals(40.0, estimate(withHead(person, yawDeg = 40.0)).yawDeg, 1.0)       // toward the player's left
        assertEquals(-25.0, estimate(withHead(person, yawDeg = -25.0)).yawDeg, 1.0)
        assertEquals(20.0, estimate(withHead(person, pitchDeg = 20.0)).pitchDeg - neutralPitch, 1.5)
        assertEquals(15.0, estimate(withHead(person, rollDeg = 15.0)).rollDeg, 1.0)
    }

    @Test
    fun `pipeline reports head pose relative to the calibrated neutral and fires look events`() {
        val cam = Synthetic.camera()
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val p = TrackingPipeline(stature)
        p.camera.setIntrinsics(cam.fx, cam.fy, cam.cx, cam.cy)
        p.camera.setUpCamera(cam.upC)
        p.startCalibration()
        var t = 0L
        var sk: Skeleton? = null
        while (p.isCalibrating && t < 5_000_000) { sk = p.process(Synthetic.frame(cam, person, t)).skeleton; t += 16_667 }
        repeat(60) { sk = p.process(Synthetic.frame(cam, person, t)).skeleton; t += 16_667 }
        val h0 = assertNotNull(sk!!.head)
        assertEquals(0.0, h0.yawDeg, 3.0)
        assertEquals(0.0, h0.pitchDeg, 3.0)

        val events = ArrayList<GestureEvent>()
        repeat(60) { events += p.process(Synthetic.frame(cam, withHead(person, yawDeg = 45.0), t)).events.also { t += 16_667 } }
        val turned = p.process(Synthetic.frame(cam, withHead(person, yawDeg = 45.0), t)).skeleton!!.head!!
        assertEquals(45.0, turned.yawDeg, 5.0)
        assertTrue(events.any { it.id == "look_l" && it.phase == "start" }, "events: $events")
        assertTrue(events.none { it.id == "look_r" })
    }
}
