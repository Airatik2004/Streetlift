package ru.streetlift.tracker.run

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Запись пробежки (одна на процесс): время без пауз и маршрут отрезками между паузами. */
object RunTracker {
    enum class Phase { IDLE, COUNTDOWN, RUNNING, PAUSED }

    data class Pt(val lat: Double, val lon: Double)

    data class State(
        val phase: Phase = Phase.IDLE,
        val startedAt: Long = 0L,
        val accumulatedMs: Long = 0L,
        val resumedAtElapsed: Long = 0L,
        val segments: List<List<Pt>> = emptyList(),
        val hasFix: Boolean = false,
        /** Обратный отсчёт перед стартом: сколько секунд осталось. */
        val countdown: Int = 0,
        /** Где сейчас и с какой точностью (м) — даже если точка грубая и в маршрут не идёт. */
        val current: Pt? = null,
        val accuracy: Float? = null,
    ) {
        val active: Boolean get() = phase != Phase.IDLE
        val running: Boolean get() = phase == Phase.RUNNING

        fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (phase == Phase.RUNNING) (now - resumedAtElapsed).coerceAtLeast(0) else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Точность, с которой точка идёт в маршрут (м). */
    const val GOOD_ACCURACY = 30f

    /** Начать: с обратным отсчётом ([countdown] > 0) или сразу. */
    fun start(countdown: Int = 0) {
        val prev = _state.value
        _state.value = State(
            phase = if (countdown > 0) Phase.COUNTDOWN else Phase.RUNNING,
            startedAt = System.currentTimeMillis(),
            resumedAtElapsed = SystemClock.elapsedRealtime(),
            segments = listOf(emptyList()),
            countdown = countdown,
            current = prev.current,
            accuracy = prev.accuracy,
            hasFix = prev.hasFix,
        )
    }

    /** Секунда отсчёта прошла; true — отсчёт закончен, бег пошёл. */
    fun tickCountdown(): Boolean {
        val s = _state.value
        if (s.phase != Phase.COUNTDOWN) return false
        return if (s.countdown <= 1) {
            _state.value = s.copy(
                phase = Phase.RUNNING, countdown = 0,
                startedAt = System.currentTimeMillis(), resumedAtElapsed = SystemClock.elapsedRealtime(),
            )
            true
        } else {
            _state.value = s.copy(countdown = s.countdown - 1)
            false
        }
    }

    /** Отменить во время отсчёта — ничего не сохраняется. */
    fun cancel() {
        _state.value = State(current = _state.value.current, accuracy = _state.value.accuracy)
    }

    fun toggle() {
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        _state.value = when (s.phase) {
            Phase.RUNNING -> s.copy(phase = Phase.PAUSED, accumulatedMs = s.elapsedMs(now))
            // После паузы — новый отрезок, чтобы не рисовать прямую через место паузы
            Phase.PAUSED -> s.copy(phase = Phase.RUNNING, resumedAtElapsed = now, segments = s.segments + listOf(emptyList()))
            Phase.IDLE, Phase.COUNTDOWN -> s
        }
    }

    /** Последняя известная точка (может быть старой) — только показать на карте. */
    fun showLast(lat: Double, lon: Double) {
        val s = _state.value
        if (s.current == null) _state.value = s.copy(current = Pt(lat, lon))
    }

    /** Новое местоположение: позиция видна всегда, в маршрут — только точные точки во время бега. */
    fun onLocation(lat: Double, lon: Double, accuracy: Float?) {
        val s = _state.value
        val good = accuracy == null || accuracy <= GOOD_ACCURACY
        var next = s.copy(current = Pt(lat, lon), accuracy = accuracy, hasFix = s.hasFix || good)
        if (good && s.phase == Phase.RUNNING) {
            val segs = s.segments.ifEmpty { listOf(emptyList()) }
            next = next.copy(segments = segs.dropLast(1) + listOf(segs.last() + Pt(lat, lon)))
        }
        _state.value = next
    }

    /** Завершить: вернуть итог и сбросить. */
    fun finish(): State {
        val s = _state.value
        val done = s.copy(accumulatedMs = s.elapsedMs(), phase = Phase.IDLE)
        _state.value = State()
        return done
    }

    /** Маршрут для хранения: «lat,lon;lat,lon|…». */
    fun encode(segments: List<List<Pt>>): String =
        segments.filter { it.isNotEmpty() }.joinToString("|") { seg ->
            seg.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        }

    fun decode(route: String): List<List<Pt>> =
        route.split('|').filter { it.isNotBlank() }.map { seg ->
            seg.split(';').mapNotNull { p ->
                val parts = p.split(',')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) Pt(lat, lon) else null
            }
        }.filter { it.isNotEmpty() }
}
