package dev.motionhub.core.replay

import dev.motionhub.core.CalibrationResult
import dev.motionhub.core.J
import dev.motionhub.core.LandmarksJson
import dev.motionhub.core.MspJson
import dev.motionhub.core.TrackingPipeline
import dev.motionhub.core.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """
Replay recorded landmarks through the Phase 0 tracking pipeline (same code as the phone).

Usage: replay --landmarks <landmarks.jsonl> --meta <meta.json> --out <dir>
              [--stature <m>] [--recalibrate]

meta.json needs: intrinsics {fx, fy, cx, cy}, gravity_device [ax, ay, az], stature_m,
and optionally calibration {camera_height_m, distance_m, origin_g [x,y,z], k_scale}.
Without a stored calibration (or with --recalibrate) the clip must start with the player
standing still for ~2 s.

Writes <out>/events.jsonl and <out>/skeleton.csv.
"""

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val landmarks = opts["landmarks"]?.let(::File)
    val metaFile = opts["meta"]?.let(::File)
    val outDir = opts["out"]?.let(::File)
    if (landmarks == null || metaFile == null || outDir == null) {
        System.err.println(USAGE); exitProcess(2)
    }
    val meta = Json.parseToJsonElement(metaFile.readText()).jsonObject
    val stature = opts["stature"]?.toDouble() ?: meta["stature_m"]?.jsonPrimitive?.double ?: 1.75

    val pipeline = TrackingPipeline(stature)
    val intr = meta["intrinsics"]!!.jsonObject
    pipeline.camera.setIntrinsics(
        intr["fx"]!!.jsonPrimitive.double, intr["fy"]!!.jsonPrimitive.double,
        intr["cx"]!!.jsonPrimitive.double, intr["cy"]!!.jsonPrimitive.double,
    )
    val g = LandmarksJson.doubles(meta["gravity_device"]) ?: error("meta.json: gravity_device missing")
    pipeline.camera.setGravityDevice(g[0], g[1], g[2])

    val stored = LandmarksJson.obj(meta["calibration"])
    if (stored != null && !opts.containsKey("recalibrate")) {
        val o = LandmarksJson.doubles(stored["origin_g"])!!
        pipeline.applyCalibration(CalibrationResult(
            statureM = stature,
            cameraHeightM = stored["camera_height_m"]!!.jsonPrimitive.double,
            distanceM = stored["distance_m"]!!.jsonPrimitive.double,
            originG = Vec3(o[0], o[1], o[2]),
            kScale = stored["k_scale"]?.jsonPrimitive?.double,
            pitchDeg = pipeline.camera.pitchDeg,
            rollDeg = pipeline.camera.rollDeg,
        ))
    } else {
        pipeline.startCalibration()
    }

    outDir.mkdirs()
    var frames = 0; var detections = 0; var eventSeq = 0L
    val counts = sortedMapOf<String, Int>()
    File(outDir, "events.jsonl").bufferedWriter().use { ev ->
        File(outDir, "skeleton.csv").bufferedWriter().use { sk ->
            sk.write("t_us,tracking,confidence,distance_m,pelvis_x,pelvis_y,pelvis_z,yaw_deg,lwrist_x,lwrist_y,lwrist_z,rwrist_x,rwrist_y,rwrist_z\n")
            landmarks.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                frames++
                val f = LandmarksJson.decode(line) ?: return@forEachLine
                detections++
                val out = pipeline.process(f)
                out.calibrationDone?.let {
                    ev.write(MspJson.calibrationDone(it)); ev.newLine()
                    System.err.println(String.format(Locale.ROOT, "calibrated: distance=%.2f m camera_height=%.2f m", it.distanceM, it.cameraHeightM))
                }
                for (e in out.events) {
                    ev.write(MspJson.gesture(e, eventSeq++)); ev.newLine()
                    if (e.phase == "start") counts.merge(e.id, 1, Int::plus)
                }
                out.skeleton?.let { s ->
                    val p = s[J.PELVIS]; val l = s[J.LEFT_WRIST]; val r = s[J.RIGHT_WRIST]
                    sk.write("${s.tUs},${s.trackingState},${fmt(s.overallConfidence)},${fmt(s.distanceM)}," +
                        listOf(p.x, p.y, p.z, s.facingYawDeg, l.x, l.y, l.z, r.x, r.y, r.z).joinToString(",") { fmt(it) } + "\n")
                }
            }
        }
    }
    if (pipeline.isCalibrating) System.err.println("warning: calibration never completed (player not still and fully visible?)")
    println("frames=$frames detections=$detections gesture_starts=$counts")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) { i++; continue }
        val key = a.removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) { m[key] = args[i + 1]; i += 2 } else { m[key] = "true"; i++ }
    }
    return m
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.4f", v)
