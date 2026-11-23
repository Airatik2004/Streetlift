package ru.streetlift.tracker.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.ProgressionEngine
import ru.streetlift.tracker.workout.sideName

/**
 * Изменение записанного подхода: вес, повторы, запас. Удаление — отдельной кнопкой.
 * У односторонних можно передать вторую сторону [log2]: вверху появится переключатель
 * «Левая | Правая», у каждой стороны свои значения; сохраняются и удаляются обе.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetEditDialog(
    log: SetLogEntity,
    onDismiss: () -> Unit,
    /** Вызывается для каждой стороны отдельно. */
    onSave: (SetLogEntity) -> Unit,
    /** Вызывается для каждой стороны отдельно. */
    onDelete: (SetLogEntity) -> Unit,
    /** Шкала упражнения: вес округляется до шага / сочетания резин. */
    scale: ru.streetlift.tracker.domain.LoadScale? = null,
    /** Вторая сторона того же подхода (у односторонних). */
    log2: SetLogEntity? = null,
) {
    // Стороны по порядку: левая, затем правая; открывается та, что передана первой
    val logs = remember(log, log2) {
        listOfNotNull(log, log2).sortedBy { if (it.side == "R") 1 else 0 }
    }
    val weights = remember(logs) { mutableStateListOf(*logs.map { Fmt.weightValue(it.weightKg) }.toTypedArray()) }
    val repsList = remember(logs) { mutableStateListOf(*logs.map { it.reps.toString() }.toTypedArray()) }
    val rirs = remember(logs) { mutableStateListOf(*logs.map { it.rir }.toTypedArray()) }
    var sel by remember(logs) { mutableIntStateOf(logs.indexOf(log).coerceAtLeast(0)) }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsedW = weights.map { parseSignedFloat(it) }
    val parsedR = repsList.map { it.trim().toIntOrNull() }
    val allValid = parsedW.all { it != null } && parsedR.all { it != null }
    val cur = logs[sel]
    val sideText = sideName(cur.side)

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить подход?",
            text = logs.joinToString("\n") { l ->
                (sideName(l.side).takeIf { it.isNotEmpty() }?.let { "${it.replaceFirstChar(Char::uppercase)}: " } ?: "${l.exerciseName}: ") +
                    "${Fmt.weight(l.weightKg)} × ${l.reps}, RIR ${Fmt.rirValue(l.rir)}"
            } + if (logs.size > 1) "\nУдалятся обе стороны." else "",
            confirm = "Удалить",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                logs.forEach(onDelete)
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${cur.exerciseName} · подход ${cur.setIndex}" + if (sideText.isNotEmpty()) " · $sideText" else "")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (logs.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        logs.forEachIndexed { i, l ->
                            val bad = parsedW[i] == null || parsedR[i] == null
                            FilterChip(
                                selected = sel == i,
                                onClick = { sel = i },
                                label = {
                                    Text(
                                        sideName(l.side).replaceFirstChar(Char::uppercase),
                                        color = if (bad) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                                    )
                                },
                            )
                        }
                    }
                }
                // key: при смене стороны поля пересоздаются с её значениями
                key(sel) {
                    OutlinedTextField(
                        value = weights[sel],
                        onValueChange = { weights[sel] = it },
                        label = { Text("Вес, кг (+ отягощение, − резина)") },
                        singleLine = true,
                        isError = parsedW[sel] == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repsList[sel],
                        onValueChange = { repsList[sel] = it.filter(Char::isDigit).take(3) },
                        label = { Text(if (cur.isSeconds) "Секунды" else "Повторы") },
                        singleLine = true,
                        isError = parsedR[sel] == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Запас (RIR)", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { v ->
                        FilterChip(selected = rirs[sel] == v, onClick = { rirs[sel] = v }, label = { Text(Fmt.rirValue(v)) })
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить подход", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allValid,
                onClick = {
                    if (allValid) {
                        logs.forEachIndexed { i, l ->
                            val w = parsedW[i]!!
                            val updated = l.copy(
                                weightKg = ProgressionEngine.roundWeight(w).let { scale?.snapManual(it) ?: it },
                                reps = parsedR[i]!!,
                                rir = rirs[i],
                            )
                            if (updated != l) onSave(updated)
                        }
                        onDismiss()
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
