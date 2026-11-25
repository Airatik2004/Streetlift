package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SkipEntity

class PastCycleTest {
    private fun ses(id: Long, day: Long, round: Int, at: Long, plan: String? = "p$id") =
        SessionEntity(
            id = id, dayId = day, dayName = "Д", startedAt = at, finishedAt = at + 1,
            roundNumber = round, isDeload = false, alternating = false, rollback = plan,
        )

    private fun skip(id: Long, day: Long, round: Int, at: Long) =
        SkipEntity(id = id, dayId = day, dayName = "Д", skippedAt = at, roundNumber = round, plan = "k$id")

    @Test
    fun previousAndCurrent_withSkips() {
        val sessions = listOf(
            ses(1, 1, 2, 100), // до сброса программы
            ses(2, 1, 1, 200), ses(3, 2, 1, 300),
            ses(5, 9, 1, 350), // день не из программы
            ses(6, 1, 2, 500),
        )
        val skips = listOf(skip(1, 2, 1, 400))
        val ev = PastCycle.events(sessions, skips, setOf(1L, 2L))
        val r = PastCycle.rounds(ev, round = 2)
        assertEquals(listOf(200L, 300L, 400L), r.previous.map { it.at })
        assertEquals("k1", r.previous.last().plan)
        assertEquals(listOf(500L), r.current.map { it.at })
    }

    @Test
    fun missingDay_takesNextPlan() {
        val cur = PastCycle.events(listOf(ses(7, 3, 2, 600, plan = null), ses(8, 3, 2, 700)), emptyList(), setOf(3L))
        assertEquals("p8", PastCycle.nextPlanFor(3, cur))
        assertNull(PastCycle.nextPlanFor(4, cur))
    }
}
