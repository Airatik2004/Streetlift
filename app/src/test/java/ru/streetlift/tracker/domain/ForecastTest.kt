package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    private val today = 20_000L

    @Test
    fun growingTrend_projectsUpWithCorridor() {
        // 8 недель, +0,5 кг/нед полной нагрузки
        val pts = (0 until 8).map { w -> Forecast.Pt(today - (7 - w) * 7L, 100.0 + w * 0.5) }
        val r = Forecast.of(pts, today, today + 56)!!
        assertEquals(103.5, r.now, 0.2)
        assertEquals(0.5, r.perWeek, 0.05)
        assertTrue(r.mid > r.now)
        assertTrue(r.low < r.mid && r.mid < r.high)
        // Дальше — коридор шире
        val far = Forecast.of(pts, today, today + 300)!!
        assertTrue(far.high - far.low > r.high - r.low)
    }

    @Test
    fun notEnoughData_orTooFar() {
        val two = listOf(Forecast.Pt(today, 100.0), Forecast.Pt(today - 7, 99.0))
        assertNull(Forecast.of(two, today, today + 30))
        val pts = (0 until 5).map { w -> Forecast.Pt(today - w * 7L, 100.0) }
        assertNull(Forecast.of(pts, today, today + 400))
        assertNotNull(Forecast.of(pts, today, today + 30))
    }

    @Test
    fun slopeIsCapped() {
        // Скачок +10 кг в неделю — тренд ограничен 1,5% в неделю
        val pts = (0 until 4).map { w -> Forecast.Pt(today - (3 - w) * 7L, 100.0 + w * 10) }
        val r = Forecast.of(pts, today, today + 28)!!
        assertTrue(r.perWeek <= 0.015 * r.now + 1e-9)
    }
}
