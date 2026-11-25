package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsHiddenTest {
    private val set = listOf(Band("Чёрная", 30f), Band("Красная", 20f, hidden = true))

    @Test
    fun hidden_roundTrip() {
        val s = Bands.encode(set)
        assertEquals("Чёрная:30,~Красная:20", s)
        assertEquals(set, Bands.decode(s))
        // Старый формат без метки — все видимые
        assertTrue(Bands.decode("Зелёная:75,Красная:20").none { it.hidden })
    }

    @Test
    fun hidden_notInLadder() {
        assertEquals(listOf(-30f), Bands.ladder(set))
        assertEquals(null, Bands.describe(-20f, set))
        assertEquals(listOf(-50f, -30f, -20f), Bands.ladder(set.map { it.copy(hidden = false) }))
    }

    @Test
    fun duplicates_ignoreCaseAndSpaces() {
        assertEquals(setOf(0, 2), Bands.duplicateIndices(listOf("Красная", "Чёрная", " красная ")))
        assertTrue(Bands.duplicateIndices(listOf("Красная", "Чёрная", "", "")).isEmpty())
    }
}
