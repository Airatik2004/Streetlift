package ru.streetlift.tracker.ui.setup

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import ru.streetlift.tracker.data.db.StageEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.ExerciseMode
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.ui.common.StagePicker
import ru.streetlift.tracker.ui.common.parseSignedFloat

data class SetupUi(
    val days: List<DayEntity> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val stages: List<StageEntity> = emptyList(),
)

class SetupViewModel(private val repo: WorkoutRepository) : ViewModel() {
    val state: StateFlow<SetupUi> = combine(repo.days, repo.exercises, repo.stages) { d, e, s ->
        SetupUi(d, e, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUi())


    fun save(drafts: List<WorkoutRepository.SetupDraft>) = viewModelScope.launch { repo.completeSetup(drafts) }
}

/** Черновик строки: вес хранится строкой, чтобы можно было печатать «−» и запятую. */
private data class Draft(val stage: String?, val weight: String, val calibrate: Boolean)

/** «Настройка стартовых параметров» — показывается при первом запуске (и из редактора). */
@Composable
fun SetupScreen(repo: WorkoutRepository) {
    val vm: SetupViewModel = viewModel(factory = viewModelFactory { initializer { SetupViewModel(repo) } })
    val ui by vm.state.collectAsStateWithLifecycle()
    val drafts = remember { mutableStateMapOf<Long, Draft>() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.exercises) {
        ui.exercises.forEach { e ->
            if (e.id !in drafts) {
                drafts[e.id] = Draft(
                    stage = e.leverageStage,
                    weight = Fmt.weight(e.weightKg).removeSuffix(" кг"),
                    // По умолчанию — подобрать на тренировке, если вес ещё не задан
                    calibrate = e.mode == ExerciseMode.CALIBRATION || e.weightKg == 0f,
                )
            }
        }
    }

    val dayNames = ui.days.associate { it.id to it.name }
    val dayOrder = ui.days.associate { it.id to it.position }
    val ordered = ui.exercises.sortedWith(compareBy({ dayOrder[it.dayId] ?: 0 }, { it.position }))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Настройка стартовых параметров", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Для каждого упражнения введите известный рабочий вес (+10, 0, −15 кг) " +
                        "или включите «Подобрать на тренировке» — тогда первый круг пройдёт в режиме калибровки: " +
                        "вы подберёте вес под коридор повторений с запасом 2 и запишете 3 рабочих подхода.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(ordered, key = { it.id }) { e ->
            val d = drafts[e.id] ?: return@items
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dayNames[e.dayId].orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Fmt.range(e.minSets, e.maxSets)} × ${Fmt.range(e.minReps, e.maxReps)} · ${Fmt.rir(e.targetRirMin, e.targetRirMax)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (e.stageGroup != null) {
                        StagePicker(
                            stages = ui.stages.filter { it.groupKey == e.stageGroup },
                            selected = d.stage,
                            onSelect = { drafts[e.id] = d.copy(stage = it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подобрать на тренировке (Калибровка)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = d.calibrate, onCheckedChange = { drafts[e.id] = d.copy(calibrate = it) })
                    }
                    if (!d.calibrate) {
                        OutlinedTextField(
                            value = d.weight,
                            onValueChange = { drafts[e.id] = d.copy(weight = it) },
                            label = { Text("Рабочий вес, кг") },
                            singleLine = true,
                            isError = parseSignedFloat(d.weight) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val bad = ordered.firstOrNull { e ->
                            val d = drafts[e.id]
                            d != null && !d.calibrate && parseSignedFloat(d.weight) == null
                        }
                        if (bad != null) {
                            error = "Проверьте вес: «${bad.name}»"
                        } else {
                            vm.save(
                                ordered.mapNotNull { e ->
                                    val d = drafts[e.id] ?: return@mapNotNull null
                                    WorkoutRepository.SetupDraft(
                                        exerciseId = e.id,
                                        stage = d.stage,
                                        weightKg = if (d.calibrate) e.weightKg else parseSignedFloat(d.weight) ?: 0f,
                                        calibrate = d.calibrate,
                                    )
                                },
                            )
                        }
                    },
                    enabled = ordered.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить и начать") }
            }
        }
    }
}
