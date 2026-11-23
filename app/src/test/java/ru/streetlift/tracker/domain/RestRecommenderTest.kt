package ru.streetlift.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestRecommenderTest {

    @Test
    fun alternatingHeavy_is2m30() {
        assertEquals(150, RestRecommender.recommend(ExerciseType.HEAVY_LEVER, alternating = true, deload = false).seconds)
        assertEquals(150, RestRecommender.recommend(ExerciseType.EXPLOSIVE, alternating = true, deload = false).seconds)
    }

    @Test
    fun straightHeavy_isBetween3m30and4m() {
        val s1 = RestRecommender.recommend(ExerciseType.HEAVY_LEVER, false, false).seconds
        val s2 = RestRecommender.recommend(ExerciseType.EXPLOSIVE, false, false).seconds
        assertTrue(s1 in 210..240)
        assertTrue(s2 in 210..240)
    }

    @Test
    fun pump_is2m_accessory2m30_isolation1m30() {
        assertEquals(120, RestRecommender.recommend(ExerciseType.HYPERTROPHY_PUMP, false, false).seconds)
        assertEquals(150, RestRecommender.recommend(ExerciseType.ACCESSORY, false, false).seconds)
        assertEquals(90, RestRecommender.recommend(ExerciseType.ISOLATION, false, false).seconds)
    }

    @Test
    fun deload_cuts30Seconds() {
        assertEquals(90, RestRecommender.recommend(ExerciseType.HYPERTROPHY_PUMP, false, true).seconds)
        assertEquals(120, RestRecommender.recommend(ExerciseType.HEAVY_LEVER, true, true).seconds)
        assertTrue(RestRecommender.recommend(ExerciseType.HEAVY_LEVER, true, true).reason.contains("разгрузка"))
    }
}
