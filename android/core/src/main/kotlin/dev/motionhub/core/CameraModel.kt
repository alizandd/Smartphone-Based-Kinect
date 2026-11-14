package dev.motionhub.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pinhole camera with a gravity-aligned frame (docs/06 §2).
 *
 * Frames:
 *  - C (camera): +x image right, +y image down, +z along the optical axis.
 *  - G (gravity): origin at the camera, +y up, +z horizontal forward, +x = image-left (right-handed).
 *    Play Space is G translated to a floor point, so G's +x is already the player's right.
 */
class CameraModel {
    var fx = 1000.0; private set
    var fy = 1000.0; private set
    var cx = 640.0; private set
    var cy = 480.0; private set

    /** Unit "up" vector expressed in camera coordinates. */
    var upC: Vec3 = Vec3(0.0, -1.0, 0.0); private set
    private var xG = Vec3(-1.0, 0.0, 0.0)
    private var yG = Vec3(0.0, -1.0, 0.0)
    private var zG = Vec3(0.0, 0.0, 1.0)

    fun setIntrinsics(fx: Double, fy: Double, cx: Double, cy: Double) {
        this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy
    }

    /** Set the up direction directly in camera coordinates (tests, replay). */
    fun setUpCamera(up: Vec3) {
        upC = up.normalized()
        yG = upC
        zG = (Vec3.UNIT_Z - upC * upC.z).normalized()
        xG = yG cross zG
    }

    /**
     * Set gravity from an Android accelerometer/gravity-sensor reading (device frame: +x right and
     * +y up in natural portrait, +z out of the screen; a resting phone reads +9.8 along "up").
     * The phone is used in landscape with the rear camera (looking along device −z) and the image
     * rotated upright, so the image's up axis is whichever of device ±x points up.
     */
    fun setGravityDevice(ax: Double, ay: Double, az: Double, frontCamera: Boolean = false) {
        val up = Vec3(ax, ay, az).normalized()
        val imageUp = Vec3(if (up.x >= 0) 1.0 else -1.0, 0.0, 0.0)
        val yC = -imageUp                 // image down, in device coordinates
        // Optical axis: rear camera looks along device −z, the front (selfie) camera along +z.
        val zC = Vec3(0.0, 0.0, if (frontCamera) 1.0 else -1.0)
        val xC = yC cross zC              // image right
        setUpCamera(Vec3(up dot xC, up dot yC, up dot zC))
    }

    /** Positive when the camera looks upward, negative when tilted down. */
    val pitchDeg: Double get() = Math.toDegrees(asin(upC.z.coerceIn(-1.0, 1.0)))

    /** Rotation about the optical axis; 0 when the horizon is level in the image. */
    val rollDeg: Double get() = Math.toDegrees(atan2(upC.x, -upC.y))

    fun rayC(u: Double, v: Double) = Vec3((u - cx) / fx, (v - cy) / fy, 1.0)
    fun cToG(p: Vec3) = Vec3(p dot xG, p dot yG, p dot zG)
    fun gToC(p: Vec3) = xG * p.x + yG * p.y + zG * p.z
    fun rayG(u: Double, v: Double) = cToG(rayC(u, v))

    /** Project a G-frame point to pixels, or null if behind the camera. */
    fun projectG(p: Vec3): Pair<Double, Double>? {
        val c = gToC(p)
        if (c.z <= 1e-6) return null
        return Pair(fx * c.x / c.z + cx, fy * c.y / c.z + cy)
    }

    /** Intersect a G-frame ray with the floor plane y = −h. Null if the ray does not point down. */
    fun floorPoint(rayG: Vec3, cameraHeight: Double): Vec3? {
        if (rayG.y >= -1e-6) return null
        return rayG * (-cameraHeight / rayG.y)
    }

    /** Point on [rayG] whose horizontal forward distance (G z) equals [depth]. */
    fun atDepth(rayG: Vec3, depth: Double): Vec3? {
        if (rayG.z <= 1e-6) return null
        return rayG * (depth / rayG.z)
    }

    /** tan of the angle above the horizon (negative = below). */
    fun tanElevation(rayG: Vec3): Double = rayG.y / hypot(rayG.x, rayG.z)

    companion object {
        /** Focal length in pixels for an image that spans the full sensor width. */
        fun focalPx(focalLengthMm: Double, sensorWidthMm: Double, imageWidthPx: Int) =
            focalLengthMm / sensorWidthMm * imageWidthPx
    }
}
