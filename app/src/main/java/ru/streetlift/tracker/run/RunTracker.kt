package ru.streetlift.tracker.run

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.streetlift.tracker.domain.RunStats

/**
 * Запись пробежки (одна на процесс): время без пауз, маршрут отрезками между паузами,
 * дистанция, темп, автопауза, отсечки километров.
 */
object RunTracker {
    enum class Phase { IDLE, COUNTDOWN, RUNNING, PAUSED }

    /** Точка маршрута: [t] — время бега от старта без пауз (мс), [alt] — высота (м). */
    data class Pt(val lat: Double, val lon: Double, val t: Long? = null, val alt: Double? = null)

    /** Отсечка километра: номер, время километра и общее время бега на отметке (мс). */
    data class KmCue(val km: Int, val kmMs: Long, val totalMs: Long, val distanceM: Double)

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
        val distanceM: Double = 0.0,
        /** Пауза включена автоматически (стоим на месте). */
        val autoPaused: Boolean = false,
        /** Начало текущего километра: время бега (мс). */
        val kmStartMs: Long = 0L,
        val lastKm: KmCue? = null,
    ) {
        val active: Boolean get() = phase != Phase.IDLE
        val running: Boolean get() = phase == Phase.RUNNING

        fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (phase == Phase.RUNNING) (now - resumedAtElapsed).coerceAtLeast(0) else 0

        /** Текущий темп (с/км) за последние ~30 с бега; null — мало данных. */
        fun currentPace(): Double? {
            val seg = segments.lastOrNull().orEmpty()
            val last = seg.lastOrNull()?.t ?: return null
            val idx = seg.indexOfLast { (it.t ?: 0) <= last - 30_000 }.coerceAtLeast(0)
            var d = 0.0
            for (i in idx + 1 until seg.size) d += RunStats.dist(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
            val dt = (last - (seg[idx].t ?: last)) / 1000.0
            if (d < 20 || dt < 5) return null
            return dt / (d / 1000.0)
        }

        /** Средний темп, с/км. */
        fun avgPace(now: Long = SystemClock.elapsedRealtime()): Double? =
            if (distanceM < 20) null else elapsedMs(now) / 1000.0 / (distanceM / 1000.0)

        /** Темп текущего километра, с/км. */
        fun kmPace(now: Long = SystemClock.elapsedRealtime()): Double? {
            val inKm = distanceM - (lastKm?.km ?: 0) * 1000.0
            if (inKm < 20) return null
            return (elapsedMs(now) - kmStartMs) / 1000.0 / (inKm / 1000.0)
        }
    }

    /** Точность, с которой точка идёт в маршрут (м). */
    const val GOOD_ACCURACY = 30f

    /** Точки ближе — шум GPS на месте, не пишем. */
    private const val MIN_STEP_M = 4.0

    /** Быстрее этого (м/с) — скачок GPS. */
    private const val MAX_SPEED = 12.0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Последняя хорошая точка (реальное время) и где встали на автопаузу. */
    @Volatile private var lastGoodAt = 0L
    @Volatile private var pausedAt: Pt? = null

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
        pausedAt = null
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

    /** Отменить во время обратного отсчёта — ничего не сохраняется. */
    fun cancel() {
        _state.value = State(current = _state.value.current, accuracy = _state.value.accuracy)
    }

    /** Пауза / продолжить вручную. */
    fun toggle() {
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        _state.value = when (s.phase) {
            Phase.RUNNING -> s.copy(phase = Phase.PAUSED, accumulatedMs = s.elapsedMs(now), autoPaused = false)
            // После ручной паузы — новый отрезок, чтобы не рисовать прямую через место паузы
            Phase.PAUSED -> s.copy(
                phase = Phase.RUNNING, resumedAtElapsed = now, autoPaused = false,
                segments = if (s.autoPaused) s.segments else s.segments + listOf(emptyList()),
            )
            Phase.IDLE, Phase.COUNTDOWN -> s
        }
        pausedAt = null
    }

    /**
     * Автопауза (раз в секунду): 10 с не сдвинулись дальше 6 м при свежем GPS — пауза.
     * Без свежего GPS (тоннель) — не трогаем.
     */
    fun checkAutoPause() {
        val s = _state.value
        if (s.phase != Phase.RUNNING) return
        if (SystemClock.elapsedRealtime() - lastGoodAt > 5_000) return
        val seg = s.segments.lastOrNull().orEmpty()
        val last = seg.lastOrNull() ?: return
        val runNow = s.elapsedMs()
        val cur = s.current ?: return
        // Точка пишется только при сдвиге ≥ 4 м: давно не было новой и рядом с последней — стоим
        val still = runNow - (last.t ?: runNow) > 10_000 && RunStats.dist(last.lat, last.lon, cur.lat, cur.lon) < 6
        if (still) {
            _state.value = s.copy(phase = Phase.PAUSED, accumulatedMs = s.elapsedMs(), autoPaused = true)
            pausedAt = last
        }
    }

    /**
     * Новое местоположение: позиция видна всегда; в маршрут — точные точки во время бега,
     * без дрожания на месте и скачков. Возвращает отсечку, если пройден очередной километр.
     */
    fun onLocation(lat: Double, lon: Double, accuracy: Float?, alt: Double?): KmCue? {
        var s = _state.value
        val good = accuracy == null || accuracy <= GOOD_ACCURACY
        s = s.copy(current = Pt(lat, lon), accuracy = accuracy, hasFix = s.hasFix || good)
        if (good) lastGoodAt = SystemClock.elapsedRealtime()

        // Автопауза снимается, когда отбежали от места остановки
        if (good && s.phase == Phase.PAUSED && s.autoPaused) {
            val p = pausedAt
            if (p != null && RunStats.dist(p.lat, p.lon, lat, lon) > 10) {
                s = s.copy(phase = Phase.RUNNING, resumedAtElapsed = SystemClock.elapsedRealtime(), autoPaused = false)
                pausedAt = null
            }
        }

        var cue: KmCue? = null
        if (good && s.phase == Phase.RUNNING) {
            val t = s.elapsedMs()
            val segs = s.segments.ifEmpty { listOf(emptyList()) }
            val seg = segs.last()
            val prev = seg.lastOrNull()
            val step = prev?.let { RunStats.dist(it.lat, it.lon, lat, lon) } ?: 0.0
            val dt = prev?.t?.let { (t - it) / 1000.0 } ?: 0.0
            val jump = prev != null && dt > 0 && step / dt > MAX_SPEED
            if (prev == null || (step >= MIN_STEP_M && !jump)) {
                val dist = s.distanceM + step
                s = s.copy(segments = segs.dropLast(1) + listOf(seg + Pt(lat, lon, t, alt)), distanceM = dist)
                // Пройден километр — время на отметке по интерполяции
                val km = (dist / 1000).toInt()
                val done = s.lastKm?.km ?: 0
                if (km > done && prev != null && step > 0) {
                    val pt = prev.t ?: t
                    val frac = ((km * 1000.0) - (dist - step)) / step
                    val tMark = pt + ((t - pt) * frac).toLong()
                    cue = KmCue(km, tMark - s.kmStartMs, tMark, km * 1000.0)
                    s = s.copy(lastKm = cue, kmStartMs = tMark)
                }
            }
        }
        _state.value = s
        return cue
    }

    /** Последняя известная точка (может быть старой) — только показать на карте. */
    fun showLast(lat: Double, lon: Double) {
        val s = _state.value
        if (s.current == null) _state.value = s.copy(current = Pt(lat, lon))
    }

    /** Завершить: вернуть итог и сбросить. */
    fun finish(): State {
        val s = _state.value
        val done = s.copy(accumulatedMs = s.elapsedMs(), phase = Phase.IDLE)
        _state.value = State(current = s.current, accuracy = s.accuracy)
        return done
    }

    /** Маршрут для хранения: «lat,lon,t,alt;…|…» (отрезки между паузами через «|»). */
    fun encode(segments: List<List<Pt>>): String =
        segments.filter { it.isNotEmpty() }.joinToString("|") { seg ->
            seg.joinToString(";") {
                String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon) +
                    (it.t?.let { t -> ",$t" } ?: "") +
                    (if (it.t != null && it.alt != null) String.format(java.util.Locale.US, ",%.1f", it.alt) else "")
            }
        }

    fun decode(route: String): List<List<Pt>> =
        route.split('|').filter { it.isNotBlank() }.map { seg ->
            seg.split(';').mapNotNull { p ->
                val parts = p.split(',')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    Pt(lat, lon, parts.getOrNull(2)?.toLongOrNull(), parts.getOrNull(3)?.toDoubleOrNull())
                } else null
            }
        }.filter { it.isNotEmpty() }

    /** Для разбора: точки домена. */
    fun toStats(segments: List<List<Pt>>): List<List<RunStats.P>> =
        segments.map { seg -> seg.map { RunStats.P(it.lat, it.lon, it.t, it.alt) } }
}
