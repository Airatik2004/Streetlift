package ru.streetlift.tracker.workout

import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.domain.RestRecommendation
import ru.streetlift.tracker.domain.RestRecommender
import ru.streetlift.tracker.domain.SessionPlan
import kotlin.math.max

data class ExerciseBlock(
    val exercise: ExerciseEntity,
    val plan: SessionPlan,
    val logs: List<SetLogEntity>,
    /** Запланировано подходов с учётом −/+ в пределах коридора. */
    val plannedCount: Int,
    /** Коридор для −/+. */
    val minCount: Int,
    val maxCount: Int,
    /** Одностороннее: с какой стороны начинать подходы этой тренировки («L»/«R»). */
    val firstSide: String? = null,
) {
    val unilateral: Boolean get() = firstSide != null

    /** Сделано подходов (у односторонних — обе стороны). */
    val doneCount: Int get() = if (unilateral) logs.size / 2 else logs.size

    /** Какая сторона сейчас («L»/«R»), null — обычное упражнение. */
    val pendingSide: String?
        get() = firstSide?.let { f -> if (logs.size % 2 == 0) f else otherSide(f) }

    /** Подход [n]: первая и вторая сторона. */
    fun setLogs(n: Int): Pair<SetLogEntity?, SetLogEntity?> =
        if (!unilateral) logs.getOrNull(n - 1) to null
        else logs.getOrNull((n - 1) * 2) to logs.getOrNull((n - 1) * 2 + 1)
}

fun otherSide(s: String) = if (s == "L") "R" else "L"

/** «левая» / «правая». */
fun sideName(s: String?) = when (s) {
    "L" -> "левая"
    "R" -> "правая"
    else -> ""
}

/** Строка списка шагов: подход (сделанный или будущий) или отдых между подходами. */
sealed interface StepRow {
    data class Work(val block: ExerciseBlock, val setNumber: Int, val log: SetLogEntity?, val log2: SetLogEntity? = null) : StepRow
    data class Rest(val seconds: Int) : StepRow
}

/** Значения для ввода подхода. */
data class Draft(val weight: Float, val reps: Int, val rir: Int, val stage: String?, val height: Int? = null)

/** Полное вычисленное состояние тренировки на текущий момент. */
data class PlanResult(
    val session: SessionEntity,
    val blocks: List<ExerciseBlock>,
    val alternating: Boolean,
    val current: ExerciseBlock?,
    val setNumber: Int,
    val stepKey: String,
    val doneSets: Int,
    val plannedSets: Int,
    val rows: List<StepRow>,
    val currentRow: Int,
    val defaults: Draft,
    val canUndo: Boolean,
    val resting: Boolean,
    /** Текущая сторона у одностороннего упражнения («L»/«R»). */
    val side: String? = null,
) {
    fun recommend(b: ExerciseBlock): RestRecommendation =
        RestRecommender.recommend(b.exercise.type, alternating, b.plan.isDeload)

    /** «Отжимания в горизонте · подход 3 из 4». */
    val exerciseLine: String
        get() = current?.let {
            "${it.exercise.name} · подход $setNumber из ${it.plannedCount}" + (side?.let { s -> " · ${sideName(s)}" } ?: "")
        } ?: "Все подходы сделаны"
}

/** Порядок подходов, текущий шаг и список шагов — общая логика экрана и уведомления. */
object WorkoutPlanner {

    fun compute(
        session: SessionEntity,
        exercises: List<ExerciseEntity>,
        plans: Map<Long, SessionPlan>,
        logs: List<SetLogEntity>,
        extra: Map<Long, Int>,
        prefill: Map<String, Draft>,
        resting: Boolean,
        firstSides: Map<Long, String> = emptyMap(),
    ): PlanResult {
        val blocks = exercises.map { ex ->
            val plan = plans.getValue(ex.id)
            val exLogs = logs.filter { it.exerciseId == ex.id }.sortedBy { it.loggedAt }
            val floor = if (plan.isDeload || plan.isCalibration) 1 else max(1, ex.minSets - 1)
            val first = firstSides[ex.id]
            val started = if (first != null) (exLogs.size + 1) / 2 else exLogs.size
            val minCount = max(floor, started)
            val maxCount = max(max(ex.maxSets, plan.sets), started)
            ExerciseBlock(
                exercise = ex,
                plan = plan,
                logs = exLogs,
                plannedCount = (plan.sets + (extra[ex.id] ?: 0)).coerceIn(minCount, maxCount),
                minCount = minCount,
                maxCount = maxCount,
                firstSide = first,
            )
        }
        // Статика и задняя дельта — всегда в конце дня, вне суперсета
        val main = blocks.filter { !it.exercise.outOfSuperset }
        val tail = blocks.filter { it.exercise.outOfSuperset }
        val alternating = session.alternating && main.size > 1
        val order = buildOrder(main, alternating) + buildOrder(tail, false)
        val byId = blocks.associateBy { it.exercise.id }
        val nextPending = order.firstOrNull { (id, n) -> n > (byId[id]?.doneCount ?: 0) }
        val current = nextPending?.let { byId[it.first] }
        val setNumber = (current?.doneCount ?: 0) + 1
        val stepKey = "${current?.exercise?.id}_${current?.logs?.size}"
        val isResting = resting && current != null

        val rows = mutableListOf<StepRow>()
        var currentRow = 0
        order.forEachIndexed { i, (id, n) ->
            val b = byId.getValue(id)
            if (i > 0) {
                val prev = byId.getValue(order[i - 1].first)
                rows += StepRow.Rest(RestRecommender.recommend(prev.exercise.type, alternating, prev.plan.isDeload).seconds)
            }
            if (current != null && id == current.exercise.id && n == setNumber) {
                currentRow = if (isResting && rows.lastOrNull() is StepRow.Rest) rows.size - 1 else rows.size
            }
            val (l1, l2) = b.setLogs(n)
            rows += StepRow.Work(b, n, l1, l2)
        }
        if (current == null) currentRow = (rows.size - 1).coerceAtLeast(0)

        val pf = prefill[stepKey]
        val prevLog = current?.logs?.lastOrNull()
        val defaults = Draft(
            weight = pf?.weight ?: prevLog?.weightKg ?: current?.plan?.weightKg ?: 0f,
            reps = pf?.reps ?: prevLog?.reps ?: current?.plan?.targetReps ?: 0,
            rir = pf?.rir ?: prevLog?.rir ?: current?.plan?.rirMin ?: 2,
            stage = pf?.stage ?: prevLog?.leverageStage ?: current?.exercise?.leverageStage,
            height = pf?.height ?: prevLog?.heightCm ?: current?.plan?.heightCm ?: if (current?.exercise?.isJump == true) 50 else null,
        )
        return PlanResult(
            session = session,
            blocks = blocks,
            alternating = alternating,
            current = current,
            setNumber = setNumber,
            stepKey = stepKey,
            doneSets = blocks.sumOf { it.doneCount },
            plannedSets = blocks.sumOf { it.plannedCount },
            rows = rows,
            currentRow = currentRow,
            defaults = defaults,
            canUndo = logs.isNotEmpty(),
            resting = isResting,
            side = current?.pendingSide,
        )
    }

    /** Последовательно: А1 А2 А3 Б1 Б2… Чередование: А1 Б1 А2 Б2… */
    private fun buildOrder(blocks: List<ExerciseBlock>, alternating: Boolean): List<Pair<Long, Int>> =
        if (!alternating) {
            blocks.flatMap { b -> (1..b.plannedCount).map { b.exercise.id to it } }
        } else {
            val maxN = blocks.maxOfOrNull { it.plannedCount } ?: 0
            (1..maxN).flatMap { n -> blocks.filter { it.plannedCount >= n }.map { it.exercise.id to n } }
        }
}
