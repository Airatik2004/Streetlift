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
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.workout.ActiveWorkout
import ru.streetlift.tracker.workout.ExerciseBlock
import ru.streetlift.tracker.workout.StepRow

data class WorkoutUi(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val dayName: String = "",
    val isDeload: Boolean = false,
    val blocks: List<ExerciseBlock> = emptyList(),
    val current: ExerciseBlock? = null,
    val currentSetNumber: Int = 0,
    /** «левая» / «правая» у односторонних, иначе пусто. */
    val currentSide: String = "",
    val defaultWeight: Float = 0f,
    val defaultReps: Int = 0,
    val defaultRir: Int = 2,
    /** Прыжки на тумбу: высота по умолчанию, см. */
    val defaultHeight: Int = 50,
    val doneSets: Int = 0,
    val plannedSets: Int = 0,
    val stepKey: String = "",
    val stages: List<StageEntity> = emptyList(),
    val currentStage: String? = null,
    val resting: Boolean = false,
    val rows: List<StepRow> = emptyList(),
    val currentRow: Int = 0,
    val canUndo: Boolean = false,
    /** Фаза подводки («Предпик», «Тейпер 1»…) или null. */
    val peakTitle: String? = null,
)

/** Экран — лишь вид на [ActiveWorkout]: та же логика работает из уведомления. */
class WorkoutViewModel(
    private val repo: WorkoutRepository,
    private val sessionId: Long,
) : ViewModel() {

    private val stageDraft = MutableStateFlow<Map<Long, String?>>(emptyMap())

    val ui: StateFlow<WorkoutUi> = combine(
        ActiveWorkout.result, ActiveWorkout.missing, repo.stages, stageDraft,
    ) { r, missing, stages, sd ->
        if (r == null || r.session.id != sessionId) {
            WorkoutUi(missing = missing)
        } else {
            val cur = r.current
            WorkoutUi(
                loaded = true,
                dayName = r.session.dayName,
                isDeload = r.session.isDeload,
                blocks = r.blocks,
                current = cur,
                currentSetNumber = r.setNumber,
                currentSide = ru.streetlift.tracker.workout.sideName(r.side),
                defaultWeight = r.defaults.weight,
                defaultReps = r.defaults.reps,
                defaultRir = r.defaults.rir,
                defaultHeight = r.defaults.height ?: 50,
                doneSets = r.doneSets,
                plannedSets = r.plannedSets,
                stepKey = r.stepKey,
                stages = stages,
                currentStage = cur?.let { b ->
                    if (sd.containsKey(b.exercise.id)) sd[b.exercise.id] else r.defaults.stage
                },
                resting = r.resting,
                rows = r.rows,
                currentRow = r.currentRow,
                canUndo = r.canUndo,
                peakTitle = r.session.peakPhase?.let {
                    runCatching { ru.streetlift.tracker.domain.PeakPhase.valueOf(it).title }.getOrNull()
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUi())

    fun setStage(exerciseId: Long, stage: String?) =
        stageDraft.update { it + (exerciseId to stage) }


    fun finish(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = repo.finishSession(sessionId)
        ActiveWorkout.end()
        onDone(s)
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        repo.discardSession(sessionId)
        ActiveWorkout.end()
        onDone()
    }
}
