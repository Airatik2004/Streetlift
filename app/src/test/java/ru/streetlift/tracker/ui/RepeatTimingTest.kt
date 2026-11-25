package ru.streetlift.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.streetlift.tracker.ui.common.RepeatTiming

class RepeatTimingTest {
    @Test
    fun slowThenAccelerates() {
        assertEquals(125L, RepeatTiming.interval(0))
        assertEquals(125L, RepeatTiming.interval(1_500))
        val mid = RepeatTiming.interval(2_100)
        assertTrue(mid in 51L..124L)
        // К 3 с удержания (0,3 с ожидания + 2,7 с прокрутки) — максимум, 20 шагов/с
        assertEquals(50L, RepeatTiming.interval(2_700))
        assertEquals(50L, RepeatTiming.interval(10_000))
    }
}
