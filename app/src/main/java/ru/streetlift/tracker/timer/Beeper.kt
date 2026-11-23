package ru.streetlift.tracker.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import ru.streetlift.tracker.R

/** Короткие звуки секундомера и интервалов: «пик» и свисток (поток будильника). */
object Beeper {
    private var pool: SoundPool? = null
    private var pipId = 0
    private var whistleId = 0
    private val loaded = mutableSetOf<Int>()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build().also { sp ->
            sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
            pipId = sp.load(context, R.raw.timer_pip, 1)
            whistleId = sp.load(context, R.raw.timer_whistle, 1)
        }
    }

    private fun volume(): Float = appContext?.let { TimerPrefs.load(it).volume.coerceIn(0.1f, 1f) } ?: 1f

    private fun enabled(): Boolean = appContext?.let { TimerPrefs.load(it).soundEnabled } ?: true

    fun pip() {
        val sp = pool ?: return
        if (pipId in loaded && enabled()) volume().let { sp.play(pipId, it, it, 2, 0, 1f) }
    }

    fun whistle() {
        val sp = pool ?: return
        if (whistleId in loaded && enabled()) volume().let { sp.play(whistleId, it, it, 1, 0, 1f) }
    }
}
