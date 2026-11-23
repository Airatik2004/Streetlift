package ru.streetlift.tracker.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.timer.RestTimer
import ru.streetlift.tracker.timer.TimerActions
import ru.streetlift.tracker.ui.common.ConfirmDialog
import ru.streetlift.tracker.ui.common.SetEditDialog
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.Stepper
import ru.streetlift.tracker.ui.common.Tag
import ru.streetlift.tracker.ui.common.TextInputDialog
import ru.streetlift.tracker.ui.common.parseSignedFloat
import ru.streetlift.tracker.ui.theme.DeloadGreen
import ru.streetlift.tracker.ui.theme.TimerDigits
import ru.streetlift.tracker.ui.theme.WarnOrange
import ru.streetlift.tracker.workout.ActiveWorkout
import ru.streetlift.tracker.workout.Draft
import ru.streetlift.tracker.workout.StepRow

/**
 * Экран тренировки — «плеер» по шагам, как у интервальных таймеров:
 * подход → отдых → подход… Внизу всегда ⏮ Предыдущий · ⏯ Пауза · ⏭ Следующий.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkoutScreen(repo: WorkoutRepository, sessionId: Long, onExit: () -> Unit) {
    val vm: WorkoutViewModel = viewModel(
        key = "workout_$sessionId",
        factory = viewModelFactory { initializer { WorkoutViewModel(repo, sessionId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val timer by RestTimer.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var menu by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var logToDelete by remember { mutableStateOf<SetLogEntity?>(null) }

    BackHandler { onExit() }

    // Тренировка живёт на уровне процесса — её же видит уведомление
    LaunchedEffect(sessionId) { ActiveWorkout.begin(ctx, sessionId) }

    if (ui.missing) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Тренировка не найдена")
            Button(onClick = onExit) { Text("На главную") }
        }
        return
    }
    // Итоги показываем и после того, как тренировка закрыта (завершена)
    summary?.let { text ->
        if (!ui.loaded) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("План на следующий цикл") },
                text = { Text(text) },
                confirmButton = {
                    TextButton(onClick = {
                        summary = null
                        onExit()
                    }) { Text("Готово") }
                },
            )
            return
        }
    }
    if (!ui.loaded) return

    // Черновик ввода текущего подхода (сбрасывается при смене подхода)
    var weight by remember(ui.stepKey) { mutableFloatStateOf(ui.defaultWeight) }
    var reps by remember(ui.stepKey) { mutableIntStateOf(ui.defaultReps) }
    var rir by remember(ui.stepKey) { mutableIntStateOf(ui.defaultRir) }
    // То, что стоит в полях, нужно и кнопке «Следующий» в уведомлении
    LaunchedEffect(ui.stepKey, weight, reps, rir, ui.currentStage) {
        ActiveWorkout.setDraft(ui.stepKey, Draft(weight, reps, rir, ui.currentStage))
    }

    val listState = rememberLazyListState()
    LaunchedEffect(ui.currentRow, ui.rows.size) {
        if (ui.rows.isNotEmpty()) listState.animateScrollToItem((ui.currentRow - 1).coerceAtLeast(0))
    }

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
            ui.peakTitle?.let { Tag(it) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Меню") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Завершить тренировку") }, onClick = {
                        menu = false
                        if (ui.doneSets >= ui.plannedSets) {
                            TimerActions.reset()
                            vm.finish { summary = it.ifBlank { "Тренировка сохранена." } }
                        } else {
                            confirmFinish = true
                        }
                    })
                    DropdownMenuItem(text = { Text("Отменить тренировку") }, onClick = {
                        menu = false
                        confirmDiscard = true
                    })
                }
            }
        }

        // --- Текущий шаг ---
        val cur = ui.current
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    cur == null -> {
                        Text("Все подходы сделаны", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        // Всё сделано — завершаем сразу, без подтверждения
                        Button(onClick = {
                            TimerActions.reset()
                            vm.finish { summary = it.ifBlank { "Тренировка сохранена." } }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Завершить тренировку")
                        }
                    }
                    ui.resting -> RestPanel(timer, ui)
                    else -> WorkPanel(
                        ui = ui,
                        weight = weight, reps = reps, rir = rir,
                        onWeight = { weight = it }, onReps = { reps = it }, onRir = { rir = it },
                        onSets = { ActiveWorkout.changeSets(cur.exercise.id, it) },
                        onStage = { vm.setStage(cur.exercise.id, it) },
                    )
                }
            }
        }

        // --- Список шагов ---
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(ui.rows) { i, row ->
                val isCurrent = i == ui.currentRow && cur != null
                val bg = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
                when (row) {
                    is StepRow.Rest -> Text(
                        "Отдых ${Fmt.clock(row.seconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(bg).padding(vertical = 4.dp),
                    )
                    is StepRow.Work -> {
                        val log = row.log
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .combinedClickable(onClick = {}, onLongClick = { if (log != null) logToDelete = log })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${row.block.exercise.name} · ${row.setNumber}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (log != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (log != null) {
                                Text(
                                    "${Fmt.weightValue(log.weightKg)} × ${log.reps}${if (log.isSeconds) " с" else ""} · ${Fmt.rirValue(log.rir)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DeloadGreen,
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()

        // --- ⏮ ⏯ ⏭ ---
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerButton(Icons.Filled.SkipPrevious, "Предыдущий", enabled = ui.canUndo) { ActiveWorkout.prev(ctx) }
            PlayerButton(
                if (ui.resting && timer.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (ui.resting && timer.running) "Пауза" else "Продолжить",
                enabled = ui.resting,
            ) { ActiveWorkout.toggle(ctx) }
            PlayerButton(Icons.Filled.SkipNext, "Следующий", enabled = cur != null) {
                ActiveWorkout.setDraft(ui.stepKey, Draft(weight, reps, rir, ui.currentStage))
                ActiveWorkout.next(ctx)
            }
        }
    }

    if (confirmFinish) {
        val left = ui.plannedSets - ui.doneSets
        ConfirmDialog(
            title = "Завершить тренировку?",
            text = if (left > 0) {
                "Осталось подходов: $left. Подходов меньше плана — вес не вырастет, и это засчитается как утомление."
            } else {
                "План на следующий цикл будет пересчитан."
            },
            confirm = "Завершить",
            onDismiss = { confirmFinish = false },
            onConfirm = {
                confirmFinish = false
                TimerActions.reset()
                RestTimer.setInfo("", "")
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
                TimerActions.reset()
                RestTimer.setInfo("", "")
                vm.discard(onExit)
            },
        )
    }
    logToDelete?.let { log ->
        SetEditDialog(
            log = log,
            scale = ui.blocks.firstOrNull { it.exercise.id == log.exerciseId }?.exercise?.loadScale,
            onDismiss = { logToDelete = null },
            onSave = {
                ActiveWorkout.updateLog(ctx, it)
                logToDelete = null
            },
            onDelete = {
                ActiveWorkout.deleteLog(ctx, it)
                logToDelete = null
            },
        )
    }
    summary?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("План на следующий цикл") },
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

@Composable
private fun PlayerButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(40.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** Шаг «Отдых»: крупные цифры, полоса, что дальше. */
@Composable
private fun RestPanel(timer: RestTimer.State, ui: WorkoutUi) {
    val cur = ui.current ?: return
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Text(if (timer.running) "Отдых" else "Отдых · пауза", style = MaterialTheme.typography.titleMedium)
    Text(Fmt.clock(timer.remainingSeconds), style = TimerDigits.copy(fontSize = 88.sp))
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(bar, size = Size(size.width * (1f - timer.progress), size.height), cornerRadius = r)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Дальше: ${cur.exercise.name} · подход ${ui.currentSetNumber} из ${cur.plannedCount}",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        if (cur.plan.isCalibration) "Калибровка · ${Fmt.range(cur.plan.repsMin, cur.plan.repsMax)} ${if (cur.exercise.isSeconds) "с" else "повт."} · RIR ${cur.plan.rirMin}"
        else "${Fmt.weight(cur.plan.weightKg)} · ${Fmt.range(cur.plan.repsMin, cur.plan.repsMax)} ${if (cur.exercise.isSeconds) "с" else "повт."} · ${Fmt.rir(cur.plan.rirMin, cur.plan.rirMax)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Шаг «Подход»: что делать и ввод результата. Записывает кнопка ⏭. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkPanel(
    ui: WorkoutUi,
    weight: Float,
    reps: Int,
    rir: Int,
    onWeight: (Float) -> Unit,
    onReps: (Int) -> Unit,
    onRir: (Int) -> Unit,
    onSets: (Int) -> Unit,
    onStage: (String?) -> Unit,
) {
    val cur = ui.current ?: return
    val plan = cur.plan
    val step = cur.exercise.weightStep
    val unit = if (cur.exercise.isSeconds) "с" else "повт."
    var editWeight by remember { mutableStateOf(false) }
    var editReps by remember { mutableStateOf(false) }

    if (editWeight) {
        TextInputDialog(
            title = "Вес, кг",
            initial = Fmt.weightValue(weight),
            keyboardType = KeyboardType.Text,
            hint = "Положительный — отягощение, 0 — свой вес, отрицательный — помощь резин (кг)",
            onDismiss = { editWeight = false },
            onConfirm = { t ->
                // Резины — к ближайшему сочетанию; где резин нет — не ниже 0
                parseSignedFloat(t)?.let { onWeight(cur.exercise.loadScale.snapManual(ProgressionEngine.roundWeight(it))) }
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
                t.trim().toIntOrNull()?.let { onReps(it.coerceIn(0, 200)) }
                editReps = false
            },
        )
    }

    Text(cur.exercise.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    // Подход N из M с −/+ в пределах коридора
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSets(-1) }, enabled = cur.plannedCount > cur.minCount) { Text("−", fontSize = 20.sp) }
        Text("Подход ${ui.currentSetNumber} из ${cur.plannedCount}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { onSets(1) }, enabled = cur.plannedCount < cur.maxCount) { Text("+", fontSize = 20.sp) }
    }
    Text(
        if (plan.isCalibration) "Калибровка: подберите вес/стадию на ${Fmt.range(plan.repsMin, plan.repsMax)} $unit с запасом ${plan.rirMin}"
        else "${Fmt.weight(plan.weightKg)} · ${Fmt.range(plan.repsMin, plan.repsMax)} ${unit} (цель ${plan.targetReps}+) · ${Fmt.rir(plan.rirMin, plan.rirMax)}",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    if (plan.isEmergencyDeload) Tag("Аварийная разгрузка упражнения", WarnOrange)
    if (plan.isPeakTarget) Tag("Пиковое упражнение")
    Spacer(Modifier.height(8.dp))

    if (plan.isCalibration && cur.exercise.stageGroup != null) {
        StagePicker(
            stages = ui.stages.filter { it.groupKey == cur.exercise.stageGroup },
            selected = ui.currentStage,
            onSelect = onStage,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    } else {
        ui.currentStage?.let {
            Text("Стадия: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
    }

    ru.streetlift.tracker.ui.common.WeightStepper(
        weight = weight,
        scale = cur.exercise.loadScale,
        onChange = onWeight,
        onValueClick = { editWeight = true },
    )
    Spacer(Modifier.height(6.dp))
    if (cur.exercise.isSeconds) {
        HoldStopwatch(onResult = onReps)
        Spacer(Modifier.height(6.dp))
    }
    Stepper(
        label = if (cur.exercise.isSeconds) "Секунды (±1)" else "Повторы",
        value = reps.toString(),
        onMinus = { onReps((reps - 1).coerceAtLeast(0)) },
        onPlus = { onReps(reps + 1) },
        onValueClick = { editReps = true },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (cur.exercise.isSeconds) "Запас, сек до потери геометрии (RIR)" else "Запас (RIR)",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..5).forEach { v ->
            FilterChip(selected = rir == v, onClick = { onRir(v) }, label = { Text(Fmt.rirValue(v)) })
        }
    }
    Text(
        "«Следующий» — записать подход и начать отдых",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Секундомер подхода для статики: задержка старта и пики — из настроек секундомера вкладки «Таймер».
 * «Стоп» подставляет секунды в результат; экран не гаснет, пока идёт удержание.
 */
@Composable
private fun HoldStopwatch(onResult: (Int) -> Unit) {
    val ctx = LocalContext.current
    val sw by ru.streetlift.tracker.timer.Stopwatch.state.collectAsStateWithLifecycle()
    val mine = sw.owner == "hold"
    ru.streetlift.tracker.ui.timer.KeepScreenOn(mine && sw.active)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    mine && sw.countdown > 0 -> "Старт через ${sw.countdown}"
                    mine -> ru.streetlift.tracker.ui.timer.formatStopwatch(sw.elapsedMs)
                    else -> "00:00.0"
                },
                style = TimerDigits.copy(fontSize = 32.sp),
                modifier = Modifier.weight(1f),
            )
            if (mine && sw.active) {
                Button(onClick = {
                    val secs = ru.streetlift.tracker.timer.Stopwatch.stop()
                    ru.streetlift.tracker.timer.Stopwatch.reset()
                    onResult(secs)
                }) { Text("Стоп") }
            } else {
                Button(onClick = {
                    ru.streetlift.tracker.timer.Stopwatch.reset()
                    ru.streetlift.tracker.timer.Stopwatch.start(ctx, owner = "hold")
                }) { Text("Старт") }
            }
        }
    }
}
