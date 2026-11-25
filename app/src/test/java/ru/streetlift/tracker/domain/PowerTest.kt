package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.streetlift.tracker.domain.Power.Kind
import ru.streetlift.tracker.domain.Power.Lift

class PowerTest {

    @Test
    fun parsesReference() {
        assertEquals(Power.Ref(80f, 5), Power.parseRef("80 × 5"))
        assertEquals(Power.Ref(82.5f, 3), Power.parseRef("82,5x3"))
        assertEquals(Power.Ref(100f, 2), Power.parseRef("100 х 2"))
        assertEquals(Power.Ref(60f, 8), Power.parseRef("60*8"))
        assertNull(Power.parseRef("80"))
        assertNull(Power.parseRef("abc"))
        assertNull(Power.parseRef("80 × 0"))
    }

    @Test
    fun refsRoundTrip() {
        val refs = mapOf(Lift.BENCH to Power.Ref(80f, 5), Lift.DEADLIFT to Power.Ref(142.5f, 3))
        assertEquals(refs, Power.decodeRefs(Power.encodeRefs(refs)))
    }

    @Test
    fun liftRotation() {
        assertEquals(listOf(Lift.BENCH), Power.liftsFor(Kind.BENCH, 3))
        // Троеборье: жим всегда, присед и тяга через раз, порядок соревнований
        assertEquals(listOf(Lift.SQUAT, Lift.BENCH), Power.liftsFor(Kind.TOTAL, 0))
        assertEquals(listOf(Lift.BENCH, Lift.DEADLIFT), Power.liftsFor(Kind.TOTAL, 1))
        // Жим + тяга: тяга через раз
        assertEquals(listOf(Lift.BENCH, Lift.DEADLIFT), Power.liftsFor(Kind.PUSH_PULL, 0))
        assertEquals(listOf(Lift.BENCH), Power.liftsFor(Kind.PUSH_PULL, 1))
    }

    @Test
    fun heavyLightAlternation() {
        // После калибровки — лёгкая, потом по очереди
        assertFalse(Power.isHeavy(Kind.BENCH, Lift.BENCH, 1))
        assertTrue(Power.isHeavy(Kind.BENCH, Lift.BENCH, 2))
        // Тяга отдельным движением — тяжёлая раз в три
        assertFalse(Power.isHeavy(Kind.DEADLIFT, Lift.DEADLIFT, 1))
        assertFalse(Power.isHeavy(Kind.DEADLIFT, Lift.DEADLIFT, 2))
        assertTrue(Power.isHeavy(Kind.DEADLIFT, Lift.DEADLIFT, 3))
    }

    @Test
    fun calibrationUsesReference() {
        val p = Power.plan(Kind.BENCH, Lift.BENCH, 0, null, Power.Ref(80f, 5), PeakPhase.PRE_PEAK)
        assertNotNull(p)
        assertTrue(p!!.isCalibration)
        assertEquals(80f, p.weightKg, 0.001f)
        assertEquals(5, p.targetReps)
        assertEquals(1, p.sets)
    }

    @Test
    fun heavyAndLightPercentages() {
        val heavy = Power.plan(Kind.BENCH, Lift.BENCH, 2, 100f, null, PeakPhase.PRE_PEAK)!!
        assertEquals(87.5f, heavy.weightKg, 0.001f) // 87 → к ближайшим 2,5
        assertEquals(4, heavy.sets)
        assertEquals(3, heavy.targetReps)
        val light = Power.plan(Kind.BENCH, Lift.BENCH, 1, 100f, null, PeakPhase.PRE_PEAK)!!
        assertEquals(70f, light.weightKg, 0.001f)
        assertEquals(3, light.sets)
        val taper = Power.plan(Kind.BENCH, Lift.BENCH, 2, 100f, null, PeakPhase.TAPER1)!!
        assertEquals(92.5f, taper.weightKg, 0.001f)
        assertEquals(2, taper.targetReps)
        val tonus = Power.plan(Kind.BENCH, Lift.BENCH, 1, 100f, null, PeakPhase.TONUS)!!
        assertEquals(85f, tonus.weightKg, 0.001f)
        assertEquals(1, tonus.targetReps)
        assertNull(Power.plan(Kind.BENCH, Lift.BENCH, 1, 100f, null, PeakPhase.NONE))
    }

    @Test
    fun warmupBelowWork() {
        val plan = Power.plan(Kind.BENCH, Lift.BENCH, 2, 100f, null, PeakPhase.TAPER1)!!
        val w = Power.warmup(Lift.BENCH, plan)
        assertEquals(3, w.size)
        assertTrue(w.all { it.weightKg < plan.weightKg })
        assertEquals(listOf(5, 3, 1), w.map { it.reps })
        assertEquals(2, Power.warmup(Lift.BENCH, plan.copy(isDeload = true)).size)
    }

    @Test
    fun wrpfRanks() {
        // 75 кг, жим 120 — КМС (117,5), до МС 135
        val (done, next) = Kind.BENCH.table!!.evaluate(75f, 120f)
        assertEquals(Standards.Rank.KMS, done!!.first)
        assertEquals(Standards.Rank.MS, next!!.first)
        assertNull(Kind.TOTAL.table)
        assertNull(Kind.SQUAT.table)
    }
}
