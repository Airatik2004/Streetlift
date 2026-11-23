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
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Long?>(null) }

    Text(
        "Лучший один подход при каждом весе${if (seconds) " (секунды)" else ""}. Зелёным — введённые вручную; долгое нажатие — удалить.",
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
                                    "${b.value}${if (seconds) " с" else ""}",
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
    val unit = if (seconds) " с" else ""
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

private fun duration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else Fmt.clock(s)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunsTab(ui: StatsUi, vm: StatsViewModel) {
    var open by remember { mutableStateOf<RunEntity?>(null) }
    var toDelete by remember { mutableStateOf<RunEntity?>(null) }
    if (ui.runs.isEmpty()) {
        Text(
            "Пробежек пока нет. Запись — во вкладке «Таймер» → «Бег».",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.runs, key = { it.id }) { r ->
            Card(
                Modifier.fillMaxWidth().combinedClickable(onClick = { open = r }, onLongClick = { toDelete = r }),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(runDateFmt),
                        Modifier.weight(1f),
                    )
                    Text(duration(r.durationMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    open?.let { r ->
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(runDateFmt),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val segs = remember(r.id) { RunTracker.decode(r.route) }
                    if (segs.isEmpty()) {
                        Text("Маршрут не записан (не было GPS)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        RouteMap(segs, Modifier.fillMaxWidth().height(420.dp), interactive = true)
                    }
                    Text("Время: ${duration(r.durationMs)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { open = null }, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
                }
            }
        }
    }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить пробежку?") },
            text = { Text(Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(runDateFmt) + " · " + duration(r.durationMs)) },
            confirmButton = { TextButton(onClick = { vm.deleteRun(r.id); toDelete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
