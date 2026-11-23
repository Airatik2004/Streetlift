package ru.streetlift.tracker.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.streetlift.tracker.data.backup.BackupData
import ru.streetlift.tracker.data.backup.BackupJson
import ru.streetlift.tracker.data.backup.RollbackData
import ru.streetlift.tracker.data.db.AppDatabase
import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.db.StageArchiveEntity
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.domain.CycleState
import ru.streetlift.tracker.domain.DeloadOverride
import ru.streetlift.tracker.domain.ExerciseMode
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.domain.SetResult
import java.time.LocalDate
import kotlin.math.max

class WorkoutRepository(private val db: AppDatabase) {

    private val settingsDao = db.settingsDao()
    private val programDao = db.programDao()
    private val sessionDao = db.sessionDao()

    val settings: Flow<SettingsEntity> = settingsDao.observe().map { it ?: SettingsEntity() }
    val days: Flow<List<DayEntity>> = programDao.observeDays()
    val exercises: Flow<List<ExerciseEntity>> = programDao.observeExercises()
    val activeSession: Flow<SessionEntity?> = sessionDao.observeActive()
    val lastFinishedSession: Flow<SessionEntity?> = sessionDao.observeLastFinished()
    val finishedSessions: Flow<List<SessionEntity>> = sessionDao.observeFinished()
    val allSets: Flow<List<SetLogEntity>> = sessionDao.observeAllSets()
    val stages: Flow<List<StageEntity>> = programDao.observeStages()
    val stageArchive: Flow<List<StageArchiveEntity>> = programDao.observeArchive()

    suspend fun getStages(): List<StageEntity> = programDao.getStages()

    fun observeSets(sessionId: Long): Flow<List<SetLogEntity>> = sessionDao.observeSets(sessionId)

    suspend fun getSettings(): SettingsEntity = settingsDao.get() ?: SettingsEntity()
    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getSession(id)
    suspend fun getExercisesForDay(dayId: Long): List<ExerciseEntity> = programDao.getExercisesForDay(dayId)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Первый запуск: заливаем шаблон цикла. */
    suspend fun ensureSeeded() {
        db.withTransaction {
            if (settingsDao.get() == null) {
                if (programDao.getDays().isEmpty()) DefaultTemplate.seedProgram(programDao)
                settingsDao.upsert(SettingsEntity())
            }
            if (programDao.getStages().isEmpty()) programDao.insertStages(DefaultTemplate.builtInStages())
        }
    }

    // ---------------- Тренировка ----------------

    suspend fun startSession(dayId: Long): Long = db.withTransaction {
        sessionDao.getActive()?.let { return@withTransaction it.id }
        val s = getSettings()
        val day = programDao.getDay(dayId) ?: error("День не найден")
        val global = ProgressionEngine.isGlobalDeload(s.cycleInMeso, s.deloadEvery, s.deloadOverride)
        sessionDao.insertSession(
            SessionEntity(
                dayId = day.id,
                dayName = day.name,
                startedAt = System.currentTimeMillis(),
                roundNumber = s.completedRounds + 1,
                isDeload = global,
                alternating = day.alternating,
            ),
        )
    }

    suspend fun logSet(set: SetLogEntity) {
        sessionDao.insertSet(set)
    }

    suspend fun deleteSet(set: SetLogEntity) = sessionDao.deleteSet(set)

    /** Исправить записанный подход (вес, повторы, запас). */
    suspend fun updateSet(set: SetLogEntity) = sessionDao.updateSet(set)

    suspend fun discardSession(sessionId: Long) = sessionDao.deleteSession(sessionId)

    /**
     * Удаление из истории. Если это последняя завершённая тренировка — откатываем её последствия:
     * позицию в цикле, круг, разгрузку и план упражнений. Возвращает true, если откат выполнен.
     */
    suspend fun deleteSession(sessionId: Long): Boolean = db.withTransaction {
        val session = sessionDao.getSession(sessionId) ?: return@withTransaction false
        val latest = sessionDao.getAllSessions().filter { it.finishedAt != null }.maxByOrNull { it.finishedAt ?: 0L }
        var rolledBack = false
        val snap = session.rollback?.let { runCatching { BackupJson.decodeRollback(it) }.getOrNull() }
        if (latest?.id == session.id && snap != null) {
            val cur = getSettings()
            settingsDao.upsert(
                cur.copy(
                    currentDayIndex = snap.settings.currentDayIndex,
                    cycleInMeso = snap.settings.cycleInMeso,
                    completedRounds = snap.settings.completedRounds,
                    deloadOverride = snap.settings.deloadOverride,
                    nextWorkoutEpochDay = snap.settings.nextWorkoutEpochDay,
                    undoDayIndex = null, undoCycleInMeso = null, undoCompletedRounds = null,
                    undoOverride = null, undoNextEpochDay = null,
                ),
            )
            val now = programDao.getAllExercises().associateBy { it.id }
            val restored = snap.exercises.mapNotNull { old ->
                now[old.id]?.withState(old.toState())?.copy(leverageStage = old.leverageStage)
            }
            if (restored.isNotEmpty()) programDao.updateExercises(restored)
            rolledBack = true
        }
        sessionDao.deleteSession(sessionId)
        rolledBack
    }

    /** Id последней завершённой тренировки, которую можно откатить. */
    val rollbackableSessionId: Flow<Long?> = sessionDao.observeLastFinished().map { s -> s?.takeIf { it.rollback != null }?.id }

    /**
     * Завершение тренировки: пересчёт плана каждого упражнения дня движком прогрессии,
     * сдвиг цикла и счётчика разгрузки. Возвращает текст итогов.
     */
    suspend fun finishSession(sessionId: Long): String = db.withTransaction {
        val session = sessionDao.getSession(sessionId) ?: return@withTransaction ""
        val logs = sessionDao.getSets(sessionId)
        val s = getSettings()
        val days = programDao.getDays()
        val exercises = session.dayId?.let { programDao.getExercisesForDay(it) } ?: emptyList()
        val rollback = runCatching { BackupJson.encodeRollback(RollbackData(s, exercises)) }.getOrNull()

        val lines = mutableListOf<String>()
        val updated = exercises.map { ex ->
            val exLogs = logs.filter { it.exerciseId == ex.id }
            val wasDeload = session.isDeload || ex.emergencyDeloadNext
            val result = ProgressionEngine.computeNext(
                ex.toConfig(),
                ex.toState(),
                exLogs.map { SetResult(it.weightKg, it.reps, it.rir) },
                wasDeload,
            )
            lines += "• ${ex.name}: ${result.message}"
            val next = ex.withState(result.state)
            // Калибровка фиксирует и стадию последнего рабочего подхода
            if (ex.mode == ExerciseMode.CALIBRATION && exLogs.isNotEmpty()) {
                next.copy(leverageStage = exLogs.last().leverageStage ?: ex.leverageStage)
            } else next
        }
        if (updated.isNotEmpty()) programDao.updateExercises(updated)

        val dayIndex = days.indexOfFirst { it.id == session.dayId }.takeIf { it >= 0 } ?: s.currentDayIndex
        val adv = ProgressionEngine.advanceCycle(
            CycleState(s.currentDayIndex, s.cycleInMeso, s.completedRounds, s.deloadOverride),
            dayIndex, days.size, s.deloadEvery,
        )
        if (adv.roundCompleted) {
            lines += if (adv.wasDeloadRound) {
                "Круг завершён. Разгрузка позади — новый мезоцикл, круг 1 из ${s.deloadEvery}."
            } else {
                "Круг завершён. Следующий — ${adv.state.cycleInMeso} из ${s.deloadEvery}" +
                    if (adv.state.cycleInMeso >= s.deloadEvery) " (плановая разгрузка)." else "."
            }
        }
        settingsDao.upsert(
            s.withoutUndo().copy(
                currentDayIndex = adv.state.dayIndex,
                cycleInMeso = adv.state.cycleInMeso,
                completedRounds = adv.state.completedRounds,
                deloadOverride = adv.state.override,
                nextWorkoutEpochDay = today() + 2, // через день отдыха
            ),
        )
        val summary = lines.joinToString("\n")
        sessionDao.updateSession(session.copy(finishedAt = System.currentTimeMillis(), summary = summary, rollback = rollback))
        summary
    }

    // ---------------- Управление циклом ----------------

    /** Пропуск дня: план не меняется, цикл сдвигается. Первый пропуск запоминает состояние для отмены. */
    suspend fun skipDay() = db.withTransaction {
        val s0 = getSettings()
        val days = programDao.getDays()
        if (days.isEmpty()) return@withTransaction
        val s = if (s0.canUndoSkip) s0 else s0.copy(
            undoDayIndex = s0.currentDayIndex,
            undoCycleInMeso = s0.cycleInMeso,
            undoCompletedRounds = s0.completedRounds,
            undoOverride = s0.deloadOverride.name,
            undoNextEpochDay = s0.nextWorkoutEpochDay,
        )
        val adv = ProgressionEngine.advanceCycle(
            CycleState(s.currentDayIndex, s.cycleInMeso, s.completedRounds, s.deloadOverride),
            s.currentDayIndex, days.size, s.deloadEvery,
        )
        settingsDao.upsert(
            s.copy(
                currentDayIndex = adv.state.dayIndex,
                cycleInMeso = adv.state.cycleInMeso,
                completedRounds = adv.state.completedRounds,
                deloadOverride = adv.state.override,
                nextWorkoutEpochDay = null,
            ),
        )
    }

    /** Вернуть все пропуски с последней тренировки. */
    suspend fun undoSkip() {
        val s = getSettings()
        if (!s.canUndoSkip) return
        settingsDao.upsert(
            s.withoutUndo().copy(
                currentDayIndex = s.undoDayIndex ?: s.currentDayIndex,
                cycleInMeso = s.undoCycleInMeso ?: s.cycleInMeso,
                completedRounds = s.undoCompletedRounds ?: s.completedRounds,
                deloadOverride = s.undoOverride?.let { runCatching { DeloadOverride.valueOf(it) }.getOrNull() } ?: s.deloadOverride,
                nextWorkoutEpochDay = s.undoNextEpochDay,
            ),
        )
    }

    private fun SettingsEntity.withoutUndo() = copy(
        undoDayIndex = null, undoCycleInMeso = null, undoCompletedRounds = null,
        undoOverride = null, undoNextEpochDay = null,
    )

    /** Вернуть следующую тренировку на день раньше (не раньше сегодня). */
    suspend fun prepone() {
        val s = getSettings()
        val next = s.nextWorkoutEpochDay ?: return
        val moved = next - 1
        settingsDao.upsert(s.copy(nextWorkoutEpochDay = if (moved <= today()) null else moved))
    }

    suspend fun selectDay(index: Int) {
        val s = getSettings()
        settingsDao.upsert(s.copy(currentDayIndex = index))
    }

    /** Сдвинуть следующую тренировку на день позже. */
    suspend fun postpone() {
        val s = getSettings()
        val base = max(s.nextWorkoutEpochDay ?: today(), today())
        settingsDao.upsert(s.copy(nextWorkoutEpochDay = base + 1))
    }

    suspend fun setDeload(want: Boolean) {
        val s = getSettings()
        settingsDao.upsert(s.copy(deloadOverride = ProgressionEngine.overrideFor(want, s.cycleInMeso, s.deloadEvery)))
    }

    suspend fun setDeloadEvery(n: Int) {
        val s = getSettings()
        settingsDao.upsert(s.copy(deloadEvery = n.coerceIn(2, 12)))
    }

    suspend fun setAutoStartTimer(enabled: Boolean) {
        val s = getSettings()
        settingsDao.upsert(s.copy(autoStartTimer = enabled))
    }

    // ---------------- Редактор ----------------

    suspend fun moveDay(dayId: Long, delta: Int) = db.withTransaction {
        val days = programDao.getDays().toMutableList()
        val i = days.indexOfFirst { it.id == dayId }
        val j = i + delta
        if (i < 0 || j !in days.indices) return@withTransaction
        val a = days[i]
        days[i] = days[j]
        days[j] = a
        programDao.updateDays(days.mapIndexed { idx, d -> d.copy(position = idx) })
    }

    suspend fun updateDay(day: DayEntity) = programDao.updateDay(day)

    suspend fun addExercise(dayId: Long) {
        val pos = programDao.getExercisesForDay(dayId).size
        programDao.insertExercise(
            ExerciseEntity(
                dayId = dayId, position = pos, name = "Новое упражнение", type = ExerciseType.ACCESSORY,
                minSets = 3, maxSets = 4, minReps = 8, maxReps = 12, targetRirMin = 1, targetRirMax = 2,
                weightStep = 2.5f, weightKg = 0f, plannedSets = 3, targetReps = 8,
            ),
        )
    }

    /** Сохранение из редактора: план подстраивается под новые диапазоны. */
    suspend fun saveExercise(e: ExerciseEntity) {
        val minSets = e.minSets.coerceIn(1, 20)
        val maxSets = e.maxSets.coerceIn(minSets, 20)
        val minReps = e.minReps.coerceIn(1, 100)
        val maxReps = e.maxReps.coerceIn(minReps, 100)
        val rirMin = e.targetRirMin.coerceIn(0, 5)
        val rirMax = e.targetRirMax.coerceIn(rirMin, 5)
        val fixed = e.copy(
            minSets = minSets, maxSets = maxSets, minReps = minReps, maxReps = maxReps,
            targetRirMin = rirMin, targetRirMax = rirMax,
            weightStep = if (e.weightStep > 0f) e.weightStep else 2.5f,
            weightKg = ProgressionEngine.roundWeight(e.weightKg),
            plannedSets = e.plannedSets.coerceIn(max(1, minSets - 1), maxSets),
            targetReps = e.targetReps.coerceIn(minReps, maxReps),
        )
        val old = programDao.getExercise(e.id)
        // Вес изменён вручную — сравнение показателей начинаем заново
        var final = if (old != null && old.weightKg != fixed.weightKg) {
            fixed.copy(roundsAtWeight = 0, declineStreak = 0, lastScore = null, lastScoreWeight = null)
        } else fixed
        if (old != null && old.leverageStage != fixed.leverageStage) {
            final = changeStage(old, final, fixed.leverageStage)
        }
        programDao.updateExercise(final)
    }

    /** Смена стадии: старая уходит в архив, упражнение — на 1 круг в калибровку. */
    private suspend fun changeStage(old: ExerciseEntity, edited: ExerciseEntity, newStage: String?): ExerciseEntity {
        old.leverageStage?.let { oldStage ->
            programDao.insertArchive(
                StageArchiveEntity(
                    exerciseId = old.id,
                    exerciseName = old.name,
                    stage = oldStage,
                    newStage = newStage,
                    weightKg = old.weightKg,
                    plannedSets = old.plannedSets,
                    targetReps = old.targetReps,
                    roundsAtWeight = old.roundsAtWeight,
                    archivedAt = System.currentTimeMillis(),
                ),
            )
        }
        return edited.withState(ProgressionEngine.onStageChanged(edited.toState()))
            .copy(leverageStage = newStage)
    }

    /** Добавить свою стадию в конец справочника. Возвращает её имя. */
    suspend fun addStage(name: String): String {
        val clean = name.trim()
        db.withTransaction {
            val list = programDao.getStages()
            if (list.none { it.name.equals(clean, ignoreCase = true) }) {
                programDao.insertStages(listOf(StageEntity(name = clean, position = (list.maxOfOrNull { it.position } ?: -1) + 1)))
            }
        }
        return clean
    }

    // ---------------- Первый запуск ----------------

    data class SetupDraft(val exerciseId: Long, val stage: String?, val weightKg: Float, val calibrate: Boolean)

    /** «Настройка стартовых параметров»: вес известен → ACTIVE, иначе → CALIBRATION. */
    suspend fun completeSetup(drafts: List<SetupDraft>) = db.withTransaction {
        val byId = programDao.getAllExercises().associateBy { it.id }
        val updated = drafts.mapNotNull { d ->
            val ex = byId[d.exerciseId] ?: return@mapNotNull null
            ex.copy(
                leverageStage = d.stage,
                weightKg = ProgressionEngine.roundWeight(d.weightKg),
                mode = if (d.calibrate) ExerciseMode.CALIBRATION else ExerciseMode.ACTIVE,
                plannedSets = ex.minSets,
                targetReps = ex.minReps,
                roundsAtWeight = 0,
                declineStreak = 0,
                emergencyDeloadNext = false,
                lastScore = null,
                lastScoreWeight = null,
            )
        }
        if (updated.isNotEmpty()) programDao.updateExercises(updated)
        settingsDao.upsert(getSettings().copy(setupDone = true))
    }

    suspend fun reopenSetup() {
        settingsDao.upsert(getSettings().copy(setupDone = false))
    }

    suspend fun deleteExercise(e: ExerciseEntity) = db.withTransaction {
        programDao.deleteExercise(e)
        val rest = programDao.getExercisesForDay(e.dayId)
        programDao.updateExercises(rest.mapIndexed { i, x -> x.copy(position = i) })
    }

    suspend fun moveExercise(e: ExerciseEntity, delta: Int) = db.withTransaction {
        val list = programDao.getExercisesForDay(e.dayId).toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        val a = list[i]
        list[i] = list[j]
        list[j] = a
        programDao.updateExercises(list.mapIndexed { idx, x -> x.copy(position = idx) })
    }

    /** Сброс программы к шаблону. История тренировок сохраняется. */
    suspend fun resetProgram() = db.withTransaction {
        programDao.clearExercises()
        programDao.clearDays()
        DefaultTemplate.seedProgram(programDao)
        val s = getSettings()
        settingsDao.upsert(
            s.copy(
                currentDayIndex = 0, cycleInMeso = 1, completedRounds = 0,
                deloadOverride = DeloadOverride.AUTO, nextWorkoutEpochDay = null,
                setupDone = false,
            ),
        )
    }

    // ---------------- Бэкап ----------------

    suspend fun exportJson(): String {
        val data = db.withTransaction {
            BackupData(
                exportedAt = System.currentTimeMillis(),
                settings = getSettings(),
                days = programDao.getDays(),
                exercises = programDao.getAllExercises(),
                sessions = sessionDao.getAllSessions(),
                sets = sessionDao.getAllSets(),
                stages = programDao.getStages(),
                stageArchive = programDao.getArchive(),
            )
        }
        return BackupJson.encode(data)
    }

    /** Полная замена базы содержимым JSON. Бросает исключение при неверном формате. */
    suspend fun importJson(text: String) {
        val data = BackupJson.decode(text)
        require(data.days.isNotEmpty()) { "В файле нет дней цикла" }
        db.withTransaction {
            sessionDao.clearSets()
            sessionDao.clearSessions()
            programDao.clearExercises()
            programDao.clearDays()
            programDao.clearStages()
            programDao.clearArchive()
            programDao.insertStages(data.stages.ifEmpty { DefaultTemplate.builtInStages() })
            programDao.insertArchives(data.stageArchive)
            programDao.insertDays(data.days)
            programDao.insertExercises(data.exercises)
            sessionDao.insertSessions(data.sessions)
            sessionDao.insertSets(data.sets)
            settingsDao.upsert(
                data.settings.copy(
                    id = 1,
                    setupDone = true,
                    currentDayIndex = data.settings.currentDayIndex.coerceIn(0, data.days.size - 1),
                ),
            )
        }
    }
}
