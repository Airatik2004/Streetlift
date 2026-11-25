package ru.streetlift.tracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.CompKind
import ru.streetlift.tracker.domain.Competition
import ru.streetlift.tracker.domain.Discipline
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.PeakPhase
import ru.streetlift.tracker.domain.Power
import ru.streetlift.tracker.domain.Peaking
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.domain.RunPlan
import ru.streetlift.tracker.domain.RunStats
import ru.streetlift.tracker.domain.SessionPlan
import ru.streetlift.tracker.ui.common.Tag
import ru.streetlift.tracker.ui.theme.DeloadGreen
import ru.streetlift.tracker.ui.theme.WarnOrange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** plan == null — упражнение в этот день пропускается (подводка). */
data class ExercisePreview(val exercise: ExerciseEntity, val plan: SessionPlan?, val powerLabel: String? = null)

data class HomeUiState(
    val loading: Boolean = true,
    val settings: SettingsEntity = SettingsEntity(),
    val days: List<DayEntity> = emptyList(),
    val nextDay: DayEntity? = null,
    val nextDayIndex: Int = 0,
    val previews: List<ExercisePreview> = emptyList(),
    val globalDeload: Boolean = false,
    val activeSession: SessionEntity? = null,
    val lastFinished: SessionEntity? = null,
    val showWeightsHint: Boolean = false,
    /** Подводка: фаза на день следующей тренировки, дней до старта, все упражнения для выбора. */
    val phase: PeakPhase = PeakPhase.NONE,
    val daysToStart: Long? = null,
    val allExercises: List<ExerciseEntity> = emptyList(),
    /** Пропущено дней после запланированной даты. */
    val missedDays: Long = 0,
    /** День выбран не по порядку — чтобы не выпало пиковое упражнение. */
    val peakDayShifted: Boolean = false,
    /** Первая тренировка подготовки после паузы — на подход меньше. */
    val afterPause: Boolean = false,
)

class HomeViewModel(private val repo: WorkoutRepository) : ViewModel() {

    /** Пауэр: движения следующей тренировки (их в программе нет — считаются по истории подготовки). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val powerItems = combine(repo.settings, repo.finishedSessions) { s, _ -> s }
        .mapLatest { s ->
            if (s.power == null) emptyList() else {
                val today = LocalDate.now().toEpochDay()
                repo.powerPlans(Peaking.phaseOn(s.peakEpochDay, maxOf(today, s.nextWorkoutEpochDay ?: today), s.peakSpanDays))
            }
        }

    val state: StateFlow<HomeUiState> = combine(
        combine(repo.settings, repo.days, repo.exercises, repo.activeSession, repo.lastFinishedSession) { a, b, c, d, e ->
            listOf(a, b, c, d, e)
        },
        repo.finishedSessions,
        repo.lastAlt,
        powerItems,
    ) { base, finished, lastAlt, powerList ->
        @Suppress("UNCHECKED_CAST")
        val s = base[0] as SettingsEntity
        @Suppress("UNCHECKED_CAST")
        val days = base[1] as List<DayEntity>
        @Suppress("UNCHECKED_CAST")
        val exs = base[2] as List<ExerciseEntity>
        val active = base[3] as SessionEntity?
        val last = base[4] as SessionEntity?
        val today = LocalDate.now().toEpochDay()
        val baseIdx = if (days.isEmpty()) 0 else s.currentDayIndex.coerceIn(0, days.size - 1)
        val idx = ru.streetlift.tracker.data.repo.plannedDayIndex(s, days, exs, finished, today)
        val day = days.getOrNull(idx)
        val workoutDay = maxOf(today, s.nextWorkoutEpochDay ?: today)
        val phase = Peaking.phaseOn(s.peakEpochDay, workoutDay, s.peakSpanDays)
        val global = s.forcedDeload && phase == PeakPhase.NONE
        val missed = s.missedDays(today)
        val afterPause = phase == PeakPhase.PRE_PEAK && missed >= Peaking.PAUSE_DAYS
        val peakIds = s.peakIds
        val power = s.power != null && phase != PeakPhase.NONE
        val powerPreviews = if (!power) emptyList() else powerList.mapIndexed { i, p ->
            ExercisePreview(ru.streetlift.tracker.data.db.powerExercise(p.lift, day?.id ?: 0L, -10 + i), p.plan, p.label)
        }
        val previews = powerPreviews + if (day == null) emptyList() else ru.streetlift.tracker.domain.ProgramOrder.order(
            day.position, exs.filter { it.dayId == day.id && it.activeInSlot(ru.streetlift.tracker.data.db.nextAltSlot(lastAlt)) }.sortedBy { it.position }, s.priority,
        ) { phase != PeakPhase.NONE && it.id in peakIds }
            .map { ExercisePreview(it, (if (power) {
                    // Пауэр: основная программа в поддержке, в снижении и тонусе — не делается
                    if (phase != PeakPhase.PRE_PEAK) null
                    else Peaking.resolve(it.toConfig(), it.toState(), it.type, it.plannedDeload(s.deloadEvery, false), PeakPhase.NONE, false, s.bodyWeightKg)
                        ?.let { p -> if (it.isJump) p else ru.streetlift.tracker.domain.RunPlan.support(p) }
                } else Peaking.resolve(it.toConfig(), it.toState(), it.type, phase == PeakPhase.NONE && it.plannedDeload(s.deloadEvery, global), phase, it.id in peakIds, s.bodyWeightKg, s.compTarget(it.id, it.name)))
                ?.let { p -> if (s.endurance && !it.isJump) ru.streetlift.tracker.domain.RunPlan.support(p) else p }
                ?.let { p -> if (afterPause) p.copy(sets = (p.sets - 1).coerceAtLeast(1)) else p }
                ?.let { p -> if (it.isJump) ru.streetlift.tracker.domain.jumpPlan(it.jumpState, p) else p }) }
        val dayOrder = days.associate { it.id to it.position }
        HomeUiState(
            loading = false,
            settings = s,
            days = days,
            nextDay = day,
            nextDayIndex = idx,
            previews = previews,
            globalDeload = global,
            activeSession = active,
            lastFinished = last,
            showWeightsHint = false,
            phase = phase,
            daysToStart = s.peakEpochDay?.let { it - today },
            allExercises = exs.sortedWith(compareBy({ dayOrder[it.dayId] ?: 0 }, { it.position })),
            missedDays = missed,
            peakDayShifted = idx != baseIdx,
            afterPause = afterPause,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Общая разгрузка на цикл и сразу старт (по порядку, чтобы тренировка уже была разгрузочной). */
    fun startWithDeload(onStarted: (Long) -> Unit) {
        val day = state.value.nextDay ?: return
        viewModelScope.launch {
            repo.setDeload(true)
            onStarted(repo.startSession(day.id))
        }
    }

    fun start(readiness: Int, onStarted: (Long) -> Unit) {
        val day = state.value.nextDay ?: return
        viewModelScope.launch { onStarted(repo.startSession(day.id, readiness)) }
    }

    fun skipDay() = viewModelScope.launch { repo.skipDay() }
    fun selectDay(index: Int) = viewModelScope.launch { repo.selectDay(index) }
    fun postpone() = viewModelScope.launch { repo.postpone() }
    fun prepone() = viewModelScope.launch { repo.prepone() }
    fun undoSkip() = viewModelScope.launch { repo.undoSkip() }
    fun setDeload(want: Boolean) = viewModelScope.launch { repo.setDeload(want) }
    fun setPeak(epochDay: Long, ids: Set<Long>, bw: Float?) = viewModelScope.launch { repo.setPeak(epochDay, ids, bw) }
    fun clearPeak() = viewModelScope.launch { repo.clearPeak() }
    fun setPowerPeak(epochDay: Long, kind: Power.Kind, refs: Map<Power.Lift, Power.Ref>, bw: Float?) =
        viewModelScope.launch { repo.setPowerPeak(epochDay, kind, refs, bw) }
    suspend fun powerEstimate(kind: Power.Kind, refs: Map<Power.Lift, Power.Ref>, target: Long?) = repo.powerEstimate(kind, refs, target)
    fun setCompetitionPeak(epochDay: Long, disc: String, bw: Float?, birth: Long?) =
        viewModelScope.launch { repo.setCompetitionPeak(epochDay, disc, bw, birth) }
    suspend fun estimate(d: Discipline, bw: Float?): Competition.Estimate? = repo.competitionEstimate(d, bw)
    suspend fun compForecast(d: Discipline, bw: Float, target: Long) = repo.competitionForecast(d, bw, target)

    val runData: StateFlow<List<RunPlan.RunData>> = repo.runData.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun setRunPlan(dist: RunPlan.Dist, race: Long, goalSec: Int?, perWeek: Int) =
        viewModelScope.launch { repo.setRunPlan(dist, race, goalSec, perWeek) }

    init {
        viewModelScope.launch {
            repo.checkPeakExpired()
            repo.checkRunPlan()
        }
    }
}

private val ruLocale: Locale = Locale.forLanguageTag("ru")
private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", ruLocale)
private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", ruLocale)

fun formatNextDate(epochDay: Long?): String {
    val today = LocalDate.now().toEpochDay()
    val d = epochDay ?: today
    return when {
        d <= today -> "сегодня"
        d == today + 1 -> "завтра"
        else -> LocalDate.ofEpochDay(d).format(dayFmt)
    }
}

fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFmt)

@Composable
fun HomeScreen(repo: WorkoutRepository, onOpenWorkout: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(repo) } })
    val ui by vm.state.collectAsStateWithLifecycle()
    val runData by vm.runData.collectAsStateWithLifecycle()
    var dayMenu by remember { mutableStateOf(false) }
    var peakDialog by remember { mutableStateOf(false) }
    // Перед стартом — самочувствие; второй «плохо» подряд — предложить разгрузку на цикл
    var askReadiness by remember { mutableStateOf(false) }
    var offerDeload by remember { mutableStateOf(false) }
    if (askReadiness) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askReadiness = false },
            title = { Text("Как самочувствие?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Сон, усталость, общее состояние.", style = MaterialTheme.typography.bodySmall)
                    listOf(
                        Triple(ru.streetlift.tracker.data.repo.READINESS_GOOD, "Хорошо", "план как есть"),
                        Triple(ru.streetlift.tracker.data.repo.READINESS_MID, "Средне", "на подход меньше, запас +1"),
                        Triple(ru.streetlift.tracker.data.repo.READINESS_BAD, "Плохо", "как разгрузка, веса не меняются"),
                    ).forEach { (v, t, hint) ->
                        OutlinedButton(
                            onClick = {
                                askReadiness = false
                                if (v == ru.streetlift.tracker.data.repo.READINESS_BAD &&
                                    ui.lastFinished?.readiness == ru.streetlift.tracker.data.repo.READINESS_BAD && !ui.globalDeload
                                ) offerDeload = true
                                else vm.start(v, onOpenWorkout)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(t, style = MaterialTheme.typography.titleSmall)
                                Text(hint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askReadiness = false }) { Text("Отмена") } },
        )
    }
    if (offerDeload) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { offerDeload = false },
            title = { Text("Второй раз подряд плохо") },
            text = { Text("Похоже на накопленную усталость. Взять разгрузку на весь этот цикл?") },
            confirmButton = {
                TextButton(onClick = {
                    offerDeload = false
                    vm.startWithDeload(onOpenWorkout)
                }) { Text("Да, разгрузка") }
            },
            dismissButton = {
                TextButton(onClick = {
                    offerDeload = false
                    vm.start(ru.streetlift.tracker.data.repo.READINESS_BAD, onOpenWorkout)
                }) { Text("Нет, только сегодня") }
            },
        )
    }
    if (peakDialog) {
        PeakDialog(
            // Новая подводка — дата не подставляется, выбирается вручную
            settings = ui.settings,
            exercises = ui.allExercises,
            dayNames = ui.days.associate { it.id to it.name },
            estimate = vm::estimate,
            runData = runData,
            onSaveRun = { dist, d, goal, per ->
                peakDialog = false
                vm.setRunPlan(dist, d, goal, per)
            },
            onDismiss = { peakDialog = false },
            onSaveRecords = { d, ids, bw ->
                peakDialog = false
                vm.setPeak(d, ids, bw)
            },
            powerEstimate = vm::powerEstimate,
            onSavePower = { d, kind, refs, bw ->
                peakDialog = false
                vm.setPowerPeak(d, kind, refs, bw)
            },
            onSaveCompetition = { d, disc, bw, birth ->
                peakDialog = false
                vm.setCompetitionPeak(d, disc, bw, birth)
            },
        )
    }
    val s = ui.settings

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Дневник тренировок", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                // Разгрузка у каждого упражнения своя — в шапке мезоцикл (жим/тяга) и цикл в нём 1..5
                val extra = when {
                    ui.phase != PeakPhase.NONE -> " · выход на пик"
                    ui.globalDeload -> " · разгрузка всего цикла"
                    else -> ""
                }
                Text(
                    ru.streetlift.tracker.domain.Priority.cycleLabel(s.priority, s.cycleInMeso, s.deloadEvery) +
                        " · день ${ui.nextDayIndex + 1} из ${ui.days.size}$extra",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (ui.globalDeload) DeloadGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Выход на пик: пропуски сокращают фазы — предупреждение, выбор дня, облегчение после паузы
                if (ui.phase != PeakPhase.NONE) {
                    val left = ui.daysToStart?.let { Peaking.daysLeftInPhase(it, s.peakSpanDays) } ?: 0L
                    if (ui.missedDays > 0) {
                        Text(
                            "Пропущено ${ui.missedDays} ${plural(ui.missedDays.toInt(), "день", "дня", "дней")} — подготовка сокращена: " +
                                "на «${ui.phase.title}» осталось $left ${plural(left.toInt(), "день", "дня", "дней")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarnOrange,
                        )
                    }
                    if (ui.peakDayShifted) {
                        Text(
                            "День выбран не по порядку — чтобы пиковое упражнение не выпало из фазы",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.afterPause) {
                        Text(
                            "После паузы — на подход меньше в каждом упражнении",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // --- Прогноз следующей тренировки ---
        ui.nextDay?.let { day ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (ui.activeSession != null) "Текущая" else "Следующая: ${formatNextDate(s.nextWorkoutEpochDay)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(day.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            // Во время тренировки день не меняем
                            if (ui.activeSession == null) Box {
                                TextButton(onClick = { dayMenu = true }) {
                                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Другой день")
                                }
                                DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                                    ui.days.forEachIndexed { i, d ->
                                        DropdownMenuItem(
                                            text = { Text(d.name) },
                                            onClick = {
                                                dayMenu = false
                                                vm.selectDay(i)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (day.alternating && ui.previews.size > 1) Tag("Чередующиеся подходы")

                        ui.previews.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider()
                            PreviewRow(p, ui.globalDeload, s.deloadEvery, ui.phase == PeakPhase.NONE)
                        }
                        if (ui.previews.isEmpty()) {
                            Text("В этом дне пока нет упражнений — добавьте их во вкладке «Цикл».")
                        }

                        if (ui.phase == PeakPhase.REST) {
                            Tag("Полный отдых перед стартом", DeloadGreen)
                            Text(
                                "Если всё же тренироваться — лёгкий тонус пиковых упражнений.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val active = ui.activeSession
                        Button(
                            onClick = { if (active != null) onOpenWorkout(active.id) else askReadiness = true },
                            enabled = active != null || ui.previews.any { it.plan != null },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (active != null) "Продолжить" else "Начать тренировку")
                        }
                        val today = LocalDate.now().toEpochDay()
                        val postponed = (s.nextWorkoutEpochDay ?: today) > today
                        if (active == null && (postponed || s.canUndoSkip)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (postponed) {
                                    TextButton(onClick = { vm.prepone() }, modifier = Modifier.weight(1f)) { Text("−1 день") }
                                }
                                if (s.canUndoSkip) {
                                    TextButton(onClick = { vm.undoSkip() }, modifier = Modifier.weight(1f)) {
                                        Text("Вернуть пропущенное")
                                    }
                                }
                            }
                        }
                        if (active == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.postpone() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("+1 день")
                            }
                            OutlinedButton(onClick = { vm.skipDay() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Пропустить")
                            }
                        }
                    }
                }
            }
        }

        // --- Подводка к старту (под карточкой тренировки) ---
        item {
            if (ui.settings.endurance) {
                RunPlanCard(ui.settings, runData, onEdit = { peakDialog = true }, onClear = { vm.clearPeak() })
            } else {
                PeakCard(
                    ui = ui,
                    forecast = vm::compForecast,
                    powerEstimate = vm::powerEstimate,
                    onEdit = { peakDialog = true },
                    onClear = { vm.clearPeak() },
                )
            }
        }

        if (ui.showWeightsHint) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "Совет: перед первой тренировкой задайте стартовые веса во вкладке «Цикл». " +
                            "Вес знаковый: +24 — гиря/жилет, 0 — свой вес, −15 — резина/облегчение.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ui.lastFinished?.let { last ->
            if (last.summary.isNotBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Итоги прошлой тренировки", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${last.dayName} · ${formatDateTime(last.startedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(last.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(p: ExercisePreview, globalDeload: Boolean, deloadEvery: Int, normalPhase: Boolean) {
    val e = p.exercise
    val plan = p.plan
    if (plan == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Tag("Пропуск · выход на пик", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    p.powerLabel?.let { label ->
        // Пауэр: движение штанги на время подготовки
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Tag("Пауэр · $label", MaterialTheme.colorScheme.primary)
            }
            Text(
                "${Fmt.step(plan.weightKg)} кг · ${plan.sets} × ${plan.targetReps} · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (plan.isCalibration) {
                Text(
                    "Калибровка по ориентиру: сделайте подход и честно отметьте запас — по нему посчитаются веса подготовки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                plan.isPeakTarget -> Tag("Пик", MaterialTheme.colorScheme.primary)
                plan.isCalibration -> Tag("Калибровка", MaterialTheme.colorScheme.secondary)
                plan.isEmergencyDeload -> Tag("Авар. разгрузка", WarnOrange)
                plan.isDeload -> Tag("Разгрузка", DeloadGreen)
                normalPhase && !globalDeload && e.deloadDue(deloadEvery) && e.skipPlannedDeload == true -> Tag("Без разгрузки", DeloadGreen)
            }
        }
        e.leverageStage?.let {
            Text("Стадия: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (e.stageCeiling) {
            Tag("Потолок стадии достигнут — можно переходить на следующую стадию в редакторе цикла", WarnOrange)
        }
        if (plan.isCalibration) {
            Text(
                if (e.isJump) "${plan.sets} × ${plan.repsMin} · высота подбирается"
                else "${plan.sets} × ${Fmt.range(plan.repsMin, plan.repsMax)}${if (e.isSeconds) " с" else ""} · RIR ${plan.rirMin} · вес подбирается",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (e.isSeconds) "Подберите вес/стадию под коридор с запасом 2 с" else "Подберите вес/стадию под коридор с запасом 2 повторения",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (e.isJump) "${plan.heightCm} см · ${Fmt.weight(plan.weightKg)} · ${plan.sets} × ${plan.targetReps}"
                else "${Fmt.weight(plan.weightKg)} · ${plan.sets} × ${Fmt.range(plan.repsMin, plan.repsMax)}${if (e.isSeconds) " с" else ""} · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Цель: ${plan.targetReps}+ ${if (e.isSeconds) "с" else "повт."} в каждом подходе · ${e.type.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (normalPhase && !plan.isDeload && !plan.isEmergencyDeload) {
                ru.streetlift.tracker.ui.editor.deloadInText(e, deloadEvery)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}

private val peakDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

@Composable
private fun PeakCard(
    ui: HomeUiState,
    forecast: suspend (Discipline, Float, Long) -> Triple<Float, Float, Float>?,
    powerEstimate: suspend (Power.Kind, Map<Power.Lift, Power.Ref>, Long?) -> List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    val s = ui.settings
    val date = s.peakEpochDay
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Выход на пик", style = MaterialTheme.typography.titleMedium)
            if (date == null) {
                Text(
                    "Выберите дату старта и дисциплину (стритлифтинг или пауэрлифтинг) — за 22 дня план перестроится сам.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onEdit) { Text("Настроить выход на пик") }
            } else {
                val days = ui.daysToStart ?: 0
                Text(
                    "Старт: ${LocalDate.ofEpochDay(date).format(peakDateFmt)} · " +
                        if (days == 0L) "сегодня" else "через $days ${plural(days.toInt(), "день", "дня", "дней")}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Tag(
                    if (ui.phase == PeakPhase.NONE) "Обычный цикл — выход на пик начнётся за 22 дня" else ui.phase.title,
                    if (ui.phase == PeakPhase.REST) DeloadGreen else MaterialTheme.colorScheme.primary,
                )
                val names = ui.allExercises.filter { it.id in s.peakIds }.map { it.name }
                val disc = s.discipline
                Text(
                    when {
                        s.power != null -> "Пауэрлифтинг: ${s.power!!.title}"
                        disc != null -> "Стритлифтинг: ${disc.title}"
                        names.isEmpty() -> "Пиковые упражнения не выбраны"
                        else -> "Пик: " + names.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                // Соревнования: прогноз результата к старту по тренду
                val bw = s.bodyWeightKg
                if (disc != null && bw != null) {
                    val f by androidx.compose.runtime.produceState<Triple<Float, Float, Float>?>(null, disc.id, bw, date) {
                        value = forecast(disc, bw, date)
                    }
                    f?.let { (lo, mid, hi) ->
                        fun v(x: Float) = if (disc.kind == CompKind.MAX) Fmt.weight(x) else "${x.toInt()}"
                        val rank = disc.table?.evaluate(bw, mid)?.first?.first?.title
                        Text(
                            "Прогноз к старту: ${v(mid)} (${v(lo)}–${v(hi)})" + (rank?.let { " · разряд $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                s.power?.let { k ->
                    val est by androidx.compose.runtime.produceState<List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>>(emptyList(), k, s.powerRefs, date, ui.lastFinished?.id) {
                        value = powerEstimate(k, s.powerRefMap, date)
                    }
                    if (est.isNotEmpty()) PowerSummary(k, est, s.bodyWeightKg)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) { Text("Изменить") }
                    TextButton(onClick = onClear) { Text("Сбросить") }
                }
            }
        }
    }
}

/**
 * Выход на пик: три вкладки, выбор только внутри одной.
 * «Стритлифтинг» — дисциплины ISF/WSF, «Пауэрлифтинг» — движения штанги (WRPF), «Выносливость» — бег.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PeakDialog(
    settings: SettingsEntity,
    exercises: List<ExerciseEntity>,
    dayNames: Map<Long, String>,
    estimate: suspend (Discipline, Float?) -> Competition.Estimate?,
    runData: List<RunPlan.RunData>,
    onSaveRun: (RunPlan.Dist, Long, Int?, Int) -> Unit,
    onDismiss: () -> Unit,
    onSaveRecords: (Long, Set<Long>, Float?) -> Unit,
    onSaveCompetition: (Long, String, Float?, Long?) -> Unit,
    powerEstimate: suspend (Power.Kind, Map<Power.Lift, Power.Ref>, Long?) -> List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>,
    onSavePower: (Long, Power.Kind, Map<Power.Lift, Power.Ref>, Float?) -> Unit,
) {
    val initialDay = settings.peakEpochDay
    val initialIds = settings.peakIds
    val wasComp = settings.peakMode == SettingsEntity.PEAK_COMPETITION
    val wasPower = settings.power != null
    var tab by remember { mutableStateOf(if (settings.endurance) 2 else if (wasPower) 3 else 0) }
    var powerKind by remember { mutableStateOf(settings.power) }
    // Ориентиры текстом «80 × 5» по движениям
    var powerRefs by remember {
        mutableStateOf(settings.powerRefMap.mapValues { (_, r) -> "${Fmt.step(r.weightKg)} × ${r.reps}" })
    }
    var runDist by remember { mutableStateOf(settings.runDist) }
    var goal by remember { mutableStateOf(settings.runGoalSec?.let { RunPlan.time(it.toDouble()) } ?: "") }
    var perWeek by remember { mutableStateOf(settings.runPerWeek ?: RunPlan.DEFAULT_RUNS) }
    var day by remember { mutableStateOf<Long?>(if (settings.endurance) settings.runRace else initialDay) }
    var bw by remember { mutableStateOf(settings.bodyWeightKg?.let { Fmt.step(it) } ?: "") }
    var birth by remember { mutableStateOf(settings.birthDate) }
    var ids by remember { mutableStateOf(if (wasComp) emptySet() else initialIds) }
    var discId by remember { mutableStateOf(if (wasComp) settings.compDiscipline else null) }
    var kind by remember { mutableStateOf(Discipline.byId(settings.compDiscipline)?.kind ?: CompKind.MAX) }
    var picking by remember { mutableStateOf(false) }
    var pickingBirth by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val minDay = today + Peaking.MIN_SPAN
    // Уже выставленную дату можно оставить, новую — не ближе 7 дней
    val d = day
    val dayOk = d != null && (d >= minDay || d == initialDay && initialDay != null || settings.endurance && d == settings.runRace)
    val bwValue = bw.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
    // Возраст — на день старта (пока дата не выбрана — на сегодня)
    val age = birth?.let { SettingsEntity.ageOn(it, d ?: today) }

    if (pickingBirth) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = birth?.let { it * 86_400_000L },
            initialDisplayMode = androidx.compose.material3.DisplayMode.Input,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis / 86_400_000L <= today
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickingBirth = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { birth = it / 86_400_000L }
                    pickingBirth = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingBirth = false }) { Text("Отмена") } },
        ) { androidx.compose.material3.DatePicker(state = state, title = { Text("Дата рождения", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
        return
    }

    if (picking) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = d?.let { maxOf(it, minDay) * 86_400_000L },
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis / 86_400_000L >= minDay
                override fun isSelectableYear(year: Int): Boolean = year >= LocalDate.ofEpochDay(minDay).year
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = it / 86_400_000L }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { androidx.compose.material3.DatePicker(state = state) }
        return
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выход на пик") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Пик — только к соревновательным движениям (вкладка «Рекорды» убрана, индекс 1 не используется)
                    listOf(0 to "Стритлифтинг", 3 to "Пауэрлифтинг", 2 to "Выносливость").forEach { (i, t) ->
                        androidx.compose.material3.FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                    }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (d == null) "Выбрать дату" else "Дата: " + LocalDate.ofEpochDay(d).format(peakDateFmt))
                }
                val left = (d ?: today) - today
                Text(
                    when {
                        d == null -> "Выберите дату пика. Ближе 7 дней выбрать нельзя."
                        !dayOk -> "Ближе 7 дней выбрать нельзя — выберите другую дату."
                        tab == 2 && left > RunPlan.MAX_WEEKS * 7L -> "Больше года — прогноз на такой срок не даём, выберите дату ближе."
                        tab == 2 -> "Дата забега. План — с сегодняшнего дня до неё."
                        left < Peaking.FULL_SPAN -> "До пика $left ${plural(left.toInt(), "день", "дня", "дней")} — меньше 22, подготовка будет ускоренной: отдых и тонус полностью, остальное сокращается. Ближе 7 дней выбрать нельзя."
                        else -> "Полная подготовка начнётся за 22 дня до даты. Ближе 7 дней выбрать нельзя."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d != null && (!dayOk || (tab != 2 && left < Peaking.FULL_SPAN) || (tab == 2 && left > RunPlan.MAX_WEEKS * 7L))) WarnOrange
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tab == 2) {
                    EnduranceTab(
                        runData = runData, day = d, dist = runDist, onDist = { runDist = it },
                        goal = goal, onGoal = { goal = it }, perWeek = perWeek, onPerWeek = { perWeek = it },
                        onDay = { day = it },
                    )
                    return@Column
                }
                androidx.compose.material3.OutlinedTextField(
                    value = bw,
                    onValueChange = { bw = it },
                    label = { Text("Свой вес, кг") },
                    singleLine = true,
                    isError = bw.isNotBlank() && bwValue == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tab == 3) {
                    PowerTab(
                        kind = powerKind, onKind = { powerKind = it },
                        refs = powerRefs, onRef = { l, t -> powerRefs = powerRefs + (l to t) },
                        bodyWeight = bwValue, day = d, estimate = powerEstimate,
                    )
                    return@Column
                }
                if (tab == 1) {
                    Text(
                        "Упражнения для выхода на пик (остальные — фоновые):",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    var lastDay: Long? = null
                    exercises.forEach { e ->
                        if (e.dayId != lastDay) {
                            lastDay = e.dayId
                            Text(
                                dayNames[e.dayId].orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = e.id in ids,
                                onCheckedChange = { on -> ids = if (on) ids + e.id else ids - e.id },
                            )
                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    return@Column
                }
                // ----- Соревнования -----
                OutlinedButton(onClick = { pickingBirth = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (birth == null) "Дата рождения" else "Дата рождения: " + LocalDate.ofEpochDay(birth!!).format(peakDateFmt))
                }
                Text(
                    if (age == null) "Без даты рождения показаны все дивизионы"
                    else "Возраст ${if (d != null) "на день старта" else "сегодня"}: $age",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompKind.entries.forEach { k ->
                        androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title) })
                    }
                }
                Discipline.all.filter { it.kind == kind && it.fits(age) }.forEach { disc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = discId == disc.id, onClick = { discId = disc.id })
                        Column(Modifier.weight(1f)) {
                            Text(disc.title, style = MaterialTheme.typography.bodyMedium)
                            val div = disc.divisions.filter { it != ru.streetlift.tracker.domain.Division.ALL }
                            Text(
                                (disc.table?.federation ?: "без нормативов") +
                                    if (div.isNotEmpty()) " · " + div.joinToString(", ") { it.title } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val disc = Discipline.byId(discId)?.takeIf { it.kind == kind }
                if (disc != null) {
                    CompInfo(disc, bwValue, estimate)
                }
            }
        },
        confirmButton = {
            val fitness = RunPlan.fitness(runData, today)
            val ok = dayOk && when (tab) {
                0 -> Discipline.byId(discId)?.kind == kind
                1 -> true
                3 -> powerKind?.let { k -> k.lifts.all { Power.parseRef(powerRefs[it].orEmpty()) != null } } == true
                else -> runDist != null && fitness != null && d != null && d - today <= RunPlan.MAX_WEEKS * 7L
            }
            TextButton(
                onClick = {
                    if (d == null) return@TextButton
                    when (tab) {
                        0 -> discId?.let { onSaveCompetition(d, it, bwValue, birth) }
                        1 -> onSaveRecords(d, ids, bwValue)
                        3 -> powerKind?.let { k ->
                            onSavePower(d, k, k.lifts.associateWith { Power.parseRef(powerRefs[it].orEmpty())!! }, bwValue)
                        }
                        else -> runDist?.let { dist ->
                            // Цель не задана — середина реалистичного коридора
                            val g = RunPlan.parseTime(goal) ?: fitness?.let { RunPlan.corridor(dist, it, perWeek, d - today).midSec.toInt() }
                            onSaveRun(dist, d, g, perWeek)
                        }
                    }
                },
                enabled = ok,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Вкладка «Пауэр»: одно движение или сумма, ориентир по каждому движению («80 × 5»),
 * оценка максимума, разряд WRPF (где есть таблица) и прогноз.
 */
@Composable
private fun PowerTab(
    kind: Power.Kind?,
    onKind: (Power.Kind) -> Unit,
    refs: Map<Power.Lift, String>,
    onRef: (Power.Lift, String) -> Unit,
    bodyWeight: Float?,
    day: Long?,
    estimate: suspend (Power.Kind, Map<Power.Lift, Power.Ref>, Long?) -> List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>,
) {
    Text(
        "Подготовка штангой: на 22 дня до даты движения идут первыми в каждой тренировке. " +
            "Основная программа сначала в поддержке (меньше подходов, без прогрессии), за 14 дней до старта — полностью убирается.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    Power.Kind.entries.forEach { k ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = kind == k, onClick = { onKind(k) })
            Column(Modifier.weight(1f)) {
                Text(k.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (k) {
                        Power.Kind.TOTAL -> "жим каждую тренировку, присед и тяга через раз · цель — сумма"
                        Power.Kind.PUSH_PULL -> "жим каждую тренировку, тяга через раз · цель — сумма"
                        Power.Kind.DEADLIFT -> "каждую тренировку, тяжёлая раз в три"
                        else -> "каждую тренировку, тяжёлая и лёгкая по очереди"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val k = kind ?: return
    Text("Ориентир по каждому движению — вес и повторы, например «80 × 5». Первая тренировка движения — калибровка по нему.",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    k.lifts.forEach { lift ->
        val t = refs[lift].orEmpty()
        androidx.compose.material3.OutlinedTextField(
            value = t,
            onValueChange = { onRef(lift, it) },
            label = { Text("${lift.title}: вес × повторы") },
            singleLine = true,
            isError = t.isNotBlank() && Power.parseRef(t) == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val parsed = k.lifts.mapNotNull { l -> Power.parseRef(refs[l].orEmpty())?.let { l to it } }.toMap()
    val est by androidx.compose.runtime.produceState<List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>>(emptyList(), k, parsed, day) {
        value = estimate(k, parsed, day)
    }
    if (est.isEmpty()) return
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PowerSummary(k, est, bodyWeight)
        Text(
            "Оценка приблизительная: по формуле из подходов (вес, повторы, запас), до подготовки — по ориентиру.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Оценка максимума по движениям, сумма, разряд и прогноз к старту. */
@Composable
private fun PowerSummary(k: Power.Kind, est: List<ru.streetlift.tracker.data.repo.WorkoutRepository.PowerEstimate>, bodyWeight: Float?) {
    fun kg(v: Float) = "${Fmt.step(v)} кг"
    est.forEach { e ->
        Text(
            "${e.lift.title}: " + (e.now?.let { "сейчас ~${kg(it)} на раз" } ?: "нет данных") +
                (e.forecast?.let { (lo, mid, hi) -> " · к старту ${kg(mid)} (${kg(lo)}–${kg(hi)})" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val now = est.mapNotNull { it.now }.takeIf { it.size == est.size }?.sum()
    if (k.isTotal && now != null) {
        val f = est.mapNotNull { it.forecast?.second }.takeIf { it.size == est.size }?.sum()
        Text(
            "Сумма сейчас ~${kg(now)}" + (f?.let { " · к старту ~${kg(it)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    val table = k.table
    if (table == null) {
        Text(
            if (k == Power.Kind.SQUAT) "Отдельного норматива на присед у WRPF нет — только оценка и прогноз."
            else "Таблицы нормативов на сумму не нашли — только оценка и прогноз.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (bodyWeight == null) {
        Text("Укажите свой вес — по нему весовая категория и разряд.", style = MaterialTheme.typography.bodySmall, color = WarnOrange)
        return
    }
    val row = table.rowFor(bodyWeight)
    val result = est.first().forecast?.second ?: now
    if (result != null) {
        val (done, next) = table.evaluate(bodyWeight, result)
        Text(
            (done?.let { "Разряд${if (est.first().forecast != null) " по прогнозу" else ""}: ${it.first.title}" } ?: "Разряд пока не выполнен") +
                (next?.let { n -> " · до ${n.first.title}: ещё ${kg(n.second - result)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text("Нормативы, ${table.label(row)} (${table.federation}):", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    Text(
        table.ranks.zip(row.values).filter { it.second != null }.joinToString(" · ") { (r, v) -> "${r.title} ${Fmt.step(v!!)}" },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Оценка результата в дисциплине и нормативы своей весовой категории. */
@Composable
private fun CompInfo(disc: Discipline, bodyWeight: Float?, estimate: suspend (Discipline, Float?) -> Competition.Estimate?) {
    val est by androidx.compose.runtime.produceState<Competition.Estimate?>(null, disc.id, bodyWeight) {
        value = estimate(disc, bodyWeight)
    }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (bodyWeight == null) {
            Text("Укажите свой вес — по нему считаются оценка и весовая категория.", style = MaterialTheme.typography.bodySmall, color = WarnOrange)
            return@Column
        }
        val result = est?.result
        val unit = disc.unit
        fun fmt(v: Float) = if (disc.kind == CompKind.MAX) Fmt.weight(v) else "${v.toInt()} $unit"
        Text(
            if (result == null) "Сейчас: оценить не по чему — нет подходов за последние 8 недель"
            else "Сейчас примерно: ${fmt(result)} (оценка по тренировкам за 8 недель)",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (disc.isTotal) {
            est?.lifts?.forEach { l ->
                val v = if (disc.kind == CompKind.MAX) l.maxAdded?.let { Fmt.weight(it) } else l.reps?.let { "$it $unit" }
                Text("· ${l.lift.short}: ${v ?: "нет данных"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        val table = disc.table
        if (table == null) {
            Text("Нормативов для этой дисциплины нет — только цель и прогноз.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val row = table.rowFor(bodyWeight)
        if (result != null) {
            val (done, next) = table.evaluate(bodyWeight, result)
            Text(
                (done?.let { "Разряд: ${it.first.title}" } ?: "Разряд пока не выполнен") +
                    (next?.let { n -> " · до ${n.first.title}: ещё ${fmt(n.second - result).let { if (disc.kind == CompKind.MAX) it else it }}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("Нормативы, ${table.label(row)} (${table.federation}, проверено ${ru.streetlift.tracker.domain.Standards.CHECKED}):", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        Text(
            table.ranks.zip(row.values).filter { it.second != null }
                .joinToString(" · ") { (r, v) -> "${r.title} ${if (disc.kind == CompKind.MAX) Fmt.step(v!!) else v!!.toInt().toString()}" },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Оценка приблизительная: по формуле из ваших подходов (вес, повторы, запас). Точнее всего по тяжёлым подходам на 1–6 повторов и с соревновательным весом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val runDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("ru"))

/** Вкладка «Выносливость»: дистанция, цель, пробежек в неделю — программа считает реалистичность и план. */
@Composable
private fun EnduranceTab(
    runData: List<RunPlan.RunData>,
    day: Long?,
    dist: RunPlan.Dist?,
    onDist: (RunPlan.Dist) -> Unit,
    goal: String,
    onGoal: (String) -> Unit,
    perWeek: Int,
    onPerWeek: (Int) -> Unit,
    onDay: (Long) -> Unit,
) {
    val today = LocalDate.now().toEpochDay()
    Text("Дистанция:", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RunPlan.Dist.entries.forEach { dd ->
            androidx.compose.material3.FilterChip(selected = dist == dd, onClick = { onDist(dd) }, label = { Text(dd.title) })
        }
    }
    val v = RunPlan.fitness(runData, today)
    if (v == null) {
        Text(
            "Нет пробежек за 8 недель хотя бы на 1 км — оценить форму не по чему. Пробегите 3–5 км в полную силу, и программа построит план.",
            style = MaterialTheme.typography.bodySmall,
            color = WarnOrange,
        )
        return
    }
    if (dist == null) return
    Text("Сейчас примерно: ${RunPlan.time(RunPlan.predictSec(dist, v))} на ${dist.title}", style = MaterialTheme.typography.bodyMedium)
    // Пробежек в неделю: сначала предлагается максимум, меньше — план и цель пересчитываются
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Пробежек в неделю", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        val less = ru.streetlift.tracker.ui.common.rememberRepeatClick { onPerWeek((perWeek - 1).coerceAtLeast(1)) }
        val more = ru.streetlift.tracker.ui.common.rememberRepeatClick { onPerWeek((perWeek + 1).coerceAtMost(7)) }
        TextButton(onClick = less.onClick, interactionSource = less.source) { Text("−") }
        Text("$perWeek", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = more.onClick, interactionSource = more.source) { Text("+") }
    }
    androidx.compose.material3.OutlinedTextField(
        value = goal,
        onValueChange = { onGoal(it.filter { c -> c.isDigit() || c == ':' }.take(8)) },
        label = { Text("Желаемое время (мм:сс или ч:мм:сс)") },
        singleLine = true,
        isError = goal.isNotBlank() && RunPlan.parseTime(goal) == null,
        modifier = Modifier.fillMaxWidth(),
    )
    val goalSec = RunPlan.parseTime(goal)?.toDouble()
    if (day != null && day > today && day - today <= RunPlan.MAX_WEEKS * 7L) {
        val c = RunPlan.corridor(dist, v, perWeek, day - today)
        Text(
            "Реалистично к дате при $perWeek пробежках в неделю: ${RunPlan.time(c.fastSec)} – ${RunPlan.time(c.slowSec)} (в среднем ${RunPlan.time(c.midSec)})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when {
            goalSec == null -> Text("Цель не задана — возьмём середину коридора.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            goalSec < c.fastSec -> {
                Text("Цель быстрее реалистичного к этой дате — скорее всего, не получится.", style = MaterialTheme.typography.bodySmall, color = WarnOrange)
                TextButton(onClick = { onGoal(RunPlan.time(c.midSec)) }) { Text("Взять реалистичную: ${RunPlan.time(c.midSec)}") }
            }
            else -> Text("Цель реалистична.", style = MaterialTheme.typography.bodySmall, color = DeloadGreen)
        }
    }
    if (goalSec != null) {
        val w = RunPlan.weeksNeeded(dist, v, goalSec, perWeek)
        when {
            w == null -> Text("На эту цель при $perWeek пробежках в неделю нужно больше года — прогноз не даём.", style = MaterialTheme.typography.bodySmall, color = WarnOrange)
            w == 0 -> Text("Уже по силам — можно ставить старт хоть через неделю.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val target = today + maxOf(w * 7L, Peaking.MIN_SPAN.toLong())
                Text("На эту цель нужно примерно $w ${plural(w, "неделю", "недели", "недель")}.", style = MaterialTheme.typography.bodySmall)
                if (day != target) TextButton(onClick = { onDay(target) }) { Text("Поставить дату: ${LocalDate.ofEpochDay(target).format(peakDateFmt)}") }
            }
        }
    }
    if (day != null && day > today && day - today <= RunPlan.MAX_WEEKS * 7L) {
        val items = RunPlan.build(today, day, dist, perWeek, RunPlan.recentWeeklyKm(runData, today))
        val firstWeek = items.filter { it.epochDay < today + 7 }
        Text(
            "План: ${items.size} ${plural(items.size, "пробежка", "пробежки", "пробежек")} до старта, силовые на это время — в поддержке (меньше подходов, без прогрессии).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        firstWeek.forEach {
            Text("· ${LocalDate.ofEpochDay(it.epochDay).format(runDateFmt)} — ${it.title()}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Рост формы — модель с допущениями, поэтому цель — коридор. Он сужается по мере тренировок; пропуск — план перестраивается.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Карточка подготовки к забегу: цель, коридор, ближайшие пробежки с темпами. */
@Composable
private fun RunPlanCard(s: SettingsEntity, runData: List<RunPlan.RunData>, onEdit: () -> Unit, onClear: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    val dist = s.runDist ?: return
    val race = s.runRace ?: return
    val v = RunPlan.fitness(runData, today)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Подготовка к забегу", style = MaterialTheme.typography.titleMedium)
            val left = race - today
            Text(
                "${dist.title} · ${LocalDate.ofEpochDay(race).format(peakDateFmt)} · " +
                    if (left == 0L) "сегодня" else "через $left ${plural(left.toInt(), "день", "дня", "дней")}",
                style = MaterialTheme.typography.bodyLarge,
            )
            s.runGoalSec?.let { Text("Цель: ${RunPlan.time(it.toDouble())}", style = MaterialTheme.typography.bodyMedium) }
            if (v != null) {
                val c = RunPlan.corridor(dist, v, s.runPerWeek ?: RunPlan.DEFAULT_RUNS, left)
                Text(
                    "Сейчас ~${RunPlan.time(RunPlan.predictSec(dist, v))} · к старту реально ${RunPlan.time(c.fastSec)} – ${RunPlan.time(c.slowSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val items = s.runItems()
            val next = items.filter { it.epochDay >= today && !RunPlan.isDone(it, runData) }.take(3)
            next.forEach { item ->
                val paces = item.segs.filter { it.kind == RunPlan.SegKind.WORK || it.kind == RunPlan.SegKind.STEADY }
                    .map { it.zone }.distinct()
                    .joinToString(", ") { z -> v?.let { "${z.title} ${RunStats.pace(RunPlan.zonePace(z, it, dist, s.runGoalSec?.toDouble()))}/км" } ?: z.title }
                Text(
                    (if (item.epochDay == today) "Сегодня" else LocalDate.ofEpochDay(item.epochDay).format(runDateFmt)) +
                        " — ${item.title()}" + if (paces.isNotEmpty()) " · $paces" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Силовые — в поддержке. Пробежку по плану можно запустить во вкладке «Таймер» → «Бег».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Изменить") }
                TextButton(onClick = onClear) { Text("Сбросить") }
            }
        }
    }
}
