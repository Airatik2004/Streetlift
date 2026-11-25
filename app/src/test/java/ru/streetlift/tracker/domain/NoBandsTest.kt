package ru.streetlift.tracker.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.streetlift.tracker.workout.nextNeedsScreen

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
    fun notificationNext() {
        assertTrue(nextNeedsScreen(isWarmup = false, isCalibration = true))
        assertFalse(nextNeedsScreen(isWarmup = true, isCalibration = true))
        assertFalse(nextNeedsScreen(isWarmup = false, isCalibration = false))
    }
}
