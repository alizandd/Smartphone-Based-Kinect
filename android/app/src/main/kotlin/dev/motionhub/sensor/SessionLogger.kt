package dev.motionhub.sensor

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes everything Phase 0 needs to measure, off the hot path (single background thread).
 *
 * sessions/<yyyyMMdd-HHmmss>/
 *   meta.json        device, camera facts, settings, calibration (rewritten on change)
 *   frames.csv       one row per pose result: timing, exposure, thermal, distance …
 *   events.jsonl     gestures, probes, calibration, video markers
 *   tv_stats.csv     per-frame TV receive/render times reported back by the TV
 *   rec_<n>/         only while recording: video.mp4, landmarks.jsonl, imu.csv
 */
class SessionLogger(context: Context) : AutoCloseable {
    val dir: File = File(context.getExternalFilesDir(null), "sessions/" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())).also { it.mkdirs() }
    private val io = Executors.newSingleThreadExecutor()
    private val frames = writer("frames.csv", FRAME_HEADER)
    private val events = writer("events.jsonl", null)
    private val tvStats = writer("tv_stats.csv", "frame_seq,t_capture_us,tv_receive_us,tv_render_us,offset_us,rtt_us\n")
    private var landmarks: BufferedWriter? = null
    private var imu: BufferedWriter? = null
    private var recIndex = 0
    var recordingDir: File? = null; private set

    private fun writer(name: String, header: String?, base: File = dir) =
        File(base, name).bufferedWriter().also { w -> header?.let { w.write(it) } }

    fun frame(row: String) = io.execute { frames.write(row); frames.newLine() }
    fun event(json: String) = io.execute { events.write(json); events.newLine() }
    fun tvStat(row: String) = io.execute { tvStats.write(row); tvStats.newLine() }
    fun landmarks(line: String) = io.execute { landmarks?.apply { write(line); newLine() } }
    fun imu(row: String) = io.execute { imu?.apply { write(row); newLine() } }
    fun meta(json: String) = io.execute { File(dir, "meta.json").writeText(json) }

    /** Opens rec_<n>/ and returns the video file to record into. */
    fun startRecording(): File {
        val rec = File(dir, "rec_${++recIndex}").also { it.mkdirs() }
        io.execute {
            landmarks = writer("landmarks.jsonl", null, rec)
            imu = writer("imu.csv", "t_us,type,x,y,z\n", rec)
        }
        recordingDir = rec
        return File(rec, "video.mp4")
    }

    fun stopRecording() {
        recordingDir = null
        io.execute {
            landmarks?.close(); landmarks = null
            imu?.close(); imu = null
        }
    }

    fun flush() = io.execute { frames.flush(); events.flush(); tvStats.flush() }

    override fun close() {
        stopRecording()
        io.execute { frames.close(); events.close(); tvStats.close() }
        io.shutdown()
    }

    companion object {
        const val FRAME_HEADER = "frame_seq,t_capture_us,t_arrival_us,t_submit_us,t_result_us,t_send_us,capture_to_arrival_ms," +
            "inference_ms,detect_call_ms,exposure_us,iso,cam_fps,pose_fps,thermal_headroom,thermal_status,center_luma,person_luma," +
            "key_joints_visible,tracking,distance_m,calibrated,model,delegate,exposure_mode,clients,image_size\n"
    }
}
