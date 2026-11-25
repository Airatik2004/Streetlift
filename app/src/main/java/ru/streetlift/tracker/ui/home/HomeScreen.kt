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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.PeakPhase
import ru.streetlift.tracker.domain.Peaking
import ru.streetlift.tracker.domain.ProgressionEngine
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
data class ExercisePreview(val exercise: ExerciseEntity, val plan: SessionPlan?)

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

    val state: StateFlow<HomeUiState> = combine(
        combine(repo.settings, repo.days, repo.exercises, repo.activeSession, repo.lastFinishedSession) { a, b, c, d, e ->
            listOf(a, b, c, d, e)
        },
        repo.finishedSessions,
        repo.lastAlt,
    ) { base, finished, lastAlt ->
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
        val previews = if (day == null) emptyList() else ru.streetlift.tracker.domain.ProgramOrder.order(
            day.position, exs.filter { it.dayId == day.id && it.activeInSlot(ru.streetlift.tracker.data.db.nextAltSlot(lastAlt)) }.sortedBy { it.position }, s.priority,
        ) { phase != PeakPhase.NONE && it.id in peakIds }
            .map { ExercisePreview(it, Peaking.resolve(it.toConfig(), it.toState(), it.type, phase == PeakPhase.NONE && it.plannedDeload(s.deloadEvery, global), phase, it.id in peakIds, s.bodyWeightKg)
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

    fun start(onStarted: (Long) -> Unit) {
        val day = state.value.nextDay ?: return
        viewModelScope.launch { onStarted(repo.startSession(day.id)) }
    }

    fun skipDay() = viewModelScope.launch { repo.skipDay() }
    fun selectDay(index: Int) = viewModelScope.launch { repo.selectDay(index) }
    fun postpone() = viewModelScope.launch { repo.postpone() }
    fun prepone() = viewModelScope.launch { repo.prepone() }
    fun undoSkip() = viewModelScope.launch { repo.undoSkip() }
    fun setDeload(want: Boolean) = viewModelScope.launch { repo.setDeload(want) }
    fun setPeak(epochDay: Long, ids: Set<Long>, bw: Float?) = viewModelScope.launch { repo.setPeak(epochDay, ids, bw) }
    fun clearPeak() = viewModelScope.launch { repo.clearPeak() }

    init {
        viewModelScope.launch { repo.checkPeakExpired() }
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
    var dayMenu by remember { mutableStateOf(false) }
    var peakDialog by remember { mutableStateOf(false) }
    if (peakDialog) {
        PeakDialog(
            // Новая подводка — дата не подставляется, выбирается вручную
            initialDay = ui.settings.peakEpochDay,
            initialIds = ui.settings.peakIds,
            initialBodyWeight = ui.settings.bodyWeightKg,
            exercises = ui.allExercises,
            dayNames = ui.days.associate { it.id to it.name },
            onDismiss = { peakDialog = false },
            onSave = { d, ids, bw ->
                peakDialog = false
                vm.setPeak(d, ids, bw)
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

        ui.activeSession?.let { active ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Незавершённая тренировка", style = MaterialTheme.typography.titleMedium)
                            Text(active.dayName, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { onOpenWorkout(active.id) }) { Text("Продолжить") }
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
                                    "Следующая: ${formatNextDate(s.nextWorkoutEpochDay)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(day.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            Box {
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
                        Button(
                            onClick = { vm.start(onOpenWorkout) },
                            enabled = ui.previews.any { it.plan != null } && ui.activeSession == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Начать тренировку")
                        }
                        val today = LocalDate.now().toEpochDay()
                        val postponed = (s.nextWorkoutEpochDay ?: today) > today
                        if (postponed || s.canUndoSkip) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            PeakCard(
                ui = ui,
                onEdit = { peakDialog = true },
                onClear = { vm.clearPeak() },
            )
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
                "Подберите вес/стадию под коридор с запасом 2 повторения",
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
private fun PeakCard(ui: HomeUiState, onEdit: () -> Unit, onClear: () -> Unit) {
    val s = ui.settings
    val date = s.peakEpochDay
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Выход на пик", style = MaterialTheme.typography.titleMedium)
            if (date == null) {
                Text(
                    "Выберите дату старта/проходки и упражнения для выхода на пик — за 22 дня план перестроится сам.",
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
                Text(
                    if (names.isEmpty()) "Пиковые упражнения не выбраны" else "Пик: " + names.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) { Text("Изменить") }
                    TextButton(onClick = onClear) { Text("Сбросить") }
                }
            }
        }
    }
}

/** Дата старта + чекбоксы пиковых упражнений. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PeakDialog(
    initialDay: Long?,
    initialIds: Set<Long>,
    initialBodyWeight: Float?,
    exercises: List<ExerciseEntity>,
    dayNames: Map<Long, String>,
    onDismiss: () -> Unit,
    onSave: (Long, Set<Long>, Float?) -> Unit,
) {
    var day by remember { mutableStateOf<Long?>(initialDay) }
    var bw by remember { mutableStateOf(initialBodyWeight?.let { Fmt.step(it) } ?: "") }
    var ids by remember { mutableStateOf(initialIds) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val minDay = today + Peaking.MIN_SPAN
    // Уже выставленную дату можно оставить, новую — не ближе 7 дней
    val d = day
    val dayOk = d != null && (d >= minDay || d == initialDay && initialIds.isNotEmpty())

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
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (d == null) "Выбрать дату" else "Дата: " + LocalDate.ofEpochDay(d).format(peakDateFmt))
                }
                val left = (d ?: today) - today
                Text(
                    when {
                        d == null -> "Выберите дату пика. Ближе 7 дней выбрать нельзя."
                        !dayOk -> "Ближе 7 дней выбрать нельзя — выберите другую дату."
                        left < Peaking.FULL_SPAN -> "До пика $left ${plural(left.toInt(), "день", "дня", "дней")} — меньше 22, подготовка будет ускоренной: отдых и тонус полностью, остальное сокращается. Ближе 7 дней выбрать нельзя."
                        else -> "Полная подготовка начнётся за 22 дня до даты. Ближе 7 дней выбрать нельзя."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d != null && (!dayOk || left < Peaking.FULL_SPAN)) WarnOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = bw,
                    onValueChange = { bw = it },
                    label = { Text("Свой вес, кг") },
                    singleLine = true,
                    isError = bw.isNotBlank() && bw.replace(',', '.').toFloatOrNull() == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (d != null) onSave(d, ids, bw.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }) },
                enabled = dayOk,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
