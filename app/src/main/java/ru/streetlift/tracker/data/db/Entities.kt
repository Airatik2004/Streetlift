package ru.streetlift.tracker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import ru.streetlift.tracker.domain.DeloadOverride
import ru.streetlift.tracker.domain.ExerciseConfig
import ru.streetlift.tracker.domain.ExerciseMode
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.Metric
import ru.streetlift.tracker.domain.PriorityMode
import ru.streetlift.tracker.domain.PlanState

/** Глобальное состояние программы (одна строка, id = 1). */
const val JUMP_NAME = "Прыжки на тумбу"

const val DEFAULT_BANDS = "Зелёная:75,Фиолетовая:45,Чёрная:30,Красная:20"

@Serializable
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val currentDayIndex: Int = 0,
    val cycleInMeso: Int = 1,
    val completedRounds: Int = 0,
    /** Плановая разгрузка — каждый 5-й цикл упражнения; в приложении не меняется. */
    val deloadEvery: Int = SettingsEntity.DELOAD_EVERY,
    val deloadOverride: DeloadOverride = DeloadOverride.AUTO,
    /** Дата следующей тренировки (LocalDate.toEpochDay), null — сегодня. */
    val nextWorkoutEpochDay: Long? = null,
    val autoStartTimer: Boolean = true,
    /** Пройден ли экран «Настройка стартовых параметров». */
    val setupDone: Boolean = false,
    // Снимок цикла до первого «Пропустить» — для отмены пропуска(ов)
    val undoDayIndex: Int? = null,
    val undoCycleInMeso: Int? = null,
    val undoCompletedRounds: Int? = null,
    val undoOverride: String? = null,
    val undoNextEpochDay: Long? = null,
    /** Подводка к старту: дата старта (epochDay) и id пиковых упражнений через запятую. */
    val peakEpochDay: Long? = null,
    val peakExerciseIds: String = "",
    /** Свой вес — для «~85%» от полной нагрузки в подводке. */
    val bodyWeightKg: Float? = null,
    /** Приоритет дней 2–4 и счётчик завершённых мезоциклов (для «Авто»). */
    val priorityMode: PriorityMode = PriorityMode.AUTO,
    val mesoIndex: Int = 0,
    /** За сколько дней до пика он выставлен (≤ 22): при 15–18 подготовка уходит в снижение. */
    val peakSpanDays: Int? = null,
    /** Резины: «Название:кг» через запятую. */
    val bands: String = DEFAULT_BANDS,
    /** Напоминания: накануне вечером и в день тренировки (минуты от полуночи). */
    val remindEve: Boolean = true,
    val remindEveMin: Int = 21 * 60,
    val remindDay: Boolean = true,
    val remindDayMin: Int = 17 * 60,
    /** Вечером в день тренировки, если она не сделана. */
    val remindLate: Boolean = true,
    val remindLateMin: Int = 21 * 60,
    /** День выбран вручную (выбор/пропуск) — автоматический выбор дня на пике не вмешивается. */
    val manualDay: Boolean = false,
    /** Год рождения — для дивизионов соревнований (v24). */
    val birthYear: Int? = null,
    /** Выход на пик: RECORDS — личные рекорды, COMPETITION — соревновательная дисциплина. */
    val peakMode: String = PEAK_RECORDS,
    /** Соревнования: id дисциплины и снимок оценок для плана (Competition.encode). */
    val compDiscipline: String? = null,
    val compPlan: String = "",
    /** Подготовка к забегу (v25): дистанция, цель (с), пробежек в неделю, начало плана, день старта, база км/нед. */
    val runDistM: Int? = null,
    val runGoalSec: Int? = null,
    val runPerWeek: Int? = null,
    val runStart: Long? = null,
    val runRace: Long? = null,
    val runBaseKm: Float? = null,
    /** Дата рождения (epochDay, v26) — возраст для дивизионов считается на день старта. Старое поле birthYear не используется. */
    val birthDate: Long? = null,
) {
    companion object {
        fun ageOn(birth: Long, day: Long): Int =
            java.time.Period.between(java.time.LocalDate.ofEpochDay(birth), java.time.LocalDate.ofEpochDay(day)).years

        /** Плановая разгрузка всегда на 5-м цикле (у задней дельты — 9-я и 10-я тренировки). */
        const val DELOAD_EVERY = 5
        const val PEAK_RECORDS = "RECORDS"
        const val PEAK_COMPETITION = "COMPETITION"
        const val PEAK_ENDURANCE = "ENDURANCE"
    }

    /** Идёт подготовка к забегу: силовые в поддержке. */
    val endurance: Boolean get() = peakMode == PEAK_ENDURANCE && runRace != null

    val runDist: ru.streetlift.tracker.domain.RunPlan.Dist? get() = ru.streetlift.tracker.domain.RunPlan.Dist.byM(runDistM)

    /** План пробежек (пусто — подготовки нет). */
    fun runItems(): List<ru.streetlift.tracker.domain.RunPlan.Item> {
        val d = runDist ?: return emptyList()
        val race = runRace ?: return emptyList()
        if (!endurance) return emptyList()
        return ru.streetlift.tracker.domain.RunPlan.build(runStart ?: race, race, d, runPerWeek ?: ru.streetlift.tracker.domain.RunPlan.DEFAULT_RUNS, (runBaseKm ?: 0f).toDouble())
    }

    /** Полных лет на день [day] (по дате рождения). */
    fun ageOn(day: Long): Int? = birthDate?.let { ageOn(it, day) }

    val discipline: ru.streetlift.tracker.domain.Discipline?
        get() = if (peakMode == PEAK_COMPETITION) ru.streetlift.tracker.domain.Discipline.byId(compDiscipline) else null

    /** Что нужно плану пикового упражнения на соревнованиях (null — не соревнования или не из дисциплины). */
    fun compTarget(exerciseId: Long, exerciseName: String): ru.streetlift.tracker.domain.Competition.Target? {
        val d = discipline ?: return null
        val i = d.lifts.indexOfFirst { it.exerciseName == exerciseName }
        if (i < 0) return null
        val snap = ru.streetlift.tracker.domain.Competition.decode(compPlan)[exerciseId]
        return ru.streetlift.tracker.domain.Competition.Target(d.kind, d.weights.getOrNull(i) ?: 0f, snap?.e1rm, snap?.reps, bodyWeightKg)
    }

    /** Сколько дней прошло после запланированной даты тренировки (0 — не пропущено). */
    fun missedDays(today: Long): Long = (today - (nextWorkoutEpochDay ?: today)).coerceAtLeast(0)

    val bandList: List<ru.streetlift.tracker.domain.Band> get() = ru.streetlift.tracker.domain.Bands.decode(bands)

    /** Общая «Разгрузка на этот цикл» — разгружает все упражнения сразу. */
    val forcedDeload: Boolean get() = deloadOverride == DeloadOverride.FORCE_ON

    /** Приоритет дня — чередование по мезоциклам (на подводке первыми идут упражнения пика). */
    val priority: ru.streetlift.tracker.domain.Priority get() = ru.streetlift.tracker.domain.Priority.forMeso(mesoIndex)

    val peakIds: Set<Long> get() = peakExerciseIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    val canUndoSkip: Boolean get() = undoDayIndex != null
}

@Serializable
@Entity(tableName = "days")
data class DayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int,
    val name: String,
    val alternating: Boolean = false,
)

@Serializable
@Entity(
    tableName = "exercises",
    foreignKeys = [
        ForeignKey(
            entity = DayEntity::class,
            parentColumns = ["id"],
            childColumns = ["dayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dayId")],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val position: Int,
    val name: String,
    val type: ExerciseType,
    val minSets: Int,
    val maxSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val targetRirMin: Int,
    val targetRirMax: Int,
    val weightStep: Float,
    // --- состояние плана, которое пересчитывает движок ---
    val weightKg: Float = 0f,
    val plannedSets: Int,
    val targetReps: Int,
    val roundsAtWeight: Int = 0,
    val declineStreak: Int = 0,
    val emergencyDeloadNext: Boolean = false,
    val lastScore: Float? = null,
    val lastScoreWeight: Float? = null,
    /** Текущая стадия рычага (горизонт, передний вис); null — не рычаговое упражнение. */
    val leverageStage: String? = null,
    val mode: ExerciseMode = ExerciseMode.ACTIVE,
    /** Группа справочника стадий (StageGroups.LEVER / HANDSTAND); null — у упражнения нет стадий. */
    val stageGroup: String? = null,
    val ceilingStreak: Int = 0,
    /** Потолок стадии достигнут — подсказать переход на следующую стадию. */
    val stageCeiling: Boolean = false,
    /** REPS — повторы; SECONDS — статика, minReps..maxReps это секунды. */
    val metric: Metric = Metric.REPS,
    /** Всегда в конце дня, вне суперсета (задняя дельта). */
    val alwaysLast: Boolean = false,
    /** Одно упражнение в нескольких днях с общим прогрессом (задняя дельта: дни 1 и 3). */
    val shareKey: String? = null,
    /** Пропустить ближайшую плановую разгрузку (true); сбрасывается в конце разгрузочного цикла. */
    val skipPlannedDeload: Boolean? = null,
    /** Не используется (v9), оставлено для совместимости схемы. */
    val cyclesSinceStage: Int = 99,
    /** Рабочих тренировок упражнения в текущем мезоцикле (калибровка не считается). */
    val deloadSessions: Int = 0,
    /** Прыжки на тумбу: рабочая высота (null — ещё не калибровали) и высота плато, которую пробиваем. */
    val heightCm: Int? = null,
    val plateauCm: Int? = null,
    /** Прыжки: блок пробивки (номер, для какой высоты, шаг), откат и промежуточный вес (v20). */
    val jumpBlock: Int = 0,
    val jumpBlockCm: Int? = null,
    val jumpStep: Int = 0,
    val jumpRollback: Boolean = false,
    val jumpNextKg: Float? = null,
    val jumpNextReps: Int? = null,
    val jumpRetryKg: Float? = null,
    /** Плато (v21): лучшая сумма повторов на весе, тренировок без роста, вес плато, сколько раз, сдвиг коридора. */
    val bestTotal: Int? = null,
    val stallRounds: Int = 0,
    val resetWeight: Float? = null,
    val resetCount: Int = 0,
    val repShift: Int = 0,
) {
    /** Прыжки на тумбу — прогрессия по высоте (Jumps). */
    val isJump: Boolean get() = name == JUMP_NAME

    /** Чередование в Дне 1 по факту: идёт то из двух, что делалось давнее (первый раз — пистолетики). */
    val altSlot: Int? get() = when (name) {
        "Пистолетики" -> 0
        JUMP_NAME -> 1
        else -> null
    }

    /** Есть ли упражнение в Дне 1, если «через цикл» выпал слот [slot] (0 пистолетики, 1 прыжки). */
    fun activeInSlot(slot: Int): Boolean = altSlot == null || altSlot == slot

    val jumpState: ru.streetlift.tracker.domain.Jumps.State?
        get() = heightCm?.let {
            ru.streetlift.tracker.domain.Jumps.State(
                it, weightKg, targetReps, plateauCm, declineStreak,
                block = jumpBlock, blockCm = jumpBlockCm, step = jumpStep, rollback = jumpRollback,
                nextKg = jumpNextKg, nextReps = jumpNextReps, retryKg = jumpRetryKg, deloadNext = emergencyDeloadNext,
            )
        }

    fun withJump(s: ru.streetlift.tracker.domain.Jumps.State) = copy(
        heightCm = s.heightCm, weightKg = s.weightKg, targetReps = s.reps, plateauCm = s.plateauCm, declineStreak = s.fails,
        jumpBlock = s.block, jumpBlockCm = s.blockCm, jumpStep = s.step, jumpRollback = s.rollback,
        jumpNextKg = s.nextKg, jumpNextReps = s.nextReps, jumpRetryKg = s.retryKg,
        mode = ExerciseMode.ACTIVE, emergencyDeloadNext = s.deloadNext,
    )

    /** Одностороннее: подход — сначала одна сторона, потом другая (пистолетики, подтягивания на 1 руке). */
    val unilateral: Boolean get() = name == "Пистолетики" || name == "Подтягивания на 1 руке" || shareKey == "rear_delt"

    /** Тренировок в цикле упражнения: задняя дельта — 2 (дни 1 и 3), остальные — 1. */
    val sessionsPerCycle: Int get() = if (shareKey != null) 2 else 1

    /** Номер текущего цикла упражнения в мезоцикле (1..N). */
    val cycleNo: Int get() = deloadSessions / sessionsPerCycle + 1

    /** Наступил разгрузочный (N-й) цикл упражнения. */
    fun deloadDue(every: Int): Boolean = every > 0 && deloadSessions >= (every - 1) * sessionsPerCycle

    /** Разгружать ли на ближайшей тренировке (плановая своя или общая принудительная). */
    fun plannedDeload(every: Int, forced: Boolean): Boolean =
        forced || (mode == ExerciseMode.ACTIVE && deloadDue(every) && skipPlannedDeload != true)

    /** Сколько циклов до разгрузки (0 — сейчас). */
    fun cyclesToDeload(every: Int): Int = (every - cycleNo).coerceAtLeast(0)

    val isSeconds: Boolean get() = metric == Metric.SECONDS

    /** Не участвует в суперсете — идёт в конце дня (статика и «всегда последние»). */
    val outOfSuperset: Boolean get() = isSeconds || alwaysLast

    /** Резина для помощи: нельзя в стойке (облегчение стадиями) и в задней дельте (гантели). */
    val allowBands: Boolean get() = stageGroup != ru.streetlift.tracker.domain.StageGroups.HANDSTAND && shareKey == null && !isJump

    /** Шкала нагрузки: резины текущего набора → свой вес → +шаг. */
    val loadScale: ru.streetlift.tracker.domain.LoadScale
        get() = ru.streetlift.tracker.domain.LoadScale(weightStep, if (allowBands) ru.streetlift.tracker.domain.Bands.ladder() else emptyList())

    /** Коридор повторов с учётом сдвига после второго плато на одном весе: 6–10 → 5–8. */
    val effMinReps: Int get() = if (repShift > 0 && !isSeconds) (minReps - 1).coerceAtLeast(1) else minReps
    val effMaxReps: Int get() = if (repShift > 0 && !isSeconds) (maxReps - 2).coerceAtLeast(effMinReps + 1) else maxReps

    fun toConfig() = ExerciseConfig(
        minSets, maxSets, effMinReps, effMaxReps, targetRirMin, targetRirMax, weightStep, isSeconds,
        assists = loadScale.assists,
    )

    fun toState() = PlanState(
        weightKg = weightKg,
        plannedSets = plannedSets,
        targetReps = targetReps,
        roundsAtWeight = roundsAtWeight,
        declineStreak = declineStreak,
        emergencyDeloadNext = emergencyDeloadNext,
        lastScore = lastScore,
        lastScoreWeight = lastScoreWeight,
        mode = mode,
        ceilingStreak = ceilingStreak,
        stageCeiling = stageCeiling,
        bestTotal = bestTotal,
        stallRounds = stallRounds,
        resetWeight = resetWeight,
        resetCount = resetCount,
        repShift = repShift,
    )

    /** Стадия не финальная и вес ≥ 0 — вес не растёт, подсказываем сменить стадию. */
    val stageCeilingEligible: Boolean
        get() = stageGroup != null && leverageStage != null &&
            leverageStage != ru.streetlift.tracker.domain.StageGroups.finalOf(stageGroup) && weightKg >= 0f

    fun withState(s: PlanState) = copy(
        weightKg = s.weightKg,
        plannedSets = s.plannedSets,
        targetReps = s.targetReps,
        roundsAtWeight = s.roundsAtWeight,
        declineStreak = s.declineStreak,
        emergencyDeloadNext = s.emergencyDeloadNext,
        lastScore = s.lastScore,
        lastScoreWeight = s.lastScoreWeight,
        mode = s.mode,
        ceilingStreak = s.ceilingStreak,
        stageCeiling = s.stageCeiling,
        bestTotal = s.bestTotal,
        stallRounds = s.stallRounds,
        resetWeight = s.resetWeight,
        resetCount = s.resetCount,
        repShift = s.repShift,
    )
}

@Serializable
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long?,
    val dayName: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val roundNumber: Int,
    val isDeload: Boolean,
    val alternating: Boolean,
    val summary: String = "",
    /** Фаза подводки на момент тренировки (PeakPhase.name) и пиковые упражнения. */
    val peakPhase: String? = null,
    val peakIds: String? = null,
    /** Приоритет на момент тренировки (Priority.name) — порядок упражнений дня. */
    val priority: String? = null,
    /** JSON-снимок цикла и плана до завершения — для отката при удалении последней тренировки. */
    val rollback: String? = null,
    /** Первая тренировка после паузы ≥ 3 дней в подготовке к пику — на подход меньше. */
    val afterPause: Boolean = false,
    /** Номер цикла внутри мезоцикла (1..5) на момент тренировки; null — старые записи. */
    val cycleInMeso: Int? = null,
    /** День 1: что выпало «через цикл» — 0 пистолетики, 1 прыжки; null — старые записи (по чётности). */
    val altSlot: Int? = null,
) {
    /** Цикл в мезоцикле для показа; у старых записей — из общего номера. */
    val shownCycle: Int get() = cycleInMeso ?: ((roundNumber - 1).mod(SettingsEntity.DELOAD_EVERY) + 1)

    /** Слот «через цикл» этой тренировки; у старых записей — по чётности общего номера. */
    val effectiveAltSlot: Int get() = altSlot ?: (roundNumber - 1).mod(2)
}

@Serializable
@Entity(
    tableName = "set_logs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long?,
    val exerciseName: String,
    val setIndex: Int,
    val weightKg: Float,
    val reps: Int,
    val rir: Int,
    val isDeload: Boolean,
    val loggedAt: Long,
    val leverageStage: String? = null,
    val isCalibration: Boolean = false,
    /** Статика: в reps — секунды удержания. */
    val isSeconds: Boolean = false,
    /** Сторона у односторонних упражнений: «L» — левая, «R» — правая; null — обе. */
    val side: String? = null,
    /** Прыжки на тумбу: высота подхода, см. */
    val heightCm: Int? = null,
)

/** Справочник стадий рычага: 7 встроенных + свои варианты пользователя. */
@Serializable
@Entity(tableName = "leverage_stages", indices = [Index(value = ["name"], unique = true)])
data class StageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val position: Int,
    val builtIn: Boolean = false,
    /** Группа справочника: у каждого упражнения свой список стадий. */
    val groupKey: String = ru.streetlift.tracker.domain.StageGroups.LEVER,
)

/** Снимок плана упражнения на старой стадии при переходе на новую. */
@Serializable
@Entity(tableName = "stage_archive")
data class StageArchiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long?,
    val exerciseName: String,
    val stage: String,
    val newStage: String?,
    val weightKg: Float,
    val plannedSets: Int,
    val targetReps: Int,
    val roundsAtWeight: Int,
    val archivedAt: Long,
)

/** Рекорд, введённый вручную: один подход упражнения (по названию) на стадии и весе, с датой. */
@Serializable
@Entity(tableName = "manual_records")
data class ManualRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseName: String,
    val leverageStage: String? = null,
    val weightKg: Float,
    /** Повторы (или секунды у статики). */
    val value: Int,
    val isSeconds: Boolean = false,
    val epochDay: Long,
)

/** Пробежка: время без пауз и маршрут («lat,lon;lat,lon|…», отрезки между паузами через «|»). */
@Serializable
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val route: String,
)
