package dev.motionhub.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureTest {
    private val stature = 1.75
    private val dtUs = 16_667L   // 60 fps

    private fun calibratedPipeline(cam: CameraModel, person: Map<Int, Vec3>): Pair<TrackingPipeline, Long> {
        val p = TrackingPipeline(stature)
        p.camera.setIntrinsics(cam.fx, cam.fy, cam.cx, cam.cy)
        p.camera.setUpCamera(cam.upC)
        p.startCalibration()
        var t = 0L
        while (p.isCalibrating && t < 5_000_000) { p.process(Synthetic.frame(cam, person, t)); t += dtUs }
        // One more second standing to settle baselines.
        repeat(60) { p.process(Synthetic.frame(cam, person, t)); t += dtUs }
        return p to t
    }

    private fun starts(events: List<GestureEvent>) = events.filter { it.phase == "start" }.map { it.id }

    @Test
    fun `a jump produces start, peak and end once`() {
        val cam = Synthetic.camera()
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val (p, t0) = calibratedPipeline(cam, person)
        val events = ArrayList<GestureEvent>()
        var t = t0
        val airtime = 0.45
        val n = (airtime * 60).toInt()
        for (i in 0..n) {
            val h = 0.25 * sin(PI * i / n)          // whole body rises up to 25 cm
            events += p.process(Synthetic.frame(cam, Synthetic.shifted(person, h), t)).events; t += dtUs
        }
        repeat(60) { events += p.process(Synthetic.frame(cam, person, t)).events; t += dtUs }
        val jump = events.filter { it.id == "jump" }.map { it.phase }
        assertEquals(listOf("start", "peak", "end"), jump, "events: $events")
        assertTrue(events.none { it.id == "squat" }, "no squat expected: $events")
        val height = events.first { it.id == "jump" && it.phase == "peak" }.attributes.getValue("height_m")
        assertEquals(0.25, height, 0.08)
    }

    @Test
    fun `a squat is detected and is not mistaken for a jump`() {
        val cam = Synthetic.camera()
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val (p, t0) = calibratedPipeline(cam, person)
        val upper = person.keys.filter { it !in setOf(BP.LEFT_ANKLE, BP.RIGHT_ANKLE, BP.LEFT_HEEL, BP.RIGHT_HEEL, BP.LEFT_TOE, BP.RIGHT_TOE, BP.LEFT_KNEE, BP.RIGHT_KNEE) }
        val events = ArrayList<GestureEvent>()
        var t = t0
        val n = 60
        for (i in 0..n) {
            val d = -0.30 * sin(PI * i / n)        // hips drop 30 cm and come back
            val pts = Synthetic.shifted(person, d, upper)
            // Knees move toward the camera as they bend (needed for a 3D knee angle).
            for (k in listOf(BP.LEFT_KNEE, BP.RIGHT_KNEE)) {
                val v = pts.getValue(k); pts[k] = Vec3(v.x, v.y + d * 0.3, v.z + d * 0.8)
            }
            events += p.process(Synthetic.frame(cam, pts, t)).events; t += dtUs
        }
        repeat(30) { events += p.process(Synthetic.frame(cam, person, t)).events; t += dtUs }
        assertEquals(listOf("squat"), starts(events), "events: $events")
        assertTrue(events.any { it.id == "squat" && it.phase == "end" })
    }

    @Test
    fun `a fast right jab toward the camera is a right punch`() {
        val cam = Synthetic.camera()
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val guard = person.toMutableMap()
        val sh = person.getValue(BP.RIGHT_SHOULDER)
        // Guard position: wrist near the chin, elbow down and bent.
        guard[BP.RIGHT_WRIST] = sh + Vec3(-0.05, -0.05, -0.20)
        guard[BP.RIGHT_ELBOW] = sh + Vec3(0.02, -0.25, -0.10)
        val (p, t0) = calibratedPipeline(cam, guard)
        val events = ArrayList<GestureEvent>()
        var t = t0
        val n = 8                                    // ~130 ms extension → ~3 m/s
        for (i in 0..n) {
            val s = i.toDouble() / n
            val wrist = sh + Vec3(-0.05 * (1 - s), -0.05 * (1 - s), -0.20 - 0.36 * s)
            val elbow = midpoint(sh, wrist) + Vec3(0.0, -0.12 * (1 - s), 0.0)
            val pts = guard.toMutableMap(); pts[BP.RIGHT_WRIST] = wrist; pts[BP.RIGHT_ELBOW] = elbow
            events += p.process(Synthetic.frame(cam, pts, t)).events; t += dtUs
        }
        repeat(30) { events += p.process(Synthetic.frame(cam, guard, t)).events; t += dtUs }
        assertEquals(listOf("punch_r"), starts(events), "events: $events")
        val start = events.first { it.id == "punch_r" && it.phase == "start" }
        assertTrue(start.attributes.getValue("dir_z") < -0.5, "punch should point toward the camera (−z): $start")
    }

    @Test
    fun `standing still produces no events`() {
        val cam = Synthetic.camera()
        val person = Synthetic.standing(stature, 0.0, 2.5)
        val (p, t0) = calibratedPipeline(cam, person)
        var t = t0
        val events = (0 until 300).flatMap { p.process(Synthetic.frame(cam, person, t)).events.also { t += dtUs } }
        assertTrue(events.isEmpty(), "events: $events")
    }

    @Test
    fun `luma probe fires once per flash`() {
        val probe = LumaProbe()
        val lumas = List(30) { 60.0 } + List(10) { 230.0 } + List(30) { 60.0 } + List(10) { 230.0 }
        assertEquals(2, lumas.count { probe.update(it) })
    }
}
