package ru.streetlift.tracker.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.ui.common.ConfirmDialog
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.Stepper
import ru.streetlift.tracker.ui.common.TextInputDialog
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

    fun moveDay(id: Long, delta: Int) = viewModelScope.launch { repo.moveDay(id, delta) }
    fun updateDay(d: DayEntity) = viewModelScope.launch { repo.updateDay(d) }
    fun addExercise(dayId: Long) = viewModelScope.launch { repo.addExercise(dayId) }
    fun saveExercise(e: ExerciseEntity) = viewModelScope.launch { repo.saveExercise(e) }
    fun deleteExercise(e: ExerciseEntity) = viewModelScope.launch { repo.deleteExercise(e) }
    fun moveExercise(e: ExerciseEntity, delta: Int) = viewModelScope.launch { repo.moveExercise(e, delta) }
    fun setDeloadEvery(n: Int) = viewModelScope.launch { repo.setDeloadEvery(n) }
    fun setAutoStart(b: Boolean) = viewModelScope.launch { repo.setAutoStartTimer(b) }
    fun resetProgram() = viewModelScope.launch { repo.resetProgram() }
    fun reopenSetup() = viewModelScope.launch { repo.reopenSetup() }
    fun addStage(name: String, onAdded: (String) -> Unit) = viewModelScope.launch { onAdded(repo.addStage(name)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(repo: WorkoutRepository) {
    val vm: EditorViewModel = viewModel(factory = viewModelFactory { initializer { EditorViewModel(repo) } })
    val ui by vm.state.collectAsStateWithLifecycle()
    val s = ui.settings

    var confirmReset by remember { mutableStateOf(false) }
    var renameDay by remember { mutableStateOf<DayEntity?>(null) }
    var editExercise by remember { mutableStateOf<ExerciseEntity?>(null) }
    var deleteExercise by remember { mutableStateOf<ExerciseEntity?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Редактор цикла", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Плановая разгрузка", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "каждый ${s.deloadEvery}-й круг",
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

        items(ui.days, key = { it.id }) { day ->
            DayCard(
                day = day,
                exercises = ui.exercises[day.id].orEmpty(),
                isFirst = day.position == 0 || ui.days.firstOrNull()?.id == day.id,
                isLast = ui.days.lastOrNull()?.id == day.id,
                onMove = { vm.moveDay(day.id, it) },
                onRename = { renameDay = day },
                onAlternating = { vm.updateDay(day.copy(alternating = it)) },
                onAdd = { vm.addExercise(day.id) },
                onEdit = { editExercise = it },
                onDelete = { deleteExercise = it },
                onMoveExercise = { e, d -> vm.moveExercise(e, d) },
            )
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Сбросить программу?",
            text = "Цикл вернётся к шаблону по умолчанию, веса и прогресс плана обнулятся. История тренировок останется.",
            confirm = "Сбросить",
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                vm.resetProgram()
            },
        )
    }
    renameDay?.let { d ->
        TextInputDialog(
            title = "Название дня",
            initial = d.name,
            onDismiss = { renameDay = null },
            onConfirm = { t ->
                if (t.isNotBlank()) vm.updateDay(d.copy(name = t.trim()))
                renameDay = null
            },
        )
    }
    deleteExercise?.let { e ->
        ConfirmDialog(
            title = "Удалить упражнение?",
            text = "«${e.name}» будет удалено из цикла. Записи в истории останутся.",
            confirm = "Удалить",
            onDismiss = { deleteExercise = null },
            onConfirm = {
                vm.deleteExercise(e)
                deleteExercise = null
            },
        )
    }
    editExercise?.let { e ->
        ExerciseDialog(
            exercise = e,
            stages = ui.stages,
            onAddStage = { name, cb -> vm.addStage(name, cb) },
            onDismiss = { editExercise = null },
            onSave = {
                vm.saveExercise(it)
                editExercise = null
            },
        )
    }
}

@Composable
private fun DayCard(
    day: DayEntity,
    exercises: List<ExerciseEntity>,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onAlternating: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ExerciseEntity) -> Unit,
    onDelete: (ExerciseEntity) -> Unit,
    onMoveExercise: (ExerciseEntity, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onRename() }
                        .padding(4.dp),
                ) {
                    Text("День ${day.position + 1} из 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Выше")
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ниже")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Чередующиеся подходы", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = day.alternating, onCheckedChange = onAlternating)
            }
            HorizontalDivider()
            exercises.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onEdit(e) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${e.type.title} · ${Fmt.range(e.minSets, e.maxSets)} × ${Fmt.range(e.minReps, e.maxReps)} · " +
                                "${Fmt.rir(e.targetRirMin, e.targetRirMax)} · шаг ${Fmt.step(e.weightStep)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        e.leverageStage?.let {
                            Text("Стадия: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (e.mode == ExerciseMode.CALIBRATION) "Калибровка на ближайшей тренировке"
                            else "Сейчас: ${Fmt.weight(e.weightKg)}, ${e.plannedSets} подх., цель ${e.targetReps}+" +
                                if (e.emergencyDeloadNext) " · аварийная разгрузка" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { onMoveExercise(e, -1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Выше")
                    }
                    IconButton(onClick = { onMoveExercise(e, 1) }, enabled = i < exercises.size - 1, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ниже")
                    }
                    IconButton(onClick = { onEdit(e) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Изменить", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDelete(e) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить", modifier = Modifier.size(20.dp))
                    }
                }
            }
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Упражнение")
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Text else KeyboardType.Number),
    )
}

@Composable
private fun ExerciseDialog(
    exercise: ExerciseEntity,
    stages: List<StageEntity>,
    onAddStage: (String, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ExerciseEntity) -> Unit,
) {
    var name by remember { mutableStateOf(exercise.name) }
    var stage by remember { mutableStateOf(exercise.leverageStage) }
    var calibrate by remember { mutableStateOf(exercise.mode == ExerciseMode.CALIBRATION) }
    var type by remember { mutableStateOf(exercise.type) }
    var typeMenu by remember { mutableStateOf(false) }
    var minSets by remember { mutableStateOf(exercise.minSets.toString()) }
    var maxSets by remember { mutableStateOf(exercise.maxSets.toString()) }
    var minReps by remember { mutableStateOf(exercise.minReps.toString()) }
    var maxReps by remember { mutableStateOf(exercise.maxReps.toString()) }
    var rirMin by remember { mutableStateOf(exercise.targetRirMin.toString()) }
    var rirMax by remember { mutableStateOf(exercise.targetRirMax.toString()) }
    var step by remember { mutableStateOf(Fmt.step(exercise.weightStep)) }
    var weight by remember { mutableStateOf(Fmt.weight(exercise.weightKg).removeSuffix(" кг")) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Упражнение") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(type.title, Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        ExerciseType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.title) }, onClick = {
                                type = t
                                typeMenu = false
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Подходов от", minSets, { minSets = it }, Modifier.weight(1f))
                    NumField("до", maxSets, { maxSets = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Повторов от", minReps, { minReps = it }, Modifier.weight(1f))
                    NumField("до", maxReps, { maxReps = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("RIR от", rirMin, { rirMin = it }, Modifier.weight(1f))
                    NumField("до", rirMax, { rirMax = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Шаг, кг", step, { step = it }, Modifier.weight(1f), decimal = true)
                    NumField("Текущий вес", weight, { weight = it }, Modifier.weight(1f), decimal = true)
                }
                StagePicker(
                    stages = stages,
                    selected = stage,
                    onSelect = { stage = it },
                    onAddCustom = { n -> onAddStage(n) { added -> stage = added } },
                    allowNone = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stage != exercise.leverageStage) {
                    Text(
                        "Стадия меняется: история «${exercise.leverageStage ?: "без стадии"}» уйдёт в архив, " +
                            "упражнение на 1 круг перейдёт в калибровку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Калибровка на следующей тренировке", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = calibrate || stage != exercise.leverageStage, onCheckedChange = { calibrate = it })
                }
                Text(
                    "Вес знаковый: +24 — отягощение, 0 — свой вес, −15 — резина/облегчение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ms = minSets.trim().toIntOrNull()
                val xs = maxSets.trim().toIntOrNull()
                val mr = minReps.trim().toIntOrNull()
                val xr = maxReps.trim().toIntOrNull()
                val rm = rirMin.trim().toIntOrNull()
                val rx = rirMax.trim().toIntOrNull()
                val st = parseSignedFloat(step)
                val w = parseSignedFloat(weight)
                error = when {
                    name.isBlank() -> "Введите название"
                    ms == null || xs == null || ms < 1 || xs < ms -> "Проверьте диапазон подходов"
                    mr == null || xr == null || mr < 1 || xr < mr -> "Проверьте диапазон повторов"
                    rm == null || rx == null || rm < 0 || rx < rm || rx > 5 -> "RIR — от 0 до 5, «до» не меньше «от»"
                    st == null || st <= 0f -> "Шаг веса должен быть больше 0"
                    w == null -> "Проверьте вес"
                    else -> null
                }
                if (error == null) {
                    onSave(
                        exercise.copy(
                            name = name.trim(), type = type,
                            minSets = ms!!, maxSets = xs!!, minReps = mr!!, maxReps = xr!!,
                            targetRirMin = rm!!, targetRirMax = rx!!,
                            weightStep = st!!, weightKg = w!!,
                            leverageStage = stage,
                            mode = if (calibrate) ExerciseMode.CALIBRATION else ExerciseMode.ACTIVE,
                        ),
                    )
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
