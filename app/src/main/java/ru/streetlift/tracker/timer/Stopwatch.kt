package ru.streetlift.tracker.timer

import android.content.Context
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

/**
 * Секундомер со счётом вверх: задержка старта (3/5 с с пиками), пик каждые N секунд, отсечки.
 * Один на процесс — им пользуются и вкладка «Таймер», и статика на тренировке.
 */
object Stopwatch {

    data class State(
        val elapsedMs: Long = 0,
        val running: Boolean = false,
        /** Идёт отсчёт перед стартом: сколько секунд осталось (0 — нет). */
        val countdown: Int = 0,
        val laps: List<Long> = emptyList(),
        /** Кто запустил: «» — вкладка «Таймер», «hold» — статика на тренировке. */
        val owner: String = "",
    ) {
        val active: Boolean get() = running || countdown > 0
        val idle: Boolean get() = !active && elapsedMs == 0L
        val seconds: Int get() = (elapsedMs / 1000).toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var startedAt = 0L
    private var baseMs = 0L
    private var beepEvery = 5

    private fun now() = SystemClock.elapsedRealtime()

    /** Старт (или продолжение после паузы). Задержка — только для старта с нуля. */
    fun start(context: Context, owner: String = _state.value.owner) {
        val s = _state.value
        if (s.active) return
        val prefs = TimerPrefs.load(context)
        beepEvery = prefs.stopwatchBeepEvery
        val delaySec = if (s.elapsedMs == 0L) prefs.stopwatchDelay else 0
        job?.cancel()
        // Отсчёт виден сразу: сервис должен застать секундомер уже активным, иначе он
        // тут же останавливается, не успев уйти в foreground, и приложение вылетает.
        if (delaySec > 0) _state.value = _state.value.copy(countdown = delaySec, owner = owner)
        TimerService.ensureRunning(context)
        val fromZero = s.elapsedMs == 0L
        job = scope.launch {
            if (delaySec > 0) {
                // Пик только на 3, 2, 1; раньше — молча, просто цифры.
                // Звук будим заранее, чтобы первый пик не пропал
                if (delaySec <= 3) {
                    SignalPlayer.warmUp(context)
                    delay(400)
                }
                for (left in delaySec downTo 1) {
                    _state.value = _state.value.copy(countdown = left, owner = owner)
                    if (left <= 3) Beeper.pip()
                    if (left == 4) {
                        delay(500)
                        SignalPlayer.warmUp(context)
                        delay(500)
                    } else {
                        delay(1_000)
                    }
                }
            }
            // Свисток — только при старте с нуля, не после паузы
            if (fromZero) Beeper.whistle()
            baseMs = _state.value.elapsedMs
            startedAt = now()
            _state.value = _state.value.copy(countdown = 0, running = true, owner = owner)
            var lastBeep = (baseMs / 1000).toInt()
            while (isActive) {
                val el = baseMs + (now() - startedAt)
                _state.value = _state.value.copy(elapsedMs = el)
                val sec = (el / 1000).toInt()
                if (beepEvery > 0 && sec != lastBeep && sec > 0 && sec % beepEvery == 0) Beeper.pip()
                lastBeep = sec
                delay(50)
            }
        }
    }

    /** Стоп/пауза. Возвращает накопленные секунды. */
    fun stop(): Int {
        job?.cancel()
        val s = _state.value
        val el = if (s.running) baseMs + (now() - startedAt) else s.elapsedMs
        _state.value = s.copy(running = false, countdown = 0, elapsedMs = el)
        return (el / 1000).toInt()
    }

    fun toggle(context: Context) {
        if (_state.value.active) stop() else start(context)
    }

    fun lap() {
        val s = _state.value
        if (s.running) _state.value = s.copy(laps = s.laps + s.elapsedMs)
    }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }
}
