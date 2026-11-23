package ru.streetlift.tracker.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import ru.streetlift.tracker.data.db.StageEntity

/**
 * Выбор стадии из упорядоченного справочника этого упражнения (список приходит уже отфильтрованным).
 * allowNone — можно убрать стадию.
 */
@Composable
fun StagePicker(
    stages: List<StageEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
    label: String = "Стадия рычага",
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected ?: "без стадии", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("Без стадии") }, onClick = {
                    open = false
                    onSelect(null)
                })
                HorizontalDivider()
            }
            stages.forEachIndexed { i, st ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${i + 1}. ${st.name}")
                            if (st.description.isNotBlank()) {
                                Text(
                                    st.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(st.name)
                    },
                )
            }
        }
    }
}
