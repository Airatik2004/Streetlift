package ru.streetlift.tracker.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    /** Последнее сделанное из пары пистолетики / прыжки. */
    val lastAlt: ru.streetlift.tracker.data.db.LastAlt? = null,
)

class EditorViewModel(private val repo: WorkoutRepository) : ViewModel() {
    val state: StateFlow<EditorUi> = combine(repo.settings, repo.days, repo.exercises, repo.stages, repo.lastAlt) { s, d, e, st, la ->
        EditorUi(s, d, e.groupBy { it.dayId }.mapValues { (_, v) -> v.sortedBy { it.position } }, st, la)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUi())

    fun saveExercise(e: ExerciseEntity) = viewModelScope.launch { repo.saveExercise(e) }
    fun setAlternating(day: DayEntity, on: Boolean) = viewModelScope.launch { repo.updateDay(day.copy(alternating = on)) }
    fun setDeload(want: Boolean) = viewModelScope.launch { repo.setDeload(want) }
    fun setBands(b: List<ru.streetlift.tracker.domain.Band>) = viewModelScope.launch { repo.setBands(b) }
    fun setReminders(s: SettingsEntity) =
        viewModelScope.launch { repo.setReminders(s.remindEve, s.remindEveMin, s.remindDay, s.remindDayMin, s.remindLate, s.remindLateMin) }
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
    var editBands by remember { mutableStateOf(false) }

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

        // Два цикла подряд: текущий и следующий. В Дне 1 — то, что там реально будет
        // (пистолетики / прыжки по очереди по факту); порядок — по приоритету мезоцикла,
        // на подводке первыми идут упражнения пика.
        val round1 = s.completedRounds + 1
        val mesoEnds = s.cycleInMeso >= s.deloadEvery || s.forcedDeload
        val nextMeso = if (mesoEnds) s.mesoIndex + 1 else s.mesoIndex
        val nextCycle = if (mesoEnds) 1 else s.cycleInMeso + 1
        // День 1 этого цикла уже сделан — в первом блоке то, что делали; иначе — что будет
        val last = ui.lastAlt
        val slot0 = if (last != null && last.roundNumber == round1) last.slot else ru.streetlift.tracker.data.db.nextAltSlot(last)
        val workoutDay = maxOf(java.time.LocalDate.now().toEpochDay(), s.nextWorkoutEpochDay ?: 0L)
        val peakOn = ru.streetlift.tracker.domain.Peaking.phaseOn(s.peakEpochDay, workoutDay, s.peakSpanDays) !=
            ru.streetlift.tracker.domain.PeakPhase.NONE
        val peakIds = if (peakOn) s.peakIds else emptySet()
        listOf(0, 1).forEach { block ->
            val slot = if (block == 0) slot0 else 1 - slot0
            val priority = ru.streetlift.tracker.domain.Priority.forMeso(if (block == 0) s.mesoIndex else nextMeso)
            val cycleNo = if (block == 0) s.cycleInMeso else nextCycle
            item(key = "head_$block") {
                Column(Modifier.padding(top = if (block == 0) 0.dp else 8.dp)) {
                    Text(
                        if (block == 0) "Текущий цикл" else "Следующий цикл",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        ru.streetlift.tracker.domain.Priority.cycleLabel(priority, cycleNo, s.deloadEvery) +
                            if (peakOn) " · подводка: упражнения пика — первыми" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.days, key = { "d${block}_${it.id}" }) { day ->
                val dayExercises = ui.exercises[day.id].orEmpty()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("День ${day.position + 1} из 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (block == 0 && day.position == s.currentDayIndex) Tag("Следующий", MaterialTheme.colorScheme.primary)
                        }
                        // Суперсет — только в текущем цикле, чтобы не дублировать
                        if (block == 0 && dayExercises.size > 1) {
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
                        ru.streetlift.tracker.domain.ProgramOrder.order(
                            day.position, dayExercises.filter { it.activeInSlot(slot) }, priority,
                        ) { it.id in peakIds }.forEach { e ->
                            // Во втором цикле упражнение, которое было и в первом, показываем «на цикл вперёд»
                            val ahead = block == 1 && e.activeInSlot(slot0)
                            ExerciseRow(e, s, block, ahead) { editExercise = e }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReminderSettings(s) { vm.setReminders(it) }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { editBands = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Резины", style = MaterialTheme.typography.titleMedium)
                            Text(
                                s.bandList.joinToString(" · ") { "${it.name} ${Fmt.step(it.kg)} кг" }.ifEmpty { "нет" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.Edit, contentDescription = "Изменить")
                    }
                    HorizontalDivider()
                    val globalDeload = s.forcedDeload
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
    if (editBands) {
        BandsDialog(
            initial = s.bandList,
            onDismiss = { editBands = false },
            onSave = { vm.setBands(it); editBands = false },
        )
    }
    editExercise?.let { e ->
        CalibrationDialog(
            exercise = e,
            stages = ui.stages.filter { it.groupKey == e.stageGroup },
            deloadEvery = s.deloadEvery,
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
    deloadEvery: Int,
    onDismiss: () -> Unit,
    onSave: (ExerciseEntity) -> Unit,
) {
    var stage by remember { mutableStateOf(exercise.leverageStage) }
    var calibrate by remember { mutableStateOf(exercise.mode == ExerciseMode.CALIBRATION) }
    var deload by remember { mutableStateOf(exercise.emergencyDeloadNext) }
    var skipPlanned by remember { mutableStateOf(exercise.skipPlannedDeload == true) }
    var weight by remember { mutableStateOf(exercise.loadScale.snap(exercise.weightKg)) }
    var height by remember { mutableStateOf(exercise.heightCm ?: 60) }
    var error by remember { mutableStateOf<String?>(null) }
    val stageChanged = stage != exercise.leverageStage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exercise.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (exercise.isJump) {
                        ru.streetlift.tracker.ui.common.Stepper(
                            label = "Высота, см",
                            value = "$height",
                            onMinus = { height = (height - ru.streetlift.tracker.domain.Jumps.STEP_CM).coerceAtLeast(ru.streetlift.tracker.domain.Jumps.MIN_CM) },
                            onPlus = { height += ru.streetlift.tracker.domain.Jumps.STEP_CM },
                        )
                    } else {
                        ru.streetlift.tracker.ui.common.WeightStepper(weight, exercise.loadScale, { weight = it })
                    }
                }
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
                            "Этот разгрузочный цикл — без разгрузки. Следующая — через $deloadEvery " +
                                ru.streetlift.tracker.ui.home.plural(deloadEvery, "цикл", "цикла", "циклов") + ", как обычно",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = skipPlanned, onCheckedChange = { skipPlanned = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = weight
                run {
                    onSave(
                        exercise.copy(
                            weightKg = if (exercise.isJump) exercise.weightKg else w,
                            heightCm = if (exercise.isJump) height else exercise.heightCm,
                            leverageStage = stage,
                            mode = if (calibrate || stageChanged) ExerciseMode.CALIBRATION else ExerciseMode.ACTIVE,
                            emergencyDeloadNext = deload,
                            skipPlannedDeload = skipPlanned.takeIf { it },
                        ),
                    )
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** «Разгрузка через k циклов» для упражнения; null — разгрузка выключена или идёт сейчас. */
internal fun deloadInText(e: ExerciseEntity, every: Int): String? {
    if (every <= 0) return null
    val k = e.cyclesToDeload(every)
    if (k <= 0) return null
    val w = when {
        k % 100 in 11..14 -> "циклов"
        k % 10 == 1 -> "цикл"
        k % 10 in 2..4 -> "цикла"
        else -> "циклов"
    }
    return "Разгрузка через $k $w"
}

/** Набор резин: название и помощь в кг; можно добавить новую и удалить. */
@Composable
private fun BandsDialog(
    initial: List<ru.streetlift.tracker.domain.Band>,
    onDismiss: () -> Unit,
    onSave: (List<ru.streetlift.tracker.domain.Band>) -> Unit,
) {
    val rows = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply { addAll(initial.map { it.name to Fmt.step(it.kg) }) } }
    val valid = rows.all { (n, kg) -> n.isNotBlank() && (kg.replace(',', '.').toFloatOrNull() ?: 0f) > 0f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Резины") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Помощь каждой резины в кг. Лестница сочетаний собирается сама.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEachIndexed { i, (name, kg) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { rows[i] = it to kg },
                            label = { Text("Название") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        OutlinedTextField(
                            value = kg,
                            onValueChange = { rows[i] = name to it },
                            label = { Text("кг") },
                            singleLine = true,
                            isError = (kg.replace(',', '.').toFloatOrNull() ?: 0f) <= 0f,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                    }
                }
                if (rows.size < 8) {
                    TextButton(onClick = { rows.add("" to "") }) { Text("+ Добавить резину") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(rows.map { (n, kg) -> ru.streetlift.tracker.domain.Band(n.trim(), kg.replace(',', '.').toFloat()) }.sortedByDescending { it.kg })
                },
                enabled = valid,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Напоминания о тренировке: накануне, в день и вечером, если не сделана; время — по нажатию. */
@Composable
private fun ReminderSettings(s: SettingsEntity, onChange: (SettingsEntity) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var exact by remember { mutableStateOf(ru.streetlift.tracker.reminder.Reminders.canExact(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        exact = ru.streetlift.tracker.reminder.Reminders.canExact(ctx)
        onPauseOrDispose { }
    }
    fun hm(m: Int) = String.format(java.util.Locale.US, "%d:%02d", m / 60, m % 60)
    fun pick(current: Int, set: (Int) -> Unit) {
        android.app.TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, current / 60, current % 60, true).show()
    }
    @Composable
    fun Item(title: String, sub: String, on: Boolean, minutes: Int, onToggle: (Boolean) -> Unit, onTime: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { pick(minutes, onTime) }) {
                Text("$title · ${hm(minutes)}", style = MaterialTheme.typography.bodyMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Напоминания", style = MaterialTheme.typography.titleMedium)
        Text("Нажмите на строку, чтобы изменить время.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Item("Накануне", "«Завтра тренировка» — если по плану она завтра", s.remindEve, s.remindEveMin,
            { onChange(s.copy(remindEve = it)) }, { onChange(s.copy(remindEveMin = it)) })
        Item("В день тренировки", "«Сегодня тренировка», кнопки «Начать» и «Завтра»", s.remindDay, s.remindDayMin,
            { onChange(s.copy(remindDay = it)) }, { onChange(s.copy(remindDayMin = it)) })
        Item("Вечером, если не сделана", "«Тренировка сегодня ещё не сделана», «Начать» и «Завтра»", s.remindLate, s.remindLateMin,
            { onChange(s.copy(remindLate = it)) }, { onChange(s.copy(remindLateMin = it)) })
        if (!exact && (s.remindEve || s.remindDay || s.remindLate)) {
            Text(
                "Нет разрешения на точное время — напоминание может прийти с опозданием на несколько минут.",
                style = MaterialTheme.typography.bodySmall,
                color = ru.streetlift.tracker.ui.theme.WarnOrange,
            )
            TextButton(onClick = {
                ru.streetlift.tracker.reminder.Reminders.exactSettingsIntent(ctx)?.let { runCatching { ctx.startActivity(it) } }
            }) { Text("Разрешить точное время") }
        }
    }
}

/**
 * Строка упражнения в «Цикле». [block] — 0 текущий цикл, 1 следующий.
 * [ahead] — во втором цикле для упражнения, которое есть и в первом: значения текущие,
 * разгрузка показывается, только если она придётся именно на следующий цикл.
 */
@Composable
private fun ExerciseRow(e: ExerciseEntity, s: SettingsEntity, block: Int, ahead: Boolean, onClick: () -> Unit) {
    // «Разгрузка на этот цикл» действует только на текущий цикл
    val forced = s.forcedDeload && block == 0
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (e.mode == ExerciseMode.CALIBRATION && !ahead) Tag("Калибровка", MaterialTheme.colorScheme.secondary)
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
        if (ahead) {
            // Разгрузка на цикл вперёд: сейчас её нет, а следующий цикл упражнения — N-й
            val nowDeload = s.forcedDeload || e.plannedDeload(s.deloadEvery, false)
            if (e.mode == ExerciseMode.ACTIVE && !nowDeload && e.cycleNo == s.deloadEvery - 1) {
                Tag("Разгрузка", ru.streetlift.tracker.ui.theme.DeloadGreen)
            }
        } else if (e.mode != ExerciseMode.CALIBRATION) {
            when {
                forced || e.plannedDeload(s.deloadEvery, false) ->
                    Tag("Разгрузка", ru.streetlift.tracker.ui.theme.DeloadGreen)
                e.deloadDue(s.deloadEvery) && e.skipPlannedDeload == true ->
                    Tag("Без разгрузки", ru.streetlift.tracker.ui.theme.DeloadGreen)
                else -> deloadInText(e, s.deloadEvery)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (e.mode != ExerciseMode.CALIBRATION) {
            Text(
                (if (e.isJump) "Сейчас: ${e.jumpState?.let { ru.streetlift.tracker.domain.Jumps.describe(it) } ?: "высота не задана"}"
                else "Сейчас: ${Fmt.weight(e.weightKg)}, ${e.plannedSets} подх., цель ${e.targetReps}+${if (e.isSeconds) " с" else ""}") +
                    (if (e.altSlot != null) " · через цикл" else "") +
                    if (e.emergencyDeloadNext && !ahead) " · разгрузка на следующей" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
