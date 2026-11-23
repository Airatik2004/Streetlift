package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatsTest {
    private fun near(expected: Double, actual: Double, tol: Double) =
        assertTrue("ожидалось $expected, получено $actual", kotlin.math.abs(expected - actual) <= tol)

    /** Прямая на север: 7 км, по 100 м; км 1–6 быстрее (4:30), остальное 5:00. */
    private fun track(): List<RunStats.TP> {
        val pts = ArrayList<RunStats.P>()
        var t = 0L
        val mPerDegLat = 111_195.0
        for (i in 0..70) {
            val d = i * 100.0
            if (i > 0) {
                val km = (d - 1) / 1000
                t += if (km >= 1 && km < 6) 27_000 else 30_000
            }
            pts += RunStats.P(55.0 + d / mPerDegLat, 37.0, t, 150.0 + if (i in 20..30) (i - 20) * 1.0 else 0.0)
        }
        return RunStats.track(listOf(pts))
    }

    @Test
    fun distanceAndSplits() {
        val tr = track()
        near(7000.0, tr.last().d, 5.0)
        val km = RunStats.splits(tr, 1000.0)
        assertEquals(7, km.size)
        near(300_000.0, km[0].timeMs.toDouble(), 1500.0)
        near(270_000.0, km[2].timeMs.toDouble(), 1500.0)
        assertEquals(14, RunStats.splits(tr, 500.0).size)
    }

    @Test
    fun bestWindowNotAlignedToKm() {
        val tr = track()
        val b = RunStats.best(tr, 5000.0)
        assertTrue(b != null)
        // Лучшие 5 км — с 1-го по 6-й: 5 × 4:30 = 22:30
        near(1_350_000.0, b!!.first.toDouble(), 2000.0)
        near(1000.0, b.second, 20.0)
    }

    @Test
    fun elevationGain() {
        val (gain, loss) = RunStats.elevation(track())
        near(10.0, gain!!, 3.0)
        near(10.0, loss!!, 3.0)
    }

    @Test
    fun formatting() {
        assertEquals("5:12", RunStats.pace(312.0))
        assertEquals("—", RunStats.pace(0.0))
    }
}
