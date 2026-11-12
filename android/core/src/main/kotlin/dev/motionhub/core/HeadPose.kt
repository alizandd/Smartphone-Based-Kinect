package dev.motionhub.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Head orientation in Play Space from the pose model's face points (nose, ears). No extra model.
 *
 * Conventions (docs/06 §2):
 *  - yaw   0° = facing the camera/TV, positive = turned toward the player's left (same as body yaw);
 *  - pitch positive = looking up, relative to the neutral head pitch captured at calibration;
 *  - roll  positive = head tilted toward the player's left shoulder (right ear higher).
 */
data class HeadPose(val yawDeg: Double, val pitchDeg: Double, val rollDeg: Double, val confidence: Double)

object HeadPoseEstimator {
    /** Typical horizontal distance from the ear line to the nose tip, used by the 2D fallback. */
    private const val NOSE_FORWARD_M = 0.10

    /**
     * @param f the pose frame (world landmarks used when present: most reliable for yaw/pitch).
     * @param bp BlazePose points already placed in Play Space (planar approximation), for the 2D fallback.
     * Returns raw angles (pitch not yet neutral-corrected), or null when the face is not visible.
     */
    fun estimate(f: PoseFrame, bp: Array<Vec3?>): HeadPose? {
        val conf = minOf(f.vis[BP.NOSE], maxOf(f.vis[BP.LEFT_EAR], f.vis[BP.RIGHT_EAR])).toDouble()
        if (conf < 0.3) return null
        val w = f.world
        if (w != null) {
            // MediaPipe world: +x image-right (= player's left), +y down, +z away from the camera.
            // Convert to Play Space axes: x = −xw, y = −yw, z = zw.
            fun p(i: Int) = Vec3(-w[3 * i].toDouble(), -w[3 * i + 1].toDouble(), w[3 * i + 2].toDouble())
            val le = p(BP.LEFT_EAR); val re = p(BP.RIGHT_EAR); val nose = p(BP.NOSE)
            return fromPoints(le, re, nose, conf)
        }
        val le = bp[BP.LEFT_EAR]; val re = bp[BP.RIGHT_EAR]; val nose = bp[BP.NOSE]
        if (le == null || re == null || nose == null) return null
        // 2D-only backends: joints sit on one depth plane, so yaw comes from the nose's sideways
        // offset relative to the ear midpoint; pitch is not observable.
        val mid = midpoint(le, re)
        val yaw = Math.toDegrees(asin(((-(nose.x - mid.x)) / NOSE_FORWARD_M).coerceIn(-1.0, 1.0)))
        val ear = re - le
        val roll = Math.toDegrees(atan2(ear.y, hypot(ear.x, ear.z)))
        return HeadPose(yaw, 0.0, roll, conf * 0.5)
    }

    fun fromPoints(leftEar: Vec3, rightEar: Vec3, nose: Vec3, confidence: Double): HeadPose {
        val forward = nose - midpoint(leftEar, rightEar)      // points out of the face
        val yaw = Math.toDegrees(atan2(-forward.x, -forward.z))
        val pitch = Math.toDegrees(atan2(forward.y, hypot(forward.x, forward.z)))
        val ear = rightEar - leftEar                           // points to the player's right
        val roll = Math.toDegrees(atan2(ear.y, hypot(ear.x, ear.z)))
        return HeadPose(yaw, pitch, roll, confidence)
    }
}
