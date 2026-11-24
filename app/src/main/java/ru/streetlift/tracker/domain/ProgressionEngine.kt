package ru.streetlift.tracker.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Неизменяемые настройки упражнения (из редактора цикла). */
data class ExerciseConfig(
    val minSets: Int,
    val maxSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val targetRirMin: Int,
    val targetRirMax: Int,
    val weightStep: Float,
    /** Статика: minReps..maxReps — секунды удержания, цель растёт на +2 с за цикл. */
    val isSeconds: Boolean = false,
    /** Лестница помощи резин: null — знаковый вес шагами (старое), пусто — ниже 0 нельзя. */
    val assists: List<Float>? = null,
) {
    val scale: LoadScale get() = LoadScale(weightStep, assists)

    val unit: String get() = if (isSeconds) "с" else "повт."
    val targetStep: Int get() = if (isSeconds) 2 else 1
}

/** Состояние плана, которое движок пересчитывает после каждой тренировки. */
data class PlanState(
    val weightKg: Float,
    val plannedSets: Int,
    val targetReps: Int,
    /** Сколько кругов уже выполнено на текущем весе (0 — первый круг на новом весе). */
    val roundsAtWeight: Int = 0,
    /** Сколько кругов подряд падают показатели. */
    val declineStreak: Int = 0,
    /** Аварийная разгрузка только для этого упражнения на следующий круг. */
    val emergencyDeloadNext: Boolean = false,
    /** Оценка прошлой тренировки: среднее (повторы + RIR) на подход. */
    val lastScore: Float? = null,
    /** Вес, на котором получена [lastScore] — сравниваем только на одинаковом весе. */
    val lastScoreWeight: Float? = null,
    /** ACTIVE — двойная прогрессия; CALIBRATION — круг подбора стартовых веса/повторов. */
    val mode: ExerciseMode = ExerciseMode.ACTIVE,
    /** Сколько кругов подряд закрыт потолок maxSets × maxReps на неконечной стадии. */
    val ceilingStreak: Int = 0,
    /** Потолок стадии достигнут — пора на следующую стадию. */
    val stageCeiling: Boolean = false,
    /** Лучшая сумма повторов на текущем весе — для поиска плато. */
    val bestTotal: Int? = null,
    /** Тренировок подряд без роста суммы повторов на текущем весе. */
    val stallRounds: Int = 0,
    /** Вес, на котором было последнее плато (сброс и разгон). */
    val resetWeight: Float? = null,
    /** Сколько раз подряд упирались в плато на [resetWeight]. */
    val resetCount: Int = 0,
    /** Коридор повторов сдвинут вниз (второе плато на одном весе): 6–10 → 5–8. */
    val repShift: Int = 0,
)

data class SetResult(val weightKg: Float, val reps: Int, val rir: Int)

enum class Decision { NOT_PERFORMED, INCREASE_WEIGHT, ADD_VOLUME, HOLD, REDUCE_VOLUME, DELOAD_DONE, CALIBRATED, STAGE_CEILING, PLATEAU_RESET }

data class ProgressionResult(
    val state: PlanState,
    val decision: Decision,
    val emergencyDeloadScheduled: Boolean,
    val message: String,
)

/** Что делать в конкретной тренировке (с учётом разгрузки). */
data class SessionPlan(
    val weightKg: Float,
    val sets: Int,
    val repsMin: Int,
    val repsMax: Int,
    val targetReps: Int,
    val rirMin: Int,
    val rirMax: Int,
    val isDeload: Boolean,
    val isEmergencyDeload: Boolean,
    val isCalibration: Boolean = false,
    val peakPhase: PeakPhase = PeakPhase.NONE,
    /** Упражнение выбрано для выхода на пик. */
    val isPeakTarget: Boolean = false,
    /** Прыжки на тумбу: высота, см. */
    val heightCm: Int? = null,
)

data class CycleState(
    /** Позиция следующего дня в цикле (0-based). */
    val dayIndex: Int,
    /** Номер круга внутри мезоцикла: 1..N, на N-м — плановая разгрузка. */
    val cycleInMeso: Int,
    val completedRounds: Int,
    val override: DeloadOverride,
)

data class CycleAdvance(val state: CycleState, val roundCompleted: Boolean, val wasDeloadRound: Boolean)

object ProgressionEngine {
    const val DELOAD_RIR_MIN = 3
    const val DELOAD_RIR_MAX = 4

    /** Калибровка: 3 рабочих подхода в коридоре повторений с запасом 2. */
    const val CALIBRATION_SETS = 3
    /** Тренировок подряд без роста суммы повторов на одном весе — плато. */
    const val PLATEAU_ROUNDS = 3
    const val CALIBRATION_RIR = 2

    /** Доля подходов, которая остаётся в разгрузке (срез 40–50% с округлением вверх). */
    private const val DELOAD_KEEP = 0.5

    fun roundWeight(w: Float): Float = (w * 100f).roundToInt() / 100f

    /** 5 → 3, 4 → 2, 3 → 2, 2 → 2 (минимум 2 подхода). */
    fun deloadSets(planned: Int): Int = max(2, ceil(planned * DELOAD_KEEP).toInt())

    fun isGlobalDeload(cycleInMeso: Int, deloadEvery: Int, override: DeloadOverride): Boolean =
        when (override) {
            DeloadOverride.FORCE_ON -> true
            DeloadOverride.FORCE_OFF -> false
            DeloadOverride.AUTO -> deloadEvery > 0 && cycleInMeso >= deloadEvery
        }

    /** Какое значение override поставить, чтобы получить желаемое состояние разгрузки. */
    fun overrideFor(wantDeload: Boolean, cycleInMeso: Int, deloadEvery: Int): DeloadOverride {
        val auto = isGlobalDeload(cycleInMeso, deloadEvery, DeloadOverride.AUTO)
        return when {
            wantDeload == auto -> DeloadOverride.AUTO
            wantDeload -> DeloadOverride.FORCE_ON
            else -> DeloadOverride.FORCE_OFF
        }
    }

    fun sessionPlan(config: ExerciseConfig, state: PlanState, globalDeload: Boolean): SessionPlan {
        val baseSets = state.plannedSets.coerceAtLeast(1)
        val target = state.targetReps.coerceIn(config.minReps, max(config.minReps, config.maxReps))
        if (state.mode == ExerciseMode.CALIBRATION) {
            // Калибровка важнее разгрузки: нужна новая база
            return SessionPlan(
                weightKg = state.weightKg,
                sets = CALIBRATION_SETS,
                repsMin = config.minReps,
                repsMax = config.maxReps,
                targetReps = config.minReps,
                rirMin = CALIBRATION_RIR,
                rirMax = CALIBRATION_RIR,
                isDeload = false,
                isEmergencyDeload = false,
                isCalibration = true,
            )
        }
        val deload = globalDeload || state.emergencyDeloadNext
        return if (deload) {
            SessionPlan(
                weightKg = state.weightKg,
                sets = deloadSets(baseSets),
                repsMin = config.minReps,
                repsMax = config.maxReps,
                targetReps = target,
                rirMin = DELOAD_RIR_MIN,
                rirMax = DELOAD_RIR_MAX,
                isDeload = true,
                isEmergencyDeload = !globalDeload,
            )
        } else {
            SessionPlan(
                weightKg = state.weightKg,
                sets = baseSets,
                repsMin = config.minReps,
                repsMax = config.maxReps,
                targetReps = target,
                rirMin = config.targetRirMin,
                rirMax = config.targetRirMax,
                isDeload = false,
                isEmergencyDeload = false,
            )
        }
    }

    /** Рабочий вес тренировки — самый частый вес в подходах (при равенстве — последний). */
    fun workingWeight(sets: List<SetResult>): Float {
        val counts = LinkedHashMap<Float, Int>()
        sets.forEach { val w = roundWeight(it.weightKg); counts[w] = (counts[w] ?: 0) + 1 }
        val best = counts.values.max()
        return sets.map { roundWeight(it.weightKg) }.last { counts[it] == best }
    }

    /** Оценка производительности: среднее (повторы + RIR) на подход. */
    fun score(sets: List<SetResult>): Float = sets.map { (it.reps + it.rir).toFloat() }.average().toFloat()

    /**
     * Пересчёт плана упражнения на следующий круг.
     * @param wasDeload тренировка выполнялась в режиме разгрузки (плановой или аварийной)
     */
    fun computeNext(
        config: ExerciseConfig,
        state: PlanState,
        sets: List<SetResult>,
        wasDeload: Boolean,
        /** Есть стадии, текущая не финальная и вес ≥ 0 — вес не растёт, подсказываем сменить стадию. */
        stageCeilingEligible: Boolean = false,
    ): ProgressionResult {
        if (sets.isEmpty()) {
            return ProgressionResult(state, Decision.NOT_PERFORMED, false, "не выполнялось — план без изменений")
        }
        if (state.mode == ExerciseMode.CALIBRATION) return finishCalibration(config, sets)

        val work = workingWeight(sets)

        if (wasDeload) {
            val next = state.copy(
                weightKg = work,
                emergencyDeloadNext = false,
                declineStreak = 0,
                lastScore = null,
                lastScoreWeight = null,
            )
            return ProgressionResult(
                next, Decision.DELOAD_DONE, false,
                "разгрузка выполнена — дальше ${Fmt.weight(work)}, ${next.plannedSets} × ${Fmt.range(config.minReps, config.maxReps)}",
            )
        }

        val planned = state.plannedSets.coerceAtLeast(1)
        val minAchieved = sets.minOf { it.reps }
        val zeroRir = sets.count { it.rir == 0 }
        val avgRir = sets.map { it.rir }.average()
        val belowMin = sets.any { it.reps < config.minReps }
        val fatigue = belowMin || (zeroRir >= 2 && config.targetRirMin > 0)
        val allMax = sets.size >= planned &&
            sets.all { it.reps >= config.maxReps && it.rir >= config.targetRirMin }

        // Детектор падения показателей (2 круга подряд → аварийная разгрузка)
        val score = score(sets)
        val prevScore = state.lastScore
        val prevWeight = state.lastScoreWeight
        val comparable = prevScore != null && prevWeight != null && abs(prevWeight - work) < 0.001f
        // Подходов сделано меньше плана (убраны вручную / не доделаны) — тоже сигнал утомления
        val reduced = sets.size < planned
        val declined = (comparable && score < prevScore!! - 0.01f) || reduced
        var streak = if (declined) state.declineStreak + 1 else 0
        var emergency = false
        if (streak >= 2) {
            emergency = true
            streak = 0
        }

        // Поиск плато: сумма повторов на одном весе не растёт PLATEAU_ROUNDS тренировок подряд
        val total = sets.sumOf { it.reps }
        val sameWeight = state.lastScoreWeight != null && abs(state.lastScoreWeight - work) < 0.001f
        val best = if (sameWeight) state.bestTotal else null
        val improved = best == null || total > best
        val stall = if (improved) 0 else state.stallRounds + 1
        val newBest = if (best == null) total else max(best, total)
        val plateau = !allMax && !fatigue && !reduced && !emergency && stall >= PLATEAU_ROUNDS

        val next: PlanState
        val decision: Decision
        val msg: String

        val atCeiling = allMax && !fatigue && stageCeilingEligible
        when {
            atCeiling && sets.size < config.maxSets -> {
                // Сначала выходим на полный объём maxSets × maxReps, вес не трогаем
                next = state.copy(
                    weightKg = work, plannedSets = config.maxSets, targetReps = config.maxReps,
                    roundsAtWeight = state.roundsAtWeight + 1, declineStreak = 0, emergencyDeloadNext = false,
                    lastScore = score, lastScoreWeight = work, ceilingStreak = 0, stageCeiling = false,
                    bestTotal = newBest, stallRounds = 0,
                )
                decision = Decision.ADD_VOLUME
                emergency = false
                msg = "все подходы на ${config.maxReps}+ — объём до ${config.maxSets} подходов, вес ${Fmt.weight(work)} (на стадии вес не растёт)"
            }

            atCeiling -> {
                val streakNow = state.ceilingStreak + 1
                val ceiling = streakNow >= 2
                next = state.copy(
                    weightKg = work, plannedSets = config.maxSets, targetReps = config.maxReps,
                    roundsAtWeight = state.roundsAtWeight + 1, declineStreak = 0, emergencyDeloadNext = false,
                    lastScore = score, lastScoreWeight = work, ceilingStreak = streakNow, stageCeiling = ceiling,
                    bestTotal = newBest, stallRounds = 0,
                )
                decision = if (ceiling) Decision.STAGE_CEILING else Decision.HOLD
                emergency = false
                msg = if (ceiling) {
                    "потолок стадии достигнут — можно переходить на следующую стадию в редакторе цикла"
                } else {
                    "${config.maxSets} × ${config.maxReps} закрыто (1 из 2 циклов до потолка стадии), вес ${Fmt.weight(work)}"
                }
            }

            allMax && !fatigue -> {
                // Ступень тяжелее: резина слабее → свой вес → +шаг
                val newWeight = config.scale.up(work)
                // Прошли вес прошлого плато — сдвиг коридора и счётчик плато больше не нужны
                val passed = state.resetWeight == null || newWeight > state.resetWeight + 0.001f
                next = PlanState(
                    weightKg = newWeight,
                    plannedSets = config.minSets,
                    targetReps = config.minReps,
                    resetWeight = if (passed) null else state.resetWeight,
                    resetCount = if (passed) 0 else state.resetCount,
                    repShift = if (passed) 0 else state.repShift,
                )
                decision = Decision.INCREASE_WEIGHT
                emergency = false
                msg = "все подходы на ${config.maxReps}+ с запасом — вес ${Fmt.weight(work)} → ${Fmt.weight(newWeight)}, " +
                    "${config.minSets} × ${config.minReps}+"
            }

            fatigue -> {
                val floor = max(1, config.minSets - 1)
                val newSets = max(floor, planned - 1)
                next = state.copy(
                    weightKg = work,
                    plannedSets = newSets,
                    targetReps = state.targetReps.coerceIn(config.minReps, max(config.minReps, config.maxReps)),
                    roundsAtWeight = state.roundsAtWeight + 1,
                    declineStreak = streak,
                    emergencyDeloadNext = emergency,
                    lastScore = score,
                    lastScoreWeight = work,
                    ceilingStreak = 0,
                    stageCeiling = false,
                    // Утомление не считается тренировкой «без роста»
                    bestTotal = newBest,
                    stallRounds = if (sameWeight) state.stallRounds else 0,
                )
                decision = Decision.REDUCE_VOLUME
                val why = if (belowMin) "повторы ниже ${config.minReps}" else "RIR 0 в $zeroRir подходах"
                msg = "утомление ($why) — вес ${Fmt.weight(work)} сохранён, подходов: $newSets"
            }

            plateau -> {
                // Плато: сброс на 2 ступени вниз с полным объёмом и разгон заново по 1,25
                val again = state.resetWeight != null && abs(state.resetWeight - work) < 0.001f
                val count = if (again) state.resetCount + 1 else 1
                val down = config.scale.down(config.scale.down(work))
                val shift = if (count >= 2) 1 else state.repShift
                next = PlanState(
                    weightKg = down,
                    plannedSets = config.maxSets,
                    targetReps = config.minReps,
                    resetWeight = work,
                    resetCount = count,
                    repShift = shift,
                )
                decision = Decision.PLATEAU_RESET
                msg = "плато: $PLATEAU_ROUNDS тренировки на ${Fmt.weight(work)} без роста повторов — " +
                    "сброс до ${Fmt.weight(down)}, ${config.maxSets} подходов, и разгон заново" +
                    if (shift > state.repShift) "; второе плато на этом весе — коридор повторов ниже (больше силы)" else ""
            }

            else -> {
                val rounds = state.roundsAtWeight + 1
                val newSets = when {
                    reduced -> max(max(1, config.minSets - 1), sets.size)
                    planned < config.minSets -> config.minSets
                    avgRir >= config.targetRirMin -> config.maxSets
                    else -> planned
                }.coerceIn(1, max(1, config.maxSets))
                val newTarget = (minAchieved + config.targetStep).coerceIn(config.minReps, max(config.minReps, config.maxReps))
                next = state.copy(
                    weightKg = work,
                    plannedSets = newSets,
                    targetReps = newTarget,
                    roundsAtWeight = rounds,
                    declineStreak = streak,
                    emergencyDeloadNext = emergency,
                    lastScore = score,
                    lastScoreWeight = work,
                    ceilingStreak = 0,
                    stageCeiling = false,
                    bestTotal = newBest,
                    stallRounds = stall,
                )
                decision = if (newSets > planned) Decision.ADD_VOLUME else Decision.HOLD
                msg = if (reduced) {
                    "подходов меньше плана (${sets.size} из $planned) — объём не растёт, держим ${Fmt.weight(work)}"
                } else if (decision == Decision.ADD_VOLUME) {
                    "объём ↑ до $newSets подходов, вес ${Fmt.weight(work)}, цель ${newTarget}+ ${config.unit}"
                } else {
                    "держим ${Fmt.weight(work)}, $newSets подходов, цель ${newTarget}+ ${config.unit}"
                }
            }
        }

        val fullMsg = if (emergency) "$msg. Показатели падают 2 цикла подряд → следующий цикл — разгрузка для упражнения" else msg
        return ProgressionResult(next, decision, emergency, fullMsg)
    }

    /** Итог калибровки: последний рабочий подход становится стартом, статус → ACTIVE. */
    private fun finishCalibration(config: ExerciseConfig, sets: List<SetResult>): ProgressionResult {
        val last = sets.last()
        val weight = roundWeight(last.weightKg)
        val target = last.reps.coerceIn(config.minReps, max(config.minReps, config.maxReps))
        val next = PlanState(
            weightKg = weight,
            plannedSets = config.minSets,
            targetReps = target,
            mode = ExerciseMode.ACTIVE,
        )
        val note = when {
            last.reps < config.minReps -> " (повторов меньше коридора — возможно, стоит облегчить)"
            last.reps > config.maxReps -> " (повторов больше коридора — можно утяжелить)"
            else -> ""
        }
        return ProgressionResult(
            next, Decision.CALIBRATED, false,
            "калибровка завершена — старт ${Fmt.weight(weight)}, ${config.minSets} × ${target}+$note",
        )
    }

    /**
     * Смена стадии рычага: прежняя статистика не сравнивается с новой,
     * упражнение уходит на 1 круг в калибровку.
     */
    fun onStageChanged(state: PlanState): PlanState = state.copy(
        mode = ExerciseMode.CALIBRATION,
        ceilingStreak = 0,
        stageCeiling = false,
        roundsAtWeight = 0,
        declineStreak = 0,
        emergencyDeloadNext = false,
        lastScore = null,
        lastScoreWeight = null,
    )

    /** Переход к следующему дню; при завершении круга — обновление счётчика мезоцикла. */
    fun advanceCycle(state: CycleState, completedDayIndex: Int, dayCount: Int, deloadEvery: Int): CycleAdvance {
        val count = max(1, dayCount)
        val idx = completedDayIndex.coerceIn(0, count - 1)
        val next = (idx + 1) % count
        if (idx != count - 1) {
            return CycleAdvance(state.copy(dayIndex = next), roundCompleted = false, wasDeloadRound = false)
        }
        val wasDeload = isGlobalDeload(state.cycleInMeso, deloadEvery, state.override)
        // Разгрузочный круг (или отменённый плановый) начинает новый мезоцикл
        val resetMeso = wasDeload || (deloadEvery > 0 && state.cycleInMeso >= deloadEvery)
        return CycleAdvance(
            CycleState(
                dayIndex = next,
                cycleInMeso = if (resetMeso) 1 else state.cycleInMeso + 1,
                completedRounds = state.completedRounds + 1,
                override = DeloadOverride.AUTO,
            ),
            roundCompleted = true,
            wasDeloadRound = wasDeload,
        )
    }
}
