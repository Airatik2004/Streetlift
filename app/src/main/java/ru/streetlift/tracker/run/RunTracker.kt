package ru.streetlift.tracker.run

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Запись пробежки (одна на процесс): время без пауз и маршрут отрезками между паузами. */
object RunTracker {
    enum class Phase { IDLE, RUNNING, PAUSED }

    data class Pt(val lat: Double, val lon: Double)

    data class State(
        val phase: Phase = Phase.IDLE,
        val startedAt: Long = 0L,
        val accumulatedMs: Long = 0L,
        val resumedAtElapsed: Long = 0L,
        val segments: List<List<Pt>> = emptyList(),
        val hasFix: Boolean = false,
    ) {
        val active: Boolean get() = phase != Phase.IDLE
        val running: Boolean get() = phase == Phase.RUNNING

        fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (phase == Phase.RUNNING) (now - resumedAtElapsed).coerceAtLeast(0) else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        _state.value = State(
            phase = Phase.RUNNING,
            startedAt = System.currentTimeMillis(),
            resumedAtElapsed = SystemClock.elapsedRealtime(),
            segments = listOf(emptyList()),
        )
    }

    fun toggle() {
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        _state.value = when (s.phase) {
            Phase.RUNNING -> s.copy(phase = Phase.PAUSED, accumulatedMs = s.elapsedMs(now))
            // После паузы — новый отрезок, чтобы не рисовать прямую через место паузы
            Phase.PAUSED -> s.copy(phase = Phase.RUNNING, resumedAtElapsed = now, segments = s.segments + listOf(emptyList()))
            Phase.IDLE -> s
        }
    }

    fun addPoint(lat: Double, lon: Double) {
        val s = _state.value
        if (s.phase != Phase.RUNNING) {
            if (!s.hasFix && s.active) _state.value = s.copy(hasFix = true)
            return
        }
        val segs = s.segments.ifEmpty { listOf(emptyList()) }
        _state.value = s.copy(segments = segs.dropLast(1) + listOf(segs.last() + Pt(lat, lon)), hasFix = true)
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
