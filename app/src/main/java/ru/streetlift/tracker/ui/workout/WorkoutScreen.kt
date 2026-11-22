package ru.streetlift.tracker.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.timer.TimerActions
import ru.streetlift.tracker.ui.common.ConfirmDialog
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.Stepper
import ru.streetlift.tracker.ui.common.Tag
import ru.streetlift.tracker.ui.common.TextInputDialog
import ru.streetlift.tracker.ui.common.parseSignedFloat
import ru.streetlift.tracker.ui.theme.DeloadGreen
import ru.streetlift.tracker.ui.theme.WarnOrange
import ru.streetlift.tracker.ui.timer.RestTimerPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(repo: WorkoutRepository, sessionId: Long, onExit: () -> Unit) {
    val vm: WorkoutViewModel = viewModel(
        key = "workout_$sessionId",
        factory = viewModelFactory { initializer { WorkoutViewModel(repo, sessionId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var menu by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var logToDelete by remember { mutableStateOf<SetLogEntity?>(null) }

    BackHandler { onExit() }

    if (ui.missing) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Тренировка не найдена")
            Button(onClick = onExit) { Text("На главную") }
        }
        return
    }
    if (!ui.loaded) return

    Column(Modifier.fillMaxSize()) {
        // --- Шапка ---
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text(ui.dayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Подходов: ${ui.doneSets} из ${ui.plannedSets}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.isDeload) Tag("Разгрузка", DeloadGreen)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Меню") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Отменить тренировку") },
                        onClick = {
                            menu = false
                            confirmDiscard = true
                        },
                    )
                }
            }
        }

        // --- Таймер ---
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            RestTimerPanel(
                recommendation = ui.recommendation,
                compact = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.canAlternate) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Чередующиеся подходы", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (ui.alternating) "А1 → отдых → Б1 → отдых → А2…" else "Последовательно: все подходы А, потом Б",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = ui.alternating, onCheckedChange = { vm.setAlternating(it) })
                    }
                }
            }

            item {
                val cur = ui.current
                if (cur == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Все запланированные подходы сделаны 💪", style = MaterialTheme.typography.titleMedium)
                            Text("Завершите тренировку — план на следующий круг пересчитается автоматически.")
                        }
                    }
                } else {
                    CurrentSetCard(
                        ui = ui,
                        onLog = { w, r, rir ->
                            vm.logSet(w, r, rir) { rec ->
                                TimerActions.start(ctx, rec.seconds * 1000L, "Дальше: следующий подход")
                            }
                        },
                        onSkip = { vm.skipCurrent() },
                        onExtra = { vm.addExtraSet(cur.exercise.id) },
                        onStage = { vm.setStage(cur.exercise.id, it) },
                        onAddStage = { vm.addStage(it) },
                    )
                }
            }

            items(ui.blocks, key = { it.exercise.id }) { b ->
                ExerciseBlockCard(
                    block = b,
                    isCurrent = ui.current?.exercise?.id == b.exercise.id,
                    onFocus = { vm.focus(b.exercise.id) },
                    onExtra = { vm.addExtraSet(b.exercise.id) },
                    onDeleteLog = { logToDelete = it },
                )
            }

            item {
                Button(
                    onClick = { confirmFinish = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Завершить тренировку")
                }
            }
        }
    }

    if (confirmFinish) {
        val left = ui.plannedSets - ui.doneSets
        ConfirmDialog(
            title = "Завершить тренировку?",
            text = if (left > 0) {
                "Осталось подходов: $left. Несделанные подходы засчитаются как невыполненные, и прогрессия веса не сработает."
            } else {
                "План на следующий круг будет пересчитан."
            },
            confirm = "Завершить",
            onDismiss = { confirmFinish = false },
            onConfirm = {
                confirmFinish = false
                vm.finish { summary = it.ifBlank { "Тренировка сохранена." } }
            },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Отменить тренировку?",
            text = "Все записанные подходы этой тренировки будут удалены, план не изменится.",
            confirm = "Удалить",
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                vm.discard(onExit)
            },
        )
    }
    logToDelete?.let { log ->
        ConfirmDialog(
            title = "Удалить подход?",
            text = "${log.exerciseName}: ${Fmt.weight(log.weightKg)} × ${log.reps}, ${Fmt.rir(log.rir, log.rir)}",
            confirm = "Удалить",
            onDismiss = { logToDelete = null },
            onConfirm = {
                vm.deleteLog(log)
                logToDelete = null
            },
        )
    }
    summary?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("План на следующий круг") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    summary = null
                    onExit()
                }) { Text("Готово") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrentSetCard(
    ui: WorkoutUi,
    onLog: (Float, Int, Int) -> Unit,
    onSkip: () -> Unit,
    onExtra: () -> Unit,
    onStage: (String?) -> Unit,
    onAddStage: (String) -> Unit,
) {
    val cur = ui.current ?: return
    val plan = cur.plan
    val step = cur.exercise.weightStep
    var weight by remember(ui.stepKey) { mutableFloatStateOf(ui.defaultWeight) }
    var reps by remember(ui.stepKey) { mutableIntStateOf(ui.defaultReps) }
    var rir by remember(ui.stepKey) { mutableIntStateOf(ui.defaultRir) }
    var editWeight by remember { mutableStateOf(false) }
    var editReps by remember { mutableStateOf(false) }

    if (editWeight) {
        TextInputDialog(
            title = "Вес, кг",
            initial = Fmt.weight(weight).removeSuffix(" кг"),
            keyboardType = KeyboardType.Text,
            hint = "Положительный — отягощение, 0 — свой вес, отрицательный — резина",
            onDismiss = { editWeight = false },
            onConfirm = { t ->
                parseSignedFloat(t)?.let { weight = ProgressionEngine.roundWeight(it) }
                editWeight = false
            },
        )
    }
    if (editReps) {
        TextInputDialog(
            title = "Повторения",
            initial = reps.toString(),
            keyboardType = KeyboardType.Number,
            onDismiss = { editReps = false },
            onConfirm = { t ->
                t.trim().toIntOrNull()?.let { reps = it.coerceIn(0, 200) }
                editReps = false
            },
        )
    }

    val extraSet = ui.currentSetNumber > cur.plannedCount
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    if (extraSet) "Доп. подход ${ui.currentSetNumber}" else "Подход ${ui.currentSetNumber} из ${cur.plannedCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(cur.exercise.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (plan.isCalibration) {
                    Spacer(Modifier.size(4.dp))
                    Tag("Калибровка", MaterialTheme.colorScheme.secondary)
                    Text(
                        "Коридор: ${Fmt.range(plan.repsMin, plan.repsMax)} повт. · RIR ${plan.rirMin}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Подберите вес/стадию под этот диапазон с запасом ${plan.rirMin} повторения " +
                            "и запишите ${plan.sets} рабочих подхода. Вес можно менять перед каждым подходом — " +
                            "стартовыми станут параметры последнего подхода.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "План: ${Fmt.weight(plan.weightKg)} · ${Fmt.range(plan.repsMin, plan.repsMax)} повт. (цель ${plan.targetReps}+) · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ui.currentStage?.let {
                        Text("Стадия: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (plan.isEmergencyDeload) {
                    Spacer(Modifier.size(4.dp))
                    Tag("Аварийная разгрузка упражнения", WarnOrange)
                }
            }

            if (plan.isCalibration && (cur.exercise.leverageStage != null || ui.currentStage != null)) {
                StagePicker(
                    stages = ui.stages,
                    selected = ui.currentStage,
                    onSelect = onStage,
                    onAddCustom = onAddStage,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stepper(
                    label = "Вес (шаг ${Fmt.step(step)})",
                    value = Fmt.weight(weight).removeSuffix(" кг"),
                    onMinus = { weight = ProgressionEngine.roundWeight(weight - step) },
                    onPlus = { weight = ProgressionEngine.roundWeight(weight + step) },
                    onValueClick = { editWeight = true },
                )
                Stepper(
                    label = "Повторы",
                    value = reps.toString(),
                    onMinus = { reps = (reps - 1).coerceAtLeast(0) },
                    onPlus = { reps += 1 },
                    onValueClick = { editReps = true },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Запас (RIR) — сколько чистых повторов оставалось",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { v ->
                        FilterChip(
                            selected = rir == v,
                            onClick = { rir = v },
                            label = { Text(Fmt.rirValue(v)) },
                        )
                    }
                }
            }

            Button(onClick = { onLog(weight, reps, rir) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Записать подход")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f), enabled = !extraSet) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Пропустить")
                }
                OutlinedButton(onClick = onExtra, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Подход")
                }
            }
        }
    }
}

@Composable
private fun ExerciseBlockCard(
    block: ExerciseBlock,
    isCurrent: Boolean,
    onFocus: () -> Unit,
    onExtra: () -> Unit,
    onDeleteLog: (SetLogEntity) -> Unit,
) {
    val plan = block.plan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(block.exercise.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (plan.isCalibration) "Калибровка · " else "${Fmt.weight(plan.weightKg)} · ") +
                            "${block.plannedCount} × ${Fmt.range(plan.repsMin, plan.repsMax)} · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${block.logs.size}/${block.plannedCount}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (block.complete) DeloadGreen else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (block.logs.isNotEmpty()) HorizontalDivider()
            block.logs.forEachIndexed { i, log ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}.  ${Fmt.weight(log.weightKg)} × ${log.reps}  ·  RIR ${Fmt.rirValue(log.rir)}" +
                            (log.leverageStage?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDeleteLog(log) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Удалить подход", modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (block.skipped > 0) {
                Text("Пропущено: ${block.skipped}", style = MaterialTheme.typography.bodySmall, color = WarnOrange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isCurrent) TextButton(onClick = onFocus) { Text("Сделать сейчас") }
                TextButton(onClick = onExtra) { Text("+ подход") }
            }
        }
    }
}
