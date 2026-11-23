package ru.streetlift.tracker

import kotlinx.coroutines.flow.MutableStateFlow

/** Запросы навигации извне экранов (например, из уведомления). */
object NavRequests {
    const val EXTRA_OPEN_SESSION = "open_session"

    /** Открыть экран тренировки с этим id. */
    val openWorkout = MutableStateFlow<Long?>(null)
}
