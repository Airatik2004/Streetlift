package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class JumpsTest {
    private fun ok(s: Jumps.State) = List(4) { Jumps.Log(s.heightCm, s.weightKg, s.reps) }
    private fun bad(s: Jumps.State) = List(4) { Jumps.Log(s.heightCm, s.weightKg, 1) }

    @Test
    fun climbs() {
        var s = Jumps.State(60)
        repeat(3) { s = Jumps.next(s, ok(s), false).state }   // 3→4→5→+2.5 кг 4×4
        assertEquals(60, s.heightCm)
        assertEquals(2.5f, s.weightKg, 0.01f)
        assertEquals(4, s.reps)
        s = Jumps.next(s, ok(s), false).state                  // +5 кг 4×4
        assertEquals(5f, s.weightKg, 0.01f)
        assertEquals(4, s.reps)
        s = Jumps.next(s, ok(s), false).state
        assertEquals(65, s.heightCm)
        assertEquals(0f, s.weightKg, 0.01f)
    }

    @Test
    fun plateauBreak() {
        var s = Jumps.State(80, reps = 4)
        s = Jumps.next(s, bad(s), false).state
        assertEquals(1, s.fails)
        s = Jumps.next(s, bad(s), false).state
        assertEquals(70, s.heightCm)
        assertEquals(5f, s.weightKg, 0.01f)
        assertEquals(80, s.plateauCm)
        s = Jumps.next(s, ok(s), false).state      // 75 с весом
        assertEquals(75, s.heightCm)
        s = Jumps.next(s, ok(s), false).state      // дошли до 80 — вес снят
        assertEquals(80, s.heightCm)
        assertEquals(0f, s.weightKg, 0.01f)
        assertEquals(null, s.plateauCm)
    }
}
