package dev.motionhub.core

/**
 * The phone-side tracking chain after pose inference: calibration → Play Space skeleton → gestures.
 * Identical code runs live in the app and offline in the replay CLI.
 */
class TrackingPipeline(statureM: Double = 1.75) {
    val camera = CameraModel()
    val builder = SkeletonBuilder(camera)
    val gestures = GestureEngine(statureM)

    var statureM: Double = statureM
        set(value) { field = value; gestures.statureM = value }

    private var calibrator: Calibrator? = null
    val calibration: CalibrationResult? get() = builder.calibration

    class Output(
        val skeleton: Skeleton?,
        val events: List<GestureEvent>,
        val calibrationStatus: Calibrator.Status?,
        /** Non-null only on the frame where calibration completed. */
        val calibrationDone: CalibrationResult?,
    )

    fun startCalibration() {
        calibrator = Calibrator(camera, statureM)
    }

    fun cancelCalibration() {
        calibrator = null
    }

    /** Apply a calibration stored from an earlier session (replay, quick start). */
    fun applyCalibration(c: CalibrationResult) {
        builder.calibration = c
        gestures.reset()
    }

    /** Forget the calibration (e.g. the camera was switched or moved). */
    fun clearCalibration() {
        builder.calibration = null
        gestures.reset()
    }

    val isCalibrating get() = calibrator != null

    fun process(f: PoseFrame): Output {
        var status: Calibrator.Status? = null
        var done: CalibrationResult? = null
        calibrator?.let { cal ->
            status = cal.update(f)
            cal.result?.let {
                done = it
                applyCalibration(it)
                builder.captureHeadNeutral = true      // player is standing still, looking ahead
                calibrator = null
            }
            if (status?.stage == Calibrator.Stage.FAILED) calibrator = null
        }
        val skeleton = builder.build(f)
        val events = if (skeleton != null && calibrator == null) gestures.update(skeleton) else emptyList()
        return Output(skeleton, events, status, done)
    }
}
