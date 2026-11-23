package ru.streetlift.tracker

import kotlinx.coroutines.flow.MutableStateFlow

/** Запросы навигации извне экранов (например, из уведомления). */
object NavRequests {
    const val EXTRA_OPEN_SESSION = "open_session"

    const val EXTRA_OPEN_TAB = "open_tab"
    const val TAB_TIMER = 2

    /** Открыть экран тренировки с этим id. */
    val openWorkout = MutableStateFlow<Long?>(null)

    /** Открыть вкладку (индекс нижней панели). */
    val openTab = MutableStateFlow<Int?>(null)
}
