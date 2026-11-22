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
        )
    }

    fun save(context: Context, prefs: Prefs) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("soundEnabled", prefs.soundEnabled)
            .putString("soundUri", prefs.soundUri)
            .putFloat("volume", prefs.volume)
            .putBoolean("vibrationOn", prefs.vibration)
            .putBoolean("countdownBeeps", prefs.countdownBeeps)
            .apply()
    }
}
