package ru.streetlift.tracker.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Подводка к старту: план тренировки в зависимости от дней до старта
 * и от того, пиковое упражнение или фоновое.
 */
object Peaking {

    const val FULL_SPAN = 22
    const val MIN_SPAN = 7

    /**
     * Фаза за [daysToStart] дней до старта. Фазы заполняются с конца: отдых 3 дня, тонус 5,
     * снижение 7, подготовка — остаток. Если пик выставлен за 15–18 дней ([span]),
     * на подготовку осталось бы 1–3 дня (1 тренировка) — эти дни отходят снижению.
     */
    fun phase(daysToStart: Long, span: Int? = null): PeakPhase = when {
        daysToStart < 0 -> PeakPhase.NONE
        daysToStart <= 2 -> PeakPhase.REST
        daysToStart <= 7 -> PeakPhase.TONUS
        daysToStart <= 14 -> PeakPhase.TAPER1
        daysToStart <= FULL_SPAN -> if (span != null && span <= 18) PeakPhase.TAPER1 else PeakPhase.PRE_PEAK
        else -> PeakPhase.NONE
    }

    /** Фаза на день [dayEpoch] при старте [peakEpochDay] (null — подводка выключена). */
    fun phaseOn(peakEpochDay: Long?, dayEpoch: Long, span: Int? = null): PeakPhase =
        if (peakEpochDay == null) PeakPhase.NONE else phase(peakEpochDay - dayEpoch, span)

    /**
     * Облегчённый вес «~pct от рабочего» для знакового веса.
     * Отягощение (> 0): процент от веса с округлением до шага (+20 × 0.85 → +17.5).
     * Свой вес / резина (≤ 0): процент посчитать нельзя — облегчаем шагами
     * (85% → −2 шага, 87–90% → −1 шаг): 0 → −5, −15 → −20.
     */
    fun reducedWeight(weight: Float, step: Float, pct: Float, bodyWeight: Float? = null, assists: List<Float>? = null): Float {
        if (assists != null) {
            // Резины: облегчение — ближайшая допустимая ступень легче рабочей
            val scale = LoadScale(step, assists)
            if (bodyWeight != null && bodyWeight > 0f) {
                val target = pct * (bodyWeight + weight) - bodyWeight
                return scale.lighterNear(weight, target) ?: weight
            }
            if (weight > 0f) {
                val st = if (step > 0f) step else 2.5f
                var steps = (weight * pct / st).roundToInt()
                if (steps * st >= weight) steps -= 1
                return ProgressionEngine.roundWeight(steps * st).let { if (it < 0f) scale.down(0f) else it }
            }
            val k = if (pct <= 0.86f) 2 else 1
            var w = weight
            repeat(k) { w = scale.down(w) }
            return w
        }
        val st = if (step > 0f) step else 2.5f
        if (bodyWeight != null && bodyWeight > 0f) {
            // Процент от полной нагрузки «свой вес + знаковый вес»: 75 кг, −15 → 0.85 × 60 = 51 → −24
            val target = pct * (bodyWeight + weight) - bodyWeight
            var steps = kotlin.math.round(target / st).toInt()
            if (steps * st >= weight) steps = kotlin.math.floor((weight - 0.001f) / st).toInt()
            return ProgressionEngine.roundWeight(steps * st)
        }
        return if (weight > 0f) {
            var steps = (weight * pct / st).roundToInt()
            // Всегда хотя бы на шаг легче рабочего (10 × 0.875 = 8.75 → округлилось бы обратно в 10)
            if (steps * st >= weight) steps -= 1
            ProgressionEngine.roundWeight(steps * st)
        } else {
            val k = if (pct <= 0.86f) 2 else 1
            ProgressionEngine.roundWeight(weight - k * st)
        }
    }

    /**
     * План упражнения с учётом подводки. null — упражнение в этот день не выполняется.
     */
    fun resolve(
        config: ExerciseConfig,
        state: PlanState,
        type: ExerciseType,
        globalDeload: Boolean,
        phase: PeakPhase,
        isPeakTarget: Boolean,
        bodyWeight: Float? = null,
    ): SessionPlan? {
        val plan = resolveReps(config, state, type, globalDeload, phase, isPeakTarget, bodyWeight) ?: return null
        // Статика: диапазоны повторов пика (3–5, 2–4…) не подходят — секунды берём из коридора упражнения,
        // в тонусе — минимум коридора
        if (!config.isSeconds || phase == PeakPhase.NONE || plan.isCalibration) return plan
        val max = if (phase == PeakPhase.TONUS || phase == PeakPhase.REST) config.minReps else config.maxReps
        return plan.copy(repsMin = config.minReps, repsMax = max, targetReps = config.minReps)
    }

    private fun resolveReps(
        config: ExerciseConfig,
        state: PlanState,
        type: ExerciseType,
        globalDeload: Boolean,
        phase: PeakPhase,
        isPeakTarget: Boolean,
        bodyWeight: Float? = null,
    ): SessionPlan? {
        val base = ProgressionEngine.sessionPlan(config, state, globalDeload)
        val planned = state.plannedSets.coerceAtLeast(1)
        val w = state.weightKg
        return when (phase) {
            PeakPhase.NONE -> base
            // 0–2 дня: полный отдых; если всё же тренироваться — лёгкий тонус только пиковых
            PeakPhase.REST -> resolveReps(config, state, type, globalDeload, PeakPhase.TONUS, isPeakTarget, bodyWeight)?.copy(peakPhase = phase)
            PeakPhase.PRE_PEAK -> if (isPeakTarget) {
                SessionPlan(
                    weightKg = config.scale.up(w),
                    sets = config.minSets, repsMin = 3, repsMax = 5, targetReps = 3,
                    rirMin = 1, rirMax = 1, isDeload = false, isEmergencyDeload = false,
                    peakPhase = phase, isPeakTarget = true,
                )
            } else if (base.isCalibration) {
                // Калибровка ждёт старта — пока обычный план без закисления
                SessionPlan(
                    weightKg = w, sets = config.minSets, repsMin = config.minReps, repsMax = config.maxReps,
                    targetReps = config.minReps, rirMin = 2, rirMax = 3, isDeload = false, isEmergencyDeload = false,
                    peakPhase = phase,
                )
            } else if (base.isDeload) {
                base.copy(peakPhase = phase)
            } else {
                // Фон: обычный план, но без закисления
                base.copy(rirMin = max(base.rirMin, 2), rirMax = max(base.rirMax, 3), peakPhase = phase)
            }
            PeakPhase.TAPER1 -> if (isPeakTarget) {
                SessionPlan(
                    weightKg = w, sets = ProgressionEngine.deloadSets(planned),
                    repsMin = 2, repsMax = 4, targetReps = 2, rirMin = 2, rirMax = 3,
                    isDeload = false, isEmergencyDeload = false, peakPhase = phase, isPeakTarget = true,
                )
            } else if (type == ExerciseType.HYPERTROPHY_PUMP) {
                null // многоповторка в тейпере пропускается
            } else {
                SessionPlan(
                    weightKg = reducedWeight(w, config.weightStep, 0.875f, bodyWeight, config.assists),
                    sets = ProgressionEngine.deloadSets(planned),
                    repsMin = config.minReps, repsMax = config.maxReps, targetReps = config.minReps,
                    rirMin = 3, rirMax = 4, isDeload = false, isEmergencyDeload = false, peakPhase = phase,
                )
            }
            PeakPhase.TONUS -> if (isPeakTarget) {
                SessionPlan(
                    weightKg = reducedWeight(w, config.weightStep, 0.85f, bodyWeight, config.assists),
                    sets = 2, repsMin = 2, repsMax = 3, targetReps = 2, rirMin = 4, rirMax = 4,
                    isDeload = false, isEmergencyDeload = false, peakPhase = phase, isPeakTarget = true,
                )
            } else {
                null // фоновые полностью исключаются
            }
        }
    }
}
