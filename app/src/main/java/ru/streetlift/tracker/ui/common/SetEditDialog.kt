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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.ProgressionEngine

/** Изменение записанного подхода: вес, повторы, запас. Удаление — отдельной кнопкой. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetEditDialog(
    log: SetLogEntity,
    onDismiss: () -> Unit,
    onSave: (SetLogEntity) -> Unit,
    onDelete: (SetLogEntity) -> Unit,
) {
    var weight by remember { mutableStateOf(Fmt.weight(log.weightKg).removeSuffix(" кг")) }
    var reps by remember { mutableStateOf(log.reps.toString()) }
    var rir by remember { mutableIntStateOf(log.rir) }
    var confirmDelete by remember { mutableStateOf(false) }
    val w = parseSignedFloat(weight)
    val r = reps.trim().toIntOrNull()

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить подход?",
            text = "${log.exerciseName}: ${Fmt.weight(log.weightKg)} × ${log.reps}, RIR ${Fmt.rirValue(log.rir)}",
            confirm = "Удалить",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete(log)
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${log.exerciseName} · подход ${log.setIndex}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Вес, кг (+ отягощение, − резина)") },
                    singleLine = true,
                    isError = w == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it.filter(Char::isDigit).take(3) },
                    label = { Text(if (log.isSeconds) "Секунды" else "Повторы") },
                    singleLine = true,
                    isError = r == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Запас (RIR)", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { v ->
                        FilterChip(selected = rir == v, onClick = { rir = v }, label = { Text(Fmt.rirValue(v)) })
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить подход", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = w != null && r != null,
                onClick = {
                    if (w != null && r != null) {
                        onSave(log.copy(weightKg = ProgressionEngine.roundWeight(w), reps = r, rir = rir))
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
