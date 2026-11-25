package ru.streetlift.tracker.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable

/** Маленькая цветная плашка-метка. */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Степпер «− значение +». Тап по значению — ручной ввод. */
@Composable
fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValueClick: (() -> Unit)? = null,
    sub: String? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val minus = rememberRepeatClick(onStep = onMinus)
            FilledTonalIconButton(onClick = minus.onClick, interactionSource = minus.source) { Icon(Icons.Filled.Remove, contentDescription = "Меньше") }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .let { if (onValueClick != null) it.clickable { onValueClick() } else it },
            )
            val plus = rememberRepeatClick(onStep = onPlus)
            FilledTonalIconButton(onClick = plus.onClick, interactionSource = plus.source) { Icon(Icons.Filled.Add, contentDescription = "Больше") }
        }
        // Пояснение под значением (какие резины взять)
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
    }
}

/** Вес кнопками −/+ по шкале упражнения: резины → свой вес → +шаг. */
@Composable
fun WeightStepper(
    weight: Float,
    scale: ru.streetlift.tracker.domain.LoadScale,
    onChange: (Float) -> Unit,
    onValueClick: (() -> Unit)? = null,
) {
    Stepper(
        label = "Вес (шаг ${ru.streetlift.tracker.domain.Fmt.step(scale.step).replace('.', ',')} кг)",
        value = ru.streetlift.tracker.domain.Fmt.weightValue(weight),
        onMinus = { onChange(scale.down(weight)) },
        onPlus = { onChange(scale.up(weight)) },
        onValueClick = onValueClick,
        sub = ru.streetlift.tracker.domain.Bands.describe(weight)?.let { "резина: $it" }
            ?: if (weight == 0f) "свой вес" else null,
    )
}

/** Диалог ввода одного значения. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                )
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Да",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** «−15», «+2,5», «−1.25» → Float. Понимает минус-тире и запятую. */
fun parseSignedFloat(s: String): Float? =
    s.trim().replace('−', '-').replace('–', '-').replace(',', '.').replace("+", "").toFloatOrNull()

/**
 * Автоповтор для кнопок −/+: короткое нажатие — один шаг; удержание 0,3 с — прокрутка
 * 8 шагов/с, через 1,5 с прокрутки разгон до 20 шагов/с к 3 с удержания.
 * Передать [RepeatClick.source] в interactionSource кнопки и [RepeatClick.onClick] в onClick.
 */
class RepeatClick(
    val source: androidx.compose.foundation.interaction.MutableInteractionSource,
    val onClick: () -> Unit,
)

object RepeatTiming {
    const val START_MS = 300L
    const val SLOW_MS = 125L
    const val FAST_MS = 50L
    /** Прокрутка идёт медленно столько, затем разгон до [FAST_MS] к 3 с удержания. */
    const val SLOW_PHASE_MS = 1_500L
    const val FULL_SPEED_AT_MS = 3_000L - START_MS

    /** Пауза до следующего шага по времени от начала прокрутки. */
    fun interval(repeatingMs: Long): Long = when {
        repeatingMs <= SLOW_PHASE_MS -> SLOW_MS
        repeatingMs >= FULL_SPEED_AT_MS -> FAST_MS
        else -> {
            val f = (repeatingMs - SLOW_PHASE_MS).toFloat() / (FULL_SPEED_AT_MS - SLOW_PHASE_MS)
            (SLOW_MS - (SLOW_MS - FAST_MS) * f).toLong()
        }
    }
}

@Composable
fun rememberRepeatClick(enabled: Boolean = true, onStep: () -> Unit): RepeatClick {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val step = androidx.compose.runtime.rememberUpdatedState(onStep)
    val en = androidx.compose.runtime.rememberUpdatedState(enabled)
    // Были шаги автоповтора — отпускание не добавляет ещё один шаг
    val repeated = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    androidx.compose.runtime.LaunchedEffect(source) {
        var job: kotlinx.coroutines.Job? = null
        source.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                    job?.cancel()
                    repeated.set(false)
                    job = launch {
                        kotlinx.coroutines.delay(RepeatTiming.START_MS)
                        val t0 = android.os.SystemClock.uptimeMillis()
                        while (en.value) {
                            step.value()
                            repeated.set(true)
                            kotlinx.coroutines.delay(RepeatTiming.interval(android.os.SystemClock.uptimeMillis() - t0))
                        }
                    }
                }
                is androidx.compose.foundation.interaction.PressInteraction.Release -> job?.cancel()
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                    job?.cancel()
                    repeated.set(false)
                }
            }
        }
    }
    return remember(source) { RepeatClick(source) { if (!repeated.getAndSet(false)) step.value() } }
}
