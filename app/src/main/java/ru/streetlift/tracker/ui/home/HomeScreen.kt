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

data class ExercisePreview(val exercise: ExerciseEntity, val plan: SessionPlan)

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
)

class HomeViewModel(private val repo: WorkoutRepository) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        repo.settings, repo.days, repo.exercises, repo.activeSession, repo.lastFinishedSession,
    ) { s, days, exs, active, last ->
        val idx = if (days.isEmpty()) 0 else s.currentDayIndex.coerceIn(0, days.size - 1)
        val day = days.getOrNull(idx)
        val global = ProgressionEngine.isGlobalDeload(s.cycleInMeso, s.deloadEvery, s.deloadOverride)
        val previews = if (day == null) emptyList() else exs
            .filter { it.dayId == day.id }
            .sortedBy { it.position }
            .map { ExercisePreview(it, ProgressionEngine.sessionPlan(it.toConfig(), it.toState(), global)) }
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun start(onStarted: (Long) -> Unit) {
        val day = state.value.nextDay ?: return
        viewModelScope.launch { onStarted(repo.startSession(day.id)) }
    }

    fun skipDay() = viewModelScope.launch { repo.skipDay() }
    fun selectDay(index: Int) = viewModelScope.launch { repo.selectDay(index) }
    fun postpone() = viewModelScope.launch { repo.postpone() }
    fun setDeload(want: Boolean) = viewModelScope.launch { repo.setDeload(want) }
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
    val s = ui.settings

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Стритлифт", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Круг ${s.completedRounds + 1} · день ${ui.nextDayIndex + 1} из ${ui.days.size}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- Статус мезоцикла и разгрузки ---
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.globalDeload) {
                        Tag("Разгрузочный круг", DeloadGreen)
                        Text(
                            "Вес 100%, подходы −50% (минимум 2), RIR 3–4, отдых короче на 30 с.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            "Мезоцикл: круг ${s.cycleInMeso.coerceAtMost(s.deloadEvery)} из ${s.deloadEvery}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val left = (s.deloadEvery - s.cycleInMeso).coerceAtLeast(0)
                        Text(
                            if (left == 0) "Плановая разгрузка отменена на этот круг"
                            else "До плановой разгрузки: $left ${plural(left, "круг", "круга", "кругов")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.setDeload(!ui.globalDeload) }) {
                        Text(if (ui.globalDeload) "Отменить разгрузку" else "Включить разгрузку на этот круг")
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
                            PreviewRow(p)
                        }
                        if (ui.previews.isEmpty()) {
                            Text("В этом дне пока нет упражнений — добавьте их во вкладке «Цикл».")
                        }

                        Button(
                            onClick = { vm.start(onOpenWorkout) },
                            enabled = ui.previews.isNotEmpty() && ui.activeSession == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Начать тренировку")
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
private fun PreviewRow(p: ExercisePreview) {
    val e = p.exercise
    val plan = p.plan
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                plan.isCalibration -> Tag("Калибровка", MaterialTheme.colorScheme.secondary)
                plan.isEmergencyDeload -> Tag("Авар. разгрузка", WarnOrange)
                plan.isDeload -> Tag("Разгрузка", DeloadGreen)
            }
        }
        e.leverageStage?.let {
            Text("Стадия: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (plan.isCalibration) {
            Text(
                "${plan.sets} × ${Fmt.range(plan.repsMin, plan.repsMax)} · RIR ${plan.rirMin} · вес подбирается",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Подберите вес/стадию под коридор с запасом 2 повторения",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${Fmt.weight(plan.weightKg)} · ${plan.sets} × ${Fmt.range(plan.repsMin, plan.repsMax)} · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Цель: ${plan.targetReps}+ повт. в каждом подходе · ${e.type.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
