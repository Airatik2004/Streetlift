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
import ru.streetlift.tracker.domain.PlanState

/** Глобальное состояние программы (одна строка, id = 1). */
@Serializable
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val currentDayIndex: Int = 0,
    val cycleInMeso: Int = 1,
    val completedRounds: Int = 0,
    val deloadEvery: Int = 5,
    val deloadOverride: DeloadOverride = DeloadOverride.AUTO,
    /** Дата следующей тренировки (LocalDate.toEpochDay), null — сегодня. */
    val nextWorkoutEpochDay: Long? = null,
    val autoStartTimer: Boolean = true,
    /** Пройден ли экран «Настройка стартовых параметров». */
    val setupDone: Boolean = false,
)

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
) {
    fun toConfig() = ExerciseConfig(minSets, maxSets, minReps, maxReps, targetRirMin, targetRirMax, weightStep)

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
    )

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
)

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
