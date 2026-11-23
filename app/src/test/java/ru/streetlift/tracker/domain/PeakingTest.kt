package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakingTest {
    private val heavy = ExerciseConfig(3, 4, 5, 8, 1, 2, 2.5f)
    private val state = PlanState(weightKg = 10f, plannedSets = 4, targetReps = 6)

    @Test
    fun phases_byDaysToStart() {
        assertEquals(PeakPhase.NONE, Peaking.phase(23))
        assertEquals(PeakPhase.PRE_PEAK, Peaking.phase(22))
        assertEquals(PeakPhase.PRE_PEAK, Peaking.phase(15))
        assertEquals(PeakPhase.TAPER1, Peaking.phase(14))
        assertEquals(PeakPhase.TAPER1, Peaking.phase(8))
        assertEquals(PeakPhase.TONUS, Peaking.phase(7))
        assertEquals(PeakPhase.TONUS, Peaking.phase(3))
        assertEquals(PeakPhase.REST, Peaking.phase(2))
        assertEquals(PeakPhase.REST, Peaking.phase(0))
        assertEquals(PeakPhase.NONE, Peaking.phase(-1)) // после старта подводка выключена
        assertEquals(PeakPhase.NONE, Peaking.phaseOn(null, 100))
    }

    @Test
    fun prePeak_peakTarget_minSetsLowRepsPlusStepRir1() {
        val p = Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.PRE_PEAK, true)!!
        assertEquals(3, p.sets)
        assertEquals(3, p.repsMin); assertEquals(5, p.repsMax)
        assertEquals(12.5f, p.weightKg, 0.001f)
        assertEquals(1, p.rirMin); assertEquals(1, p.rirMax)
        assertTrue(p.isPeakTarget)
    }

    @Test
    fun prePeak_background_normalPlanWithRir2to3() {
        val p = Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.PRE_PEAK, false)!!
        assertEquals(4, p.sets)
        assertEquals(10f, p.weightKg, 0.001f)
        assertEquals(2, p.rirMin); assertEquals(3, p.rirMax)
    }

    @Test
    fun taper1_peakAndBackground() {
        val peak = Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.TAPER1, true)!!
        assertEquals(2, peak.sets)
        assertEquals(10f, peak.weightKg, 0.001f)
        assertEquals(2, peak.repsMin); assertEquals(4, peak.repsMax)
        assertEquals(2, peak.rirMin); assertEquals(3, peak.rirMax)

        val bg = Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.TAPER1, false)!!
        assertEquals(2, bg.sets)
        assertEquals(7.5f, bg.weightKg, 0.001f) // 10 × 0.875 → ближайший шаг 2.5
        assertEquals(3, bg.rirMin); assertEquals(4, bg.rirMax)

        assertEquals(null, Peaking.resolve(heavy, state, ExerciseType.HYPERTROPHY_PUMP, false, PeakPhase.TAPER1, false))
    }

    @Test
    fun tonus_peakOnly_backgroundExcluded() {
        val peak = Peaking.resolve(heavy, state.copy(weightKg = 20f), ExerciseType.EXPLOSIVE, false, PeakPhase.TONUS, true)!!
        assertEquals(2, peak.sets)
        assertEquals(17.5f, peak.weightKg, 0.001f)
        assertEquals(4, peak.rirMin)
        assertEquals(null, Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.TONUS, false))
        // 0–2 дня: только лёгкий тонус пиковых, фоновые — нет
        assertEquals(2, Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.REST, true)!!.sets)
        assertEquals(null, Peaking.resolve(heavy, state, ExerciseType.HEAVY_LEVER, false, PeakPhase.REST, false))
    }

    @Test
    fun reducedWeight_signedWeights() {
        assertEquals(17.5f, Peaking.reducedWeight(20f, 2.5f, 0.85f), 0.001f)
        assertEquals(-5f, Peaking.reducedWeight(0f, 2.5f, 0.85f), 0.001f)
        assertEquals(-20f, Peaking.reducedWeight(-15f, 2.5f, 0.85f), 0.001f)
        assertEquals(-17.5f, Peaking.reducedWeight(-15f, 2.5f, 0.875f), 0.001f)
    }

    // ---------------- Потолок стадии ----------------

    private fun sets(w: Float, n: Int, reps: Int, rir: Int) = List(n) { SetResult(w, reps, rir) }

    @Test
    fun stageCeiling_twoRoundsAtMaxSetsMaxReps_noWeightIncrease() {
        var st = PlanState(weightKg = 0f, plannedSets = 4, targetReps = 8)
        var r = ProgressionEngine.computeNext(heavy, st, sets(0f, 4, 8, 2), false, stageCeilingEligible = true)
        assertEquals(0f, r.state.weightKg, 0.001f)
        assertEquals(1, r.state.ceilingStreak)
        assertFalse(r.state.stageCeiling)
        st = r.state
        r = ProgressionEngine.computeNext(heavy, st, sets(0f, 4, 9, 2), false, stageCeilingEligible = true)
        assertEquals(Decision.STAGE_CEILING, r.decision)
        assertTrue(r.state.stageCeiling)
        assertEquals(0f, r.state.weightKg, 0.001f)
        assertTrue(r.message.contains("следующую стадию"))
    }

    @Test
    fun stageCeiling_fewerThanMaxSets_firstRaisesVolume() {
        val st = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 8)
        val r = ProgressionEngine.computeNext(heavy, st, sets(0f, 3, 8, 2), false, stageCeilingEligible = true)
        assertEquals(Decision.ADD_VOLUME, r.decision)
        assertEquals(4, r.state.plannedSets)
        assertEquals(0f, r.state.weightKg, 0.001f)
    }

    @Test
    fun notEligible_keepsNormalWeightIncrease() {
        val st = PlanState(weightKg = -10f, plannedSets = 4, targetReps = 8)
        val r = ProgressionEngine.computeNext(heavy, st, sets(-10f, 4, 8, 2), false, stageCeilingEligible = false)
        assertEquals(Decision.INCREASE_WEIGHT, r.decision)
        assertEquals(-7.5f, r.state.weightKg, 0.001f)
    }

    @Test
    fun stageGroups_lists() {
        assertEquals(6, StageGroups.lists.getValue(StageGroups.LEVER).size)
        assertEquals(3, StageGroups.lists.getValue(StageGroups.HANDSTAND).size)
        assertEquals("Фулл", StageGroups.finalOf(StageGroups.LEVER))
        assertEquals("Свободная (ноги вместе)", StageGroups.finalOf(StageGroups.HANDSTAND))
        assertEquals(StageGroups.LEVER, StageGroups.groupForName("Отжимания в горизонте"))
        assertEquals(StageGroups.LEVER, StageGroups.groupForName("Подтягивания в переднем висе"))
        assertEquals(StageGroups.HANDSTAND, StageGroups.groupForName("Отжимания в стойке на руках"))
        assertEquals(null, StageGroups.groupForName("Выходы силой"))
        assertEquals(null, StageGroups.groupForName("Подтягивания на 1 руке"))
    }

    @Test
    fun reducedWeight_fromTotalLoadWithBodyWeight() {
        // 75 кг, резина −15: 0.85 × 60 = 51 → −24 → шаг 2.5 → −25 (round(−9.6) = −10 шагов)
        assertEquals(-25f, Peaking.reducedWeight(-15f, 2.5f, 0.85f, 75f), 0.001f)
        // +20 с весом 75: 0.85 × 95 = 80.75 → +5.75 → +5
        assertEquals(5f, Peaking.reducedWeight(20f, 2.5f, 0.85f, 75f), 0.001f)
        // свой вес 0: 0.875 × 75 = 65.6 → −9.4 → −10
        assertEquals(-10f, Peaking.reducedWeight(0f, 2.5f, 0.875f, 75f), 0.001f)
    }
}
