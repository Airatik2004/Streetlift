package ru.streetlift.tracker.data.repo

import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.ProgramDao
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.StageGroups
import ru.streetlift.tracker.domain.ExerciseType.ACCESSORY
import ru.streetlift.tracker.domain.ExerciseType.EXPLOSIVE
import ru.streetlift.tracker.domain.ExerciseType.HEAVY_LEVER
import ru.streetlift.tracker.domain.ExerciseType.HYPERTROPHY_PUMP
import ru.streetlift.tracker.domain.ExerciseType.ISOLATION
import ru.streetlift.tracker.domain.ExerciseType.STATIC_HOLD
import ru.streetlift.tracker.domain.Metric

/** Предустановленный 4-тактный цикл (первый запуск и «Сбросить к шаблону»). */
object DefaultTemplate {

    private data class Ex(
        val name: String,
        val type: ExerciseType,
        val sets: IntRange,
        val reps: IntRange,
        val rir: IntRange,
        val step: Float,
        val group: String? = null,
        val metric: Metric = Metric.REPS,
        val alwaysLast: Boolean = false,
        val shareKey: String? = null,
    )

    private data class Day(val name: String, val alternating: Boolean, val exercises: List<Ex>)

    /**
     * Программа зашита в коде. Порядок — для приоритета «Жим»; при «Тяге» в днях 2–4
     * первые два упражнения меняются местами (ProgramOrder). Статика всегда в конце дня.
     */
    private val days = listOf(
        Day(
            "День 1 · Выходы + ноги", false,
            listOf(
                Ex("Выходы силой", EXPLOSIVE, 5..6, 6..10, 1..2, 1.25f),
                Ex("Пистолетики", ACCESSORY, 3..4, 8..12, 2..2, 1.25f),
                // Через цикл с пистолетиками; растёт высота (Jumps)
                Ex("Прыжки на тумбу", EXPLOSIVE, 4..4, 3..5, 2..2, 2.5f),
                Ex("Разведения на дельту", ISOLATION, 3..3, 15..20, 2..2, 1.25f, alwaysLast = true, shareKey = "rear_delt"),
            ),
        ),
        Day(
            "День 2 · Горизонт + П1Р", false,
            listOf(
                Ex("Отжимания в горизонте", HEAVY_LEVER, 3..3, 5..8, 1..2, 1.25f, StageGroups.LEVER),
                Ex("Подтягивания на 1 руке", HEAVY_LEVER, 3..4, 3..6, 1..2, 1.25f),
                Ex("Удержание горизонта", STATIC_HOLD, 3..3, 8..15, 2..2, 1.25f, StageGroups.LEVER, Metric.SECONDS),
            ),
        ),
        Day(
            "День 3 · Многоповторка", false,
            listOf(
                Ex("Отжимания на брусьях", HYPERTROPHY_PUMP, 4..4, 15..20, 2..2, 1.25f),
                Ex("Подтягивания", HYPERTROPHY_PUMP, 4..4, 15..20, 2..2, 1.25f),
                Ex("Разведения на дельту", ISOLATION, 3..3, 15..20, 2..2, 1.25f, alwaysLast = true, shareKey = "rear_delt"),
            ),
        ),
        Day(
            "День 4 · Стойка + передний вис", false,
            listOf(
                Ex("Отжимания в стойке на руках", HEAVY_LEVER, 4..5, 6..10, 1..2, 1.25f, StageGroups.HANDSTAND),
                Ex("Подтягивания в переднем висе", HEAVY_LEVER, 3..3, 5..8, 1..2, 1.25f, StageGroups.LEVER),
                Ex("Удержание переднего виса", STATIC_HOLD, 3..3, 8..15, 2..2, 1.25f, StageGroups.LEVER, Metric.SECONDS),
            ),
        ),
    )

    /** Старые названия → новые (для импорта старых бэкапов). */
    val renamed = mapOf(
        "Подтягивания глубоким хватом" to "Подтягивания",
        "Обратные разведения на заднюю дельту" to "Разведения на дельту",
    )

    /** Старые названия дней → новые (для импорта старых бэкапов). */
    val renamedDays = mapOf("День 2 · Горизонт + 1 рука" to "День 2 · Горизонт + П1Р")

    fun builtInStages(): List<StageEntity> = StageGroups.lists.flatMap { (group, list) ->
        list.mapIndexed { i, st ->
            StageEntity(name = st.name, description = st.description, position = i, builtIn = true, groupKey = group)
        }
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
                        leverageStage = e.group?.let { StageGroups.defaultFor(it) },
                        stageGroup = e.group,
                        metric = e.metric,
                        alwaysLast = e.alwaysLast,
                        shareKey = e.shareKey,
                    ),
                )
            }
        }
    }
}
