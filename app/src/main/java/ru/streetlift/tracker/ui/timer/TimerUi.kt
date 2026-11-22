package ru.streetlift.tracker.ui.timer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.streetlift.tracker.domain.ExerciseType
import ru.streetlift.tracker.domain.Fmt
import ru.streetlift.tracker.domain.RestRecommendation
import ru.streetlift.tracker.domain.RestRecommender
import ru.streetlift.tracker.timer.RestTimer
import ru.streetlift.tracker.timer.TimerActions
import ru.streetlift.tracker.ui.theme.TimerDigits

val PRESETS_SECONDS = listOf(60, 90, 120, 150, 180, 240, 300)

/** Круговой индикатор прогресса. */
@Composable
fun TimerRing(
    progress: Float,
    diameter: Dp,
    stroke: Dp,
    finished: Boolean,
    content: @Composable () -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (finished) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(sw))
            drawArc(
                color, -90f, 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = topLeft, size = arc, style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

/** Плашка-подсказка с рекомендуемым временем: «Поставить» — один тап. */
@Composable
fun RecommendationBanner(rec: RestRecommendation, onApply: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Рекомендация: ${Fmt.clock(rec.seconds)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(rec.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            TextButton(onClick = onApply) { Text("Поставить") }
        }
    }
}

@Composable
private fun PresetRow(onPick: (Int) -> Unit, onManual: () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PRESETS_SECONDS.forEach { sec ->
            OutlinedButton(
                onClick = { onPick(sec) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text(Fmt.clock(sec)) }
        }
        FilledTonalButton(onClick = onManual) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Своё")
        }
    }
}

@Composable
private fun ControlsRow(st: RestTimer.State, big: Boolean) {
    val ctx = LocalContext.current
    val btn = if (big) 64.dp else 48.dp
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = if (big) 16.dp else 8.dp, vertical = 0.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 16.dp else 6.dp)) {
        FilledTonalButton(onClick = { TimerActions.add(-15_000) }, contentPadding = pad) { Text("−15с") }
        FilledIconButton(
            onClick = { TimerActions.toggle(ctx) },
            modifier = Modifier.size(btn),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (st.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (st.running) "Пауза" else "Старт",
                modifier = Modifier.size(if (big) 34.dp else 26.dp),
            )
        }
        FilledTonalButton(onClick = { TimerActions.add(15_000) }, contentPadding = pad) { Text("+15с") }
        FilledTonalIconButton(onClick = { TimerActions.reset() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Сброс")
        }
    }
}

/** Диалог ручного ввода времени (минуты и секунды). */
@Composable
fun ManualTimeDialog(initialSeconds: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var min by remember { mutableStateOf((initialSeconds / 60).toString()) }
    var sec by remember { mutableStateOf((initialSeconds % 60).toString().padStart(2, '0')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Своё время отдыха") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = min, onValueChange = { min = it.filter(Char::isDigit).take(2) },
                    label = { Text("мин") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(":", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    value = sec, onValueChange = { sec = it.filter(Char::isDigit).take(2) },
                    label = { Text("сек") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val total = (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0).coerceIn(0, 59)
                if (total > 0) onConfirm(total)
            }) { Text("Запустить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Панель таймера отдыха. compact = true — для экрана тренировки.
 */
@Composable
fun RestTimerPanel(
    recommendation: RestRecommendation?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val st by RestTimer.state.collectAsStateWithLifecycle()
    var showManual by remember { mutableStateOf(false) }
    val label = st.label

    if (showManual) {
        ManualTimeDialog(
            initialSeconds = (st.totalMs / 1000).toInt(),
            onDismiss = { showManual = false },
            onConfirm = { s ->
                showManual = false
                TimerActions.start(ctx, s * 1000L, label)
            },
        )
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimerRing(st.progress, 92.dp, 6.dp, st.finished) {
                    Text(
                        Fmt.clock(st.remainingSeconds),
                        style = TimerDigits.copy(fontSize = 24.sp),
                        color = if (st.finished) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                ControlsRow(st, big = false)
            }
            PresetRow(onPick = { TimerActions.start(ctx, it * 1000L, label) }, onManual = { showManual = true })
        } else {
            TimerRing(st.progress, 280.dp, 12.dp, st.finished) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Fmt.clock(st.remainingSeconds),
                        style = TimerDigits,
                        color = if (st.finished) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            st.finished -> "Отдых окончен"
                            st.running -> "из ${Fmt.clock((st.totalMs / 1000).toInt())}"
                            st.idle -> "готов"
                            else -> "пауза"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ControlsRow(st, big = true)
            PresetRow(onPick = { TimerActions.start(ctx, it * 1000L, label) }, onManual = { showManual = true })
        }
        if (recommendation != null) {
            RecommendationBanner(recommendation) {
                TimerActions.start(ctx, recommendation.seconds * 1000L, label)
            }
        }
    }
}

/** Отдельная вкладка «Таймер» — полноразмерный таймер + справка по рекомендациям. */
@Composable
fun TimerScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Таймер отдыха", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        RestTimerPanel(recommendation = null, compact = false)

        Text(
            "Быстрые рекомендации",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        val recs = listOf(
            RestRecommender.recommend(ExerciseType.HEAVY_LEVER, alternating = true, deload = false),
            RestRecommender.recommend(ExerciseType.HEAVY_LEVER, alternating = false, deload = false),
            RestRecommender.recommend(ExerciseType.HYPERTROPHY_PUMP, alternating = false, deload = false),
            RestRecommender.recommend(ExerciseType.ACCESSORY, alternating = false, deload = false),
        )
        recs.forEach { r ->
            RecommendationBanner(r) { TimerActions.start(ctx, r.seconds * 1000L, "") }
        }
        Text(
            "В разгрузочном цикле рекомендации автоматически короче на 30 секунд.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
