package dev.motionhub.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceExpressionTest {
    private fun bs(vararg kv: Pair<String, Float>) = mapOf(*kv)
    private fun expr(vararg kv: Pair<String, Float>) = FaceExpressionClassifier.classify(bs(*kv)).expression

    @Test
    fun `expressions from blendshapes`() {
        assertEquals(Expression.NEUTRAL, expr())
        assertEquals(Expression.HAPPY, expr("mouthSmileLeft" to 0.7f, "mouthSmileRight" to 0.6f))
        assertEquals(Expression.SAD, expr("mouthFrownLeft" to 0.5f, "mouthFrownRight" to 0.4f, "browInnerUp" to 0.4f))
        assertEquals(Expression.SURPRISED, expr("jawOpen" to 0.6f, "browInnerUp" to 0.7f))
        assertEquals(Expression.ANGRY, expr("browDownLeft" to 0.6f, "browDownRight" to 0.6f))
        assertEquals(Expression.MOUTH_OPEN, expr("jawOpen" to 0.7f))
        // A big smile with raised brows is still happy, not surprised (mouth not open).
        assertEquals(Expression.HAPPY, expr("mouthSmileLeft" to 0.8f, "mouthSmileRight" to 0.8f, "browInnerUp" to 0.5f))
    }

    @Test
    fun `wink, blink and debounced expression events`() {
        val t = FaceEventTracker()
        fun frame(vararg kv: Pair<String, Float>) = FaceExpressionClassifier.classify(bs(*kv))
        val events = ArrayList<GestureEvent>()
        repeat(4) { events += t.update(frame("mouthSmileLeft" to 0.7f, "mouthSmileRight" to 0.7f), it.toLong()) }
        repeat(3) { events += t.update(frame("eyeBlinkLeft" to 0.8f, "eyeBlinkRight" to 0.05f), 10L + it) }
        repeat(3) { events += t.update(frame(), 20L + it) }
        repeat(3) { events += t.update(frame("eyeBlinkLeft" to 0.9f, "eyeBlinkRight" to 0.85f), 30L + it) }
        val ids = events.map { "${it.id}:${it.phase}" }
        // eye events debounce over 2 frames, expressions over 3, so the wink is reported before "neutral"
        assertEquals(listOf("happy:start", "wink_l:start", "neutral:start", "wink_l:end", "blink:start"), ids)
    }
}
