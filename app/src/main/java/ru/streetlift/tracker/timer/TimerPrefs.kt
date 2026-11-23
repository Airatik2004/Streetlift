package ru.streetlift.tracker.timer

import android.content.Context

/**
 * Настройки сигнала окончания отдыха. Хранятся на устройстве (SharedPreferences):
 * мелодия — ссылка на звук конкретного телефона, в JSON-бэкап её переносить нет смысла.
 */
object TimerPrefs {

    /** Вибрация звучит «под свисток»: одна, той же длины. */
    const val VIBRATION_MS = 1_000L

    data class Prefs(
        val soundEnabled: Boolean = true,
        /** content:// ссылка на системный звук; null — встроенный свисток. */
        val soundUri: String? = null,
        /** Громкость 0.1..1.0 (Android 9+). */
        val volume: Float = 1f,
        val vibration: Boolean = true,
        /** Короткие пики за 3, 2, 1 секунду до конца. */
        val countdownBeeps: Boolean = true,
        /** Секундомер (статика): задержка старта 0/3/5 с и пик каждые 0/5/10 с. */
        val stopwatchDelay: Int = 3,
        val stopwatchBeepEvery: Int = 5,
        /** Интервалы: подготовка, работа, отдых (с) и раунды. */
        val ivPrep: Int = 5,
        val ivWork: Int = 15,
        val ivRest: Int = 45,
        val ivRounds: Int = 5,
    )

    private const val FILE = "timer_signal"

    fun load(context: Context): Prefs {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Prefs(
            soundEnabled = p.getBoolean("soundEnabled", true),
            soundUri = p.getString("soundUri", null),
            volume = p.getFloat("volume", 1f),
            vibration = p.getBoolean("vibrationOn", true),
            countdownBeeps = p.getBoolean("countdownBeeps", true),
            stopwatchDelay = p.getInt("swDelay", 3),
            stopwatchBeepEvery = p.getInt("swBeep", 5),
            ivPrep = p.getInt("ivPrep", 5),
            ivWork = p.getInt("ivWork", 15),
            ivRest = p.getInt("ivRest", 45),
            ivRounds = p.getInt("ivRounds", 5),
        )
    }

    fun save(context: Context, prefs: Prefs) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("soundEnabled", prefs.soundEnabled)
            .putString("soundUri", prefs.soundUri)
            .putFloat("volume", prefs.volume)
            .putBoolean("vibrationOn", prefs.vibration)
            .putBoolean("countdownBeeps", prefs.countdownBeeps)
            .putInt("swDelay", prefs.stopwatchDelay)
            .putInt("swBeep", prefs.stopwatchBeepEvery)
            .putInt("ivPrep", prefs.ivPrep)
            .putInt("ivWork", prefs.ivWork)
            .putInt("ivRest", prefs.ivRest)
            .putInt("ivRounds", prefs.ivRounds)
            .apply()
    }
}
