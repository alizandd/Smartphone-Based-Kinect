package dev.motionhub.core

import kotlin.math.abs
import kotlin.math.atan2

/** Canonical 25-joint skeleton (docs/06 §3). */
object J {
    const val COUNT = 25
    const val PELVIS = 0
    const val SPINE_MID = 1
    const val NECK = 2
    const val HEAD = 3
    const val NOSE = 4
    const val LEFT_SHOULDER = 5
    const val LEFT_ELBOW = 6
    const val LEFT_WRIST = 7
    const val LEFT_HAND = 8
    const val RIGHT_SHOULDER = 9
    const val RIGHT_ELBOW = 10
    const val RIGHT_WRIST = 11
    const val RIGHT_HAND = 12
    const val LEFT_HIP = 13
    const val LEFT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val LEFT_HEEL = 16
    const val LEFT_TOE = 17
    const val RIGHT_HIP = 18
    const val RIGHT_KNEE = 19
    const val RIGHT_ANKLE = 20
    const val RIGHT_HEEL = 21
    const val RIGHT_TOE = 22
    const val LEFT_EYE = 23
    const val RIGHT_EYE = 24

    /** Direct mappings from BlazePose indices; derived joints (pelvis, spine, neck, head, hands) are −1. */
    val FROM_BP = intArrayOf(
        -1, -1, -1, -1, BP.NOSE,
        BP.LEFT_SHOULDER, BP.LEFT_ELBOW, BP.LEFT_WRIST, -1,
        BP.RIGHT_SHOULDER, BP.RIGHT_ELBOW, BP.RIGHT_WRIST, -1,
        BP.LEFT_HIP, BP.LEFT_KNEE, BP.LEFT_ANKLE, BP.LEFT_HEEL, BP.LEFT_TOE,
        BP.RIGHT_HIP, BP.RIGHT_KNEE, BP.RIGHT_ANKLE, BP.RIGHT_HEEL, BP.RIGHT_TOE,
        BP.LEFT_EYE, BP.RIGHT_EYE,
    )

    const val STATE_LOST = 0
    const val STATE_INFERRED = 1
    const val STATE_TRACKED = 2
}

/** One player's skeleton in Play Space (metres; +x player's right, +y up, +z away from the camera). */
class Skeleton(
    val tUs: Long,
    val joints: Array<Vec3>,
    val confidence: FloatArray,
    val state: IntArray,
    val trackingState: Int,
    val overallConfidence: Double,
    val zConfidence: Double,
    val facingYawDeg: Double,
    val distanceM: Double,
    /** Head orientation (HeadPose conventions); null when the face is not visible. */
    val head: HeadPose? = null,
) {
    operator fun get(j: Int) = joints[j]
    /** Pelvis projected to the floor. */
    val root: Vec3 get() = Vec3(joints[J.PELVIS].x, 0.0, joints[J.PELVIS].z)
}

/**
 * Converts a [PoseFrame] into a Play Space [Skeleton] (docs/03 §6, Phase 0 version):
 *  - root distance: floor-plane intersection of the foot contact, cross-checked against
 *    torso-size scaling (which takes over while airborne);
 *  - joints: each pixel ray placed at the root distance, plus the model's relative world z;
 *  - per-coordinate One Euro filtering; low-visibility joints marked inferred/lost.
 */
class SkeletonBuilder(private val cam: CameraModel) {
    var calibration: CalibrationResult? = null
        set(value) { field = value; distanceFilter.reset() }

    /** Defaults used before calibration so the TV still gets a plausible skeleton. */
    var defaultCameraHeightM = 1.0
    var defaultDistanceM = 2.5
    var filterEnabled = true

    private val distanceFilter = OneEuroFilter(minCutoff = 0.5, beta = 0.5)
    private val jointFilters = Array(J.COUNT * 3) { OneEuroFilter(minCutoff = 1.5, beta = 0.3) }
    private var lastDistance = Double.NaN
    private val headFilters = Array(3) { OneEuroFilter(minCutoff = 1.0, beta = 0.02) }
    /** Raw head pitch when looking straight ahead, captured right after calibration. */
    var headPitchNeutralDeg = Double.NaN
    /** Set by the pipeline when calibration completes; the next frame's raw pitch becomes neutral. */
    var captureHeadNeutral = false
    private var last: Array<Vec3>? = null

    private val cameraHeight get() = calibration?.cameraHeightM ?: defaultCameraHeightM
    private val origin get() = calibration?.originG ?: Vec3(0.0, -cameraHeight, defaultDistanceM)

    fun build(f: PoseFrame): Skeleton? {
        // 4 key joints (e.g. nose, shoulders, one elbow) is enough to stream an upper body;
        // games that need legs check per-joint state.
        if (f.keyJointsVisible(0.3f) < 4) return null
        val tSec = f.tUs / 1e6
        val distance = estimateDistance(f) ?: return null

        val bp = arrayOfNulls<Vec3>(BP.COUNT)
        for (i in 0 until BP.COUNT) {
            if (f.px[2 * i].isNaN()) continue
            val p = cam.atDepth(cam.rayG(f.u(i), f.v(i)), distance) ?: continue
            bp[i] = Vec3(p.x, p.y, p.z + f.worldZ(i)) - origin
        }

        val joints = Array(J.COUNT) { Vec3.ZERO }
        val conf = FloatArray(J.COUNT)
        val state = IntArray(J.COUNT)
        val prev = last
        fun put(j: Int, p: Vec3?, c: Float) {
            conf[j] = c
            state[j] = when {
                p == null || c < 0.2f -> J.STATE_LOST
                c < 0.5f -> J.STATE_INFERRED
                else -> J.STATE_TRACKED
            }
            joints[j] = p ?: prev?.get(j) ?: Vec3.ZERO
        }
        fun avg(a: Int, b: Int): Vec3? {
            val pa = bp[a]; val pb = bp[b]
            return if (pa != null && pb != null) midpoint(pa, pb) else pa ?: pb
        }
        fun minVis(vararg ids: Int) = ids.minOf { f.vis[it] }

        for (j in 0 until J.COUNT) {
            val src = J.FROM_BP[j]
            if (src >= 0) put(j, bp[src], f.vis[src])
        }
        val pelvis = avg(BP.LEFT_HIP, BP.RIGHT_HIP)
        val neck = avg(BP.LEFT_SHOULDER, BP.RIGHT_SHOULDER)
        put(J.PELVIS, pelvis, minVis(BP.LEFT_HIP, BP.RIGHT_HIP))
        put(J.NECK, neck, minVis(BP.LEFT_SHOULDER, BP.RIGHT_SHOULDER))
        put(J.SPINE_MID, if (pelvis != null && neck != null) midpoint(pelvis, neck) else null,
            minOf(conf[J.PELVIS], conf[J.NECK]))
        put(J.HEAD, avg(BP.LEFT_EAR, BP.RIGHT_EAR), maxOf(f.vis[BP.LEFT_EAR], f.vis[BP.RIGHT_EAR]))
        put(J.LEFT_HAND, avg(BP.LEFT_PINKY, BP.LEFT_INDEX) ?: bp[BP.LEFT_WRIST],
            maxOf(f.vis[BP.LEFT_INDEX], f.vis[BP.LEFT_WRIST] * 0.8f))
        put(J.RIGHT_HAND, avg(BP.RIGHT_PINKY, BP.RIGHT_INDEX) ?: bp[BP.RIGHT_WRIST],
            maxOf(f.vis[BP.RIGHT_INDEX], f.vis[BP.RIGHT_WRIST] * 0.8f))

        if (filterEnabled) {
            for (j in 0 until J.COUNT) {
                val p = joints[j]
                joints[j] = Vec3(
                    jointFilters[3 * j].filter(p.x, tSec),
                    jointFilters[3 * j + 1].filter(p.y, tSec),
                    jointFilters[3 * j + 2].filter(p.z, tSec),
                )
            }
        }
        last = joints

        val keyConf = BP.KEY_JOINTS.map { f.vis[it].toDouble() }.average()
        val tracking = when {
            keyConf >= 0.6 -> J.STATE_TRACKED
            keyConf >= 0.3 -> J.STATE_INFERRED
            else -> J.STATE_LOST
        }
        return Skeleton(
            tUs = f.tUs,
            joints = joints,
            confidence = conf,
            state = state,
            trackingState = tracking,
            overallConfidence = keyConf,
            zConfidence = if (f.world != null) 0.5 else 0.2,
            facingYawDeg = facingYaw(joints[J.LEFT_SHOULDER], joints[J.RIGHT_SHOULDER]),
            distanceM = distance,
            head = headPose(f, bp, tSec),
        )
    }

    private fun headPose(f: PoseFrame, bp: Array<Vec3?>, tSec: Double): HeadPose? {
        val raw = HeadPoseEstimator.estimate(f, bp) ?: return null
        if (captureHeadNeutral) { headPitchNeutralDeg = raw.pitchDeg; captureHeadNeutral = false }
        val pitch = raw.pitchDeg - (if (headPitchNeutralDeg.isNaN()) 0.0 else headPitchNeutralDeg)
        return HeadPose(
            yawDeg = headFilters[0].filter(raw.yawDeg, tSec),
            pitchDeg = headFilters[1].filter(pitch, tSec),
            rollDeg = headFilters[2].filter(raw.rollDeg, tSec),
            confidence = raw.confidence,
        )
    }

    private fun estimateDistance(f: PoseFrame): Double? {
        val tSec = f.tUs / 1e6
        val foot = footContactPx(f)
        val dFloor = foot?.let { cam.floorPoint(cam.rayG(it.first, it.second), cameraHeight)?.z }
        val k = calibration?.kScale
        val torso = torsoPx(f)
        val dScale = if (k != null && torso != null && torso > 10) k / torso else null
        val raw = when {
            dFloor != null && (dScale == null || abs(dFloor - dScale) < 0.15) -> dFloor
            dScale != null -> dScale
            !lastDistance.isNaN() -> lastDistance
            else -> calibration?.distanceM ?: defaultDistanceM
        }
        if (raw !in 0.3..10.0) return if (lastDistance.isNaN()) null else lastDistance
        lastDistance = distanceFilter.filter(raw, tSec)
        return lastDistance
    }

    companion object {
        /** 0° = facing the camera; positive = turned toward the player's left (CCW seen from above). */
        fun facingYaw(leftShoulder: Vec3, rightShoulder: Vec3): Double {
            val s = rightShoulder - leftShoulder
            val facing = Vec3.UNIT_Y cross s
            return Math.toDegrees(atan2(-facing.x, -facing.z))
        }
    }
}
