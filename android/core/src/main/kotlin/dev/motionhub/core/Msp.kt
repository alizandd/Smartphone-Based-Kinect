package dev.motionhub.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * MSP binary TrackingFrame (docs/06 §4.1). Golden vectors: protocol/golden/tracking_frame_v{1,2}.json.
 * v2 adds head orientation to the per-player header (20 bytes instead of 12). The encoder writes v2.
 */
object MspCodec {
    const val MSG_TRACKING_FRAME: Int = 0x01
    const val VERSION: Int = 2
    const val HEADER_BYTES = 24
    const val PLAYER_HEADER_BYTES = 20
    const val JOINT_BYTES = 8

    const val FLAG_PREDICTED = 1
    const val FLAG_DEGRADED = 2
    const val FLAG_UNCALIBRATED = 4

    class Player(
        val playerId: Int,
        val trackingState: Int,
        val confidence: Int,          // 0..255
        val zConfidence: Int,         // 0..255
        val facingYawCentideg: Int,
        val rootMm: IntArray,         // 3
        val jointsMm: IntArray,       // 3 × jointCount
        val jointConf: IntArray,      // jointCount, 0..255
        val jointState: IntArray,     // jointCount
        val headYawCentideg: Int = 0,
        val headPitchCentideg: Int = 0,
        val headRollCentideg: Int = 0,
        val headConfidence: Int = 0,  // 0..255, 0 = no head estimate
    )

    fun frameSize(players: Int, jointCount: Int) =
        HEADER_BYTES + players * (PLAYER_HEADER_BYTES + JOINT_BYTES * jointCount)

    fun encode(
        flags: Int, frameSeq: Long, tCaptureUs: Long, sendDelayUs: Long,
        jointCount: Int, players: List<Player>,
    ): ByteArray {
        val buf = ByteBuffer.allocate(frameSize(players.size, jointCount)).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MSG_TRACKING_FRAME.toByte())
        buf.put(VERSION.toByte())
        buf.putShort(flags.toShort())
        buf.putInt(frameSeq.toInt())
        buf.putLong(tCaptureUs)
        buf.putInt(sendDelayUs.coerceIn(0, 0xFFFFFFFFL).toInt())
        buf.put(players.size.toByte())
        buf.put(jointCount.toByte())
        buf.putShort(0)
        for (p in players) {
            buf.put(p.playerId.toByte())
            buf.put(p.trackingState.toByte())
            buf.put(p.confidence.coerceIn(0, 255).toByte())
            buf.put(p.zConfidence.coerceIn(0, 255).toByte())
            buf.putShort(clamp16(p.facingYawCentideg))
            for (k in 0 until 3) buf.putShort(clamp16(p.rootMm[k]))
            buf.putShort(clamp16(p.headYawCentideg))
            buf.putShort(clamp16(p.headPitchCentideg))
            buf.putShort(clamp16(p.headRollCentideg))
            buf.put(p.headConfidence.coerceIn(0, 255).toByte())
            buf.put(0)
            for (j in 0 until jointCount) {
                for (k in 0 until 3) buf.putShort(clamp16(p.jointsMm[3 * j + k]))
                buf.put(p.jointConf[j].coerceIn(0, 255).toByte())
                buf.put((p.jointState[j] and 0x03).toByte())
            }
        }
        return buf.array()
    }

    fun playerFrom(s: Skeleton, playerId: Int = 1): Player {
        val mm = IntArray(J.COUNT * 3)
        for (j in 0 until J.COUNT) {
            mm[3 * j] = (s.joints[j].x * 1000).roundToInt()
            mm[3 * j + 1] = (s.joints[j].y * 1000).roundToInt()
            mm[3 * j + 2] = (s.joints[j].z * 1000).roundToInt()
        }
        val r = s.root
        return Player(
            playerId = playerId,
            trackingState = s.trackingState,
            confidence = (s.overallConfidence * 255).roundToInt(),
            zConfidence = (s.zConfidence * 255).roundToInt(),
            facingYawCentideg = (s.facingYawDeg * 100).roundToInt(),
            rootMm = intArrayOf((r.x * 1000).roundToInt(), 0, (r.z * 1000).roundToInt()),
            jointsMm = mm,
            jointConf = IntArray(J.COUNT) { (s.confidence[it] * 255).roundToInt() },
            jointState = s.state.copyOf(),
            headYawCentideg = ((s.head?.yawDeg ?: 0.0) * 100).roundToInt(),
            headPitchCentideg = ((s.head?.pitchDeg ?: 0.0) * 100).roundToInt(),
            headRollCentideg = ((s.head?.rollDeg ?: 0.0) * 100).roundToInt(),
            headConfidence = ((s.head?.confidence ?: 0.0) * 255).roundToInt(),
        )
    }

    private fun clamp16(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/** JSON control/event messages (docs/06 §5, Phase 0 subset in protocol/README.md). */
object MspJson {
    fun welcome(sessionId: String, deviceModel: String, maxFps: Int): String = buildJsonObject {
        put("type", "welcome")
        put("session_id", sessionId)
        put("sensor_version", "poc-0.1")
        put("device_model", deviceModel)
        putJsonObject("capabilities") { put("max_fps", maxFps); put("max_players", 1) }
    }.toString()

    fun pong(t0: Double, t1Us: Long, t2Us: Long): String = buildJsonObject {
        put("type", "pong"); put("t0", t0); put("t1", t1Us); put("t2", t2Us)
    }.toString()

    fun gesture(e: GestureEvent, eventSeq: Long, playerId: Int = 1): String = buildJsonObject {
        put("type", "gesture")
        put("id", e.id)
        put("phase", e.phase)
        put("player_id", playerId)
        put("event_seq", eventSeq)
        put("t_source_us", e.tUs)
        put("confidence", e.confidence)
        putJsonObject("attributes") { e.attributes.forEach { (k, v) -> put(k, v) } }
    }.toString()

    fun probe(tSourceUs: Long, luma: Double): String = buildJsonObject {
        put("type", "probe"); put("t_source_us", tSourceUs); put("luma", luma)
    }.toString()

    fun setupStatus(s: Calibrator.Status, pitchDeg: Double = Double.NaN, rollDeg: Double = Double.NaN): String = buildJsonObject {
        put("type", "setup_status")
        put("stage", s.stage.name.lowercase())
        put("progress", s.progress)
        put("hint", s.hint)
        putJsonObject("parts") { s.parts.forEach { (k, v) -> put(k, v) } }
        if (!pitchDeg.isNaN()) put("pitch_deg", Math.round(pitchDeg).toInt())
        if (!rollDeg.isNaN()) put("roll_deg", Math.round(rollDeg).toInt())
        s.motion?.let { put("motion", Math.round(it * 100) / 100.0) }
    }.toString()

    fun calibrationDone(c: CalibrationResult): String = buildJsonObject {
        put("type", "calibration_done")
        put("stature_m", c.statureM)
        put("camera_height_m", c.cameraHeightM)
        put("distance_m", c.distanceM)
        put("pitch_deg", c.pitchDeg)
        put("roll_deg", c.rollDeg)
        put("partial", c.partial)
    }.toString()

    fun sensor(event: String, detail: JsonObject = JsonObject(emptyMap())): String = buildJsonObject {
        put("type", "sensor"); put("event", event); put("detail", detail)
    }.toString()
}
