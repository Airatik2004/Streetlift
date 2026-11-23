package ru.streetlift.tracker.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Подводка к старту: план тренировки в зависимости от дней до старта
 * и от того, пиковое упражнение или фоновое.
 */
object Peaking {

    fun phase(daysToStart: Long): PeakPhase = when {
        daysToStart < 0 -> PeakPhase.NONE
        daysToStart <= 2 -> PeakPhase.REST
        daysToStart <= 7 -> PeakPhase.TONUS
        daysToStart <= 14 -> PeakPhase.TAPER1
        daysToStart <= 22 -> PeakPhase.PRE_PEAK
        else -> PeakPhase.NONE
    }

    /** Фаза на день [dayEpoch] при старте [peakEpochDay] (null — подводка выключена). */
    fun phaseOn(peakEpochDay: Long?, dayEpoch: Long): PeakPhase =
        if (peakEpochDay == null) PeakPhase.NONE else phase(peakEpochDay - dayEpoch)

    /**
     * Облегчённый вес «~pct от рабочего» для знакового веса.
     * Отягощение (> 0): процент от веса с округлением до шага (+20 × 0.85 → +17.5).
     * Свой вес / резина (≤ 0): процент посчитать нельзя — облегчаем шагами
     * (85% → −2 шага, 87–90% → −1 шаг): 0 → −5, −15 → −20.
     */
    fun reducedWeight(weight: Float, step: Float, pct: Float, bodyWeight: Float? = null): Float {
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
        val base = ProgressionEngine.sessionPlan(config, state, globalDeload)
        val planned = state.plannedSets.coerceAtLeast(1)
        val w = state.weightKg
        return when (phase) {
            PeakPhase.NONE -> base
            // 0–2 дня: полный отдых; если всё же тренироваться — лёгкий тонус только пиковых
            PeakPhase.REST -> resolve(config, state, type, globalDeload, PeakPhase.TONUS, isPeakTarget, bodyWeight)?.copy(peakPhase = phase)
            PeakPhase.PRE_PEAK -> if (isPeakTarget) {
                SessionPlan(
                    weightKg = ProgressionEngine.roundWeight(w + config.weightStep),
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
                    weightKg = reducedWeight(w, config.weightStep, 0.875f, bodyWeight),
                    sets = ProgressionEngine.deloadSets(planned),
                    repsMin = config.minReps, repsMax = config.maxReps, targetReps = config.minReps,
                    rirMin = 3, rirMax = 4, isDeload = false, isEmergencyDeload = false, peakPhase = phase,
                )
            }
            PeakPhase.TONUS -> if (isPeakTarget) {
                SessionPlan(
                    weightKg = reducedWeight(w, config.weightStep, 0.85f, bodyWeight),
                    sets = 2, repsMin = 2, repsMax = 3, targetReps = 2, rirMin = 4, rirMax = 4,
                    isDeload = false, isEmergencyDeload = false, peakPhase = phase, isPeakTarget = true,
                )
            } else {
                null // фоновые полностью исключаются
            }
        }
    }
}
