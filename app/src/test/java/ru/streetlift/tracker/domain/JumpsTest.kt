package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpsTest {
    private fun ok(s: Jumps.State) = List(4) { Jumps.Log(s.heightCm, s.weightKg, s.reps) }
    private fun bad(s: Jumps.State) = List(4) { Jumps.Log(s.heightCm, s.weightKg, 1) }
    private fun win(s: Jumps.State) = Jumps.next(s, ok(s), false).state
    private fun lose(s: Jumps.State) = Jumps.next(s, bad(s), false).state

    @Test
    fun normal_repsThenPlus15() {
        var s = Jumps.State(60)
        s = win(s); assertEquals(4, s.reps)
        s = win(s); assertEquals(5, s.reps)
        s = win(s)
        assertEquals(75, s.heightCm)
        assertEquals(0f, s.weightKg, 0.01f)
        assertEquals(3, s.reps)
    }

    @Test
    fun newHeightFailed_blockBetweenControlPoints() {
        var s = Jumps.State(75)                 // 60 освоено, 75 не взяли
        s = lose(s)
        assertEquals(75, s.plateauCm)
        assertEquals(45, s.heightCm); assertEquals(2.5f, s.weightKg, 0.01f)
        // Ожидаемая цепочка: 45+2,5, 60+2,5, 45+5, 60+5, 45+7,5, 60+7,5, затем 75 без веса
        val expected = listOf(60 to 2.5f, 45 to 5f, 60 to 5f, 45 to 7.5f, 60 to 7.5f)
        for ((h, w) in expected) {
            repeat(3) { s = win(s) }             // 4×3 → 4×4 → 4×5 → следующий шаг
            assertEquals(h, s.heightCm); assertEquals(w, s.weightKg, 0.01f); assertEquals(3, s.reps)
        }
        repeat(3) { s = win(s) }
        assertEquals(75, s.heightCm); assertEquals(0f, s.weightKg, 0.01f); assertNull(s.plateauCm)
    }

    @Test
    fun weightFailed_rollbackThenIntermediate() {
        // Блок: 60+5 закрыт, 60+7,5 не взяли
        var s = Jumps.State(60, 7.5f, 3, plateauCm = 75, block = 0, blockCm = 75, step = 5)
        s = lose(s)
        assertTrue(s.rollback)
        assertEquals(60, s.heightCm); assertEquals(5f, s.weightKg, 0.01f); assertEquals(5, s.reps)
        s = win(s)                                // откат получился → промежуточный 6,25
        assertFalse(s.rollback)
        assertEquals(6.25f, s.weightKg, 0.01f); assertEquals(3, s.reps)
        repeat(3) { s = win(s) }                  // 4×3 → 4×5 → снова 7,5
        assertEquals(7.5f, s.weightKg, 0.01f); assertEquals(3, s.reps); assertNull(s.retryKg)
        assertEquals(1, s.fails)
        s = win(s)                                // закрыли то, что не шло
        assertEquals(0, s.fails)
    }

    @Test
    fun secondFail_isFatigue_deloadThenSamePlan() {
        var s = Jumps.State(60, 7.5f, 3, plateauCm = 75, block = 0, blockCm = 75, step = 5)
        s = lose(s)                               // откат 60+5
        s = win(s)                                // промежуточный 6,25
        val mid = s
        s = lose(s)                               // не взяли — усталость
        assertTrue(s.deloadNext)
        assertEquals(mid.weightKg, s.weightKg, 0.01f)
        val (sets, reps, d) = Jumps.plan(s, true)
        assertEquals(3, sets); assertEquals(3, reps); assertEquals(45, d.heightCm); assertEquals(0f, d.weightKg, 0.01f)
        s = Jumps.next(s, ok(d.copy(reps = 3)).take(3), true).state
        assertFalse(s.deloadNext)
        assertEquals(6.25f, s.weightKg, 0.01f)    // после разгрузки — тот же промежуточный
    }

    @Test
    fun repsFailed_rollbackOneRep() {
        var s = Jumps.State(60, reps = 4)
        s = lose(s)
        assertTrue(s.rollback); assertEquals(3, s.reps)
        s = win(s)
        assertEquals(4, s.reps); assertFalse(s.rollback)
    }

    @Test
    fun repeatedPlateau_heavierBlock() {
        var s = Jumps.State(75, block = 0, blockCm = 75)   // после блока 75 снова не взяли
        s = lose(s)
        assertEquals(1, s.block)
        assertEquals(5f, s.weightKg, 0.01f)
        assertEquals(8, Jumps.blockSteps(1))
        s = Jumps.State(75, block = 2, blockCm = 75)
        s = lose(s)
        assertEquals(2, s.block)                            // больше 15 кг не бывает
        assertEquals(7.5f, s.weightKg, 0.01f)
        assertEquals(15f, Jumps.blockWeights(5).last(), 0.01f)
    }

    @Test
    fun calibration_maxHeightSnappedTo15() {
        val s = Jumps.fromCalibration(listOf(Jumps.Log(105, 0f, 3), Jumps.Log(122, 0f, 3), Jumps.Log(90, 0f, 0)), null)
        assertEquals(120, s.heightCm)
        assertEquals(0f, s.weightKg, 0.01f)
    }
}
