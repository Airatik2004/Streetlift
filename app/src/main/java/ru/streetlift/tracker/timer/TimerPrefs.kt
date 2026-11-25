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
        /** Куда выводить звук: Bluetooth-гарнитура и/или динамик телефона (независимо). */
        val outBt: Boolean = true,
        val outSpeaker: Boolean = false,
        /** Гарнитура не подключена — сигнал в динамик (имеет смысл при outBt и выключенном outSpeaker). */
        val speakerFallback: Boolean = true,
        /** Короткие пики за 3, 2, 1 секунду до конца. */
        val countdownBeeps: Boolean = true,
        /** Секундомер (статика): задержка старта 0/3/5 с и пик каждые 0/5/10 с. */
        val stopwatchDelay: Int = 3,
        val stopwatchBeepEvery: Int = 5,
        /** Бег: обратный отсчёт перед стартом, с (0 — без отсчёта). */
        val runCountdown: Int = 5,
        /** Бег: автопауза, когда стоим. */
        val runAutoPause: Boolean = true,
        /** Бег: отсечка каждого километра — 0 выкл, 1 сигнал, 2 голос. */
        val runKmCue: Int = 2,
        /** Интервалы: подготовка, работа, отдых (с) и раунды. */
        val ivPrep: Int = 5,
        val ivWork: Int = 15,
        val ivRest: Int = 45,
        val ivRounds: Int = 5,
    )

    /** Программа интервалов (свои + стартовые). */
    data class IntervalProgram(val name: String, val prep: Int, val work: Int, val rest: Int, val rounds: Int)

    private val DEFAULT_PROGRAMS = listOf(
        IntervalProgram("Статика 15/45 × 5", 5, 15, 45, 5),
        IntervalProgram("EMOM 1:00 × 10", 5, 60, 0, 10),
        IntervalProgram("Растяжка 45/15 × 6", 5, 45, 15, 6),
    )

    fun loadPrograms(context: Context): List<IntervalProgram> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("ivPrograms", null)
            ?: return DEFAULT_PROGRAMS
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 5) null else runCatching {
                IntervalProgram(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
            }.getOrNull()
        }
    }

    fun savePrograms(context: Context, list: List<IntervalProgram>) {
        val raw = list.joinToString("\n") { "${it.name.replace('|', '/').replace('\n', ' ')}|${it.prep}|${it.work}|${it.rest}|${it.rounds}" }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("ivPrograms", raw).apply()
    }

    private const val FILE = "timer_signal"

    fun load(context: Context): Prefs {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Prefs(
            soundEnabled = p.getBoolean("soundEnabled", true),
            soundUri = p.getString("soundUri", null),
            volume = p.getFloat("volume", 1f),
            vibration = p.getBoolean("vibrationOn", true),
            outBt = p.getBoolean("outBt", true),
            outSpeaker = p.getBoolean("outSpeaker", false),
            speakerFallback = p.getBoolean("speakerFallback", true),
            countdownBeeps = p.getBoolean("countdownBeeps", true),
            stopwatchDelay = p.getInt("swDelay", 3),
            runCountdown = p.getInt("runCountdown", 5),
            runAutoPause = p.getBoolean("runAutoPause", true),
            runKmCue = p.getInt("runKmCue", 2),
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
            .putBoolean("outBt", prefs.outBt)
            .putBoolean("outSpeaker", prefs.outSpeaker)
            .putBoolean("speakerFallback", prefs.speakerFallback)
            .putBoolean("countdownBeeps", prefs.countdownBeeps)
            .putInt("swDelay", prefs.stopwatchDelay)
            .putInt("runCountdown", prefs.runCountdown)
            .putBoolean("runAutoPause", prefs.runAutoPause)
            .putInt("runKmCue", prefs.runKmCue)
            .putInt("swBeep", prefs.stopwatchBeepEvery)
            .putInt("ivPrep", prefs.ivPrep)
            .putInt("ivWork", prefs.ivWork)
            .putInt("ivRest", prefs.ivRest)
            .putInt("ivRounds", prefs.ivRounds)
            .apply()
    }
}
