package ru.streetlift.tracker.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Форматирование чисел для интерфейса. */
object Fmt {
    /** «+24 кг», «0 кг», «−50 кг · Чёрная + Красная», «+12.5 кг». */
    fun weight(w: Float): String {
        val base = "${weightValue(w)} кг"
        return Bands.describe(w)?.let { "$base · $it" } ?: base
    }

    /** Число со знаком без единиц: «+24», «0», «−15». */
    fun weightValue(w: Float): String {
        val hundredths = (w * 100f).roundToInt()
        val num = number(abs(hundredths))
        return when {
            hundredths > 0 -> "+$num"
            hundredths < 0 -> "−$num"
            else -> "0"
        }
    }

    /** Шаг веса без знака: «2.5», «1.25», «5». */
    fun step(w: Float): String = number(abs((w * 100f).roundToInt()))

    private fun number(absHundredths: Int): String =
        if (absHundredths % 100 == 0) (absHundredths / 100).toString()
        else String.format(Locale.US, "%.2f", absHundredths / 100f).trimEnd('0').trimEnd('.')

    fun range(a: Int, b: Int): String = if (a == b) "$a" else "$a–$b"

    fun rirValue(v: Int): String = if (v >= 5) "5+" else v.toString()

    fun rir(min: Int, max: Int): String =
        if (min == max) "RIR ${rirValue(min)}" else "RIR $min–${rirValue(max)}"

    fun clock(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
