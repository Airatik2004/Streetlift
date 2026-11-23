package ru.streetlift.tracker.timer

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Единственный источник правды для таймера отдыха (процесс-синглтон).
 * Отсчёт идёт по elapsedRealtime, поэтому не сбивается при блокировке экрана.
 * Все методы вызываются с главного потока.
 */
object RestTimer {

    data class State(
        val totalMs: Long = 90_000,
        val remainingMs: Long = 90_000,
        val running: Boolean = false,
        val finished: Boolean = false,
        val label: String = "",
        /** Шапка уведомления, напр. «подходов 5/12». */
        val info: String = "",
    ) {
        val remainingSeconds: Int get() = ((remainingMs + 999) / 1000).toInt()
        val progress: Float get() = if (totalMs <= 0) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
        val idle: Boolean get() = !running && !finished && remainingMs >= totalMs
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var endAt = 0L
    private var tickJob: Job? = null

    private fun now() = SystemClock.elapsedRealtime()

    fun start(totalMs: Long = _state.value.totalMs, label: String = _state.value.label) {
        val t = totalMs.coerceIn(1_000, 99 * 60_000L)
        endAt = now() + t
        _state.value = State(totalMs = t, remainingMs = t, running = true, finished = false, label = label, info = _state.value.info)
        launchTick()
    }

    /** Задать длительность без запуска. */
    fun setDuration(totalMs: Long, label: String = _state.value.label) {
        tickJob?.cancel()
        val t = totalMs.coerceIn(1_000, 99 * 60_000L)
        _state.value = State(totalMs = t, remainingMs = t, running = false, finished = false, label = label, info = _state.value.info)
    }

    /** Обновить подписи (что дальше, прогресс тренировки), не трогая отсчёт. */
    fun setInfo(label: String, info: String) {
        val s = _state.value
        if (s.label != label || s.info != info) _state.value = s.copy(label = label, info = info)
    }

    fun pause() {
        val s = _state.value
        if (!s.running) return
        tickJob?.cancel()
        _state.value = s.copy(running = false, remainingMs = (endAt - now()).coerceAtLeast(0))
    }

    fun resume() {
        val s = _state.value
        if (s.running) return
        if (s.remainingMs <= 0 || s.finished) {
            start(s.totalMs, s.label)
            return
        }
        endAt = now() + s.remainingMs
        _state.value = s.copy(running = true, finished = false)
        launchTick()
    }

    fun toggle() {
        if (_state.value.running) pause() else resume()
    }

    fun reset() {
        tickJob?.cancel()
        val s = _state.value
        _state.value = s.copy(running = false, finished = false, remainingMs = s.totalMs)
    }

    /** ±15 секунд и т.п. Остаток не уходит ниже 1 секунды. */
    fun add(deltaMs: Long) {
        val s = _state.value
        if (s.running) {
            val remaining = max(1_000L, (endAt - now()) + deltaMs)
            endAt = now() + remaining
            _state.value = s.copy(remainingMs = remaining, totalMs = max(remaining, s.totalMs + deltaMs))
        } else {
            val remaining = max(1_000L, s.remainingMs + deltaMs)
            val total = if (s.finished) remaining else max(remaining, s.totalMs + deltaMs)
            _state.value = s.copy(remainingMs = remaining, totalMs = max(1_000L, total), finished = false)
        }
    }

    private fun launchTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val rem = endAt - now()
                if (rem <= 0) {
                    _state.value = _state.value.copy(remainingMs = 0, running = false, finished = true)
                    break
                }
                _state.value = _state.value.copy(remainingMs = rem)
                delay(100)
            }
        }
    }
}
