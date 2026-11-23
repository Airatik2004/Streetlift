package ru.streetlift.tracker.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
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
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.ExerciseMode
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.ui.common.ConfirmDialog
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.Stepper
import ru.streetlift.tracker.ui.common.Tag
import ru.streetlift.tracker.ui.common.parseSignedFloat

data class EditorUi(
    val settings: SettingsEntity = SettingsEntity(),
    val days: List<DayEntity> = emptyList(),
    val exercises: Map<Long, List<ExerciseEntity>> = emptyMap(),
    val stages: List<StageEntity> = emptyList(),
)

class EditorViewModel(private val repo: WorkoutRepository) : ViewModel() {
    val state: StateFlow<EditorUi> = combine(repo.settings, repo.days, repo.exercises, repo.stages) { s, d, e, st ->
        EditorUi(s, d, e.groupBy { it.dayId }.mapValues { (_, v) -> v.sortedBy { it.position } }, st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUi())

    fun saveExercise(e: ExerciseEntity) = viewModelScope.launch { repo.saveExercise(e) }
    fun setAlternating(day: DayEntity, on: Boolean) = viewModelScope.launch { repo.updateDay(day.copy(alternating = on)) }
    fun setDeloadEvery(n: Int) = viewModelScope.launch { repo.setDeloadEvery(n) }
    fun setDeload(want: Boolean) = viewModelScope.launch { repo.setDeload(want) }
    fun setPriority(m: ru.streetlift.tracker.domain.PriorityMode) = viewModelScope.launch { repo.setPriorityMode(m) }
    fun setAutoStart(b: Boolean) = viewModelScope.launch { repo.setAutoStartTimer(b) }
    fun resetProgram() = viewModelScope.launch { repo.resetProgram() }
    fun reopenSetup() = viewModelScope.launch { repo.reopenSetup() }
}

/**
 * Вкладка «Цикл»: программа фиксирована (дни, упражнения, порядок, диапазоны).
 * Меняется только калибровка упражнения: текущий вес, стадия рычага, повторная калибровка.
 */
@Composable
fun EditorScreen(repo: WorkoutRepository) {
    val vm: EditorViewModel = viewModel(factory = viewModelFactory { initializer { EditorViewModel(repo) } })
    val ui by vm.state.collectAsStateWithLifecycle()
    val s = ui.settings

    var confirmReset by remember { mutableStateOf(false) }
    var editExercise by remember { mutableStateOf<ExerciseEntity?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Цикл", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Программа задана заранее. Нажмите на упражнение, чтобы изменить вес, стадию или запустить калибровку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(ui.days, key = { it.id }) { day ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("День ${day.position + 1} из 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (ui.exercises[day.id].orEmpty().size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Суперсет", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (day.alternating) "сейчас: А1 → Б1 → А2 → Б2…" else "сейчас: все подходы А, потом все подходы Б",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = day.alternating, onCheckedChange = { vm.setAlternating(day, it) })
                        }
                    }
                    HorizontalDivider()
                    ru.streetlift.tracker.domain.ProgramOrder.order(day.position, ui.exercises[day.id].orEmpty(), s.priority).forEach { e ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editExercise = e }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (e.mode == ExerciseMode.CALIBRATION) Tag("Калибровка", MaterialTheme.colorScheme.secondary)
                            }
                            Text(
                                "${Fmt.range(e.minSets, e.maxSets)} × ${Fmt.range(e.minReps, e.maxReps)}${if (e.isSeconds) " с" else ""} · " +
                                    "${Fmt.rir(e.targetRirMin, e.targetRirMax)} · шаг ${Fmt.step(e.weightStep)} кг",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            e.leverageStage?.let { Text("Стадия: $it", style = MaterialTheme.typography.bodySmall) }
                            if (e.stageCeiling) {
                                Tag("Потолок стадии достигнут — можно переходить на следующую стадию", ru.streetlift.tracker.ui.theme.WarnOrange)
                            }
                            if (e.plannedDeloadExempt && e.mode != ExerciseMode.CALIBRATION &&
                                ru.streetlift.tracker.domain.ProgressionEngine.isGlobalDeload(s.cycleInMeso, s.deloadEvery, s.deloadOverride)
                            ) {
                                Tag(deloadExemptLabel(e), ru.streetlift.tracker.ui.theme.DeloadGreen)
                            }
                            if (e.mode != ExerciseMode.CALIBRATION) {
                                Text(
                                    "Сейчас: ${Fmt.weight(e.weightKg)}, ${e.plannedSets} подх., цель ${e.targetReps}+${if (e.isSeconds) " с" else ""}" +
                                        if (e.emergencyDeloadNext) " · разгрузка на следующей" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Плановая разгрузка", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "каждый ${s.deloadEvery}-й цикл",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Stepper(
                            label = "N",
                            value = s.deloadEvery.toString(),
                            onMinus = { vm.setDeloadEvery(s.deloadEvery - 1) },
                            onPlus = { vm.setDeloadEvery(s.deloadEvery + 1) },
                        )
                    }
                    HorizontalDivider()
                    ru.streetlift.tracker.ui.common.PrioritySwitch(s.priorityMode, s.priority) { vm.setPriority(it) }
                    HorizontalDivider()
                    val globalDeload = ru.streetlift.tracker.domain.ProgressionEngine.isGlobalDeload(s.cycleInMeso, s.deloadEvery, s.deloadOverride)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Разгрузка на этот цикл", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "все упражнения: вес 100%, подходы −50% (мин. 2), RIR 3–4",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = globalDeload, onCheckedChange = { vm.setDeload(it) })
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Автостарт таймера", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "после записи подхода с рекомендуемым временем",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.autoStartTimer, onCheckedChange = { vm.setAutoStart(it) })
                    }
                    TextButton(onClick = { vm.reopenSetup() }) { Text("Настройка стартовых параметров…") }
                    TextButton(onClick = { confirmReset = true }) { Text("Сбросить программу к шаблону") }
                }
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Сбросить программу?",
            text = "Веса и прогресс плана обнулятся, откроется настройка стартовых параметров. История тренировок останется.",
            confirm = "Сбросить",
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                vm.resetProgram()
            },
        )
    }
    editExercise?.let { e ->
        CalibrationDialog(
            exercise = e,
            stages = ui.stages.filter { it.groupKey == e.stageGroup },
            onDismiss = { editExercise = null },
            onSave = {
                vm.saveExercise(it)
                editExercise = null
            },
        )
    }
}

/** Калибровка упражнения: вес, стадия рычага, повторная калибровка. */
@Composable
private fun CalibrationDialog(
    exercise: ExerciseEntity,
    stages: List<StageEntity>,
    onDismiss: () -> Unit,
    onSave: (ExerciseEntity) -> Unit,
) {
    var stage by remember { mutableStateOf(exercise.leverageStage) }
    var calibrate by remember { mutableStateOf(exercise.mode == ExerciseMode.CALIBRATION) }
    var deload by remember { mutableStateOf(exercise.emergencyDeloadNext) }
    // null — не трогали (остаётся авто), иначе явный выбор
    var skipPlanned by remember { mutableStateOf(exercise.skipPlannedDeload) }
    var weight by remember { mutableStateOf(Fmt.weight(exercise.weightKg).removeSuffix(" кг")) }
    var error by remember { mutableStateOf<String?>(null) }
    val stageChanged = stage != exercise.leverageStage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exercise.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Текущий рабочий вес, кг") },
                    singleLine = true,
                    isError = parseSignedFloat(weight) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "+24 — отягощение, 0 — свой вес, −15 — резина/облегчение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exercise.stageGroup != null) {
                    StagePicker(
                        stages = stages,
                        selected = stage,
                        onSelect = { if (it != null) stage = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (stageChanged) {
                        Text(
                            "История «${exercise.leverageStage}» уйдёт в архив, упражнение на 1 цикл перейдёт в калибровку.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Калибровка на следующей тренировке", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = calibrate || stageChanged, onCheckedChange = { calibrate = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Разгрузка на следующей тренировке", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "только это упражнение: подходы −50%, RIR 3–4",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = deload, onCheckedChange = { deload = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Пропустить плановую разгрузку", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (skipPlanned == null && exercise.autoDeloadExempt) {
                                "Новая стадия: ${exercise.cyclesSinceStage} из 3 циклов — разгрузка пропускается автоматически"
                            } else {
                                "только ближайшая плановая разгрузка, потом сбрасывается"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = skipPlanned ?: exercise.autoDeloadExempt, onCheckedChange = { skipPlanned = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = parseSignedFloat(weight)
                if (w == null) {
                    error = "Проверьте вес"
                } else {
                    onSave(
                        exercise.copy(
                            weightKg = w,
                            leverageStage = stage,
                            mode = if (calibrate || stageChanged) ExerciseMode.CALIBRATION else ExerciseMode.ACTIVE,
                            emergencyDeloadNext = deload,
                            skipPlannedDeload = skipPlanned,
                        ),
                    )
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Подпись исключения из плановой разгрузки. */
internal fun deloadExemptLabel(e: ExerciseEntity): String =
    if (e.skipPlannedDeload == null && e.autoDeloadExempt) "Без разгрузки — новая стадия (${e.cyclesSinceStage} из 3 циклов)"
    else "Без разгрузки"
