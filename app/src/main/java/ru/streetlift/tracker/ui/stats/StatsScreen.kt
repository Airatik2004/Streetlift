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
@Composable
fun StatsScreen(repo: WorkoutRepository) {
    val vm: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(repo) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Выбор упражнения/стадии/веса общий для рекордов и графиков
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by rememberSaveable { mutableStateOf<String?>(null) }
    var stageSet by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("История", "Рекорды", "Графики", "Бег").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HistoryScreen(repo)
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
                            ChartTab(ui, sel, selStage)
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
        "Лучший один подход при каждом весе${if (seconds) " (секунды)" else ""}. Долгое нажатие на ✎ — удалить.",
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
                                    "${b.value}${if (seconds) " с" else ""}${if (b.manualId != null) " ✎" else ""}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(day(b.epochDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ChartTab(ui: StatsUi, name: String, stage: String?) {
    val weights = Records.weights(ui.entries, name, stage)
    var wKey by rememberSaveable(name, stage) { mutableStateOf<Int?>(null) }
    val w = weights.firstOrNull { Records.key(it) == wKey } ?: weights.firstOrNull()
    var sum by rememberSaveable { mutableStateOf(false) }
    val seconds = ui.entries.firstOrNull { it.name == name }?.isSeconds ?: false
    if (w == null) {
        Text("Нет подходов", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    ChipRow(weights.map { Fmt.weight(it) }, Fmt.weight(w)) { label -> wKey = weights.firstOrNull { Fmt.weight(it) == label }?.let { Records.key(it) } }
    ChipRow(listOf("Лучший подход", "Сумма повторов"), if (sum) "Сумма повторов" else "Лучший подход") { sum = it == "Сумма повторов" }
    val pts = Records.series(ui.entries, name, stage, w, sum)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            LineChart(pts, if (seconds) " с" else "")
        }
    }
    Text(
        if (sum) "Сумма всех подходов с этим весом за тренировку." else "Лучший подход с этим весом за тренировку; ✎ — рекорды, введённые вручную.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Простой линейный график по датам; нажатие — значение ближайшей точки. */
@Composable
private fun LineChart(points: List<Records.Point>, unit: String) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val manualColor = MaterialTheme.colorScheme.tertiary
    var picked by remember(points) { mutableStateOf<Records.Point?>(points.lastOrNull()) }
    if (points.isEmpty()) {
        Text("Нет точек", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val minX = points.first().epochDay
    val maxX = points.last().epochDay
    val minY = points.minOf { it.value }
    val maxY = points.maxOf { it.value }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.height(200.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("$maxY", style = MaterialTheme.typography.labelSmall)
            Text("$minY", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.padding(start = 6.dp))
        Canvas(
            Modifier.weight(1f).height(200.dp).pointerInput(points) {
                detectTapGestures { off ->
                    val w = size.width.toFloat()
                    picked = points.minByOrNull {
                        val x = if (maxX == minX) w / 2 else (it.epochDay - minX).toFloat() / (maxX - minX) * w
                        kotlin.math.abs(x - off.x)
                    }
                }
            },
        ) {
            val pad = 8.dp.toPx()
            fun xy(p: Records.Point): Offset {
                val x = if (maxX == minX) size.width / 2 else (p.epochDay - minX).toFloat() / (maxX - minX) * (size.width - 2 * pad) + pad
                val y = if (maxY == minY) size.height / 2 else size.height - pad - (p.value - minY).toFloat() / (maxY - minY) * (size.height - 2 * pad)
                return Offset(x, y)
            }
            listOf(0f, 0.5f, 1f).forEach { f ->
                drawLine(grid, Offset(0f, pad + f * (size.height - 2 * pad)), Offset(size.width, pad + f * (size.height - 2 * pad)), 1f)
            }
            val line = points.filter { !it.manual }
            if (line.size >= 2) {
                val path = Path()
                line.forEachIndexed { i, p -> xy(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                drawPath(path, primary, style = Stroke(width = 3.dp.toPx()))
            }
            points.forEach { p ->
                drawCircle(if (p.manual) manualColor else primary, radius = if (p == picked) 7.dp.toPx() else 4.dp.toPx(), center = xy(p))
            }
            picked?.let { drawCircle(Color.White, radius = 2.dp.toPx(), center = xy(it)) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(day(minX), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(day(maxX), style = MaterialTheme.typography.labelSmall)
    }
    picked?.let {
        Text(
            "${day(it.epochDay)}: ${it.value}$unit${if (it.manual) " ✎" else ""}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
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
