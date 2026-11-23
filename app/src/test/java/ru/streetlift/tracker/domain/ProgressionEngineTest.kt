package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionEngineTest {

    // Горизонт: 3–4 × 5–8, RIR 1–2
    private val heavy = ExerciseConfig(
        minSets = 3, maxSets = 4, minReps = 5, maxReps = 8,
        targetRirMin = 1, targetRirMax = 2, weightStep = 2.5f,
    )

    private fun sets(weight: Float, vararg repsRir: Pair<Int, Int>) =
        repsRir.map { SetResult(weight, it.first, it.second) }

    @Test
    fun allSetsAtMaxRepsWithReserve_increasesWeightAndResetsSets() {
        val cfg = heavy.copy(weightStep = 5f)
        val state = PlanState(weightKg = -20f, plannedSets = 4, targetReps = 8, roundsAtWeight = 2)
        val r = ProgressionEngine.computeNext(cfg, state, sets(-20f, 8 to 1, 8 to 2, 9 to 1, 8 to 1), wasDeload = false)

        assertEquals(Decision.INCREASE_WEIGHT, r.decision)
        assertEquals(-15f, r.state.weightKg, 0.001f)
        assertEquals(3, r.state.plannedSets)
        assertEquals(5, r.state.targetReps)
        assertEquals(0, r.state.roundsAtWeight)
    }

    @Test
    fun positiveWeightStep_isRoundedCorrectly() {
        val state = PlanState(weightKg = 10f, plannedSets = 3, targetReps = 8)
        val r = ProgressionEngine.computeNext(heavy, state, sets(10f, 8 to 2, 8 to 1, 8 to 1), false)
        assertEquals(12.5f, r.state.weightKg, 0.001f)
    }

    @Test
    fun maxRepsButNoReserve_doesNotIncreaseWeight() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 8)
        // один подход с RIR 0 — ниже целевого RIR 1
        val r = ProgressionEngine.computeNext(heavy, state, sets(0f, 8 to 1, 8 to 1, 8 to 0), false)
        assertTrue(r.decision != Decision.INCREASE_WEIGHT)
        assertEquals(0f, r.state.weightKg, 0.001f)
    }

    @Test
    fun fewerSetsThanPlanned_doesNotIncreaseWeight() {
        val state = PlanState(weightKg = 0f, plannedSets = 4, targetReps = 8)
        val r = ProgressionEngine.computeNext(heavy, state, sets(0f, 8 to 2, 8 to 2, 8 to 2), false)
        assertTrue(r.decision != Decision.INCREASE_WEIGHT)
    }

    @Test
    fun firstRoundAtWeight_thenVolumeGoesToMaxSets() {
        val state = PlanState(weightKg = 5f, plannedSets = 3, targetReps = 5, roundsAtWeight = 0)
        val r = ProgressionEngine.computeNext(heavy, state, sets(5f, 6 to 2, 6 to 1, 5 to 1), false)

        assertEquals(Decision.ADD_VOLUME, r.decision)
        assertEquals(4, r.state.plannedSets)
        assertEquals(1, r.state.roundsAtWeight)
        assertEquals(6, r.state.targetReps) // min достигнутых (5) + 1
        assertEquals(5f, r.state.weightKg, 0.001f)
    }

    @Test
    fun lowAverageRir_keepsSetCount() {
        val cfg = heavy.copy(targetRirMin = 2, targetRirMax = 2)
        val state = PlanState(weightKg = 5f, plannedSets = 3, targetReps = 5)
        val r = ProgressionEngine.computeNext(cfg, state, sets(5f, 6 to 1, 6 to 1, 6 to 2), false)
        assertEquals(Decision.HOLD, r.decision)
        assertEquals(3, r.state.plannedSets)
    }

    @Test
    fun repsBelowMin_reducesSetsByOne() {
        val state = PlanState(weightKg = 10f, plannedSets = 4, targetReps = 6)
        val r = ProgressionEngine.computeNext(heavy, state, sets(10f, 6 to 1, 5 to 1, 4 to 0, 3 to 0), false)
        assertEquals(Decision.REDUCE_VOLUME, r.decision)
        assertEquals(3, r.state.plannedSets)
        assertEquals(10f, r.state.weightKg, 0.001f)
    }

    @Test
    fun fatigue_neverGoesBelowMinSetsMinusOne() {
        val state = PlanState(weightKg = 10f, plannedSets = 2, targetReps = 6)
        val r = ProgressionEngine.computeNext(heavy, state, sets(10f, 4 to 0, 3 to 0), false)
        assertEquals(2, r.state.plannedSets) // minSets(3) - 1
    }

    @Test
    fun twoSetsAtRirZero_isFatigue() {
        val state = PlanState(weightKg = 0f, plannedSets = 4, targetReps = 6)
        val r = ProgressionEngine.computeNext(heavy, state, sets(0f, 7 to 1, 7 to 0, 6 to 0, 6 to 1), false)
        assertEquals(Decision.REDUCE_VOLUME, r.decision)
        assertEquals(3, r.state.plannedSets)
    }

    @Test
    fun afterReduction_volumeRestoresToMinSetsFirst() {
        val state = PlanState(weightKg = 0f, plannedSets = 2, targetReps = 6, roundsAtWeight = 3)
        val r = ProgressionEngine.computeNext(heavy, state, sets(0f, 7 to 2, 7 to 2), false)
        assertEquals(3, r.state.plannedSets)
    }

    @Test
    fun declineTwoRoundsInARow_schedulesEmergencyDeload() {
        var state = PlanState(weightKg = 10f, plannedSets = 4, targetReps = 7, roundsAtWeight = 2)
        // круг 1 — базовая оценка
        var r = ProgressionEngine.computeNext(heavy, state, sets(10f, 7 to 2, 7 to 2, 7 to 1, 7 to 1), false)
        assertFalse(r.emergencyDeloadScheduled)
        state = r.state
        // круг 2 — хуже
        r = ProgressionEngine.computeNext(heavy, state, sets(10f, 7 to 1, 6 to 1, 6 to 1, 6 to 1), false)
        assertFalse(r.emergencyDeloadScheduled)
        assertEquals(1, r.state.declineStreak)
        state = r.state
        // круг 3 — ещё хуже → аварийная разгрузка
        r = ProgressionEngine.computeNext(heavy, state, sets(10f, 6 to 1, 6 to 1, 5 to 1, 5 to 1), false)
        assertTrue(r.emergencyDeloadScheduled)
        assertTrue(r.state.emergencyDeloadNext)

        val plan = ProgressionEngine.sessionPlan(heavy, r.state, globalDeload = false)
        assertTrue(plan.isDeload)
        assertTrue(plan.isEmergencyDeload)
        assertEquals(ProgressionEngine.DELOAD_RIR_MIN, plan.rirMin)
    }

    @Test
    fun deloadSession_clearsEmergencyFlagAndKeepsPlan() {
        val state = PlanState(weightKg = 10f, plannedSets = 4, targetReps = 7, emergencyDeloadNext = true, lastScore = 9f, lastScoreWeight = 10f)
        val r = ProgressionEngine.computeNext(heavy, state, sets(10f, 6 to 3, 6 to 4), wasDeload = true)
        assertEquals(Decision.DELOAD_DONE, r.decision)
        assertFalse(r.state.emergencyDeloadNext)
        assertEquals(4, r.state.plannedSets)
        assertEquals(10f, r.state.weightKg, 0.001f)
        assertEquals(null, r.state.lastScore)
    }

    @Test
    fun decline_isNotComparedAcrossDifferentWeights() {
        val state = PlanState(weightKg = 12.5f, plannedSets = 3, targetReps = 5, declineStreak = 1, lastScore = 12f, lastScoreWeight = 10f)
        val r = ProgressionEngine.computeNext(heavy, state, sets(12.5f, 5 to 1, 5 to 1, 5 to 1), false)
        assertEquals(0, r.state.declineStreak)
        assertFalse(r.emergencyDeloadScheduled)
    }

    @Test
    fun deloadSets_cutRoundedUpWithMinimumTwo() {
        assertEquals(3, ProgressionEngine.deloadSets(6))
        assertEquals(3, ProgressionEngine.deloadSets(5))
        assertEquals(2, ProgressionEngine.deloadSets(4))
        assertEquals(2, ProgressionEngine.deloadSets(3))
        assertEquals(2, ProgressionEngine.deloadSets(2))
    }

    @Test
    fun globalDeloadPlan_keepsWeightAndCutsSets() {
        val state = PlanState(weightKg = 24f, plannedSets = 5, targetReps = 8)
        val plan = ProgressionEngine.sessionPlan(heavy.copy(maxSets = 6), state, globalDeload = true)
        assertTrue(plan.isDeload)
        assertFalse(plan.isEmergencyDeload)
        assertEquals(24f, plan.weightKg, 0.001f)
        assertEquals(3, plan.sets)
        assertEquals(3, plan.rirMin)
        assertEquals(4, plan.rirMax)
    }

    @Test
    fun scheduledDeload_onEveryNthCycle_andManualOverride() {
        assertFalse(ProgressionEngine.isGlobalDeload(4, 5, DeloadOverride.AUTO))
        assertTrue(ProgressionEngine.isGlobalDeload(5, 5, DeloadOverride.AUTO))
        assertFalse(ProgressionEngine.isGlobalDeload(5, 5, DeloadOverride.FORCE_OFF))
        assertTrue(ProgressionEngine.isGlobalDeload(2, 5, DeloadOverride.FORCE_ON))

        assertEquals(DeloadOverride.FORCE_ON, ProgressionEngine.overrideFor(true, 2, 5))
        assertEquals(DeloadOverride.AUTO, ProgressionEngine.overrideFor(true, 5, 5))
        assertEquals(DeloadOverride.FORCE_OFF, ProgressionEngine.overrideFor(false, 5, 5))
    }

    @Test
    fun advanceCycle_wrapsAndCountsRounds() {
        val s = CycleState(dayIndex = 2, cycleInMeso = 3, completedRounds = 7, override = DeloadOverride.AUTO)
        val mid = ProgressionEngine.advanceCycle(s, completedDayIndex = 2, dayCount = 4, deloadEvery = 5)
        assertFalse(mid.roundCompleted)
        assertEquals(3, mid.state.dayIndex)

        val end = ProgressionEngine.advanceCycle(mid.state, completedDayIndex = 3, dayCount = 4, deloadEvery = 5)
        assertTrue(end.roundCompleted)
        assertEquals(0, end.state.dayIndex)
        assertEquals(4, end.state.cycleInMeso)
        assertEquals(8, end.state.completedRounds)
    }

    @Test
    fun advanceCycle_afterDeloadRound_resetsMesoCounterToOne() {
        val s = CycleState(dayIndex = 4, cycleInMeso = 5, completedRounds = 9, override = DeloadOverride.AUTO)
        val end = ProgressionEngine.advanceCycle(s, completedDayIndex = 4, dayCount = 5, deloadEvery = 5)
        assertTrue(end.wasDeloadRound)
        assertEquals(1, end.state.cycleInMeso)

        val manual = CycleState(dayIndex = 3, cycleInMeso = 2, completedRounds = 1, override = DeloadOverride.FORCE_ON)
        val m = ProgressionEngine.advanceCycle(manual, completedDayIndex = 3, dayCount = 4, deloadEvery = 5)
        assertTrue(m.wasDeloadRound)
        assertEquals(1, m.state.cycleInMeso)
        assertEquals(DeloadOverride.AUTO, m.state.override)
    }

    @Test
    fun notPerformed_keepsState() {
        val state = PlanState(weightKg = 3f, plannedSets = 3, targetReps = 6)
        val r = ProgressionEngine.computeNext(heavy, state, emptyList(), false)
        assertEquals(Decision.NOT_PERFORMED, r.decision)
        assertEquals(state, r.state)
    }

    @Test
    fun formatting() {
        assertEquals("+24 кг", Fmt.weight(24f))
        assertEquals("0 кг", Fmt.weight(0f))
        assertEquals("−15 кг", Fmt.weight(-15f))
        assertEquals("+12.5 кг", Fmt.weight(12.5f))
        assertEquals("−1.25 кг", Fmt.weight(-1.25f))
        assertEquals("2:30", Fmt.clock(150))
    }

    @Test
    fun fewerSetsByChoice_holdsVolumeAndCountsAsDecline() {
        var state = PlanState(weightKg = 0f, plannedSets = 4, targetReps = 6, roundsAtWeight = 2)
        var r = ProgressionEngine.computeNext(heavy, state, sets(0f, 7 to 2, 7 to 2, 7 to 2), false)
        assertEquals(3, r.state.plannedSets)
        assertEquals(1, r.state.declineStreak)
        assertFalse(r.emergencyDeloadScheduled)
        state = r.state.copy(plannedSets = 4)
        r = ProgressionEngine.computeNext(heavy, state, sets(0f, 7 to 2, 7 to 2), false)
        assertTrue(r.emergencyDeloadScheduled) // 2 круга подряд — внеплановая разгрузка
    }

    // ---------------- Калибровка и стадии рычага ----------------

    @Test
    fun calibrationPlan_showsCorridorRir2AndThreeSets_evenInGlobalDeload() {
        val state = PlanState(weightKg = 0f, plannedSets = 4, targetReps = 6, mode = ExerciseMode.CALIBRATION)
        val plan = ProgressionEngine.sessionPlan(heavy, state, globalDeload = true)
        assertTrue(plan.isCalibration)
        assertFalse(plan.isDeload)
        assertEquals(3, plan.sets)
        assertEquals(2, plan.rirMin)
        assertEquals(2, plan.rirMax)
        assertEquals(5, plan.repsMin)
        assertEquals(8, plan.repsMax)
    }

    @Test
    fun calibration_fixesLastWorkingSetAndBecomesActive() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 5, mode = ExerciseMode.CALIBRATION)
        // подбирали вес: −20 → −15 → −15
        val sets = listOf(SetResult(-20f, 9, 3), SetResult(-15f, 7, 2), SetResult(-15f, 6, 2))
        val r = ProgressionEngine.computeNext(heavy, state, sets, wasDeload = true)
        assertEquals(Decision.CALIBRATED, r.decision)
        assertEquals(ExerciseMode.ACTIVE, r.state.mode)
        assertEquals(-15f, r.state.weightKg, 0.001f)
        assertEquals(6, r.state.targetReps)
        assertEquals(3, r.state.plannedSets) // minSets
        assertEquals(0, r.state.roundsAtWeight)
    }

    @Test
    fun calibration_repsAboveCorridor_areClampedToMax() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 5, mode = ExerciseMode.CALIBRATION)
        val r = ProgressionEngine.computeNext(heavy, state, listOf(SetResult(5f, 12, 2)), false)
        assertEquals(8, r.state.targetReps)
        assertTrue(r.message.contains("утяжелить"))
    }

    @Test
    fun calibration_notPerformed_staysInCalibration() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 5, mode = ExerciseMode.CALIBRATION)
        val r = ProgressionEngine.computeNext(heavy, state, emptyList(), false)
        assertEquals(ExerciseMode.CALIBRATION, r.state.mode)
    }

    @Test
    fun stageChange_resetsComparisonAndStartsCalibration() {
        val state = PlanState(
            weightKg = -10f, plannedSets = 4, targetReps = 7, roundsAtWeight = 3,
            declineStreak = 1, emergencyDeloadNext = true, lastScore = 9f, lastScoreWeight = -10f,
        )
        val s = ProgressionEngine.onStageChanged(state)
        assertEquals(ExerciseMode.CALIBRATION, s.mode)
        assertEquals(0, s.declineStreak)
        assertFalse(s.emergencyDeloadNext)
        assertEquals(null, s.lastScore)
        assertEquals(-10f, s.weightKg, 0.001f)
    }

    @Test
    fun builtInStages_areOrderedSixWithDefault() {
        assertEquals(6, LeverageStages.builtIn.size)
        assertEquals("Закрытый", LeverageStages.builtIn.first().name)
        assertEquals("Фулл", LeverageStages.builtIn.last().name)
        assertTrue(LeverageStages.builtIn.any { it.name == LeverageStages.DEFAULT })
    }
}
