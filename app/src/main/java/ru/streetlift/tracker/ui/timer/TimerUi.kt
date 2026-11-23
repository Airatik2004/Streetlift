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
import ru.streetlift.tracker.timer.IntervalTimer
import ru.streetlift.tracker.timer.Stopwatch
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
        FilledTonalButton(onClick = { TimerActions.add(-10_000) }, contentPadding = pad) { Text("−10с") }
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
        FilledTonalButton(onClick = { TimerActions.add(10_000) }, contentPadding = pad) { Text("+10с") }
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

/** Вкладка «Таймер»: три режима — Отдых, Секундомер, Интервалы. */
@Composable
fun TimerScreen() {
    var mode by remember { mutableStateOf(0) }
    val sw by Stopwatch.state.collectAsStateWithLifecycle()
    val iv by IntervalTimer.state.collectAsStateWithLifecycle()
    // Экран не гаснет, пока идёт секундомер или интервалы
    KeepScreenOn(sw.active || iv.active)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModeSwitch(listOf("Отдых", "Секундомер", "Интервалы"), mode) { mode = it }
        when (mode) {
            0 -> {
                RestTimerPanel(recommendation = null, compact = false)
                SignalSettingsCard()
            }
            1 -> StopwatchPanel(sw)
            else -> IntervalsPanel(iv)
        }
    }
}

/** Держать экран включённым (FLAG_KEEP_SCREEN_ON) пока [on]. */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Переключатель режимов в синем стиле. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitch(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(4.dp)) {
            items.forEachIndexed { i, t ->
                val on = i == selected
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onSelect(i) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        t,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** 00:00.0 */
fun formatStopwatch(ms: Long): String {
    val tenths = (ms / 100) % 10
    val s = (ms / 1000) % 60
    val m = ms / 60_000
    return String.format(java.util.Locale.US, "%02d:%02d.%d", m, s, tenths)
}

@Composable
private fun ChoiceRow(label: String, options: List<Pair<Int, String>>, value: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (v, t) ->
                FilterChip(selected = value == v, onClick = { onPick(v) }, label = { Text(t) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopwatchPanel(sw: Stopwatch.State) {
    val ctx = LocalContext.current
    var prefs by remember { mutableStateOf(TimerPrefs.load(ctx)) }
    fun update(p: TimerPrefs.Prefs) {
        prefs = p
        TimerPrefs.save(ctx, p)
    }
    Text(
        if (sw.countdown > 0) "${sw.countdown}" else formatStopwatch(sw.elapsedMs),
        style = TimerDigits.copy(fontSize = 64.sp),
        color = if (sw.countdown > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { if (sw.running) Stopwatch.lap() else Stopwatch.reset() }) {
            Text(if (sw.running) "Отсечка" else "Сброс")
        }
        FilledIconButton(
            onClick = { Stopwatch.toggle(ctx) },
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (sw.active) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (sw.active) "Пауза" else "Старт",
                modifier = Modifier.size(34.dp),
            )
        }
    }
    if (sw.laps.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sw.laps.forEachIndexed { i, t ->
                    val prev = if (i == 0) 0L else sw.laps[i - 1]
                    Row {
                        Text("Попытка ${i + 1}", Modifier.weight(1f))
                        Text("+${formatStopwatch(t - prev)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(formatStopwatch(t), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow("Задержка старта", listOf(0 to "Выкл", 3 to "3с", 5 to "5с"), prefs.stopwatchDelay) {
                update(prefs.copy(stopwatchDelay = it))
            }
            ChoiceRow("Звуковая отсечка", listOf(0 to "Выкл", 5 to "каждые 5с", 10 to "каждые 10с"), prefs.stopwatchBeepEvery) {
                update(prefs.copy(stopwatchBeepEvery = it))
            }
            Text(
                "Эти же настройки — у секундомера статики на тренировке.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntervalsPanel(iv: IntervalTimer.State) {
    val ctx = LocalContext.current
    var prefs by remember { mutableStateOf(TimerPrefs.load(ctx)) }
    fun update(p: TimerPrefs.Prefs) {
        prefs = p
        TimerPrefs.save(ctx, p)
    }
    val cfg = IntervalTimer.Config(prefs.ivPrep, prefs.ivWork, prefs.ivRest, prefs.ivRounds)

    TimerRing(if (iv.active) iv.progress else 1f, 240.dp, 12.dp, iv.phase == IntervalTimer.Phase.DONE) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(iv.phase.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (iv.active) Fmt.clock(iv.remainingSeconds) else Fmt.clock(cfg.work),
                style = TimerDigits.copy(fontSize = 56.sp),
            )
            Text(
                if (iv.active) "Раунд ${iv.round} из ${iv.rounds}" else "${cfg.rounds} раундов",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { IntervalTimer.stop() }, enabled = iv.active) { Text("Стоп") }
        FilledIconButton(
            onClick = { if (iv.active) IntervalTimer.toggle() else IntervalTimer.start(ctx, cfg) },
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (iv.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (iv.running) "Пауза" else "Старт",
                modifier = Modifier.size(34.dp),
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IntervalTimer.PRESETS.forEach { (name, c) ->
            OutlinedButton(onClick = {
                update(prefs.copy(ivPrep = c.prep, ivWork = c.work, ivRest = c.rest, ivRounds = c.rounds))
            }) { Text(name) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IntervalStepper("Подготовка", Fmt.clock(prefs.ivPrep)) { d -> update(prefs.copy(ivPrep = (prefs.ivPrep + d * 5).coerceIn(0, 60))) }
            IntervalStepper("Работа", Fmt.clock(prefs.ivWork)) { d -> update(prefs.copy(ivWork = (prefs.ivWork + d * 5).coerceIn(5, 3600))) }
            IntervalStepper("Отдых", Fmt.clock(prefs.ivRest)) { d -> update(prefs.copy(ivRest = (prefs.ivRest + d * 5).coerceIn(0, 3600))) }
            IntervalStepper("Раунды", prefs.ivRounds.toString()) { d -> update(prefs.copy(ivRounds = (prefs.ivRounds + d).coerceIn(1, 99))) }
        }
    }
}

@Composable
private fun IntervalStepper(label: String, value: String, onDelta: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton(onClick = { onDelta(-1) }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
        FilledTonalIconButton(onClick = { onDelta(1) }) { Text("+") }
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
