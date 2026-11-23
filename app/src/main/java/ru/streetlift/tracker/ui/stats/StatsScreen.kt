package ru.streetlift.tracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.ManualRecordEntity
import ru.streetlift.tracker.data.db.RunEntity
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.data.repo.recordEntries
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.Records
import ru.streetlift.tracker.run.RouteMap
import ru.streetlift.tracker.run.RunTracker
import ru.streetlift.tracker.ui.common.Stepper
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.WeightStepper
import ru.streetlift.tracker.ui.history.HistoryScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StatsUi(
    val entries: List<Records.Entry> = emptyList(),
    /** Упражнения программы — по одному на название (задняя дельта одна). */
    val exercises: List<ExerciseEntity> = emptyList(),
    val stages: List<StageEntity> = emptyList(),
    val runs: List<RunEntity> = emptyList(),
)

class StatsViewModel(private val repo: WorkoutRepository) : ViewModel() {
    val ui: StateFlow<StatsUi> = combine(
        repo.allSets, repo.finishedSessions, repo.manualRecords, repo.exercises, repo.runs,
    ) { sets, finished, manual, exs, runs ->
        val ids = finished.map { it.id }.toSet()
        StatsUi(
            entries = recordEntries(sets.filter { it.sessionId in ids }, manual),
            exercises = exs.distinctBy { it.name },
            runs = runs,
        )
    }.combine(repo.stages) { u, st -> u.copy(stages = st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())

    fun addManual(r: ManualRecordEntity) = viewModelScope.launch { repo.addManualRecord(r) }
    fun deleteManual(id: Long) = viewModelScope.launch { repo.deleteManualRecord(id) }
    fun deleteRun(id: Long) = viewModelScope.launch { repo.deleteRun(id) }
}

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yy")
private fun day(d: Long) = LocalDate.ofEpochDay(d).format(dateFmt)

/** Вкладка «Статистика»: история, рекорды, графики, бег. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(repo: WorkoutRepository) {
    val vm: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(repo) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Выбор упражнения/стадии/веса общий для рекордов и графиков
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by rememberSaveable { mutableStateOf<String?>(null) }
    var stageSet by rememberSaveable { mutableStateOf(false) }
    // Период графика (null — весь) и переход к тренировке в истории
    var from by rememberSaveable { mutableStateOf<Long?>(null) }
    var to by rememberSaveable { mutableStateOf<Long?>(null) }
    var focus by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("История", "Рекорды", "Графики", "Бег").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HistoryScreen(repo, focus) { focus = null }
                1, 2 -> {
                    val names = (ui.exercises.map { it.name } + ui.entries.map { it.name }).distinct()
                    val sel = name?.takeIf { it in names } ?: names.firstOrNull()
                    val stages = sel?.let { Records.stages(ui.entries, it) }.orEmpty()
                    val selStage = if (stageSet && stage in stages) stage else stages.lastOrNull()
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChipRow(names, sel) { name = it; stageSet = false }
                        if (stages.size > 1 || stages.firstOrNull() != null) {
                            ChipRow(stages.map { it ?: "стадия не указана" }, selStage ?: "стадия не указана") { v ->
                                stage = v.takeIf { it != "стадия не указана" }
                                stageSet = true
                            }
                        }
                        if (sel == null) {
                            Text("Пока нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (tab == 1) {
                            RecordsTab(ui, vm, sel, selStage)
                        } else {
                            ChartTab(
                                ui, vm, sel, selStage, from, to,
                                onRange = { f, t -> from = f; to = t },
                                onOpenSession = { focus = it; tab = 0 },
                            )
                        }
                    }
                }
                else -> RunsTab(ui, vm)
            }
        }
    }
}

@Composable
private fun ChipRow(items: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { t -> FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(t) }) }
    }
}

// ---------------- Рекорды ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordsTab(ui: StatsUi, vm: StatsViewModel, name: String, stage: String?) {
    val today = LocalDate.now().toEpochDay()
    val rows = Records.table(ui.entries, name, stage, today)
    val seconds = ui.entries.firstOrNull { it.name == name }?.isSeconds
        ?: ui.exercises.firstOrNull { it.name == name }?.isSeconds ?: false
    val height = ui.entries.firstOrNull { it.name == name }?.isHeight ?: false
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Long?>(null) }

    Text(
        if (height) "Максимальная высота при каждом весе. Зелёным — введённые вручную; долгое нажатие — удалить."
        else "Лучший один подход при каждом весе${if (seconds) " (секунды)" else ""}. Зелёным — введённые вручную; долгое нажатие — удалить.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Вес", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium)
                listOf("3 мес", "Год", "Всё время").forEach {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
            if (rows.isEmpty()) Text("Нет подходов", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.weight(r.weight), Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium)
                    listOf(r.months3, r.year, r.all).forEach { b ->
                        Column(
                            Modifier.weight(1f).combinedClickable(
                                onClick = {},
                                onLongClick = { b?.manualId?.let { toDelete = it } },
                            ),
                        ) {
                            if (b == null) {
                                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    "${b.value}${if (seconds) " с" else if (height) " см" else ""}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (b.manualId != null) ManualGreen else Color.Unspecified,
                                )
                                Text(day(b.epochDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (b.manualId != null) {
                                    Text("вручную", style = MaterialTheme.typography.labelSmall, color = ManualGreen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить рекорд") }

    if (adding) {
        AddRecordDialog(ui, name, stage, onDismiss = { adding = false }) {
            vm.addManual(it)
            adding = false
        }
    }
    toDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить рекорд?") },
            text = { Text("Рекорд, введённый вручную, будет удалён.") },
            confirmButton = { TextButton(onClick = { vm.deleteManual(id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRecordDialog(
    ui: StatsUi,
    initialName: String,
    initialStage: String?,
    onDismiss: () -> Unit,
    onSave: (ManualRecordEntity) -> Unit,
) {
    var name by remember { mutableStateOf(ui.exercises.firstOrNull { it.name == initialName }?.name ?: ui.exercises.firstOrNull()?.name.orEmpty()) }
    val ex = ui.exercises.firstOrNull { it.name == name }
    var stage by remember(name) { mutableStateOf(if (name == initialName) initialStage ?: ex?.leverageStage else ex?.leverageStage) }
    var weight by remember(name) { mutableStateOf(ex?.let { it.loadScale.snap(it.weightKg) } ?: 0f) }
    var value by remember(name) { mutableIntStateOf(ex?.minReps ?: 5) }
    val today = LocalDate.now().toEpochDay()
    var date by remember { mutableLongStateOf(today) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = date * 86_400_000L,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis / 86_400_000L <= today
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = it / 86_400_000L }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { androidx.compose.material3.DatePicker(state = state) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить рекорд") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChipRow(ui.exercises.map { it.name }, name) { name = it }
                if (ex?.stageGroup != null) {
                    StagePicker(
                        stages = ui.stages.filter { it.groupKey == ex.stageGroup },
                        selected = stage,
                        onSelect = { stage = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ex != null) WeightStepper(weight, ex.loadScale, { weight = it })
                Stepper(
                    label = if (ex?.isSeconds == true) "Секунды" else "Повторы",
                    value = value.toString(),
                    onMinus = { value = (value - 1).coerceAtLeast(1) },
                    onPlus = { value += 1 },
                )
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Дата: ${day(date)}") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ex != null,
                onClick = {
                    onSave(
                        ManualRecordEntity(
                            exerciseName = name,
                            leverageStage = stage.takeIf { ex?.stageGroup != null },
                            weightKg = weight,
                            value = value,
                            isSeconds = ex?.isSeconds == true,
                            epochDay = date,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------- Графики ----------------

private val ManualGreen = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChartTab(
    ui: StatsUi,
    vm: StatsViewModel,
    name: String,
    stage: String?,
    from: Long?,
    to: Long?,
    onRange: (Long?, Long?) -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val weights = Records.weights(ui.entries, name, stage)
    var wKey by rememberSaveable(name, stage) { mutableStateOf<Int?>(null) }
    val w = weights.firstOrNull { Records.key(it) == wKey } ?: weights.firstOrNull()
    val seconds = ui.entries.firstOrNull { it.name == name }?.isSeconds ?: false
    val unit = when {
        seconds -> " с"
        ui.entries.firstOrNull { it.name == name }?.isHeight == true -> " см"
        else -> ""
    }
    var picking by remember { mutableStateOf<Int?>(null) } // 0 — «с», 1 — «по»
    var toDelete by remember { mutableStateOf<Long?>(null) }
    var picked by remember(name, stage, w, from, to) { mutableStateOf<Long?>(null) }

    if (w == null) {
        Text("Нет подходов", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    ChipRow(weights.map { Fmt.weight(it) }, Fmt.weight(w)) { label -> wKey = weights.firstOrNull { Fmt.weight(it) == label }?.let { Records.key(it) } }

    // Период: по умолчанию — весь, за который есть данные
    val allDays = ui.entries.filter { it.name == name && it.stage == stage && it.weightKey == Records.key(w) }.map { it.epochDay }
    val minDay = allDays.minOrNull() ?: LocalDate.now().toEpochDay()
    val maxDay = allDays.maxOrNull() ?: LocalDate.now().toEpochDay()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { picking = 0 }, modifier = Modifier.weight(1f)) { Text("С: ${day(from ?: minDay)}") }
        OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f)) { Text("По: ${day(to ?: maxDay)}") }
    }
    if (from != null || to != null) {
        TextButton(onClick = { onRange(null, null) }) { Text("Весь период") }
    }

    val pts = Records.series(ui.entries, name, stage, w, from, to)
    val rows = Records.history(ui.entries, name, stage, w, from, to)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (pts.isEmpty()) {
                Text("Нет подходов за этот период", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LineChart(pts, unit, picked) { picked = it }
            }
        }
    }
    Text(
        "Лучший подход с этим весом за тренировку. Зелёные точки — рекорды, введённые вручную.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // История при этом весе за период: тренировка — открыть в «Истории», ручной рекорд — удалить
    if (rows.isNotEmpty()) {
        Text("История", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    val manual = r.manualId != null
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {
                                picked = r.epochDay
                                if (manual) toDelete = r.manualId else r.sessionId?.let(onOpenSession)
                            })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(day(r.epochDay), Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val bestIdx = r.values.indexOf(r.best)
                            r.values.forEachIndexed { k, v ->
                                Text(
                                    "$v${if (k < r.values.size - 1) "," else ""}",
                                    fontWeight = if (k == bestIdx) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        manual -> ManualGreen
                                        k == bestIdx -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            if (manual) Text("вручную", color = ManualGreen, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            if (manual) "удалить" else "открыть",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    picking?.let { which ->
        val today = LocalDate.now().toEpochDay()
        val init = if (which == 0) from ?: minDay else to ?: maxDay
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = init * 86_400_000L,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = utcTimeMillis / 86_400_000L
                    return d <= today && if (which == 0) d <= (to ?: today) else d >= (from ?: Long.MIN_VALUE)
                }
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = ms / 86_400_000L
                        if (which == 0) onRange(d, to) else onRange(from, d)
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Отмена") } },
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
    toDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить рекорд?") },
            text = { Text("Рекорд, введённый вручную, будет удалён.") },
            confirmButton = { TextButton(onClick = { vm.deleteManual(id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Линейный график по датам: значения слева, даты снизу; нажатие — ближайшая точка. */
@Composable
private fun LineChart(points: List<Records.Point>, unit: String, pickedDay: Long?, onPick: (Long) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minX = points.first().epochDay
    val maxX = points.last().epochDay
    val rawMin = points.minOf { it.value }
    val rawMax = points.maxOf { it.value }
    // Запас сверху и снизу и «круглые» деления
    val span = (rawMax - rawMin).coerceAtLeast(4)
    val stepY = listOf(1, 2, 5, 10, 20, 50).firstOrNull { span / it <= 5 } ?: 100
    val minY = ((rawMin - 1) / stepY * stepY).coerceAtLeast(0)
    val maxY = ((rawMax + stepY) / stepY) * stepY
    val ticks = (minY..maxY step stepY).toList()
    val pick = points.lastOrNull { it.epochDay == pickedDay } ?: points.last()
    val chartH = 340.dp

    Row(verticalAlignment = Alignment.Top) {
        // Подписи значений
        Box(Modifier.height(chartH).padding(end = 6.dp)) {
            Column(Modifier.height(chartH), verticalArrangement = Arrangement.SpaceBetween) {
                ticks.reversed().forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = labelColor) }
            }
        }
        Canvas(
            Modifier.weight(1f).height(chartH).pointerInput(points) {
                detectTapGestures { off ->
                    val wpx = size.width.toFloat()
                    points.minByOrNull {
                        val x = if (maxX == minX) wpx / 2 else (it.epochDay - minX).toFloat() / (maxX - minX) * wpx
                        kotlin.math.abs(x - off.x)
                    }?.let { onPick(it.epochDay) }
                }
            },
        ) {
            val padX = 10.dp.toPx()
            val padY = 8.dp.toPx()
            fun xOf(d: Long) = if (maxX == minX) size.width / 2 else (d - minX).toFloat() / (maxX - minX) * (size.width - 2 * padX) + padX
            fun yOf(v: Int) = size.height - padY - (v - minY).toFloat() / (maxY - minY) * (size.height - 2 * padY)
            ticks.forEach { t -> drawLine(grid, Offset(0f, yOf(t)), Offset(size.width, yOf(t)), 1f) }
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(p.epochDay), yOf(p.value)) else path.lineTo(xOf(p.epochDay), yOf(p.value)) }
                drawPath(path, primary, style = Stroke(width = 4.dp.toPx()))
            }
            points.forEach { p ->
                val c = Offset(xOf(p.epochDay), yOf(p.value))
                drawCircle(if (p.manual) ManualGreen else primary, radius = 6.dp.toPx(), center = c)
            }
            val c = Offset(xOf(pick.epochDay), yOf(pick.value))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(width = 3.dp.toPx()))
        }
    }
    // Даты снизу: 4 подписи равномерно по периоду
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp)) {
        val n = if (maxX == minX) 1 else 4
        (0 until n).forEach { i ->
            val d = if (n == 1) minX else minX + (maxX - minX) * i / (n - 1)
            Text(
                day(d),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.weight(1f),
                textAlign = when (i) {
                    0 -> androidx.compose.ui.text.style.TextAlign.Start
                    n - 1 -> androidx.compose.ui.text.style.TextAlign.End
                    else -> androidx.compose.ui.text.style.TextAlign.Center
                },
            )
        }
    }
    Text(
        "${day(pick.epochDay)}: ${pick.value}$unit${if (pick.manual) " · вручную" else ""}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (pick.manual) ManualGreen else Color.Unspecified,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// ---------------- Бег ----------------

private val runDateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

private fun runDate(r: RunEntity) = Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(runDateFmt)

/** Разобранная пробежка: трек и сводка. */
private class RunInfo(val run: RunEntity) {
    val segments = RunTracker.decode(run.route)
    val track = ru.streetlift.tracker.domain.RunStats.track(RunTracker.toStats(segments))
    val summary = ru.streetlift.tracker.domain.RunStats.summary(track, run.durationMs)
}

/** Дистанции личных рекордов. */
private val PR_DISTANCES = listOf(1000.0 to "1 км", 3000.0 to "3 км", 5000.0 to "5 км", 10_000.0 to "10 км", 21_097.5 to "Полумарафон")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunsTab(ui: StatsUi, vm: StatsViewModel) {
    var open by remember { mutableStateOf<RunInfo?>(null) }
    var toDelete by remember { mutableStateOf<RunEntity?>(null) }
    val infos = remember(ui.runs) { ui.runs.map { RunInfo(it) } }
    // Личные рекорды: лучший отрезок нужной длины в любом месте любой пробежки
    val records = remember(infos) {
        PR_DISTANCES.mapNotNull { (d, label) ->
            infos.mapNotNull { info -> ru.streetlift.tracker.domain.RunStats.best(info.track, d)?.let { Triple(info, it.first, it.second) } }
                .minByOrNull { it.second }
                ?.let { Triple(label, it, d) }
        }
    }
    if (infos.isEmpty()) {
        Text(
            "Пробежек пока нет. Запись — во вкладке «Таймер» → «Бег».",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (records.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Личные рекорды", style = MaterialTheme.typography.titleMedium)
                        records.forEach { (label, rec, d) ->
                            val (info, ms, from) = rec
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { open = info }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, Modifier.weight(1f))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${ru.streetlift.tracker.domain.RunStats.time(ms)} · ${ru.streetlift.tracker.domain.RunStats.pace(ms / 1000.0 / (d / 1000))}/км",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${day(Instant.ofEpochMilli(info.run.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())} · " +
                                            "с ${ru.streetlift.tracker.domain.RunStats.distance(from)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        items(infos, key = { it.run.id }) { info ->
            val sm = info.summary
            Card(
                Modifier.fillMaxWidth().combinedClickable(onClick = { open = info }, onLongClick = { toDelete = info.run }),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(runDate(info.run), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (sm.distanceM > 0) ru.streetlift.tracker.domain.RunStats.distance(sm.distanceM) else "—",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(ru.streetlift.tracker.domain.RunStats.time(info.run.durationMs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${ru.streetlift.tracker.domain.RunStats.pace(sm.pace)}/км",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
    open?.let { info -> RunDetail(info) { open = null } }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить пробежку?") },
            text = { Text(runDate(r) + " · " + ru.streetlift.tracker.domain.RunStats.time(r.durationMs)) },
            confirmButton = { TextButton(onClick = { vm.deleteRun(r.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Пробежка подробно: карта по темпу, сводка, раскладка, графики темпа и высоты. */
@Composable
private fun RunDetail(info: RunInfo, onClose: () -> Unit) {
    val rs = ru.streetlift.tracker.domain.RunStats
    val sm = info.summary
    var step by rememberSaveable { mutableStateOf(1000.0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxSize().padding(8.dp)) {
          // Шапка и карта закреплены: карта не прокручивается (иначе рисуется поверх текста)
          Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(runDate(info.run), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                if (info.segments.isEmpty()) {
                    Text("Маршрут не записан (не было GPS)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(Modifier.fillMaxWidth().height(260.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
                        RouteMap(info.segments, Modifier.fillMaxWidth().height(260.dp), interactive = true, paceColors = true)
                    }
                    if (info.track.size >= 2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("быстрее", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelSmall)
                            Text("цвет маршрута — темп", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            Text("медленнее", color = Color(0xFFF44336), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // Сводка
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row {
                            StatCell("Время", rs.time(info.run.durationMs), Modifier.weight(1f))
                            StatCell("Дистанция", if (sm.distanceM > 0) rs.distance(sm.distanceM) else "—", Modifier.weight(1f))
                            StatCell("Темп", "${rs.pace(sm.pace)}/км", Modifier.weight(1f))
                        }
                        Row {
                            StatCell("Скорость", if (sm.speedKmh > 0) String.format(java.util.Locale("ru"), "%.1f км/ч", sm.speedKmh) else "—", Modifier.weight(1f))
                            StatCell("Набор высоты", sm.gainM?.let { "${it.toInt()} м" } ?: "—", Modifier.weight(1f))
                            StatCell("Сброс", sm.lossM?.let { "${it.toInt()} м" } ?: "—", Modifier.weight(1f))
                        }
                        val bestKm = rs.best(info.track, 1000.0)
                        if (bestKm != null || sm.minAlt != null) {
                            Row {
                                StatCell("Лучший км", bestKm?.let { rs.pace(it.first / 1000.0) } ?: "—", Modifier.weight(1f))
                                StatCell("Мин. высота", sm.minAlt?.let { "${it.toInt()} м" } ?: "—", Modifier.weight(1f))
                                StatCell("Макс. высота", sm.maxAlt?.let { "${it.toInt()} м" } ?: "—", Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (info.track.size < 2) {
                    Text(
                        "Подробная статистика есть только у пробежек, записанных в новой версии.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Раскладка
                    Text("Раскладка", style = MaterialTheme.typography.titleMedium)
                    ChipRow(listOf("250 м", "500 м", "1 км", "5 км", "10 км"), when (step) {
                        250.0 -> "250 м"
                        500.0 -> "500 м"
                        5000.0 -> "5 км"
                        10_000.0 -> "10 км"
                        else -> "1 км"
                    }) { t -> step = when (t) { "250 м" -> 250.0; "500 м" -> 500.0; "5 км" -> 5000.0; "10 км" -> 10_000.0; else -> 1000.0 } }
                    val splits = rs.splits(info.track, step)
                    if (splits.isEmpty()) {
                        Text("Пробежка короче отрезка", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val fastest = splits.filter { it.lengthM >= step * 0.99 }.minByOrNull { it.pace }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text("Отрезок", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium)
                                    Text("Время", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    Text("Темп", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    Text("± к ср.", Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium)
                                }
                                splits.forEach { sp ->
                                    HorizontalDivider()
                                    val diff = sp.pace - sm.pace
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (sp.lengthM < step * 0.99) "${sp.index} (${rs.distance(sp.lengthM)})" else "${sp.index}",
                                            Modifier.weight(1.3f),
                                            fontWeight = if (sp == fastest) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        Text(rs.time(sp.timeMs), Modifier.weight(1f))
                                        Text(
                                            rs.pace(sp.pace),
                                            Modifier.weight(1f),
                                            fontWeight = if (sp == fastest) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sp == fastest) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                        )
                                        Text(
                                            (if (diff <= 0) "−" else "+") + rs.pace(kotlin.math.abs(diff)).let { if (it == "—") "0:00" else it },
                                            Modifier.weight(0.9f),
                                            color = if (diff <= 0) Color(0xFF4CAF50) else ru.streetlift.tracker.ui.theme.WarnOrange,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Графики
                    val pace = rs.paceSeries(info.track)
                    if (pace.size >= 2) {
                        Text("Темп", style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                XYChart(pace, invertY = true, yLabel = { rs.pace(it) }, valueText = { "${rs.pace(it)}/км" })
                            }
                        }
                    }
                    val alt = rs.altSeries(info.track)
                    if (alt.size >= 2) {
                        Text("Высота", style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                XYChart(alt, invertY = false, yLabel = { "${it.toInt()}" }, valueText = { "${it.toInt()} м" })
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** График по дистанции (x — метры): подписи значений слева, км снизу; нажатие — значение в точке. */
@Composable
private fun XYChart(
    points: List<Pair<Double, Double>>,
    invertY: Boolean,
    yLabel: (Double) -> String,
    valueText: (Double) -> String,
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Отбрасываем выбросы темпа (старт, остановки), чтобы шкала не растягивалась
    val ys = points.map { it.second }.sorted()
    val lo = ys[(ys.size * 0.02).toInt().coerceIn(0, ys.size - 1)]
    val hi = ys[(ys.size * 0.98).toInt().coerceIn(0, ys.size - 1)]
    val pad = ((hi - lo) * 0.1).coerceAtLeast(1.0)
    val minY = lo - pad
    val maxY = hi + pad
    val minX = points.first().first
    val maxX = points.last().first
    var picked by remember(points) { mutableStateOf<Pair<Double, Double>?>(null) }
    val h = 220.dp
    Row {
        Column(Modifier.height(h).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            val top = if (invertY) minY else maxY
            val bottom = if (invertY) maxY else minY
            (0..3).forEach { i ->
                Text(yLabel(top + (bottom - top) * i / 3), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
        Canvas(
            Modifier.weight(1f).height(h).pointerInput(points) {
                detectTapGestures { off ->
                    val x = minX + (off.x / size.width) * (maxX - minX)
                    picked = points.minByOrNull { kotlin.math.abs(it.first - x) }
                }
            },
        ) {
            fun xOf(x: Double) = ((x - minX) / (maxX - minX).coerceAtLeast(1.0) * size.width).toFloat()
            fun yOf(y: Double): Float {
                val f = ((y.coerceIn(minY, maxY) - minY) / (maxY - minY)).toFloat()
                return if (invertY) f * size.height else size.height - f * size.height
            }
            (0..3).forEach { i -> val yy = size.height * i / 3; drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1f) }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(p.first), yOf(p.second)) else path.lineTo(xOf(p.first), yOf(p.second)) }
            drawPath(path, primary, style = Stroke(width = 3.dp.toPx()))
            picked?.let {
                drawLine(grid, Offset(xOf(it.first), 0f), Offset(xOf(it.first), size.height), 2f)
                drawCircle(primary, 6.dp.toPx(), Offset(xOf(it.first), yOf(it.second)))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp)) {
        (0..3).forEach { i ->
            val x = minX + (maxX - minX) * i / 3
            Text(
                String.format(java.util.Locale("ru"), "%.1f км", x / 1000),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.weight(1f),
                textAlign = when (i) {
                    0 -> androidx.compose.ui.text.style.TextAlign.Start
                    3 -> androidx.compose.ui.text.style.TextAlign.End
                    else -> androidx.compose.ui.text.style.TextAlign.Center
                },
            )
        }
    }
    picked?.let {
        Text(
            "${String.format(java.util.Locale("ru"), "%.2f км", it.first / 1000)}: ${valueText(it.second)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
