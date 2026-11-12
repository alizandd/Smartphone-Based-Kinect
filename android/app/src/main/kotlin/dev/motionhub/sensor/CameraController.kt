package dev.motionhub.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * CameraX + Camera2 interop configured for motion tracking (docs/03 §3):
 * rear main camera, 4:3 ~1280×960 analysis stream, fixed 60 fps when available, EIS off,
 * per-frame capture metadata, runtime exposure control and optional 4:3 video recording.
 */
@ExperimentalCamera2Interop
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val analyzer: (ImageProxy) -> Unit,
) {
    /** On-screen preview target; null while no activity is visible (background mode: analysis only). */
    var surfaceProvider: Preview.SurfaceProvider? = null
    /** Requested analysis resolution (4:3, landscape). The pose model only needs ~256 px across the person. */
    var analysisSize: Size = Size(960, 720)
    /** Bind the on-screen preview stream (only when a [surfaceProvider] is attached). */
    var previewEnabled = true

    /** Static facts about the camera, written to meta.json. */
    data class Info(
        val cameraId: String,
        val focalLengthMm: Double,
        val sensorWidthMm: Double,
        val sensorHeightMm: Double,
        val timestampSourceRealtime: Boolean,
        val isoRange: Range<Int>?,
        val exposureRangeNs: Range<Long>?,
        val fpsRanges: List<String>,
        val chosenFps: Int,
        val intrinsicCalibration: FloatArray?,
        val activeArray: String?,
    )

    class FrameMeta(val exposureNs: Long, val iso: Int, val frameDurationNs: Long)

    lateinit var info: Info; private set
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var videoUseCase: VideoCapture<Recorder>? = null

    /** Display rotation (Surface.ROTATION_*) that analysis frames are rotated upright for. */
    var targetRotation: Int = android.view.Surface.ROTATION_90
        set(value) {
            field = value
            previewUseCase?.targetRotation = value
            analysisUseCase?.targetRotation = value
            videoUseCase?.targetRotation = value
        }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    // High priority: in background mode Android otherwise schedules us on slower cores.
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); r.run() }, "analysis")
    }
    private val metaByTimestamp = ConcurrentHashMap<Long, FrameMeta>()

    var recordingEnabled = false; private set

    fun isInfoReady() = ::info.isInitialized

    /** Capture metadata for the frame with this sensor timestamp (µs). */
    fun metaFor(sensorTimestampUs: Long): FrameMeta? = metaByTimestamp.remove(sensorTimestampUs).also {
        if (metaByTimestamp.size > 64) metaByTimestamp.keys.removeIf { it < sensorTimestampUs }
    }

    @SuppressLint("MissingPermission")
    fun start(withVideo: Boolean, onError: (String) -> Unit) {
        info = readInfo()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            try {
                bind(provider, withVideo)
            } catch (e: Exception) {
                Log.e(TAG, "bind failed (video=$withVideo)", e)
                onError("Camera bind failed${if (withVideo) " with video" else ""}: ${e.message}")
                if (withVideo) runCatching { bind(provider, false) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(provider: ProcessCameraProvider, withVideo: Boolean) {
        val selector4x3 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(selector4x3)
        applyInterop(previewBuilder, captureCallback = false)
        previewBuilder.setTargetRotation(targetRotation)
        val sp = surfaceProvider
        val preview = previewBuilder.build().also { if (sp != null) it.setSurfaceProvider(sp) }
        previewUseCase = preview

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(targetRotation)
        applyInterop(analysisBuilder, captureCallback = true)
        val analysis = analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, analyzer) }
        analysisUseCase = analysis

        provider.unbindAll()
        recordingEnabled = false
        videoCapture = null
        val withPreview = previewEnabled && sp != null
        val streams = listOfNotNull(if (withPreview) preview else null, analysis)
        if (!withPreview) previewUseCase = null
        camera = if (withVideo) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .setAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            val vcBuilder = VideoCapture.Builder(recorder)
            applyInterop(vcBuilder, captureCallback = false)
            vcBuilder.setTargetRotation(targetRotation)
            val vc = vcBuilder.build()
            videoCapture = vc
            videoUseCase = vc
            provider.bindToLifecycle(owner, CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build(),
                *(streams + vc).toTypedArray()).also { recordingEnabled = true }
        } else {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *streams.toTypedArray())
        }
    }

    private fun <T> applyInterop(builder: ExtendableBuilder<T>, captureCallback: Boolean) {
        val ext = Camera2Interop.Extender(builder)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(info.chosenFps, info.chosenFps))
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (captureCallback) ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
                metaByTimestamp[ts / 1000] = FrameMeta(
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1,
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: -1,
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION) ?: -1,
                )
            }
        })
    }

    /** Auto exposure (stock AE, fps-capped) or a fixed exposure time with a caller-driven ISO. */
    fun setExposure(fixedExposureNs: Long?, iso: Int) {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (fixedExposureNs == null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, fixedExposureNs)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / info.chosenFps)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    fun startRecording(file: File, onEvent: (String) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        recording = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> onEvent("video_start")
                    is VideoRecordEvent.Finalize -> onEvent(if (e.hasError()) "video_error_${e.error}" else "video_saved")
                }
            }
        return true
    }

    fun stopRecording() {
        recording?.stop(); recording = null
    }

    private fun readInfo(): Info {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.first {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = mgr.getCameraCharacteristics(id)
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val fps = if (ranges.any { it.lower == 60 && it.upper == 60 }) 60 else 30
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return Info(
            cameraId = id,
            focalLengthMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 6.3,
            sensorWidthMm = size?.width?.toDouble() ?: 9.8,
            sensorHeightMm = size?.height?.toDouble() ?: 7.4,
            timestampSourceRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            fpsRanges = ranges.map { "[${it.lower},${it.upper}]" },
            chosenFps = fps,
            intrinsicCalibration = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString(),
        )
    }

    fun shutdown() {
        stopRecording()
        analysisExecutor.shutdown()
    }

    companion object { private const val TAG = "CameraController" }
}
