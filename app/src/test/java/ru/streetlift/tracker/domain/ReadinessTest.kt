package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.streetlift.tracker.data.repo.READINESS_BAD
import ru.streetlift.tracker.data.repo.READINESS_MID
import ru.streetlift.tracker.data.repo.applyReadiness

class ReadinessTest {
    private fun plan(w: Float, reps: Int = 6, sets: Int = 5) = SessionPlan(
        weightKg = w, sets = sets, repsMin = reps, repsMax = reps + 4, targetReps = reps,
        rirMin = 1, rirMax = 2, isDeload = false, isEmergencyDeload = false,
    )

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
