package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin

/** Synthetic camera + person generator for tests: true 3D points in G, projected to pixels. */
object Synthetic {
    const val W = 1280
    const val H = 960
    const val CAMERA_HEIGHT = 1.0

    fun camera(pitchDeg: Double = -8.0): CameraModel = CameraModel().apply {
        setIntrinsics(887.0, 887.0, W / 2.0, H / 2.0)
        val p = Math.toRadians(pitchDeg)
        setUpCamera(Vec3(0.0, -cos(p), sin(p)))
    }

    /**
     * Standing person in the G frame, feet on the floor (y = −CAMERA_HEIGHT) at (x, z).
     * +x is the player's right, so left-side joints get negative x offsets.
     */
    fun standing(stature: Double, x: Double, z: Double): MutableMap<Int, Vec3> {
        val floor = -CAMERA_HEIGHT
        fun pt(dx: Double, heightRatio: Double, dz: Double = 0.0) = Vec3(x + dx * stature, floor + heightRatio * stature, z + dz)
        return mutableMapOf(
            BP.NOSE to pt(0.0, 0.925, -0.05),
            BP.LEFT_EYE to pt(-0.018, Anthropometry.EYE_HEIGHT_RATIO),
            BP.RIGHT_EYE to pt(0.018, Anthropometry.EYE_HEIGHT_RATIO),
            BP.LEFT_EAR to pt(-0.045, 0.93),
            BP.RIGHT_EAR to pt(0.045, 0.93),
            BP.LEFT_SHOULDER to pt(-0.13, 0.818),
            BP.RIGHT_SHOULDER to pt(0.13, 0.818),
            BP.LEFT_ELBOW to pt(-0.15, 0.63),
            BP.RIGHT_ELBOW to pt(0.15, 0.63),
            BP.LEFT_WRIST to pt(-0.16, 0.485),
            BP.RIGHT_WRIST to pt(0.16, 0.485),
            BP.LEFT_HIP to pt(-0.095, 0.53),
            BP.RIGHT_HIP to pt(0.095, 0.53),
            BP.LEFT_KNEE to pt(-0.09, 0.285),
            BP.RIGHT_KNEE to pt(0.09, 0.285),
            BP.LEFT_ANKLE to pt(-0.085, 0.039),
            BP.RIGHT_ANKLE to pt(0.085, 0.039),
            BP.LEFT_HEEL to pt(-0.085, 0.0, 0.03),
            BP.RIGHT_HEEL to pt(0.085, 0.0, 0.03),
            BP.LEFT_TOE to pt(-0.085, 0.0, -0.12),
            BP.RIGHT_TOE to pt(0.085, 0.0, -0.12),
        )
    }

    fun frame(cam: CameraModel, points: Map<Int, Vec3>, tUs: Long, withWorld: Boolean = true): PoseFrame {
        val px = FloatArray(BP.COUNT * 2) { Float.NaN }
        val vis = FloatArray(BP.COUNT)
        val world = if (withWorld) FloatArray(BP.COUNT * 3) else null
        val hips = midpoint(points.getValue(BP.LEFT_HIP), points.getValue(BP.RIGHT_HIP))
        for ((i, p) in points) {
            val (u, v) = cam.projectG(p) ?: continue
            px[2 * i] = u.toFloat(); px[2 * i + 1] = v.toFloat(); vis[i] = 0.95f
            if (world != null) {
                // MediaPipe world convention: +x image-right (= −G x), +y down, +z away from camera.
                world[3 * i] = (-(p.x - hips.x)).toFloat()
                world[3 * i + 1] = (-(p.y - hips.y)).toFloat()
                world[3 * i + 2] = (p.z - hips.z).toFloat()
            }
        }
        return PoseFrame(tUs, W, H, px, vis, world)
    }

    fun shifted(points: Map<Int, Vec3>, dy: Double, ids: Collection<Int> = points.keys): MutableMap<Int, Vec3> =
        points.mapValues { (k, v) -> if (k in ids) Vec3(v.x, v.y + dy, v.z) else v }.toMutableMap()
}
