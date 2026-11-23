package ru.streetlift.tracker.workout

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.streetlift.tracker.StreetLiftApp
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.timer.RestTimer
import ru.streetlift.tracker.timer.TimerActions
import ru.streetlift.tracker.timer.TimerService

/**
 * Идущая тренировка на уровне процесса. Ею управляют и экран, и кнопки уведомления
 * (⏮ ⏯ ⏭), поэтому всё работает и при свёрнутом приложении.
 */
object ActiveWorkout {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _result = MutableStateFlow<PlanResult?>(null)
    /** Текущее состояние тренировки; null — тренировки нет. */
    val result: StateFlow<PlanResult?> = _result.asStateFlow()

    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    private val extra = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val prefill = MutableStateFlow<Map<String, Draft>>(emptyMap())

    /** Что сейчас стоит в полях ввода на экране (для «Следующий» из уведомления). */
    @Volatile private var draftKey = ""
    @Volatile private var draft: Draft? = null

    fun owner(id: Long) = "session_$id"

    private fun repo(ctx: Context): WorkoutRepository = (ctx.applicationContext as StreetLiftApp).repository

    /** Начать/продолжить отслеживание тренировки. */
    fun begin(ctx: Context, sessionId: Long) {
        val app = ctx.applicationContext
        if (_sessionId.value == sessionId && job?.isActive == true) {
            TimerService.ensureRunning(app)
            return
        }
        job?.cancel()
        _sessionId.value = sessionId
        _missing.value = false
        extra.value = emptyMap()
        prefill.value = emptyMap()
        draft = null
        val repo = repo(app)
        job = scope.launch {
            val session = repo.getSession(sessionId)
            if (session == null || session.finishedAt != null) {
                _missing.value = session == null
                return@launch
            }
            val exercises = session.dayId?.let { repo.getExercisesForDay(it) } ?: emptyList()
            val plans = exercises.associate {
                it.id to ProgressionEngine.sessionPlan(it.toConfig(), it.toState(), session.isDeload)
            }
            val own = owner(sessionId)
            TimerService.ensureRunning(app)
            combine(repo.observeSets(sessionId), extra, prefill, RestTimer.state) { logs, ex, pf, timer ->
                val resting = timer.owner == own && !timer.finished && !timer.idle
                WorkoutPlanner.compute(session, exercises, plans, logs, ex, pf, resting)
            }.collect { _result.value = it }
        }
    }

    /** Тренировка завершена или отменена. */
    fun end() {
        job?.cancel()
        job = null
        _sessionId.value = null
        _result.value = null
        extra.value = emptyMap()
        prefill.value = emptyMap()
        draft = null
    }

    fun setDraft(key: String, d: Draft) {
        draftKey = key
        draft = d
    }

    /**
     * ⏭ Следующий: на подходе — записать его и начать отдых; на отдыхе — закончить отдых.
     * [byPlan] — из уведомления: записать ровно по плану (вес, цель повторов, верхний RIR).
     */
    fun next(ctx: Context, byPlan: Boolean = false) {
        val r = _result.value ?: return
        if (r.resting) {
            TimerActions.reset()
            return
        }
        val cur = r.current ?: return
        val d = if (byPlan) {
            Draft(cur.plan.weightKg, cur.plan.targetReps, cur.plan.rirMax, r.defaults.stage)
        } else {
            draft?.takeIf { draftKey == r.stepKey } ?: r.defaults
        }
        val app = ctx.applicationContext
        scope.launch {
            repo(app).logSet(
                SetLogEntity(
                    sessionId = r.session.id,
                    exerciseId = cur.exercise.id,
                    exerciseName = cur.exercise.name,
                    setIndex = cur.logs.size + 1,
                    weightKg = ProgressionEngine.roundWeight(d.weight),
                    reps = d.reps.coerceAtLeast(0),
                    rir = d.rir.coerceIn(0, 5),
                    isDeload = cur.plan.isDeload,
                    loggedAt = System.currentTimeMillis(),
                    leverageStage = d.stage,
                    isCalibration = cur.plan.isCalibration,
                ),
            )
            prefill.update { it - r.stepKey }
            draft = null
            if (r.doneSets + 1 < r.plannedSets) {
                TimerActions.start(app, r.recommend(cur).seconds * 1000L, owner = owner(r.session.id))
            }
        }
    }

    /** ⏮ Предыдущий: откатить последний записанный подход (значения вернутся в ввод). */
    fun prev(ctx: Context) {
        val r = _result.value ?: return
        if (r.resting) TimerActions.reset()
        val logs = r.blocks.flatMap { it.logs }
        val last = logs.maxByOrNull { it.loggedAt } ?: return
        val key = "${last.exerciseId}_${logs.count { it.exerciseId == last.exerciseId } - 1}"
        prefill.update { it + (key to Draft(last.weightKg, last.reps, last.rir, last.leverageStage)) }
        val app = ctx.applicationContext
        scope.launch { repo(app).deleteSet(last) }
    }

    /** ⏯ Пауза — только для отдыха. */
    fun toggle(ctx: Context) {
        if (_result.value?.resting == true) TimerActions.toggle(ctx.applicationContext)
    }

    /** −/+ подход текущего упражнения в пределах коридора. */
    fun changeSets(exerciseId: Long, delta: Int) {
        val b = _result.value?.blocks?.firstOrNull { it.exercise.id == exerciseId } ?: return
        val target = (b.plannedCount + delta).coerceIn(b.minCount, b.maxCount)
        extra.update { it + (exerciseId to (target - b.plan.sets)) }
    }

    fun deleteLog(ctx: Context, log: SetLogEntity) {
        val app = ctx.applicationContext
        scope.launch { repo(app).deleteSet(log) }
    }
}
