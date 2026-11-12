package dev.motionhub.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HandShapeTest {
    /**
     * Synthetic upright hand in image pixels (+y down), palm facing the camera. Each finger is
     * either straight (tip far from the wrist) or curled (tip folded back toward the palm).
     */
    private fun hand(extended: BooleanArray, thumbUp: Boolean = false, pinch: Boolean = false, rotDeg: Double = 0.0, scale: Double = 1.0): HandLandmarks {
        val p = DoubleArray(HL.COUNT * 3)
        fun set(i: Int, x: Double, y: Double) { p[3 * i] = x; p[3 * i + 1] = y }
        set(HL.WRIST, 0.0, 0.0)
        val mcpX = doubleArrayOf(-30.0, -10.0, 10.0, 28.0)
        val mcps = intArrayOf(HL.INDEX_MCP, HL.MIDDLE_MCP, HL.RING_MCP, HL.PINKY_MCP)
        for (k in 0 until 4) {
            val mcp = mcps[k]
            set(mcp, mcpX[k], -80.0)
            if (extended[k + 1]) {
                set(mcp + 1, mcpX[k], -115.0); set(mcp + 2, mcpX[k], -140.0); set(mcp + 3, mcpX[k], -160.0)
            } else {
                set(mcp + 1, mcpX[k], -100.0); set(mcp + 2, mcpX[k], -85.0); set(mcp + 3, mcpX[k], -70.0)
            }
        }
        set(HL.THUMB_CMC, -25.0, -20.0); set(HL.THUMB_MCP, -45.0, -40.0)
        when {
            pinch -> { set(HL.THUMB_IP, -45.0, -120.0); set(HL.THUMB_TIP, -33.0, -152.0) }
            thumbUp -> { set(HL.THUMB_IP, -50.0, -90.0); set(HL.THUMB_TIP, -52.0, -130.0) }   // thumb ≈ 0.65 palm above the knuckles
            extended[0] -> { set(HL.THUMB_IP, -65.0, -60.0); set(HL.THUMB_TIP, -85.0, -75.0) }
            else -> { set(HL.THUMB_IP, -30.0, -60.0); set(HL.THUMB_TIP, -12.0, -65.0) }
        }
        if (pinch) { set(HL.INDEX_PIP, -30.0, -115.0); set(HL.INDEX_DIP, -32.0, -140.0); set(HL.INDEX_TIP, -33.0, -152.0) }
        val r = Math.toRadians(rotDeg)
        for (i in 0 until HL.COUNT) {
            val x = p[3 * i]; val y = p[3 * i + 1]
            p[3 * i] = scale * (x * cos(r) - y * sin(r)); p[3 * i + 1] = scale * (x * sin(r) + y * cos(r))
        }
        return HandLandmarks(p)
    }

    private fun shape(h: HandLandmarks) = HandShapeClassifier.classify(h).shape

    @Test
    fun `basic shapes`() {
        assertEquals(HandShape.OPEN, shape(hand(booleanArrayOf(true, true, true, true, true))))
        assertEquals(HandShape.FIST, shape(hand(booleanArrayOf(false, false, false, false, false))))
        assertEquals(HandShape.POINT, shape(hand(booleanArrayOf(false, true, false, false, false))))
        assertEquals(HandShape.VICTORY, shape(hand(booleanArrayOf(false, true, true, false, false))))
        assertEquals(HandShape.THUMBS_UP, shape(hand(booleanArrayOf(false, false, false, false, false), thumbUp = true)))
        assertEquals(HandShape.PINCH, shape(hand(booleanArrayOf(false, false, true, true, true), pinch = true)))
    }

    @Test
    fun `shapes survive rotation and scale`() {
        assertEquals(HandShape.POINT, shape(hand(booleanArrayOf(false, true, false, false, false), rotDeg = 50.0, scale = 0.4)))
        assertEquals(HandShape.FIST, shape(hand(booleanArrayOf(false, false, false, false, false), rotDeg = -70.0, scale = 2.0)))
        assertEquals(HandShape.OPEN, shape(hand(booleanArrayOf(true, true, true, true, true), rotDeg = 160.0)))
    }

    @Test
    fun `tracker debounces and emits once per change`() {
        val t = HandShapeTracker("r", frames = 3)
        assertNull(t.update(HandShape.FIST, 0, 0.9))
        assertNull(t.update(HandShape.FIST, 1, 0.9))
        val e = assertNotNull(t.update(HandShape.FIST, 2, 0.9))
        assertEquals("fist_r", e.id)
        assertNull(t.update(HandShape.FIST, 3, 0.9))
        assertNull(t.update(HandShape.OPEN, 4, 0.9))          // a single flicker does not switch
        assertNull(t.update(HandShape.FIST, 5, 0.9))
        assertEquals(HandShape.FIST, t.current)
    }
}
