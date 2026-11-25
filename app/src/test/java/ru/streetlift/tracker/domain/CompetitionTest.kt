package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionTest {

    @Test
    fun allTables_parseAndHaveRows() {
        // Обращение к каждой дисциплине разбирает таблицу; несовпадение колонок упадёт здесь
        Discipline.all.forEach { d -> d.table?.let { assertTrue(d.id, it.rows.isNotEmpty()) } }
        assertEquals(21, Discipline.all.size)
    }

    @Test
    fun weightClass_andRanks() {
        val t = Standards.PULL_24
        assertEquals(75f, t.rowFor(71f).maxKg)
        assertEquals(75f, t.rowFor(75f).maxKg)
        assertNull(t.rowFor(150f).maxKg)
        // До 75 кг: МСМК 20, МС 18, КМС 15, I 12
        val (done, next) = t.evaluate(71f, 16f)
        assertEquals(Standards.Rank.KMS, done!!.first)
        assertEquals(Standards.Rank.MS, next!!.first)
        assertEquals(18f, next.second)
        val (none, first) = t.evaluate(71f, 1f)
        assertNull(none)
        assertEquals(Standards.Rank.Y3, first!!.first)
    }

    @Test
    fun wsfTypoFixed() {
        val row = Standards.WSF_DIP_25.rowFor(80f)
        assertEquals(26f, row.values[Standards.WSF_DIP_25.ranks.indexOf(Standards.Rank.R1)])
    }

    @Test
    fun divisions_byAge() {
        val pull8 = Discipline.byId("pull_8")!!
        val pull24 = Discipline.byId("pull_24")!!
        val pull16 = Discipline.byId("pull_16")!!
        assertFalse(pull8.fits(22))
        assertTrue(pull24.fits(22))
        assertTrue(pull16.fits(22))
        assertFalse(pull16.fits(30))
        assertTrue(pull16.fits(45))
        assertTrue(pull8.fits(null))
    }

    @Test
    fun estimate_maxAndReps() {
        val bw = 71f
        // +20 × 5 с RIR 1 → (91) × (1 + 6/30) = 109,2 → максимум +37,5 (кратно 1,25 вниз)
        val sets = listOf(Competition.LiftSet(20f, 5, 1))
        val m = Competition.bestE1rm(bw, sets)!!
        assertEquals(109.2f, m, 0.01f)
        assertEquals(37.5f, Competition.maxAdded(bw, m), 0.001f)
        // С 24 кг без прямых подходов: 30 × (109,2 / 95 − 1) = 4,48 → 4
        assertEquals(4, Competition.repsAt(bw, 24f, m, sets))
        // Прямой подход с 24 кг важнее пересчёта
        assertEquals(9, Competition.repsAt(bw, 24f, m, sets + Competition.LiftSet(24f, 7, 2)))
    }

    @Test
    fun estimate_totalSumsLifts() {
        val d = Discipline.byId("total_24_32")!!
        val est = Competition.estimate(
            d, 71f,
            mapOf(
                CompLift.PULL to listOf(Competition.LiftSet(24f, 10, 0)),
                CompLift.DIP to listOf(Competition.LiftSet(32f, 15, 0)),
            ),
        )
        assertEquals(25f, est.result)
        val none = Competition.estimate(d, 71f, mapOf(CompLift.PULL to listOf(Competition.LiftSet(24f, 10, 0))))
        assertNull(none.result)
    }

    @Test
    fun plan_competitionPhases() {
        val scale = LoadScale(1.25f, null)
        val max = Competition.Target(CompKind.MAX, 0f, 109.2f, null, 71f)
        val pre = Competition.sessionPlan(max, PeakPhase.PRE_PEAK, scale)!!
        assertEquals(4, pre.sets); assertEquals(3, pre.targetReps)
        // 0,87 × 109,2 − 71 = 24,0
        assertEquals(24f, pre.weightKg, 0.7f)
        val reps = Competition.Target(CompKind.REPS, 24f, null, 10, 71f)
        val taper = Competition.sessionPlan(reps, PeakPhase.TAPER1, scale)!!
        assertEquals(24f, taper.weightKg, 0.001f)
        assertEquals(6, taper.targetReps)
        assertNotNull(Competition.sessionPlan(reps, PeakPhase.REST, scale))
        assertNull(Competition.sessionPlan(reps, PeakPhase.NONE, scale))
        // Нет данных — обычная подводка
        assertNull(Competition.sessionPlan(Competition.Target(CompKind.REPS, 24f, null, null, 71f), PeakPhase.TONUS, scale))
    }

    @Test
    fun snapshot_roundTrip() {
        val s = Competition.encode(listOf(Competition.Snap(9, 109.2f, 4), Competition.Snap(8, null, 12)))
        val m = Competition.decode(s)
        assertEquals(109.2f, m[9]!!.e1rm!!, 0.001f)
        assertNull(m[8]!!.e1rm)
        assertEquals(12, m[8]!!.reps)
        assertTrue(Competition.decode("").isEmpty())
    }
}
