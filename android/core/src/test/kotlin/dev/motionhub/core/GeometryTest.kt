package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeometryTest {
    @Test
    fun `gravity in landscape gives pitch and roll`() {
        val cam = CameraModel()
        val t = Math.toRadians(10.0)
        cam.setGravityDevice(9.81 * cos(t), 0.0, 9.81 * sin(t))
        assertEquals(-10.0, cam.pitchDeg, 1e-6)
        assertEquals(0.0, cam.rollDeg, 1e-6)
        // Phone mounted the other way round (still landscape, upright image).
        cam.setGravityDevice(-9.81 * cos(t), 0.0, 9.81 * sin(t))
        assertEquals(-10.0, cam.pitchDeg, 1e-6)
        assertEquals(0.0, cam.rollDeg, 1e-6)
        // Small roll: gravity leans toward device y.
        val r = Math.toRadians(3.0)
        cam.setGravityDevice(9.81 * cos(r), 9.81 * sin(r), 0.0)
        assertEquals(3.0, kotlin.math.abs(cam.rollDeg), 1e-6)
    }

    @Test
    fun `G frame x axis is image-left so it matches the player's right`() {
        val cam = Synthetic.camera(0.0)
        val (u, _) = cam.projectG(Vec3(0.5, 0.0, 2.0))!!
        assert(u < cam.cx) { "a point at +x (player's right) must appear on the image's left" }
    }

    @Test
    fun `stature solver recovers distance and camera height`() {
        val cam = Synthetic.camera(-8.0)
        val person = Synthetic.standing(1.75, 0.1, 2.5)
        val foot = midpoint(person.getValue(BP.LEFT_HEEL), person.getValue(BP.RIGHT_TOE))
        val eye = midpoint(person.getValue(BP.LEFT_EYE), person.getValue(BP.RIGHT_EYE))
        val (fu, fv) = cam.projectG(foot)!!
        val (eu, ev) = cam.projectG(eye)!!
        val sol = assertNotNull(StatureSolver.solve(cam, fu, fv, eu, ev, 1.75))
        assertEquals(foot.z, sol.distanceM, 0.02)
        assertEquals(Synthetic.CAMERA_HEIGHT, sol.cameraHeightM, 0.02)
    }

    @Test
    fun `calibrator converges on a still player and sets the play space origin`() {
        val cam = Synthetic.camera(-8.0)
        val cal = Calibrator(cam, 1.75)
        val person = Synthetic.standing(1.75, -0.2, 2.6)
        var status: Calibrator.Status? = null
        for (i in 0 until 150) {
            status = cal.update(Synthetic.frame(cam, person, i * 16_667L))
            if (status.stage == Calibrator.Stage.DONE) break
        }
        assertEquals(Calibrator.Stage.DONE, status!!.stage)
        val r = cal.result!!
        assertEquals(2.6 - 0.045, r.distanceM, 0.05)   // foot contact is the heel/toe mean
        assertEquals(1.0, r.cameraHeightM, 0.03)
        assertEquals(-1.0, r.originG.y, 0.03)
        assertEquals(-0.2, r.originG.x, 0.03)
        assertNotNull(r.kScale)
    }

    @Test
    fun `skeleton lands in play space with pelvis near origin and correct sides`() {
        val cam = Synthetic.camera(-8.0)
        val person = Synthetic.standing(1.75, 0.3, 2.5)
        val pipeline = TrackingPipeline(1.75)
        pipeline.camera.setIntrinsics(cam.fx, cam.fy, cam.cx, cam.cy)
        pipeline.camera.setUpCamera(cam.upC)
        pipeline.startCalibration()
        var sk: Skeleton? = null
        for (i in 0 until 150) sk = pipeline.process(Synthetic.frame(cam, person, i * 16_667L)).skeleton
        assertNotNull(pipeline.calibration)
        val s = sk!!
        assertEquals(0.0, s[J.PELVIS].x, 0.05)
        assertEquals(0.53 * 1.75, s[J.PELVIS].y, 0.06)
        assert(s[J.RIGHT_SHOULDER].x > s[J.LEFT_SHOULDER].x) { "right shoulder must be at +x" }
        assertEquals(0.0, s.facingYawDeg, 5.0)
    }
}
