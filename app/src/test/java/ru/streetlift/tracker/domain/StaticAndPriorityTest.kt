package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticAndPriorityTest {

    // Удержание горизонта: 3 × 8–15 с, RIR 2
    private val hold = ExerciseConfig(
        minSets = 3, maxSets = 3, minReps = 8, maxReps = 15,
        targetRirMin = 2, targetRirMax = 2, weightStep = 2.5f, isSeconds = true,
    )

    @Test
    fun seconds_targetGrowsByTwo() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 8, roundsAtWeight = 1)
        val sets = listOf(SetResult(0f, 10, 2), SetResult(0f, 9, 2), SetResult(0f, 9, 2))
        val r = ProgressionEngine.computeNext(hold, state, sets, false)
        assertEquals(11, r.state.targetReps) // минимум 9 + 2 с
        assertTrue(r.message.contains("с"))
    }

    @Test
    fun seconds_ceilingAtMaxSecondsTwoCycles() {
        var state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 15)
        val top = listOf(SetResult(0f, 15, 2), SetResult(0f, 16, 2), SetResult(0f, 15, 3))
        var r = ProgressionEngine.computeNext(hold, state, top, false, stageCeilingEligible = true)
        assertEquals(1, r.state.ceilingStreak)
        state = r.state
        r = ProgressionEngine.computeNext(hold, state, top, false, stageCeilingEligible = true)
        assertTrue(r.state.stageCeiling)
        assertEquals(0f, r.state.weightKg, 0.001f) // вес не растёт — подсказка сменить стадию
    }

    @Test
    fun priority_swapsFirstTwoInDays2to4() {
        val day = listOf("Горизонт", "П1Р", "Удержание")
        assertEquals(listOf("П1Р", "Горизонт", "Удержание"), ProgramOrder.order(1, day, Priority.PULL))
        assertEquals(day, ProgramOrder.order(1, day, Priority.PRESS))
        // День 1 не меняется никогда
        assertEquals(listOf("Выходы", "Пистолетики"), ProgramOrder.order(0, listOf("Выходы", "Пистолетики"), Priority.PULL))
    }

    @Test
    fun priority_alternatesByMesocycle() {
        assertEquals(Priority.PRESS, Priority.forMeso(0))
        assertEquals(Priority.PULL, Priority.forMeso(1))
        assertEquals(Priority.PRESS, Priority.forMeso(2))
    }

    @Test
    fun peak_selectedFirst_thenRest_withMesoPriority() {
        val day = listOf("Горизонт", "П1Р", "Удержание")
        // Подводка к удержанию при «Жиме»: удержание первым, остальные по обычному порядку
        assertEquals(listOf("Удержание", "Горизонт", "П1Р"), ProgramOrder.order(1, day, Priority.PRESS) { it == "Удержание" })
        // Выбраны двое при «Тяге»: внутри выбранных — порядок мезоцикла
        assertEquals(listOf("П1Р", "Горизонт", "Удержание"), ProgramOrder.order(1, day, Priority.PULL) { it != "Удержание" })
        // Дельта в конце, пока не выбрана; выбранная — первая
        val d1 = listOf("Выходы", "Пистолетики", "Дельта")
        assertEquals(d1, ProgramOrder.order(0, d1, Priority.PULL) { false })
        assertEquals(listOf("Дельта", "Выходы", "Пистолетики"), ProgramOrder.order(0, d1, Priority.PRESS) { it == "Дельта" })
    }

    @Test
    fun staticRest_is3minutes() {
        assertEquals(180, RestRecommender.recommend(ExerciseType.STATIC_HOLD, false, false).seconds)
    }

    @Test
    fun peaking_staticKeepsSecondsCorridor() {
        val state = PlanState(weightKg = 0f, plannedSets = 3, targetReps = 12)
        val pre = Peaking.resolve(hold, state, ExerciseType.STATIC_HOLD, false, PeakPhase.PRE_PEAK, true)!!
        assertEquals(8, pre.repsMin)
        assertEquals(15, pre.repsMax)
        val tonus = Peaking.resolve(hold, state, ExerciseType.STATIC_HOLD, false, PeakPhase.TONUS, true)!!
        assertEquals(8, tonus.repsMax)
    }
}
