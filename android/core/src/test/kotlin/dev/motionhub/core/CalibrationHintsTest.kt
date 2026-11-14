package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalibrationHintsTest {
    private val lower = setOf(BP.LEFT_KNEE, BP.RIGHT_KNEE, BP.LEFT_ANKLE, BP.RIGHT_ANKLE, BP.LEFT_HEEL, BP.RIGHT_HEEL, BP.LEFT_TOE, BP.RIGHT_TOE)

    private fun cameraAt(pitchDeg: Double, rollDeg: Double = 0.0) = CameraModel().apply {
        setIntrinsics(887.0, 887.0, Synthetic.W / 2.0, Synthetic.H / 2.0)
        val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        // up vector in camera coordinates for a given pitch and roll
        setUpCamera(Vec3(sin(r) * cos(p), -cos(r) * cos(p), sin(p)))
    }

    private fun firstHint(cam: CameraModel, points: Map<Int, Vec3>): Calibrator.Status =
        Calibrator(cam, 1.75).update(Synthetic.frame(cam, points, 0))

    @Test
    fun `hints say what is wrong with the phone or the player`() {
        val person = Synthetic.standing(1.75, 0.0, 2.5)
        assertEquals("tilt_down", firstHint(cameraAt(50.0), person).hint)
        assertEquals("tilt_up", firstHint(cameraAt(-60.0), person).hint)
        assertEquals("level_phone", firstHint(cameraAt(-8.0, 25.0), person).hint)
        assertEquals("tilt_up", firstHint(cameraAt(-89.0, -33.0), person).hint)   // lying flat on a table
        assertEquals("turn_landscape", firstHint(cameraAt(-5.0, 85.0), person).hint)  // held in portrait
        val empty = PoseFrame.empty(0, Synthetic.W, Synthetic.H)
        assertEquals("nobody_in_view", Calibrator(cameraAt(-8.0), 1.75).update(empty).hint)
        val upperOnly = person.filterKeys { it !in lower }
        val s = Calibrator(cameraAt(-8.0), 1.75).update(Synthetic.frame(cameraAt(-8.0), upperOnly, 0))
        assertEquals(false, s.parts["feet"])
        assertEquals(true, s.parts["hips"])
    }

    @Test
    fun `upper body only still calibrates, marked partial`() {
        val cam = Synthetic.camera(-8.0)
        val person = Synthetic.standing(1.75, 0.2, 2.6).filterKeys { it !in lower }
        val cal = Calibrator(cam, 1.75)
        var status: Calibrator.Status? = null
        for (i in 0 until 300) {
            status = cal.update(Synthetic.frame(cam, person, i * 33_333L))
            if (status.stage == Calibrator.Stage.DONE) break
        }
        assertEquals(Calibrator.Stage.DONE, status!!.stage)
        assertEquals("done_without_feet", status.hint)
        val r = assertNotNull(cal.result)
        assertTrue(r.partial)
        assertEquals(2.6, r.distanceM, 0.1)
        assertEquals(1.0, r.cameraHeightM, 0.1)
        assertEquals(0.2, r.originG.x, 0.1)
    }

    @Test
    fun `phone tilted up 26 degrees still calibrates from the upper body`() {
        val cam = cameraAt(26.0)
        val person = Synthetic.standing(1.75, 0.0, 2.0).filterKeys { it !in lower }
        val cal = Calibrator(cam, 1.75)
        var status: Calibrator.Status? = null
        val hints = mutableSetOf<String>()
        for (i in 0 until 300) {
            status = cal.update(Synthetic.frame(cam, person, i * 33_333L))
            hints += status.hint
            if (status.stage == Calibrator.Stage.DONE) break
        }
        assertTrue("tilt_down_for_feet" in hints, hints.toString())
        assertEquals(Calibrator.Stage.DONE, status!!.stage)
        assertEquals(2.0, assertNotNull(cal.result).distanceM, 0.15)
    }

    @Test
    fun `waving hands does not block calibration`() {
        val cam = Synthetic.camera(-8.0)
        val base = Synthetic.standing(1.75, 0.0, 2.5)
        val cal = Calibrator(cam, 1.75)
        var status: Calibrator.Status? = null
        for (i in 0 until 300) {
            val wave = 0.25 * kotlin.math.sin(i * 0.7)
            val person = base.toMutableMap()
            for (w in listOf(BP.LEFT_WRIST, BP.RIGHT_WRIST)) base[w]?.let { person[w] = Vec3(it.x + wave, it.y + wave, it.z) }
            status = cal.update(Synthetic.frame(cam, person, i * 33_333L))
            if (status.stage == Calibrator.Stage.DONE) break
        }
        assertEquals(Calibrator.Stage.DONE, status!!.stage)
    }
}
