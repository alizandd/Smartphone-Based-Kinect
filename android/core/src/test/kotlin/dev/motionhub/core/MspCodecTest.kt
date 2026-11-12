package dev.motionhub.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MspCodecTest {
    private val golden = Json.parseToJsonElement(
        File(System.getProperty("golden.dir"), "tracking_frame_v2.json").readText()
    ).jsonObject

    @Test
    fun `encoder matches the v2 golden vector byte for byte`() {
        val f = golden["frame"]!!.jsonObject
        val jointCount = f["joint_count"]!!.jsonPrimitive.int
        val players = f["players"]!!.jsonArray.map { pe ->
            val p = pe.jsonObject
            val joints = p["joints"]!!.jsonArray.map { it.jsonObject }
            MspCodec.Player(
                playerId = p["player_id"]!!.jsonPrimitive.int,
                trackingState = p["tracking_state"]!!.jsonPrimitive.int,
                confidence = p["confidence"]!!.jsonPrimitive.int,
                zConfidence = p["z_confidence"]!!.jsonPrimitive.int,
                facingYawCentideg = p["facing_yaw_centideg"]!!.jsonPrimitive.int,
                rootMm = p["root_mm"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                jointsMm = joints.flatMap { j -> listOf("x", "y", "z").map { j[it]!!.jsonPrimitive.int } }.toIntArray(),
                jointConf = joints.map { it["conf"]!!.jsonPrimitive.int }.toIntArray(),
                jointState = joints.map { it["state"]!!.jsonPrimitive.int }.toIntArray(),
                headYawCentideg = p["head_yaw_centideg"]!!.jsonPrimitive.int,
                headPitchCentideg = p["head_pitch_centideg"]!!.jsonPrimitive.int,
                headRollCentideg = p["head_roll_centideg"]!!.jsonPrimitive.int,
                headConfidence = p["head_confidence"]!!.jsonPrimitive.int,
            )
        }
        val bytes = MspCodec.encode(
            flags = f["flags"]!!.jsonPrimitive.int,
            frameSeq = f["frame_seq"]!!.jsonPrimitive.long,
            tCaptureUs = f["t_capture_us"]!!.jsonPrimitive.long,
            sendDelayUs = f["send_delay_us"]!!.jsonPrimitive.long,
            jointCount = jointCount,
            players = players,
        )
        assertEquals(golden["byte_length"]!!.jsonPrimitive.int, bytes.size)
        assertEquals(golden["hex"]!!.jsonPrimitive.content, bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `landmarks json round trips`() {
        val cam = Synthetic.camera()
        val f = Synthetic.frame(cam, Synthetic.standing(1.75, 0.0, 2.5), 123_456L)
        val line = LandmarksJson.encode(f, f.tUs, f.width, f.height, "test")
        val back = LandmarksJson.decode(line)!!
        assertEquals(f.tUs, back.tUs)
        assertEquals(f.u(BP.NOSE), back.u(BP.NOSE), 0.05)
        assertEquals(f.worldZ(BP.LEFT_TOE), back.worldZ(BP.LEFT_TOE), 1e-4)
        assert(back.px[2 * BP.LEFT_PINKY].isNaN())
    }
}
