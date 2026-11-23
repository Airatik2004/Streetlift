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

/** Интервалы: Подготовка → (Работа → Отдых) × раунды. Пики за 3 с до смены фазы, свисток на смене. */
object IntervalTimer {

    enum class Phase(val title: String) { IDLE("Готов"), PREP("Подготовка"), WORK("Работа"), REST("Отдых"), DONE("Готово") }

    data class Config(val prep: Int, val work: Int, val rest: Int, val rounds: Int)

    data class State(
        val phase: Phase = Phase.IDLE,
        val remainingMs: Long = 0,
        val phaseMs: Long = 0,
        val round: Int = 0,
        val rounds: Int = 0,
        val running: Boolean = false,
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE
        val remainingSeconds: Int get() = ((remainingMs + 999) / 1000).toInt()
        val progress: Float get() = if (phaseMs <= 0) 0f else (remainingMs.toFloat() / phaseMs).coerceIn(0f, 1f)
    }

    val PRESETS = listOf(
        "Статика 15/45 × 5" to Config(5, 15, 45, 5),
        "EMOM 1:00 × 10" to Config(5, 60, 0, 10),
        "Растяжка 45/15 × 6" to Config(5, 45, 15, 6),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var config = Config(5, 15, 45, 5)
    private var endAt = 0L

    private fun now() = SystemClock.elapsedRealtime()

    fun start(context: Context, cfg: Config) {
        config = cfg.copy(rounds = cfg.rounds.coerceAtLeast(1), work = cfg.work.coerceAtLeast(1))
        job?.cancel()
        TimerService.ensureRunning(context)
        val first = if (config.prep > 0) Phase.PREP else Phase.WORK
        enter(first, 1, (if (first == Phase.PREP) config.prep else config.work) * 1000L)
        launchTick()
    }

    private fun enter(phase: Phase, round: Int, ms: Long) {
        endAt = now() + ms
        _state.value = State(phase, ms, ms, round, config.rounds, running = true)
    }

    private fun launchTick() {
        job?.cancel()
        job = scope.launch {
            var lastSec = -1
            while (isActive) {
                val s = _state.value
                val rem = endAt - now()
                if (rem <= 0) {
                    Beeper.whistle()
                    next(s)
                    if (!_state.value.active) break
                    lastSec = -1
                    continue
                }
                val sec = ((rem + 999) / 1000).toInt()
                if (sec in 1..3 && sec != lastSec) Beeper.pip()
                lastSec = sec
                _state.value = s.copy(remainingMs = rem)
                delay(50)
            }
        }
    }

    private fun next(s: State) {
        when (s.phase) {
            Phase.PREP -> enter(Phase.WORK, 1, config.work * 1000L)
            Phase.WORK ->
                if (s.round >= config.rounds) finish()
                else if (config.rest > 0) enter(Phase.REST, s.round, config.rest * 1000L)
                else enter(Phase.WORK, s.round + 1, config.work * 1000L)
            Phase.REST -> enter(Phase.WORK, s.round + 1, config.work * 1000L)
            else -> finish()
        }
    }

    private fun finish() {
        _state.value = _state.value.copy(phase = Phase.DONE, remainingMs = 0, running = false)
    }

    fun toggle() {
        val s = _state.value
        if (!s.active) return
        if (s.running) {
            job?.cancel()
            _state.value = s.copy(running = false, remainingMs = (endAt - now()).coerceAtLeast(0))
        } else {
            endAt = now() + s.remainingMs
            _state.value = s.copy(running = true)
            launchTick()
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = State()
    }
}
