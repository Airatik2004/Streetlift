package ru.streetlift.tracker.timer

import android.content.Context

/**
 * Настройки сигнала окончания отдыха. Хранятся на устройстве (SharedPreferences):
 * мелодия — это ссылка на звук конкретного телефона, в JSON-бэкап её переносить нет смысла.
 */
object TimerPrefs {

    enum class Vibration(val title: String, val pattern: LongArray) {
        OFF("Выкл", longArrayOf()),
        SHORT("Короткая", longArrayOf(0, 300, 250, 300, 900)),
        LONG("Длинная", longArrayOf(0, 900, 400, 900, 700)),
    }

    data class Prefs(
        val soundEnabled: Boolean = true,
        /** content:// ссылка на системный звук; null — звук уведомления по умолчанию. */
        val soundUri: String? = null,
        /** Длительность сигнала, с; 0 — до нажатия «Стоп» (но не дольше 60 с). */
        val durationSec: Int = 5,
        /** Громкость 0.1..1.0 (Android 9+). */
        val volume: Float = 1f,
        val vibration: Vibration = Vibration.LONG,
        /** Короткие пики за 3, 2, 1 секунду до конца. */
        val countdownBeeps: Boolean = true,
    )

    val DURATIONS = listOf(3, 5, 10, 30, 0)
    const val UNTIL_STOP_CAP_SEC = 60

    private const val FILE = "timer_signal"

    fun load(context: Context): Prefs {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Prefs(
            soundEnabled = p.getBoolean("soundEnabled", true),
            soundUri = p.getString("soundUri", null),
            durationSec = p.getInt("durationSec", 5),
            volume = p.getFloat("volume", 1f),
            vibration = runCatching { Vibration.valueOf(p.getString("vibration", null) ?: "") }
                .getOrDefault(Vibration.LONG),
            countdownBeeps = p.getBoolean("countdownBeeps", true),
        )
    }

    fun save(context: Context, prefs: Prefs) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("soundEnabled", prefs.soundEnabled)
            .putString("soundUri", prefs.soundUri)
            .putInt("durationSec", prefs.durationSec)
            .putFloat("volume", prefs.volume)
            .putString("vibration", prefs.vibration.name)
            .putBoolean("countdownBeeps", prefs.countdownBeeps)
            .apply()
    }
}
