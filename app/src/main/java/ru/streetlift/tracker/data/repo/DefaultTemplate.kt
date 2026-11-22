package ru.streetlift.tracker.data.repo

import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.ProgramDao
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.LeverageStages
import ru.streetlift.tracker.domain.ExerciseType.ACCESSORY
import ru.streetlift.tracker.domain.ExerciseType.EXPLOSIVE
import ru.streetlift.tracker.domain.ExerciseType.HEAVY_LEVER
import ru.streetlift.tracker.domain.ExerciseType.HYPERTROPHY_PUMP

/** Предустановленный 4-тактный цикл (первый запуск и «Сбросить к шаблону»). */
object DefaultTemplate {

    private data class Ex(
        val name: String,
        val type: ExerciseType,
        val sets: IntRange,
        val reps: IntRange,
        val rir: IntRange,
        val step: Float,
        val stage: String? = null,
    )

    private data class Day(val name: String, val alternating: Boolean, val exercises: List<Ex>)

    private val days = listOf(
        Day(
            "День 1 · Выходы + ноги", false,
            listOf(
                Ex("Выходы силой", EXPLOSIVE, 5..6, 6..10, 1..2, 2.5f),
                Ex("Пистолетики", ACCESSORY, 3..4, 8..12, 2..2, 2.5f),
            ),
        ),
        Day(
            "День 2 · Горизонт + 1 рука", true,
            listOf(
                Ex("Отжимания в горизонте", HEAVY_LEVER, 3..4, 5..8, 1..2, 2.5f, LeverageStages.DEFAULT),
                Ex("Подтягивания на 1 руке", HEAVY_LEVER, 3..4, 3..6, 1..2, 2.5f),
            ),
        ),
        Day(
            "День 3 · Многоповторка", false,
            listOf(
                Ex("Отжимания на брусьях", HYPERTROPHY_PUMP, 4..4, 15..20, 2..2, 2.5f),
                Ex("Подтягивания глубоким хватом", HYPERTROPHY_PUMP, 4..4, 15..20, 2..2, 2.5f),
                Ex("Обратные разведения на заднюю дельту", ACCESSORY, 3..3, 15..20, 2..2, 1.25f),
            ),
        ),
        Day(
            "День 4 · Стойка + передний вис", true,
            listOf(
                Ex("Отжимания в стойке на руках", HEAVY_LEVER, 4..5, 6..10, 1..2, 2.5f),
                Ex("Подтягивания в переднем висе", HEAVY_LEVER, 4..4, 5..8, 1..2, 2.5f, LeverageStages.DEFAULT),
            ),
        ),
    )

    fun builtInStages(): List<StageEntity> = LeverageStages.builtIn.mapIndexed { i, st ->
        StageEntity(name = st.name, description = st.description, position = i, builtIn = true)
    }

    suspend fun seedProgram(dao: ProgramDao) {
        days.forEachIndexed { i, d ->
            val dayId = dao.insertDay(DayEntity(position = i, name = d.name, alternating = d.alternating))
            d.exercises.forEachIndexed { j, e ->
                dao.insertExercise(
                    ExerciseEntity(
                        dayId = dayId,
                        position = j,
                        name = e.name,
                        type = e.type,
                        minSets = e.sets.first,
                        maxSets = e.sets.last,
                        minReps = e.reps.first,
                        maxReps = e.reps.last,
                        targetRirMin = e.rir.first,
                        targetRirMax = e.rir.last,
                        weightStep = e.step,
                        weightKg = 0f,
                        plannedSets = e.sets.first,
                        targetReps = e.reps.first,
                        leverageStage = e.stage,
                    ),
                )
            }
        }
    }
}
