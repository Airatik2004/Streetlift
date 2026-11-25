package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPlanTest {

    @Test
    fun vdot_matchesDanielsTables() {
        // 5 км за 20:00 — VDOT ≈ 49,8 по таблицам Дэниелса
        assertEquals(49.8, RunPlan.vdot(5000.0, 1200.0), 0.3)
        // Обратный расчёт возвращает то же время
        val v = RunPlan.vdot(10000.0, 45 * 60.0)
        assertEquals(45 * 60.0, RunPlan.predictSec(RunPlan.Dist.K10, v), 1.0)
        // Порог (T) для VDOT 50 — около 4:15/км
        assertEquals(255.0, RunPlan.paceAt(50.0, 0.88), 10.0)
        // Марафон с поправкой медленнее «чистого» прогноза
        assertTrue(RunPlan.predictSec(RunPlan.Dist.MARATHON, 50.0) > 3 * 3600)
    }

    @Test
    fun corridor_narrowsAndOrders() {
        val far = RunPlan.corridor(RunPlan.Dist.K10, 45.0, 6, 120)
        val near = RunPlan.corridor(RunPlan.Dist.K10, 45.0, 6, 14)
        assertTrue(far.fastSec < far.midSec && far.midSec < far.slowSec)
        assertTrue(far.slowSec - far.fastSec > near.slowSec - near.fastSec)
        // Меньше пробежек — медленнее середина
        assertTrue(RunPlan.corridor(RunPlan.Dist.K10, 45.0, 3, 120).midSec > far.midSec)
    }

    @Test
    fun weeksNeeded_realisticAndCapped() {
        val now = RunPlan.predictSec(RunPlan.Dist.K5, 45.0)
        assertEquals(0, RunPlan.weeksNeeded(RunPlan.Dist.K5, 45.0, now + 10, 5))
        assertNotNull(RunPlan.weeksNeeded(RunPlan.Dist.K5, 45.0, now - 30, 5))
        // Минус 8 минут на 5 км — больше года
        assertNull(RunPlan.weeksNeeded(RunPlan.Dist.K5, 45.0, now - 480, 5))
    }

    @Test
    fun build_planShape() {
        val start = 20_000L
        val race = start + 8 * 7
        val items = RunPlan.build(start, race, RunPlan.Dist.K10, 5, 20.0)
        assertEquals(RunPlan.Type.RACE, items.last().type)
        assertEquals(race, items.last().epochDay)
        assertTrue(items.all { it.epochDay in start..race })
        // Накануне старта — отдых
        assertFalse(items.any { it.epochDay == race - 1 })
        // В неделю не больше 5 пробежек, есть интервалы, темповые и длинные
        items.groupBy { (it.epochDay - start) / 7 }.values.forEach { assertTrue(it.size <= 5) }
        assertTrue(items.any { it.type == RunPlan.Type.INTERVALS })
        assertTrue(items.any { it.type == RunPlan.Type.TEMPO })
        assertTrue(items.any { it.type == RunPlan.Type.LONG })
        // Одна пробежка в неделю — тоже план
        assertTrue(RunPlan.build(start, race, RunPlan.Dist.K5, 1, 0.0).size >= 8)
    }

    @Test
    fun done_andTimeParsing() {
        val item = RunPlan.Item(100, RunPlan.Type.EASY, listOf(RunPlan.Seg(RunPlan.SegKind.STEADY, 6000, RunPlan.Zone.EASY)))
        assertTrue(RunPlan.isDone(item, listOf(RunPlan.RunData(100, 4300.0, emptyMap()))))
        assertFalse(RunPlan.isDone(item, listOf(RunPlan.RunData(100, 3000.0, emptyMap()))))
        assertFalse(RunPlan.isDone(item, listOf(RunPlan.RunData(101, 9000.0, emptyMap()))))
        assertEquals(45 * 60 + 30, RunPlan.parseTime("45:30"))
        assertEquals(3600 + 5 * 60 + 7, RunPlan.parseTime("1:05:07"))
        assertNull(RunPlan.parseTime("45:70"))
        assertEquals("1:05:07", RunPlan.time(3907.0))
    }

    @Test
    fun fitness_fromEfforts() {
        val runs = listOf(RunPlan.RunData(1000, 5200.0, mapOf(1000 to 210_000L, 5000 to 1_200_000L)))
        val v = RunPlan.fitness(runs, 1010)!!
        assertEquals(49.8, v, 0.5)
        assertNull(RunPlan.fitness(runs, 1100)) // старше 8 недель
    }
}
