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
import ru.streetlift.tracker.domain.PeakPhase
import ru.streetlift.tracker.domain.Peaking
import ru.streetlift.tracker.domain.Priority
import ru.streetlift.tracker.domain.ProgramOrder
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

    /** Пауза на смену стороны у односторонних упражнений. */
    const val SIDE_SWITCH_MS = 30_000L

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
            // Порядок дня по приоритету тренировки (Жим/Тяга): в днях 2–4 первые два меняются местами
            val dayPos = session.dayId?.let { repo.getDay(it)?.position } ?: 0
            val priority = session.priority?.let { runCatching { Priority.valueOf(it) }.getOrNull() } ?: Priority.PRESS
            // На подводке первыми идут упражнения пика, внутри групп — порядок мезоцикла
            val sessionPeakIds = session.peakIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
            val all = ProgramOrder.order(dayPos, session.dayId?.let { repo.getExercisesForDay(it) } ?: emptyList(), priority) { it.id in sessionPeakIds }
                // Через цикл: пистолетики / прыжки на тумбу
                .filter { it.activeInSlot(session.effectiveAltSlot) }
            // Подводка: у пиковых/фоновых свой план, часть упражнений пропускается
            val st = repo.getSettings()
            val bodyWeight = st.bodyWeightKg
            val phase = session.peakPhase?.let { runCatching { PeakPhase.valueOf(it) }.getOrNull() } ?: PeakPhase.NONE
            val peakIds = session.peakIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
            // Пауэр: движения штанги первыми (их в программе нет), основная программа — в поддержке,
            // в снижении и тонусе полностью исключается
            val power = st.power != null && phase != PeakPhase.NONE
            val powerItems = if (power) {
                val lifts = peakIds.mapNotNull { ru.streetlift.tracker.domain.Power.Lift.byId(it) }
                repo.powerPlans(phase, lifts, exclude = sessionId)
            } else emptyList()
            val powerEx = powerItems.mapIndexed { i, p -> ru.streetlift.tracker.data.db.powerExercise(p.lift, session.dayId ?: 0L, -10 + i) }
            val plans = (powerEx + all).mapNotNull { e ->
                val item = powerItems.firstOrNull { it.lift.id == e.id }
                (item?.plan ?: if (power) {
                    if (phase != PeakPhase.PRE_PEAK) null
                    else Peaking.resolve(e.toConfig(), e.toState(), e.type, e.plannedDeload(st.deloadEvery, session.isDeload), PeakPhase.NONE, false, bodyWeight)
                        ?.let { if (e.isJump) it else ru.streetlift.tracker.domain.RunPlan.support(it) }
                } else Peaking.resolve(
                    e.toConfig(), e.toState(), e.type, phase == PeakPhase.NONE && e.plannedDeload(st.deloadEvery, session.isDeload), phase, e.id in peakIds, bodyWeight,
                    comp = st.compTarget(e.id, e.name),
                ))
                    // Подготовка к забегу: силовые в поддержке
                    ?.let { if (st.endurance && !e.isJump) ru.streetlift.tracker.domain.RunPlan.support(it) else it }
                    // Самочувствие: средне — меньше объём (у прыжков объём не режем), плохо — как разгрузка
                    ?.let { if (e.isJump && session.readiness == ru.streetlift.tracker.data.repo.READINESS_MID) it else ru.streetlift.tracker.data.repo.applyReadiness(it, session.readiness) }
                    // Первая тренировка подготовки после паузы — на подход меньше
                    ?.let { if (session.afterPause) it.copy(sets = (it.sets - 1).coerceAtLeast(1)) else it }
                    ?.let { if (e.isJump) ru.streetlift.tracker.domain.jumpPlan(e.jumpState, it) else it }
                    ?.let { e.id to it }
            }.toMap()
            val exercises = (powerEx + all).filter { it.id in plans }
            // Односторонние: на всю тренировку начинаем со слабой стороны прошлой тренировки (поровну — с левой)
            val firstSides = exercises.filter { it.unilateral }.associate { it.id to repo.weakSide(it.name, sessionId) }
            val own = owner(sessionId)
            TimerService.ensureRunning(app)
            combine(repo.observeSets(sessionId), extra, prefill, RestTimer.state) { logs, ex, pf, timer ->
                val resting = timer.owner == own && !timer.finished && !timer.idle
                WorkoutPlanner.compute(session, exercises, plans, logs, ex, pf, resting, firstSides)
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
            Draft(cur.plan.weightKg, cur.plan.targetReps, cur.plan.rirMax, r.defaults.stage, cur.plan.heightCm)
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
                    setIndex = if (cur.unilateral) cur.doneCount + 1 else cur.logs.size + 1,
                    weightKg = ProgressionEngine.roundWeight(d.weight),
                    reps = d.reps.coerceAtLeast(0),
                    rir = d.rir.coerceIn(0, 5),
                    isDeload = cur.plan.isDeload,
                    loggedAt = System.currentTimeMillis(),
                    leverageStage = d.stage,
                    isCalibration = cur.plan.isCalibration,
                    isSeconds = cur.exercise.isSeconds,
                    side = r.side,
                    heightCm = if (cur.exercise.isJump) d.height ?: cur.plan.heightCm ?: 60 else null,
                ),
            )
            prefill.update { it - r.stepKey }
            draft = null
            val firstOfPair = cur.unilateral && cur.logs.size % 2 == 0
            when {
                // Первая сторона сделана — 30 с на смену стороны
                firstOfPair -> TimerActions.start(app, SIDE_SWITCH_MS, owner = owner(r.session.id))
                r.doneSets + 1 < r.plannedSets ->
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
        prefill.update { it + (key to Draft(last.weightKg, last.reps, last.rir, last.leverageStage, last.heightCm)) }
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

    fun updateLog(ctx: Context, log: SetLogEntity) {
        val app = ctx.applicationContext
        scope.launch { repo(app).updateSet(log) }
    }

    fun deleteLog(ctx: Context, log: SetLogEntity) {
        val app = ctx.applicationContext
        scope.launch { repo(app).deleteSet(log) }
    }
}
