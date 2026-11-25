package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.streetlift.tracker.data.repo.READINESS_BAD
import ru.streetlift.tracker.data.repo.READINESS_MID
import ru.streetlift.tracker.data.repo.applyReadiness

class WarmupTest {
    private fun plan(w: Float, reps: Int = 6, sets: Int = 5, height: Int? = null) = SessionPlan(
        weightKg = w, sets = sets, repsMin = reps, repsMax = reps + 4, targetReps = reps,
        rirMin = 1, rirMax = 2, isDeload = false, isEmergencyDeload = false, heightCm = height,
    )
    private val scale = LoadScale(1.25f, null)

    @Test
    fun weighted_threeSteps() {
        val s = Warmup.sets(ExerciseType.EXPLOSIVE, false, false, plan(20f), scale)
        assertEquals(listOf(0f, 10f, 15f), s.map { it.weightKg })
        assertEquals(listOf(5, 3, 2), s.map { it.reps })
        assertTrue(s.all { it.weightKg < 20f })
    }

    @Test
    fun bodyweight_andBands() {
        // Без резин: три подхода без веса, меньше цели
        val bw = Warmup.sets(ExerciseType.HEAVY_LEVER, false, false, plan(0f, reps = 10), scale)
        assertEquals(listOf(3, 5, 3), bw.map { it.reps })
        assertTrue(bw.all { it.weightKg == 0f })
        // С резинами: помощь сильнее, три подхода
        val bands = LoadScale(1.25f, listOf(-75f, -45f, -30f, -20f))
        val s = Warmup.sets(ExerciseType.HEAVY_LEVER, false, false, plan(-20f), bands)
        assertEquals(listOf(-45f, -30f, -30f), s.map { it.weightKg })
    }

    @Test
    fun alwaysThree_noDedup_deloadTwo() {
        // Малый вес: веса совпадают после округления, но подходов всё равно 3
        val s = Warmup.sets(ExerciseType.EXPLOSIVE, false, false, plan(1.25f), scale)
        assertEquals(3, s.size)
        assertTrue(s.all { it.weightKg < 1.25f })
        assertEquals(2, Warmup.sets(ExerciseType.EXPLOSIVE, false, false, plan(20f).copy(isDeload = true), scale).size)
    }

    @Test
    fun skipped_forStaticIsolation_andJumpsLower() {
        assertTrue(Warmup.sets(ExerciseType.STATIC_HOLD, true, false, plan(0f), scale).isEmpty())
        assertTrue(Warmup.sets(ExerciseType.ISOLATION, false, false, plan(0f), scale).isEmpty())
        val j = Warmup.sets(ExerciseType.EXPLOSIVE, false, true, plan(0f, height = 60), scale)
        assertEquals(listOf(45, 45, 45), j.map { it.heightCm })
        assertEquals(listOf(0f, 5f, 10f), j.map { it.weightKg })
    }

    @Test
    fun readiness_mid_and_bad() {
        val p = plan(20f, sets = 5)
        val mid = applyReadiness(p, READINESS_MID)
        assertEquals(4, mid.sets); assertEquals(2, mid.rirMin)
        val bad = applyReadiness(p, READINESS_BAD)
        assertTrue(bad.isDeload); assertTrue(bad.sets < 5); assertEquals(3, bad.rirMin)
        assertEquals(p, applyReadiness(p.copy(isCalibration = true), READINESS_BAD).copy(isCalibration = false))
    }
}
