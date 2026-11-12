package dev.motionhub.core

import kotlin.math.max
import kotlin.math.min

enum class Expression { NEUTRAL, HAPPY, SAD, SURPRISED, ANGRY, MOUTH_OPEN }

/**
 * Facial expression from MediaPipe Face Landmarker blendshapes (52 ARKit-style scores in 0..1,
 * e.g. "mouthSmileLeft", "eyeBlinkRight", "jawOpen"). Rule-based starting point: thresholds are
 * meant to be tuned on recorded faces, and sad/angry are inherently less reliable than
 * smile/open-mouth/blink from a few dozen face pixels.
 *
 * Left/right follow MediaPipe's blendshape naming; whether that is the subject's or the image's
 * left must be confirmed on device (one wink test), see [swapEyes].
 */
object FaceExpressionClassifier {
    /** Set to true if a left-eye wink is reported as "wink_r" on device. */
    var swapEyes = false

    data class Result(
        val expression: Expression,
        val scores: Map<String, Double>,
        val blinkLeft: Double,
        val blinkRight: Double,
    )

    fun classify(bs: Map<String, Float>): Result {
        fun g(k: String) = (bs[k] ?: 0f).toDouble()
        fun avg(a: String, b: String) = (g(a) + g(b)) / 2
        val smile = avg("mouthSmileLeft", "mouthSmileRight")
        val frown = avg("mouthFrownLeft", "mouthFrownRight")
        val browUp = g("browInnerUp")
        val browDown = avg("browDownLeft", "browDownRight")
        val jaw = g("jawOpen")
        val eyeWide = avg("eyeWideLeft", "eyeWideRight")
        val sneer = avg("noseSneerLeft", "noseSneerRight")
        val bl = if (swapEyes) g("eyeBlinkRight") else g("eyeBlinkLeft")
        val br = if (swapEyes) g("eyeBlinkLeft") else g("eyeBlinkRight")

        val sad = min(1.0, 0.6 * frown + 0.45 * browUp * (1 - smile))
        val surprise = min(1.0, 0.6 * jaw + 0.6 * max(browUp, eyeWide))
        val angry = min(1.0, browDown * (1 - smile) + 0.3 * sneer)
        val expression = when {
            surprise > 0.55 && jaw > 0.3 -> Expression.SURPRISED
            smile > 0.45 -> Expression.HAPPY
            angry > 0.45 -> Expression.ANGRY
            sad > 0.35 -> Expression.SAD
            jaw > 0.5 -> Expression.MOUTH_OPEN
            else -> Expression.NEUTRAL
        }
        return Result(
            expression,
            linkedMapOf(
                "smile" to smile, "sad" to sad, "surprise" to surprise, "angry" to angry,
                "jaw_open" to jaw, "blink_l" to bl, "blink_r" to br, "brow_up" to browUp, "brow_down" to browDown,
            ),
            bl, br,
        )
    }
}

/**
 * Turns per-frame face results into events: expression changes (debounced) and eye events
 * blink / wink_l / wink_r (short, with a refractory period).
 */
class FaceEventTracker(private val expressionFrames: Int = 3, private val eyeFrames: Int = 2) {
    var expression = Expression.NEUTRAL; private set
    private var candidate = Expression.NEUTRAL
    private var count = 0
    private var eyeCandidate = ""
    private var eyeCount = 0
    private var eyeActive = ""
    var winkThreshold = 0.5
    var openThreshold = 0.25

    fun update(r: FaceExpressionClassifier.Result?, tUs: Long): List<GestureEvent> {
        val out = ArrayList<GestureEvent>(2)
        val e = r?.expression ?: Expression.NEUTRAL
        if (e == expression) { candidate = e; count = 0 } else {
            if (e != candidate) { candidate = e; count = 1 } else count++
            if (count >= expressionFrames) {
                expression = e; count = 0
                if (e != Expression.NEUTRAL) out += GestureEvent(e.name.lowercase(), "start", tUs, r?.scores?.values?.maxOrNull() ?: 0.0)
                else out += GestureEvent("neutral", "start", tUs, 1.0)
            }
        }
        // Eyes: wink needs one eye clearly closed and the other clearly open.
        val eye = when {
            r == null -> ""
            r.blinkLeft > winkThreshold && r.blinkRight > winkThreshold -> "blink"
            r.blinkLeft > winkThreshold && r.blinkRight < openThreshold -> "wink_l"
            r.blinkRight > winkThreshold && r.blinkLeft < openThreshold -> "wink_r"
            else -> ""
        }
        if (eye != eyeCandidate) { eyeCandidate = eye; eyeCount = 1 } else eyeCount++
        if (eyeCount >= eyeFrames && eye != eyeActive) {
            if (eyeActive.isNotEmpty()) out += GestureEvent(eyeActive, "end", tUs, 1.0)
            if (eye.isNotEmpty()) out += GestureEvent(eye, "start", tUs, 0.8)
            eyeActive = eye
        }
        return out
    }
}
