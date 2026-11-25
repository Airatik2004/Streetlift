package ru.streetlift.tracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface ProgramDao {
    @Query("SELECT * FROM days ORDER BY position")
    fun observeDays(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days ORDER BY position")
    suspend fun getDays(): List<DayEntity>

    @Query("SELECT * FROM days WHERE id = :id")
    suspend fun getDay(id: Long): DayEntity?

    @Insert
    suspend fun insertDay(day: DayEntity): Long

    @Insert
    suspend fun insertDays(days: List<DayEntity>)

    @Update
    suspend fun updateDay(day: DayEntity)

    @Update
    suspend fun updateDays(days: List<DayEntity>)

    @Delete
    suspend fun deleteDay(day: DayEntity)

    @Query("DELETE FROM days")
    suspend fun clearDays()

    @Query("SELECT * FROM exercises ORDER BY dayId, position")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises ORDER BY dayId, position")
    suspend fun getAllExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE dayId = :dayId ORDER BY position")
    suspend fun getExercisesForDay(dayId: Long): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getExercise(id: Long): ExerciseEntity?

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntity): Long

    @Insert
    suspend fun insertExercises(exercises: List<ExerciseEntity>)

    @Update
    suspend fun updateExercise(exercise: ExerciseEntity)

    @Update
    suspend fun updateExercises(exercises: List<ExerciseEntity>)

    @Delete
    suspend fun deleteExercise(exercise: ExerciseEntity)

    @Query("DELETE FROM exercises")
    suspend fun clearExercises()

    // --- стадии рычага ---
    @Query("SELECT * FROM leverage_stages ORDER BY position")
    fun observeStages(): Flow<List<StageEntity>>

    @Query("SELECT * FROM leverage_stages ORDER BY position")
    suspend fun getStages(): List<StageEntity>

    @Insert
    suspend fun insertStages(stages: List<StageEntity>)

    @Query("DELETE FROM leverage_stages")
    suspend fun clearStages()

    // --- архив стадий ---
    @Insert
    suspend fun insertArchive(entry: StageArchiveEntity)

    @Insert
    suspend fun insertArchives(entries: List<StageArchiveEntity>)

    @Query("SELECT * FROM stage_archive ORDER BY archivedAt DESC")
    fun observeArchive(): Flow<List<StageArchiveEntity>>

    @Query("SELECT * FROM stage_archive ORDER BY id")
    suspend fun getArchive(): List<StageArchiveEntity>

    @Query("DELETE FROM stage_archive")
    suspend fun clearArchive()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE finishedAt IS NOT NULL ORDER BY finishedAt DESC LIMIT 1")
    fun observeLastFinished(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE finishedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinished(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM set_logs WHERE exerciseName = :name AND leverageStage = :stage")
    suspend fun countSetsAtStage(name: String, stage: String): Int

    /** Последнее сделанное из пары «через цикл» (пистолетики / прыжки) в завершённых тренировках. */
    @Query(
        "SELECT l.exerciseName AS exerciseName, s.roundNumber AS roundNumber FROM set_logs l " +
            "JOIN sessions s ON l.sessionId = s.id " +
            "WHERE s.finishedAt IS NOT NULL AND l.exerciseName IN ('Пистолетики', 'Прыжки на тумбу') " +
            "ORDER BY l.loggedAt DESC LIMIT 1",
    )
    fun observeLastAlt(): Flow<LastAlt?>

    @Query(
        "SELECT l.exerciseName AS exerciseName, s.roundNumber AS roundNumber FROM set_logs l " +
            "JOIN sessions s ON l.sessionId = s.id " +
            "WHERE s.finishedAt IS NOT NULL AND l.exerciseName IN ('Пистолетики', 'Прыжки на тумбу') " +
            "ORDER BY l.loggedAt DESC LIMIT 1",
    )
    suspend fun getLastAlt(): LastAlt?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Insert
    suspend fun insertSet(set: SetLogEntity): Long

    @Insert
    suspend fun insertSets(sets: List<SetLogEntity>)

    @Delete
    suspend fun deleteSet(set: SetLogEntity)

    @Update
    suspend fun updateSet(set: SetLogEntity)

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY loggedAt")
    fun observeSets(sessionId: Long): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY loggedAt")
    suspend fun getSets(sessionId: Long): List<SetLogEntity>

    @Query("SELECT * FROM set_logs ORDER BY loggedAt")
    fun observeAllSets(): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs ORDER BY id")
    suspend fun getAllSets(): List<SetLogEntity>

    @Query("DELETE FROM set_logs")
    suspend fun clearSets()
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM manual_records ORDER BY epochDay")
    fun observeManual(): Flow<List<ManualRecordEntity>>

    @Query("SELECT * FROM manual_records")
    suspend fun getManual(): List<ManualRecordEntity>

    @Insert
    suspend fun insertManual(r: ManualRecordEntity): Long

    @Insert
    suspend fun insertManuals(r: List<ManualRecordEntity>)

    @Query("DELETE FROM manual_records WHERE id = :id")
    suspend fun deleteManual(id: Long)

    @Query("DELETE FROM manual_records")
    suspend fun clearManual()

    @Query("SELECT * FROM runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs")
    suspend fun getRuns(): List<RunEntity>

    @Insert
    suspend fun insertRun(r: RunEntity): Long

    @Insert
    suspend fun insertRuns(r: List<RunEntity>)

    @Query("DELETE FROM runs WHERE id = :id")
    suspend fun deleteRun(id: Long)

    @Query("DELETE FROM runs")
    suspend fun clearRuns()
}

/** Что из пары «через цикл» делалось последним и в каком общем цикле. */
data class LastAlt(val exerciseName: String, val roundNumber: Int) {
    /** Слот сделанного: 0 пистолетики, 1 прыжки. */
    val slot: Int get() = if (exerciseName == JUMP_NAME) 1 else 0

    /** Следующий по очереди слот. */
    val nextSlot: Int get() = 1 - slot
}

/** Слот Дня 1 следующей тренировки: противоположный последнему сделанному; впервые — пистолетики. */
fun nextAltSlot(last: LastAlt?): Int = last?.nextSlot ?: 0
