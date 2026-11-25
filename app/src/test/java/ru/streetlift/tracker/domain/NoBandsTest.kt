package ru.streetlift.tracker.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import ru.streetlift.tracker.timer.holdResult

class NoBandsTest {
    @Test
    fun plancheWithoutBands_frontLeverWith() {
        assertTrue(StageGroups.noBandsByName("Отжимания в горизонте"))
        assertTrue(StageGroups.noBandsByName("Удержание горизонта"))
        assertFalse(StageGroups.noBandsByName("Передний вис"))
        assertFalse(StageGroups.noBandsByName("Подтягивания"))
    }

    @Test
    fun plancheScale_neverBelowBodyweight() {
        // Пустая лестница помощи: ниже своего веса нельзя, сброс со своего веса остаётся на 0
        val s = LoadScale(1.25f, emptyList())
        assertTrue(s.down(0f) == 0f)
        assertTrue(s.snap(-20f) == 0f)
    }

    @Test
    fun holdResult_capsAtTarget() {
        // «Стоп» после сигнала цели — пишется цель, время на подход к телефону не считается
        assertEquals(12, holdResult(17, 12))
        assertEquals(12, holdResult(12, 12))
        // Сорвался раньше — пишется как есть
        assertEquals(9, holdResult(9, 12))
        assertEquals(20, holdResult(20, null))
    }
}
