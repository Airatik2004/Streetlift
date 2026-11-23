package ru.streetlift.tracker.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import ru.streetlift.tracker.domain.Priority
import ru.streetlift.tracker.domain.PriorityMode

/** «Жим / Тяга / Авто» — порядок упражнений в днях 2–4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritySwitch(mode: PriorityMode, effective: Priority, onPick: (PriorityMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Приоритет", style = MaterialTheme.typography.bodyMedium)
            PriorityMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { onPick(m) }, label = { Text(m.title) })
            }
        }
        if (mode == PriorityMode.AUTO) {
            Text(
                "Сейчас: ${effective.title.lowercase()} — меняется с каждым мезоциклом",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
