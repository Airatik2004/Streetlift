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
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.DeloadOverride
import ru.streetlift.tracker.domain.ExerciseMode
import ru.streetlift.tracker.domain.PeakPhase
import ru.streetlift.tracker.domain.Peaking
import ru.streetlift.tracker.domain.StageGroups
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.domain.SetResult
import java.time.LocalDate
import kotlin.math.max

class WorkoutRepository(private val db: AppDatabase) {

    private val settingsDao = db.settingsDao()
    private val programDao = db.programDao()
    private val sessionDao = db.sessionDao()
    private val statsDao = db.statsDao()

    // ---------------- Статистика ----------------

    val manualRecords: Flow<List<ru.streetlift.tracker.data.db.ManualRecordEntity>> = statsDao.observeManual()
    val runs: Flow<List<ru.streetlift.tracker.data.db.RunEntity>> = statsDao.observeRuns()

    suspend fun addManualRecord(r: ru.streetlift.tracker.data.db.ManualRecordEntity) { statsDao.insertManual(r) }
    suspend fun deleteManualRecord(id: Long) = statsDao.deleteManual(id)
    suspend fun saveRun(r: ru.streetlift.tracker.data.db.RunEntity) { statsDao.insertRun(r) }
    suspend fun deleteRun(id: Long) = statsDao.deleteRun(id)

    val settings: Flow<SettingsEntity> = settingsDao.observe().map { (it ?: SettingsEntity()).also(::applyBands) }
    val days: Flow<List<DayEntity>> = programDao.observeDays()
    val exercises: Flow<List<ExerciseEntity>> = programDao.observeExercises()
    val activeSession: Flow<SessionEntity?> = sessionDao.observeActive()
    val lastFinishedSession: Flow<SessionEntity?> = sessionDao.observeLastFinished()
    val finishedSessions: Flow<List<SessionEntity>> = sessionDao.observeFinished()
    suspend fun countSetsAtStage(name: String, stage: String): Int = sessionDao.countSetsAtStage(name, stage)

    /** Последнее сделанное из пары пистолетики / прыжки — от него зависит, что будет в Дне 1. */
    val lastAlt: Flow<ru.streetlift.tracker.data.db.LastAlt?> = sessionDao.observeLastAlt()
    val allSets: Flow<List<SetLogEntity>> = sessionDao.observeAllSets()
    val stages: Flow<List<StageEntity>> = programDao.observeStages()
    val stageArchive: Flow<List<StageArchiveEntity>> = programDao.observeArchive()

    suspend fun getStages(): List<StageEntity> = programDao.getStages()

    fun observeSets(sessionId: Long): Flow<List<SetLogEntity>> = sessionDao.observeSets(sessionId)

    suspend fun getSettings(): SettingsEntity = (settingsDao.get() ?: SettingsEntity()).also(::applyBands)

    /** Набор резин из настроек — для лестницы помощи и подписей. */
    private fun applyBands(s: SettingsEntity) {
        val list = s.bandList
        if (list != ru.streetlift.tracker.domain.Bands.current) ru.streetlift.tracker.domain.Bands.current = list
    }

    /** Отрицательные веса — к ближайшему сочетанию резин; где резин нет — 0. */
    private suspend fun snapAssistWeights() {
        getSettings()
        val fixed = programDao.getAllExercises().mapNotNull { e ->
            val w = e.loadScale.snap(e.weightKg)
            if (kotlin.math.abs(w - e.weightKg) > 0.001f) e.copy(weightKg = w) else null
        }
        if (fixed.isNotEmpty()) programDao.updateExercises(fixed)
    }

    /** Напоминания: накануне и в день тренировки (минуты от полуночи). */
    suspend fun setReminders(eve: Boolean, eveMin: Int, day: Boolean, dayMin: Int, late: Boolean, lateMin: Int) {
        settingsDao.upsert(
            getSettings().copy(
                remindEve = eve, remindEveMin = eveMin, remindDay = day, remindDayMin = dayMin,
                remindLate = late, remindLateMin = lateMin,
            ),
        )
    }

    /** Сохранить набор резин (название + помощь в кг). */
    suspend fun setBands(bands: List<ru.streetlift.tracker.domain.Band>) {
        val clean = bands.filter { it.name.isNotBlank() && it.kg > 0f }
            .map { it.copy(name = it.name.replace(",", " ").replace(":", " ").trim()) }
        settingsDao.upsert(getSettings().copy(bands = ru.streetlift.tracker.domain.Bands.encode(clean)))
        snapAssistWeights()
    }
    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getSession(id)
    suspend fun getExercisesForDay(dayId: Long): List<ExerciseEntity> = programDao.getExercisesForDay(dayId)
    suspend fun getDay(id: Long): DayEntity? = programDao.getDay(id)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Первый запуск: заливаем шаблон цикла. */
    suspend fun ensureSeeded() {
        db.withTransaction {
            if (settingsDao.get() == null) {
                if (programDao.getDays().isEmpty()) DefaultTemplate.seedProgram(programDao)
                settingsDao.upsert(SettingsEntity())
            }
            if (programDao.getStages().isEmpty()) programDao.insertStages(DefaultTemplate.builtInStages())
            snapAssistWeights()
        }
    }

    /** Общий прогресс: состояние упражнения копируется во все его копии с тем же shareKey. */
    private suspend fun syncShared(changed: List<ExerciseEntity>) {
        val keyed = changed.filter { it.shareKey != null }
        if (keyed.isEmpty()) return
        val all = programDao.getAllExercises()
        val copies = keyed.flatMap { src ->
            all.filter { it.shareKey == src.shareKey && it.id != src.id }
                .map {
                    it.withState(src.toState()).copy(
                        leverageStage = src.leverageStage,
                        skipPlannedDeload = src.skipPlannedDeload,
                        deloadSessions = src.deloadSessions,
                    )
                }
        }
        if (copies.isNotEmpty()) programDao.updateExercises(copies)
    }

    // ---------------- Тренировка ----------------

    suspend fun startSession(dayId: Long): Long = db.withTransaction {
        sessionDao.getActive()?.let { return@withTransaction it.id }
        val s = getSettings()
        val day = programDao.getDay(dayId) ?: error("День не найден")
        val phase = Peaking.phaseOn(s.peakEpochDay, today(), s.peakSpanDays)
        // Подготовка к пику после паузы ≥ 3 дней — первая тренировка на подход легче
        val afterPause = phase == PeakPhase.PRE_PEAK && s.missedDays(today()) >= Peaking.PAUSE_DAYS
        sessionDao.insertSession(
            SessionEntity(
                dayId = day.id,
                dayName = day.name,
                startedAt = System.currentTimeMillis(),
                roundNumber = s.completedRounds + 1,
                // Общая принудительная разгрузка; плановая — у каждого упражнения своя. Подводка перекрывает
                isDeload = s.forcedDeload && phase == PeakPhase.NONE,
                alternating = day.alternating,
                peakPhase = phase.takeIf { it != PeakPhase.NONE }?.name,
                peakIds = s.peakExerciseIds.takeIf { phase != PeakPhase.NONE },
                priority = s.priority.name,
                afterPause = afterPause,
                cycleInMeso = s.cycleInMeso,
                // День 1: пистолетики или прыжки — то, что делалось давнее; фиксируется на всю тренировку
                altSlot = if (programDao.getExercisesForDay(day.id).any { it.altSlot != null }) {
                    ru.streetlift.tracker.data.db.nextAltSlot(sessionDao.getLastAlt())
                } else null,
            ),
        )
    }

    /** Начать тренировку запланированного дня (из уведомления «Начать»); идущая — вернуть её. */
    suspend fun startPlanned(): Long? {
        sessionDao.getActive()?.let { return it.id }
        val days = programDao.getDays()
        val idx = plannedDayIndex(getSettings(), days, programDao.getAllExercises(), sessionDao.getAllSessions(), today())
        val day = days.getOrNull(idx) ?: return null
        return startSession(day.id)
    }

    /** Запланированный день цикла (с учётом выхода на пик) — для напоминаний. */
    suspend fun plannedDay(): DayEntity? {
        val days = programDao.getDays()
        val idx = plannedDayIndex(getSettings(), days, programDao.getAllExercises(), sessionDao.getAllSessions(), today())
        return days.getOrNull(idx)
    }

    suspend fun hasActiveSession(): Boolean = sessionDao.getActive() != null

    /**
     * Слабая сторона по прошлой тренировке упражнения: меньше повторов в сумме;
     * поровну — ближе к отказу (меньше RIR в сумме); и это поровну или нет данных — левая.
     */
    suspend fun weakSide(exerciseName: String, excludeSession: Long): String {
        val sided = sessionDao.getAllSets().filter { it.exerciseName == exerciseName && it.side != null && it.sessionId != excludeSession }
        val last = sided.maxOfOrNull { it.sessionId } ?: return "L"
        val s = sided.filter { it.sessionId == last }
        val left = s.filter { it.side == "L" }
        val right = s.filter { it.side == "R" }
        val lReps = left.sumOf { it.reps }
        val rReps = right.sumOf { it.reps }
        if (lReps != rReps) return if (rReps < lReps) "R" else "L"
        return if (right.sumOf { it.rir } < left.sumOf { it.rir }) "R" else "L"
    }

    // ---------------- Подводка к старту ----------------

    suspend fun setPeak(epochDay: Long, exerciseIds: Set<Long>, bodyWeightKg: Float?) {
        settingsDao.upsert(
            getSettings().copy(
                peakEpochDay = epochDay,
                // Дата та же — длина подводки сохраняется, иначе считается от сегодня
                peakSpanDays = if (getSettings().peakEpochDay == epochDay && getSettings().peakSpanDays != null) getSettings().peakSpanDays
                else (epochDay - today()).toInt().coerceIn(Peaking.MIN_SPAN, Peaking.FULL_SPAN),
                peakExerciseIds = exerciseIds.joinToString(","),
                bodyWeightKg = bodyWeightKg ?: getSettings().bodyWeightKg,
                peakMode = SettingsEntity.PEAK_RECORDS, compDiscipline = null, compPlan = "",
            ),
        )
    }

    suspend fun clearPeak() {
        settingsDao.upsert(
            getSettings().copy(
                peakEpochDay = null, peakExerciseIds = "", peakSpanDays = null,
                peakMode = SettingsEntity.PEAK_RECORDS, compDiscipline = null, compPlan = "",
            ),
        )
    }

    suspend fun setBirthYear(year: Int?) {
        settingsDao.upsert(getSettings().copy(birthYear = year))
    }

    // ---------------- Соревнования ----------------

    /** Подходы упражнения за последние недели (завершённые тренировки) — для оценки результата. */
    private suspend fun liftSets(names: Set<String>): Map<String, List<ru.streetlift.tracker.domain.Competition.LiftSet>> {
        val since = System.currentTimeMillis() - ru.streetlift.tracker.domain.Competition.HISTORY_DAYS * 86_400_000L
        val finished = sessionDao.getAllSessions().filter { it.finishedAt != null }.map { it.id }.toSet()
        return sessionDao.getAllSets()
            .filter { it.sessionId in finished && it.loggedAt >= since && it.exerciseName in names && !it.isSeconds }
            .groupBy { it.exerciseName }
            .mapValues { (_, v) -> v.map { ru.streetlift.tracker.domain.Competition.LiftSet(it.weightKg, it.reps, it.rir) } }
    }

    /** Оценка текущего результата в дисциплине (null — свой вес не указан). */
    suspend fun competitionEstimate(d: ru.streetlift.tracker.domain.Discipline, bodyWeight: Float?): ru.streetlift.tracker.domain.Competition.Estimate? {
        val bw = bodyWeight?.takeIf { it > 0f } ?: return null
        val sets = liftSets(d.lifts.map { it.exerciseName }.toSet())
        return ru.streetlift.tracker.domain.Competition.estimate(d, bw, d.lifts.associateWith { sets[it.exerciseName].orEmpty() })
    }

    /** Снимок оценок по упражнениям дисциплины для плана пика. */
    private suspend fun competitionSnapshot(d: ru.streetlift.tracker.domain.Discipline, bodyWeight: Float?): String {
        val est = competitionEstimate(d, bodyWeight) ?: return ""
        val byName = programDao.getAllExercises().associateBy { it.name }
        return ru.streetlift.tracker.domain.Competition.encode(
            est.lifts.mapNotNull { l ->
                byName[l.lift.exerciseName]?.let { ru.streetlift.tracker.domain.Competition.Snap(it.id, l.e1rm, l.reps) }
            },
        )
    }

    /** Выход на пик к соревнованиям: дата, дисциплина, свой вес и год рождения. */
    suspend fun setCompetitionPeak(epochDay: Long, disciplineId: String, bodyWeightKg: Float?, birthYear: Int?) {
        val d = ru.streetlift.tracker.domain.Discipline.byId(disciplineId) ?: return
        val cur = getSettings()
        val bw = bodyWeightKg ?: cur.bodyWeightKg
        val names = d.lifts.map { it.exerciseName }.toSet()
        val ids = programDao.getAllExercises().filter { it.name in names }.map { it.id }.toSet()
        settingsDao.upsert(
            cur.copy(
                peakEpochDay = epochDay,
                peakSpanDays = if (cur.peakEpochDay == epochDay && cur.peakSpanDays != null) cur.peakSpanDays
                else (epochDay - today()).toInt().coerceIn(Peaking.MIN_SPAN, Peaking.FULL_SPAN),
                peakExerciseIds = ids.joinToString(","),
                bodyWeightKg = bw,
                birthYear = birthYear ?: cur.birthYear,
                peakMode = SettingsEntity.PEAK_COMPETITION,
                compDiscipline = d.id,
                compPlan = competitionSnapshot(d, bw),
            ),
        )
    }

    /** Обновить снимок оценок соревновательного пика (после тренировки). */
    suspend fun refreshCompetitionPlan() {
        val s = getSettings()
        val d = s.discipline ?: return
        settingsDao.upsert(s.copy(compPlan = competitionSnapshot(d, s.bodyWeightKg)))
    }

    /**
     * На следующий день после старта подводка выключается: мезоцикл с круга 1,
     * пиковые упражнения — на 1 круг в калибровку (новая база с учётом выросшей формы).
     */
    suspend fun checkPeakExpired() = db.withTransaction {
        val s = getSettings()
        val d = s.peakEpochDay ?: return@withTransaction
        if (today() <= d) return@withTransaction
        val ids = s.peakIds
        // После старта — новый мезоцикл у всех, пиковые упражнения уходят на калибровку
        val recalibrate = programDao.getAllExercises().map {
            if (it.id in ids) it.withState(ProgressionEngine.onStageChanged(it.toState())).copy(deloadSessions = 0, skipPlannedDeload = null)
            else it.copy(deloadSessions = 0, skipPlannedDeload = null)
        }
        if (recalibrate.isNotEmpty()) {
            programDao.updateExercises(recalibrate)
            syncShared(recalibrate)
        }
        settingsDao.upsert(
            s.copy(
                peakEpochDay = null, peakExerciseIds = "", peakSpanDays = null, cycleInMeso = 1, deloadOverride = DeloadOverride.AUTO,
                mesoIndex = s.mesoIndex + 1, peakMode = SettingsEntity.PEAK_RECORDS, compDiscipline = null, compPlan = "",
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
            if (restored.isNotEmpty()) {
                programDao.updateExercises(restored)
                syncShared(restored)
            }
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
        val peak = session.peakPhase?.let { runCatching { PeakPhase.valueOf(it) }.getOrNull() }
        if (peak != null) lines += "Выход на пик (${peak.title}) — рабочий план не меняется, результаты в истории."
        // Через цикл (пистолетики / прыжки): не участвовавшее в этой тренировке упражнение не трогаем
        val updated = if (peak != null) emptyList() else exercises.filter { it.activeInSlot(session.effectiveAltSlot) }.map { ex ->
            val exLogs = logs.filter { it.exerciseId == ex.id }
            // Своя плановая разгрузка упражнения (или общая принудительная) либо аварийная
            val planned = ex.plannedDeload(s.deloadEvery, session.isDeload)
            val wasDeload = planned || ex.emergencyDeloadNext
            if (ex.isJump) {
                // Прыжки: высота, вес и прыжки по своим правилам
                val jl = exLogs.map { ru.streetlift.tracker.domain.Jumps.Log(it.heightCm ?: 0, it.weightKg, it.reps) }
                val st = ex.jumpState
                val (newState, msg) = if (st == null || ex.mode == ExerciseMode.CALIBRATION) {
                    if (jl.isEmpty()) st to "прыжков нет — калибровка на следующей"
                    else ru.streetlift.tracker.domain.Jumps.fromCalibration(jl, st).let { it to "калибровка → ${ru.streetlift.tracker.domain.Jumps.describe(it)}" }
                } else ru.streetlift.tracker.domain.Jumps.next(st, jl, wasDeload).let { it.state to it.message }
                lines += "• ${ex.name}: $msg"
                val worked = exLogs.isNotEmpty() && ex.mode == ExerciseMode.ACTIVE
                var sessions = ex.deloadSessions
                if (worked) {
                    sessions += 1
                    if (s.deloadEvery > 0 && sessions >= s.deloadEvery || session.isDeload) sessions = 0
                }
                return@map (newState?.let { ex.withJump(it) } ?: ex).copy(deloadSessions = sessions, skipPlannedDeload = if (sessions == 0) null else ex.skipPlannedDeload)
            }
            val result = ProgressionEngine.computeNext(
                ex.toConfig(),
                ex.toState(),
                // Односторонние: подход засчитывается по слабой стороне (меньше повторов, тяжелее RIR)
                if (ex.unilateral) {
                    exLogs.groupBy { it.setIndex }.toSortedMap().values.map { pair ->
                        SetResult(pair.first().weightKg, pair.minOf { it.reps }, pair.minOf { it.rir })
                    }
                } else exLogs.map { SetResult(it.weightKg, it.reps, it.rir) },
                wasDeload,
                stageCeilingEligible = ex.stageCeilingEligible,
            )
            lines += "• ${ex.name}: ${result.message}"
            // Счётчик разгрузки упражнения: калибровка не считается
            val worked = exLogs.isNotEmpty() && ex.mode == ExerciseMode.ACTIVE
            var sessions = ex.deloadSessions
            var skip = ex.skipPlannedDeload
            if (worked) {
                sessions += 1
                val mesoEnd = s.deloadEvery > 0 && sessions >= s.deloadEvery * ex.sessionsPerCycle
                // Аварийная и общая разгрузка тоже дают отдых — мезоцикл упражнения начинается заново
                if (mesoEnd || ex.emergencyDeloadNext || session.isDeload) {
                    sessions = 0
                    skip = null
                }
            }
            val next = ex.withState(result.state).copy(deloadSessions = sessions, skipPlannedDeload = skip)
            // Калибровка фиксирует и стадию последнего рабочего подхода
            if (ex.mode == ExerciseMode.CALIBRATION && exLogs.isNotEmpty()) {
                val st = exLogs.last().leverageStage ?: ex.leverageStage
                next.copy(leverageStage = st, deloadSessions = if (st != ex.leverageStage) 0 else next.deloadSessions)
            } else next
        }
        if (updated.isNotEmpty()) {
            programDao.updateExercises(updated)
            syncShared(updated)
        }

        val dayIndex = days.indexOfFirst { it.id == session.dayId }.takeIf { it >= 0 } ?: s.currentDayIndex
        val adv = ProgressionEngine.advanceCycle(
            CycleState(s.currentDayIndex, s.cycleInMeso, s.completedRounds, s.deloadOverride),
            dayIndex, days.size, s.deloadEvery,
        )
        if (adv.roundCompleted) {
            lines += "${ru.streetlift.tracker.domain.Priority.cycleLabel(s.priority, s.cycleInMeso, s.deloadEvery)} завершён." +
                if (adv.state.cycleInMeso == 1) " Начинается мезоцикл ${ru.streetlift.tracker.domain.Priority.forMeso(s.mesoIndex + 1).genitive}." else ""
        }
        settingsDao.upsert(
            s.withoutUndo().copy(
                currentDayIndex = adv.state.dayIndex,
                cycleInMeso = adv.state.cycleInMeso,
                completedRounds = adv.state.completedRounds,
                mesoIndex = if (adv.roundCompleted && adv.state.cycleInMeso == 1) s.mesoIndex + 1 else s.mesoIndex,
                deloadOverride = adv.state.override,
                nextWorkoutEpochDay = today() + 2, // через день отдыха
                manualDay = false,
            ),
        )
        // Новые рекорды при весе (лучший подход выше прежнего)
        runCatching {
            val finishedIds = sessionDao.getAllSessions().filter { it.finishedAt != null || it.id == sessionId }.map { it.id }.toSet()
            val entries = recordEntries(sessionDao.getAllSets().filter { it.sessionId in finishedIds }, statsDao.getManual())
            ru.streetlift.tracker.domain.Records.newRecords(entries, sessionId).forEach { r ->
                lines += "Рекорд: ${r.name}${r.stage?.let { " ($it)" } ?: ""} — ${if (r.isHeight) "${r.value} см · ${Fmt.weight(r.weight)}" else "${Fmt.weight(r.weight)} × ${r.value}${if (r.isSeconds) " с" else ""}"}"
            }
        }
        val summary = lines.joinToString("\n")
        sessionDao.updateSession(session.copy(finishedAt = System.currentTimeMillis(), summary = summary, rollback = rollback))
        // Соревнования: оценка обновляется после каждой тренировки — план пика по свежим данным
        runCatching { refreshCompetitionPlan() }
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
        // Пропускается показанный день (на пике он может быть выбран не по порядку)
        val shown = plannedDayIndex(s0, days, programDao.getAllExercises(), sessionDao.getAllSessions(), today())
        val adv = ProgressionEngine.advanceCycle(
            CycleState(s.currentDayIndex, s.cycleInMeso, s.completedRounds, s.deloadOverride),
            shown, days.size, s.deloadEvery,
        )
        settingsDao.upsert(
            s.copy(
                manualDay = true,
                currentDayIndex = adv.state.dayIndex,
                cycleInMeso = adv.state.cycleInMeso,
                completedRounds = adv.state.completedRounds,
                mesoIndex = if (adv.roundCompleted && adv.state.cycleInMeso == 1) s.mesoIndex + 1 else s.mesoIndex,
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
                manualDay = false,
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
        settingsDao.upsert(s.copy(currentDayIndex = index, manualDay = true))
    }

    /** Сдвинуть следующую тренировку на день позже. */
    suspend fun postpone() {
        val s = getSettings()
        val base = max(s.nextWorkoutEpochDay ?: today(), today())
        settingsDao.upsert(s.copy(nextWorkoutEpochDay = base + 1))
    }

    suspend fun setDeload(want: Boolean) {
        val s = getSettings()
        // Общая разгрузка всего сразу; выключение — обратно к своим разгрузкам упражнений
        settingsDao.upsert(s.copy(deloadOverride = if (want) DeloadOverride.FORCE_ON else DeloadOverride.AUTO))
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
        syncShared(listOf(final))
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
        return edited.withState(ProgressionEngine.onStageChanged(edited.toState())).copy(deloadSessions = 0)
            .copy(leverageStage = newStage)
    }

    // ---------------- Первый запуск ----------------

    data class SetupDraft(val exerciseId: Long, val stage: String?, val weightKg: Float, val calibrate: Boolean, val heightCm: Int? = null)

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
                // Вес задан вручную — счёт плато с нуля
                bestTotal = null, stallRounds = 0, resetWeight = null, resetCount = 0, repShift = 0,
            ).let { if (it.isJump) it.copy(
                heightCm = d.heightCm?.let(ru.streetlift.tracker.domain.Jumps::snapHeight), weightKg = 0f, targetReps = 3, plateauCm = null,
                jumpBlock = 0, jumpBlockCm = null, jumpStep = 0, jumpRollback = false,
                jumpNextKg = null, jumpNextReps = null, jumpRetryKg = null,
            ) else it }
        }
        if (updated.isNotEmpty()) {
            programDao.updateExercises(updated)
            syncShared(updated)
        }
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
                manualRecords = statsDao.getManual(),
                runs = statsDao.getRuns(),
            )
        }
        return BackupJson.encode(data)
    }

    /** Полная замена базы содержимым JSON. Бросает исключение при неверном формате. */
    /**
     * Импорт бэкапа. Программа зашита в коде, поэтому дни/упражнения берутся из шаблона,
     * а веса, стадии и прогресс переносятся по названию. История — целиком.
     */
    suspend fun importJson(text: String) {
        val data = BackupJson.decode(text)
        db.withTransaction {
            sessionDao.clearSets()
            sessionDao.clearSessions()
            programDao.clearExercises()
            programDao.clearDays()
            programDao.clearStages()
            programDao.clearArchive()
            programDao.insertStages(DefaultTemplate.builtInStages())
            DefaultTemplate.seedProgram(programDao)

            val oldByName = data.exercises.associateBy { DefaultTemplate.renamed[it.name] ?: it.name }
            val validStages = DefaultTemplate.builtInStages().groupBy({ it.groupKey }, { it.name })
            val restored = programDao.getAllExercises().map { e ->
                val old = oldByName[e.name] ?: return@map e
                val stage = old.leverageStage?.takeIf { st -> e.stageGroup != null && st in validStages[e.stageGroup].orEmpty() }
                e.withState(old.toState()).copy(
                    leverageStage = stage ?: e.leverageStage,
                    plannedSets = old.plannedSets.coerceIn(maxOf(1, e.minSets - 1), e.maxSets),
                    targetReps = old.targetReps.coerceIn(e.minReps, e.maxReps),
                ).let { ne ->
                    // Прыжки: высота и блок пробивки; старая высота с шагом 5 см — вниз до кратной 15
                    val js = old.jumpState
                    if (!ne.isJump || js == null) ne
                    else ne.withJump(
                        if (js.heightCm % ru.streetlift.tracker.domain.Jumps.STEP_CM != 0 || (js.plateauCm ?: 0) % ru.streetlift.tracker.domain.Jumps.STEP_CM != 0) {
                            ru.streetlift.tracker.domain.Jumps.State(ru.streetlift.tracker.domain.Jumps.snapHeight(js.heightCm))
                        } else js,
                    ).copy(mode = old.mode)
                }
            }
            programDao.updateExercises(restored)

            // Ссылки истории на упражнения — по названию в новой программе
            val newIdByName = restored.associate { it.name to it.id }
            val dayIdByName = programDao.getDays().associate { it.name to it.id }
            sessionDao.insertSessions(
                data.sessions.map { ses ->
                    val dn = DefaultTemplate.renamedDays[ses.dayName] ?: ses.dayName
                    ses.copy(dayName = dn, dayId = dayIdByName[dn])
                },
            )
            sessionDao.insertSets(
                data.sets.map { st ->
                    val name = DefaultTemplate.renamed[st.exerciseName] ?: st.exerciseName
                    st.copy(exerciseId = newIdByName[name], exerciseName = name)
                },
            )
            programDao.insertArchives(data.stageArchive)
            statsDao.clearManual()
            statsDao.insertManuals(data.manualRecords.map { it.copy(id = 0, exerciseName = DefaultTemplate.renamed[it.exerciseName] ?: it.exerciseName) })
            statsDao.clearRuns()
            statsDao.insertRuns(data.runs.map { it.copy(id = 0) })
            val days = programDao.getDays()
            settingsDao.upsert(
                data.settings.copy(
                    id = 1,
                    setupDone = true,
                    // Плановая разгрузка всегда на 5-м цикле, даже если в файле другое число
                    deloadEvery = SettingsEntity.DELOAD_EVERY,
                    priorityMode = ru.streetlift.tracker.domain.PriorityMode.AUTO,
                    currentDayIndex = data.settings.currentDayIndex.coerceIn(0, days.size - 1),
                ),
            )
        }
    }
}


/**
 * Индекс следующего дня цикла. На выходе на пик — день с пиковым упражнением,
 * ещё не сделанным в текущей фазе (чтобы пропуски не выбивали пиковые упражнения).
 */
fun plannedDayIndex(
    s: SettingsEntity,
    days: List<DayEntity>,
    exs: List<ExerciseEntity>,
    sessions: List<SessionEntity>,
    today: Long,
): Int {
    if (days.isEmpty()) return 0
    val base = s.currentDayIndex.coerceIn(0, days.size - 1)
    if (s.manualDay) return base
    val peak = s.peakEpochDay ?: return base
    val phase = Peaking.phaseOn(peak, maxOf(today, s.nextWorkoutEpochDay ?: today), s.peakSpanDays)
    if (phase == PeakPhase.NONE) return base
    val zone = java.time.ZoneId.systemDefault()
    val doneDays = sessions
        .filter { it.finishedAt != null && it.peakPhase == phase.name }
        .filter { java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().toEpochDay() >= peak - Peaking.FULL_SPAN }
        .mapNotNull { it.dayId }
        .toSet()
    val done = exs.filter { it.dayId in doneDays }.map { it.id }.toSet()
    val sorted = days.sortedBy { it.position }
    val perDay = sorted.map { d -> exs.filter { it.dayId == d.id }.map { it.id }.toSet() }
    val pos = days[base].position
    val picked = Peaking.pickPeakDay(sorted.indexOfFirst { it.position == pos }.coerceAtLeast(0), perDay, s.peakIds, done)
    return days.indexOfFirst { it.id == sorted[picked].id }.coerceAtLeast(0)
}


/** Подходы тренировок и ручные рекорды — в общий список для рекордов и графиков. */
fun recordEntries(
    sets: List<SetLogEntity>,
    manual: List<ru.streetlift.tracker.data.db.ManualRecordEntity>,
): List<ru.streetlift.tracker.domain.Records.Entry> {
    val zone = java.time.ZoneId.systemDefault()
    return sets.filter { it.reps > 0 }.map {
        val jump = it.heightCm != null
        ru.streetlift.tracker.domain.Records.Entry(
            name = it.exerciseName + when (it.side) {
                "L" -> " · левая"
                "R" -> " · правая"
                else -> ""
            },
            stage = it.leverageStage,
            weight = it.weightKg,
            // Прыжки: рекорд — высота при весе
            value = if (jump) it.heightCm!! else it.reps,
            isSeconds = it.isSeconds,
            isHeight = jump,
            epochDay = java.time.Instant.ofEpochMilli(it.loggedAt).atZone(zone).toLocalDate().toEpochDay(),
            sessionId = it.sessionId,
        )
    } + manual.map {
        ru.streetlift.tracker.domain.Records.Entry(
            name = it.exerciseName,
            stage = it.leverageStage,
            weight = it.weightKg,
            value = it.value,
            isSeconds = it.isSeconds,
            epochDay = it.epochDay,
            manualId = it.id,
        )
    }
}
