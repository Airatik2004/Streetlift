package ru.streetlift.tracker.ui.timer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.core.content.IntentCompat
import ru.streetlift.tracker.timer.TimerPrefs
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
        FilledTonalButton(onClick = onManual) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Своё")
        }
        PRESETS_SECONDS.forEach { sec ->
            OutlinedButton(
                onClick = { onPick(sec) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text(Fmt.clock(sec)) }
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
            }) { Text("Поставить") }
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
                TimerActions.set(s * 1000L, label)
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
            PresetRow(onPick = { TimerActions.set(it * 1000L, label) }, onManual = { showManual = true })
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
            PresetRow(onPick = { TimerActions.set(it * 1000L, label) }, onManual = { showManual = true })
        }
        if (recommendation != null) {
            RecommendationBanner(recommendation) {
                TimerActions.set(recommendation.seconds * 1000L, label)
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
        SignalSettingsCard()

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
            RecommendationBanner(r) { TimerActions.set(r.seconds * 1000L, "") }
        }
        Text(
            "В разгрузочном цикле рекомендации автоматически короче на 30 секунд.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun soundTitle(context: Context, uri: String?): String {
    if (uri == null) return "свисток (встроенный)"
    return runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }
        .getOrNull() ?: "выбранный звук"
}

/** Настройки сигнала окончания отдыха: мелодия, длительность, громкость, вибрация, пики. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalSettingsCard() {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(TimerPrefs.load(ctx)) }
    fun update(np: TimerPrefs.Prefs) {
        p = np
        TimerPrefs.save(ctx, np)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val picked = res.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            update(p.copy(soundUri = picked?.toString()))
        }
    }
    val title = remember(p.soundUri) { soundTitle(ctx, p.soundUri) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Сигнал окончания отдыха", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Звук", Modifier.weight(1f))
                Switch(checked = p.soundEnabled, onCheckedChange = { update(p.copy(soundEnabled = it)) })
            }
            if (p.soundEnabled) {
                OutlinedButton(
                    onClick = {
                        val current = p.soundUri?.let { Uri.parse(it) }
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Сигнал таймера")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                        runCatching { picker.launch(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Мелодия: $title", maxLines = 1)
                }
                if (p.soundUri != null) {
                    TextButton(onClick = { update(p.copy(soundUri = null)) }) { Text("Вернуть встроенный свисток") }
                }
                Text("Громкость: ${(p.volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = p.volume,
                    onValueChange = { p = p.copy(volume = it) },
                    onValueChangeFinished = { TimerPrefs.save(ctx, p) },
                    valueRange = 0.1f..1f,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Вибрация вместе со свистком", Modifier.weight(1f))
                Switch(checked = p.vibration, onCheckedChange = { update(p.copy(vibration = it)) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Пики за 3 секунды до конца")
                    Text(
                        "короткий обратный отсчёт: пик-пик-пик",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = p.countdownBeeps, onCheckedChange = { update(p.copy(countdownBeeps = it)) })
            }

            Button(onClick = { TimerActions.start(ctx, 4_000, "Проверка сигнала") }, modifier = Modifier.fillMaxWidth()) {
                Text("Проверить (таймер на 4 с)")
            }
            Text(
                "Звук идёт через громкость будильника, поэтому слышен и в беззвучном режиме.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
