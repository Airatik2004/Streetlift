package ru.streetlift.tracker.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.domain.RestRecommendation
import ru.streetlift.tracker.domain.RestRecommender
import ru.streetlift.tracker.domain.SessionPlan
import ru.streetlift.tracker.timer.RestTimer
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
) {
    val doneCount: Int get() = logs.size
}

/** Строка списка шагов: подход (сделанный или будущий) или отдых между подходами. */
sealed interface StepRow {
    data class Work(val block: ExerciseBlock, val setNumber: Int, val log: SetLogEntity?) : StepRow
    data class Rest(val seconds: Int) : StepRow
}

data class WorkoutUi(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val dayName: String = "",
    val isDeload: Boolean = false,
    val blocks: List<ExerciseBlock> = emptyList(),
    /** Текущий подход (null — все сделаны). */
    val current: ExerciseBlock? = null,
    val currentSetNumber: Int = 0,
    val defaultWeight: Float = 0f,
    val defaultReps: Int = 0,
    val defaultRir: Int = 2,
    val doneSets: Int = 0,
    val plannedSets: Int = 0,
    /** Меняется при смене подхода — сбрасывает черновик ввода. */
    val stepKey: String = "",
    val stages: List<StageEntity> = emptyList(),
    val currentStage: String? = null,
    /** Сейчас идёт отдых этой тренировки. */
    val resting: Boolean = false,
    val rows: List<StepRow> = emptyList(),
    /** Индекс текущей строки в [rows] — для подсветки и прокрутки. */
    val currentRow: Int = 0,
    val canUndo: Boolean = false,
)

class WorkoutViewModel(
    private val repo: WorkoutRepository,
    private val sessionId: Long,
) : ViewModel() {

    private data class Static(
        val session: SessionEntity,
        val exercises: List<ExerciseEntity>,
        val plans: Map<Long, SessionPlan>,
        val stages: List<StageEntity>,
    )

    private data class Prefill(val weight: Float, val reps: Int, val rir: Int, val stage: String?)

    private data class Local(
        val extra: Map<Long, Int> = emptyMap(),
        val missing: Boolean = false,
        val stageDraft: Map<Long, String?> = emptyMap(),
        /** Значения откатанного подхода — подставляются в ввод. */
        val prefill: Map<String, Prefill> = emptyMap(),
    )

    val owner = "session_$sessionId"

    private val static = MutableStateFlow<Static?>(null)
    private val local = MutableStateFlow(Local())

    val ui: StateFlow<WorkoutUi> = combine(
        static, repo.observeSets(sessionId), local, RestTimer.state,
    ) { st, logs, loc, timer ->
        build(st, logs, loc, timer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUi())

    init {
        viewModelScope.launch {
            val session = repo.getSession(sessionId)
            if (session == null) {
                local.update { it.copy(missing = true) }
                return@launch
            }
            val exercises = session.dayId?.let { repo.getExercisesForDay(it) } ?: emptyList()
            val plans = exercises.associate {
                it.id to ProgressionEngine.sessionPlan(it.toConfig(), it.toState(), session.isDeload)
            }
            static.value = Static(session, exercises, plans, repo.getStages())
        }
    }

    private fun recommend(b: ExerciseBlock, st: Static): RestRecommendation =
        RestRecommender.recommend(b.exercise.type, st.session.alternating && st.exercises.size > 1, b.plan.isDeload)

    private fun build(st: Static?, logs: List<SetLogEntity>, loc: Local, timer: RestTimer.State): WorkoutUi {
        if (st == null) return WorkoutUi(missing = loc.missing)

        val blocks = st.exercises.map { ex ->
            val plan = st.plans.getValue(ex.id)
            val exLogs = logs.filter { it.exerciseId == ex.id }.sortedBy { it.loggedAt }
            val floor = if (plan.isDeload || plan.isCalibration) 1 else max(1, ex.minSets - 1)
            val minCount = max(floor, exLogs.size)
            val maxCount = max(max(ex.maxSets, plan.sets), exLogs.size)
            ExerciseBlock(
                exercise = ex,
                plan = plan,
                logs = exLogs,
                plannedCount = (plan.sets + (loc.extra[ex.id] ?: 0)).coerceIn(minCount, maxCount),
                minCount = minCount,
                maxCount = maxCount,
            )
        }
        val alternating = st.session.alternating && blocks.size > 1
        val order = buildOrder(blocks, alternating)
        val byId = blocks.associateBy { it.exercise.id }
        val nextPending = order.firstOrNull { (id, n) -> n > (byId[id]?.doneCount ?: 0) }
        val current = nextPending?.let { byId[it.first] }
        val setNumber = (current?.doneCount ?: 0) + 1
        val stepKey = "${current?.exercise?.id}_${current?.doneCount}"
        val resting = timer.owner == owner && (timer.running || (!timer.finished && !timer.idle))

        // Список шагов: подходы по порядку, между ними отдых
        val rows = mutableListOf<StepRow>()
        var currentRow = 0
        order.forEachIndexed { i, (id, n) ->
            val b = byId.getValue(id)
            if (i > 0) {
                val prev = byId.getValue(order[i - 1].first)
                rows += StepRow.Rest(recommend(prev, st).seconds)
            }
            if (current != null && id == current.exercise.id && n == setNumber) {
                currentRow = if (resting && rows.lastOrNull() is StepRow.Rest) rows.size - 1 else rows.size
            }
            rows += StepRow.Work(b, n, b.logs.getOrNull(n - 1))
        }
        if (current == null) currentRow = (rows.size - 1).coerceAtLeast(0)

        val pf = loc.prefill[stepKey]
        val prevLog = current?.logs?.lastOrNull()
        val stage = current?.let { b ->
            when {
                loc.stageDraft.containsKey(b.exercise.id) -> loc.stageDraft[b.exercise.id]
                pf != null -> pf.stage
                else -> prevLog?.leverageStage ?: b.exercise.leverageStage
            }
        }
        return WorkoutUi(
            loaded = true,
            dayName = st.session.dayName,
            isDeload = st.session.isDeload,
            blocks = blocks,
            current = current,
            currentSetNumber = setNumber,
            defaultWeight = pf?.weight ?: prevLog?.weightKg ?: current?.plan?.weightKg ?: 0f,
            defaultReps = pf?.reps ?: prevLog?.reps ?: current?.plan?.targetReps ?: 0,
            defaultRir = pf?.rir ?: prevLog?.rir ?: current?.plan?.rirMin ?: 2,
            doneSets = blocks.sumOf { it.doneCount },
            plannedSets = blocks.sumOf { it.plannedCount },
            stepKey = stepKey,
            stages = st.stages,
            currentStage = stage,
            resting = resting && current != null,
            rows = rows,
            currentRow = currentRow,
            canUndo = logs.isNotEmpty(),
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

    /** «Следующий» на шаге подхода: записать подход и начать отдых. */
    fun logSet(weight: Float, reps: Int, rir: Int, onRest: (RestRecommendation) -> Unit) {
        val u = ui.value
        val block = u.current ?: return
        val st = static.value ?: return
        viewModelScope.launch {
            repo.logSet(
                SetLogEntity(
                    sessionId = sessionId,
                    exerciseId = block.exercise.id,
                    exerciseName = block.exercise.name,
                    setIndex = block.logs.size + 1,
                    weightKg = ProgressionEngine.roundWeight(weight),
                    reps = reps.coerceAtLeast(0),
                    rir = rir.coerceIn(0, 5),
                    isDeload = block.plan.isDeload,
                    loggedAt = System.currentTimeMillis(),
                    leverageStage = u.currentStage,
                    isCalibration = block.plan.isCalibration,
                ),
            )
            local.update { it.copy(prefill = it.prefill - u.stepKey) }
            if (u.doneSets + 1 < u.plannedSets) onRest(recommend(block, st))
        }
    }

    /** «Предыдущий»: откатить последний записанный подход и вернуться к нему. */
    fun undoLast() = viewModelScope.launch {
        val logs = ui.value.blocks.flatMap { it.logs }
        val last = logs.maxByOrNull { it.loggedAt } ?: return@launch
        val key = "${last.exerciseId}_${logs.count { it.exerciseId == last.exerciseId } - 1}"
        local.update {
            it.copy(prefill = it.prefill + (key to Prefill(last.weightKg, last.reps, last.rir, last.leverageStage)))
        }
        repo.deleteSet(last)
    }

    /** −/+ подход для текущего упражнения в пределах коридора. */
    fun changeSets(exerciseId: Long, delta: Int) {
        val b = ui.value.blocks.firstOrNull { it.exercise.id == exerciseId } ?: return
        val target = (b.plannedCount + delta).coerceIn(b.minCount, b.maxCount)
        local.update { it.copy(extra = it.extra + (exerciseId to (target - b.plan.sets))) }
    }

    fun setStage(exerciseId: Long, stage: String?) =
        local.update { it.copy(stageDraft = it.stageDraft + (exerciseId to stage)) }

    fun addStage(name: String) = viewModelScope.launch {
        val added = repo.addStage(name)
        static.update { it?.copy(stages = repo.getStages()) }
        ui.value.current?.exercise?.id?.let { setStage(it, added) }
    }

    fun deleteLog(log: SetLogEntity) = viewModelScope.launch { repo.deleteSet(log) }

    fun finish(onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(repo.finishSession(sessionId))
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        repo.discardSession(sessionId)
        onDone()
    }
}
