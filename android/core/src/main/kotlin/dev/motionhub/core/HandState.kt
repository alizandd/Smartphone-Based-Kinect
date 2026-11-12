package dev.motionhub.core

import kotlin.math.hypot

/** MediaPipe hand landmark indices (21 points). */
object HL {
    const val COUNT = 21
    const val WRIST = 0
    const val THUMB_CMC = 1; const val THUMB_MCP = 2; const val THUMB_IP = 3; const val THUMB_TIP = 4
    const val INDEX_MCP = 5; const val INDEX_PIP = 6; const val INDEX_DIP = 7; const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9; const val MIDDLE_PIP = 10; const val MIDDLE_DIP = 11; const val MIDDLE_TIP = 12
    const val RING_MCP = 13; const val RING_PIP = 14; const val RING_DIP = 15; const val RING_TIP = 16
    const val PINKY_MCP = 17; const val PINKY_PIP = 18; const val PINKY_DIP = 19; const val PINKY_TIP = 20

    /** Connections for drawing. */
    val BONES = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
    )
}

enum class HandShape { UNKNOWN, OPEN, FIST, POINT, VICTORY, PINCH, THUMBS_UP }

/**
 * One hand's 21 landmarks as (x, y, z): x/y in any consistent 2D unit with +y DOWN (image pixels),
 * z relative depth in the same scale (MediaPipe's normalised z × crop size works).
 */
class HandLandmarks(val points: DoubleArray) {
    init { require(points.size == HL.COUNT * 3) }
    fun x(i: Int) = points[3 * i]
    fun y(i: Int) = points[3 * i + 1]
    fun z(i: Int) = points[3 * i + 2]
    fun dist(a: Int, b: Int) = hypot(hypot(x(a) - x(b), y(a) - y(b)), z(a) - z(b))
}

/**
 * Rule-based hand-shape classifier, invariant to hand size and in-plane rotation: a finger is
 * extended when its tip is clearly farther from the wrist than its PIP joint.
 */
object HandShapeClassifier {
    data class Result(val shape: HandShape, val extended: BooleanArray, val pinchRatio: Double)

    private val FINGERS = listOf(
        intArrayOf(HL.INDEX_MCP, HL.INDEX_PIP, HL.INDEX_TIP),
        intArrayOf(HL.MIDDLE_MCP, HL.MIDDLE_PIP, HL.MIDDLE_TIP),
        intArrayOf(HL.RING_MCP, HL.RING_PIP, HL.RING_TIP),
        intArrayOf(HL.PINKY_MCP, HL.PINKY_PIP, HL.PINKY_TIP),
    )

    fun classify(h: HandLandmarks): Result {
        val palm = h.dist(HL.WRIST, HL.MIDDLE_MCP).coerceAtLeast(1e-6)
        val ext = BooleanArray(5)
        // Thumb: tip far from the index MCP relative to the palm, and straighter than bent.
        ext[0] = h.dist(HL.THUMB_TIP, HL.INDEX_MCP) > 0.55 * palm && h.dist(HL.THUMB_TIP, HL.WRIST) > h.dist(HL.THUMB_IP, HL.WRIST)
        for ((k, f) in FINGERS.withIndex()) {
            ext[k + 1] = h.dist(f[2], HL.WRIST) > 1.15 * h.dist(f[1], HL.WRIST) && h.dist(f[2], f[0]) > 0.45 * palm
        }
        val pinch = h.dist(HL.THUMB_TIP, HL.INDEX_TIP) / palm
        val fingers = (1..4).count { ext[it] }
        val shape = when {
            pinch < 0.25 && ext[2] -> HandShape.PINCH          // thumb-index closed, other fingers out ("OK")
            fingers == 4 && ext[0] -> HandShape.OPEN
            fingers == 4 -> HandShape.OPEN
            fingers == 0 && ext[0] && h.y(HL.THUMB_TIP) < h.y(HL.THUMB_MCP) - 0.3 * palm -> HandShape.THUMBS_UP
            fingers == 0 -> HandShape.FIST
            fingers == 1 && ext[1] -> HandShape.POINT
            fingers == 2 && ext[1] && ext[2] -> HandShape.VICTORY
            pinch < 0.25 -> HandShape.PINCH
            else -> HandShape.UNKNOWN
        }
        return Result(shape, ext, pinch)
    }
}

/** Debounces shape changes (a new shape must persist for [frames] frames) and emits events. */
class HandShapeTracker(private val side: String, private val frames: Int = 3) {
    var current = HandShape.UNKNOWN; private set
    private var candidate = HandShape.UNKNOWN
    private var count = 0

    /** Returns an event when the stable shape changes. Pass null when the hand is not visible. */
    fun update(shape: HandShape?, tUs: Long, confidence: Double): GestureEvent? {
        val s = shape ?: HandShape.UNKNOWN
        if (s == current) { candidate = s; count = 0; return null }
        if (s != candidate) { candidate = s; count = 1 } else count++
        if (count < frames) return null
        current = s; count = 0
        if (s == HandShape.UNKNOWN) return null
        return GestureEvent("${s.name.lowercase()}_$side", "start", tUs, confidence)
    }
}
