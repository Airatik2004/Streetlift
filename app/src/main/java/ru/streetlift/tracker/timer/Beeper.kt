package ru.streetlift.tracker.timer

import android.content.Context

/** Пики и свисток секундомера/интервалов — через [SignalPlayer] (выбор гарнитура/динамик, вибрация). */
object Beeper {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun pip() {
        appContext?.let { SignalPlayer.pip(it) }
    }

    fun whistle() {
        appContext?.let { SignalPlayer.signal(it) }
    }
}
