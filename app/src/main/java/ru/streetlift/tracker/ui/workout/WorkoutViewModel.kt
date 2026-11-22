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
import kotlin.math.max

data class ExerciseBlock(
    val exercise: ExerciseEntity,
    val plan: SessionPlan,
    val logs: List<SetLogEntity>,
    /** Запланировано подходов с учётом добавленных «+ подход». */
    val plannedCount: Int,
    val skipped: Int,
) {
    val doneCount: Int get() = logs.size + skipped
    val complete: Boolean get() = doneCount >= plannedCount
}

data class WorkoutUi(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val dayName: String = "",
    val isDeload: Boolean = false,
    val alternating: Boolean = false,
    val canAlternate: Boolean = false,
    val blocks: List<ExerciseBlock> = emptyList(),
    val current: ExerciseBlock? = null,
    val currentSetNumber: Int = 0,
    val defaultWeight: Float = 0f,
    val defaultReps: Int = 0,
    val defaultRir: Int = 2,
    val recommendation: RestRecommendation? = null,
    val doneSets: Int = 0,
    val plannedSets: Int = 0,
    /** Меняется при смене текущего подхода — сбрасывает черновик ввода. */
    val stepKey: String = "",
    val stages: List<StageEntity> = emptyList(),
    /** Стадия для текущего подхода (в калибровке её можно менять). */
    val currentStage: String? = null,
)

class WorkoutViewModel(
    private val repo: WorkoutRepository,
    private val sessionId: Long,
) : ViewModel() {

    private data class Static(
        val session: SessionEntity,
        val exercises: List<ExerciseEntity>,
        val plans: Map<Long, SessionPlan>,
        val autoStart: Boolean,
        val stages: List<StageEntity>,
    )

    private data class Local(
        val alternating: Boolean = false,
        val extra: Map<Long, Int> = emptyMap(),
        val skipped: Map<Long, Int> = emptyMap(),
        val focusId: Long? = null,
        val missing: Boolean = false,
        val stageDraft: Map<Long, String?> = emptyMap(),
    )

    private val static = MutableStateFlow<Static?>(null)
    private val local = MutableStateFlow(Local())

    val ui: StateFlow<WorkoutUi> = combine(static, repo.observeSets(sessionId), local) { st, logs, loc ->
        build(st, logs, loc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUi())

    init {
        viewModelScope.launch {
            val session = repo.getSession(sessionId)
            if (session == null) {
                local.update { it.copy(missing = true) }
                return@launch
            }
            val exercises = session.dayId?.let { repo.getExercisesForDay(it) } ?: emptyList()
            val settings = repo.getSettings()
            val plans = exercises.associate {
                it.id to ProgressionEngine.sessionPlan(it.toConfig(), it.toState(), session.isDeload)
            }
            local.update { it.copy(alternating = session.alternating && exercises.size > 1) }
            static.value = Static(session, exercises, plans, settings.autoStartTimer, repo.getStages())
        }
    }

    private fun build(st: Static?, logs: List<SetLogEntity>, loc: Local): WorkoutUi {
        if (st == null) return WorkoutUi(missing = loc.missing)

        val blocks = st.exercises.map { ex ->
            val plan = st.plans.getValue(ex.id)
            ExerciseBlock(
                exercise = ex,
                plan = plan,
                logs = logs.filter { it.exerciseId == ex.id }.sortedBy { it.setIndex },
                plannedCount = plan.sets + (loc.extra[ex.id] ?: 0),
                skipped = loc.skipped[ex.id] ?: 0,
            )
        }
        val alternating = loc.alternating && blocks.size > 1
        val order = buildOrder(blocks, alternating)
        val byId = blocks.associateBy { it.exercise.id }
        val nextPending = order.firstOrNull { (id, n) -> n > (byId[id]?.doneCount ?: 0) }
        val focus = loc.focusId?.let { byId[it] }
        val current = focus ?: nextPending?.let { byId[it.first] }

        val lastLog = logs.maxByOrNull { it.loggedAt }
        val recBlock = lastLog?.exerciseId?.let { byId[it] } ?: current
        val rec = recBlock?.let {
            RestRecommender.recommend(it.exercise.type, alternating, it.plan.isDeload)
        }

        val prev = current?.logs?.lastOrNull()
        return WorkoutUi(
            loaded = true,
            dayName = st.session.dayName,
            isDeload = st.session.isDeload,
            alternating = alternating,
            canAlternate = blocks.size > 1,
            blocks = blocks,
            current = current,
            currentSetNumber = (current?.logs?.size ?: 0) + 1,
            defaultWeight = prev?.weightKg ?: current?.plan?.weightKg ?: 0f,
            defaultReps = prev?.reps ?: current?.plan?.targetReps ?: 0,
            defaultRir = prev?.rir ?: current?.plan?.rirMin ?: 2,
            recommendation = rec,
            doneSets = blocks.sumOf { it.doneCount },
            plannedSets = blocks.sumOf { max(it.plannedCount, it.doneCount) },
            stepKey = "${current?.exercise?.id}_${current?.logs?.size}_${current?.skipped}",
            stages = st.stages,
            currentStage = current?.let { stageFor(it, loc) },
        )
    }

    private fun stageFor(b: ExerciseBlock, loc: Local): String? =
        if (loc.stageDraft.containsKey(b.exercise.id)) loc.stageDraft[b.exercise.id]
        else b.logs.lastOrNull()?.leverageStage ?: b.exercise.leverageStage

    /** Выбор стадии во время калибровки. */
    fun setStage(exerciseId: Long, stage: String?) =
        local.update { it.copy(stageDraft = it.stageDraft + (exerciseId to stage)) }

    fun addStage(name: String) = viewModelScope.launch {
        val added = repo.addStage(name)
        static.update { it?.copy(stages = repo.getStages()) }
        ui.value.current?.exercise?.id?.let { setStage(it, added) }
    }

    /** Последовательно: А1 А2 А3 Б1 Б2… Чередование: А1 Б1 А2 Б2… */
    private fun buildOrder(blocks: List<ExerciseBlock>, alternating: Boolean): List<Pair<Long, Int>> =
        if (!alternating) {
            blocks.flatMap { b -> (1..b.plannedCount).map { b.exercise.id to it } }
        } else {
            val maxN = blocks.maxOfOrNull { it.plannedCount } ?: 0
            (1..maxN).flatMap { n -> blocks.filter { it.plannedCount >= n }.map { it.exercise.id to n } }
        }

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
            local.update { it.copy(focusId = null) }
            val remaining = u.plannedSets - u.doneSets - 1
            if (st.autoStart && remaining > 0) {
                onRest(RestRecommender.recommend(block.exercise.type, u.alternating, block.plan.isDeload))
            }
        }
    }

    fun skipCurrent() {
        val id = ui.value.current?.exercise?.id ?: return
        local.update { it.copy(skipped = it.skipped + (id to ((it.skipped[id] ?: 0) + 1)), focusId = null) }
    }

    fun addExtraSet(exerciseId: Long) {
        local.update { it.copy(extra = it.extra + (exerciseId to ((it.extra[exerciseId] ?: 0) + 1)), focusId = exerciseId) }
    }

    fun focus(exerciseId: Long) = local.update { it.copy(focusId = exerciseId) }

    fun setAlternating(on: Boolean) = local.update { it.copy(alternating = on, focusId = null) }

    fun deleteLog(log: SetLogEntity) = viewModelScope.launch { repo.deleteSet(log) }

    fun finish(onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(repo.finishSession(sessionId))
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        repo.discardSession(sessionId)
        onDone()
    }
}
