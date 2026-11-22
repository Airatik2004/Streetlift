package ru.streetlift.tracker.ui.history

import android.content.ContentResolver
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.db.StageArchiveEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.ui.common.ConfirmDialog
import ru.streetlift.tracker.ui.common.Tag
import ru.streetlift.tracker.ui.home.formatDateTime
import ru.streetlift.tracker.ui.theme.DeloadGreen
import java.time.LocalDate

data class HistoryItem(val session: SessionEntity, val byExercise: List<Pair<String, List<SetLogEntity>>>)

class HistoryViewModel(private val repo: WorkoutRepository) : ViewModel() {

    val items: StateFlow<List<HistoryItem>> = combine(repo.finishedSessions, repo.allSets) { sessions, sets ->
        val bySession = sets.groupBy { it.sessionId }
        sessions.map { s ->
            val logs = bySession[s.id].orEmpty().sortedBy { it.loggedAt }
            // Разные стадии одного упражнения — разные строки статистики
            HistoryItem(
                s,
                logs.groupBy { log ->
                    log.exerciseName + (log.leverageStage?.let { " · $it" } ?: "") + if (log.isCalibration) " (калибровка)" else ""
                }.toList(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archive: StateFlow<List<StageArchiveEntity>> =
        repo.stageArchive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun export(cr: ContentResolver, uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching {
            val text = repo.exportJson()
            withContext(Dispatchers.IO) {
                val out = cr.openOutputStream(uri) ?: error("не удалось открыть файл")
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }
        onResult(r.fold({ "База экспортирована" }, { "Ошибка экспорта: ${it.message}" }))
    }

    fun importBackup(cr: ContentResolver, uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching {
            val text = withContext(Dispatchers.IO) {
                val input = cr.openInputStream(uri) ?: error("не удалось открыть файл")
                input.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            repo.importJson(text)
        }
        onResult(r.fold({ "База восстановлена из файла" }, { "Ошибка импорта: ${it.message}" }))
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteSession(id) }
}

@Composable
fun HistoryScreen(repo: WorkoutRepository) {
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(repo) } })
    val history by vm.items.collectAsStateWithLifecycle()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var toDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(ctx.contentResolver, uri, toast)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("История", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Бэкап базы (JSON)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Цикл, веса, прогресс плана и вся история. Импорт полностью заменяет текущие данные.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("streetlift-backup-${LocalDate.now()}.json") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Экспорт")
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Импорт")
                        }
                    }
                }
            }
        }

        if (archive.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Архив стадий рычага", style = MaterialTheme.typography.titleMedium)
                        archive.forEach { a ->
                            Text(
                                "${a.exerciseName}: ${a.stage} → ${a.newStage ?: "без стадии"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${formatDateTime(a.archivedAt)} · было ${Fmt.weight(a.weightKg)}, " +
                                    "${a.plannedSets} подх., цель ${a.targetReps}+, кругов на весе: ${a.roundsAtWeight}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    "Пока нет завершённых тренировок.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(history, key = { it.session.id }) { entry ->
            val s = entry.session
            val isOpen = s.id in expanded
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (isOpen) expanded - s.id else expanded + s.id },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.dayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatDateTime(s.startedAt)} · круг ${s.roundNumber}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.isDeload) Tag("Разгрузка", DeloadGreen)
                        IconButton(onClick = { toDelete = s }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить", modifier = Modifier.size(20.dp))
                        }
                    }
                    entry.byExercise.forEach { (name, logs) ->
                        Text(
                            "$name: " + logs.joinToString("  ") { "${Fmt.weight(it.weightKg).removeSuffix(" кг")}×${it.reps}(${Fmt.rirValue(it.rir)})" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isOpen && s.summary.isNotBlank()) {
                        HorizontalDivider()
                        Text("Решения движка:", style = MaterialTheme.typography.labelLarge)
                        Text(s.summary, style = MaterialTheme.typography.bodySmall)
                    } else if (s.summary.isNotBlank()) {
                        Text(
                            "Нажмите, чтобы увидеть пересчёт плана",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = "Импортировать базу?",
            text = "Все текущие данные (цикл, веса, история) будут заменены содержимым файла.",
            confirm = "Импортировать",
            onDismiss = { pendingImport = null },
            onConfirm = {
                pendingImport = null
                vm.importBackup(ctx.contentResolver, uri, toast)
            },
        )
    }
    toDelete?.let { s ->
        ConfirmDialog(
            title = "Удалить тренировку из истории?",
            text = "${s.dayName}, ${formatDateTime(s.startedAt)}. План цикла это не изменит.",
            confirm = "Удалить",
            onDismiss = { toDelete = null },
            onConfirm = {
                vm.delete(s.id)
                toDelete = null
            },
        )
    }
}
