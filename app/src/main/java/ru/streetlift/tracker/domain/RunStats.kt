package ru.streetlift.tracker.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Разбор пробежки: дистанция, темп, раскладка по отрезкам, лучшие отрезки (скользящее окно),
 * графики темпа и высоты, набор высоты.
 */
object RunStats {

    /** Точка маршрута: [t] — время бега от старта без пауз (мс), [alt] — высота (м). */
    data class P(val lat: Double, val lon: Double, val t: Long? = null, val alt: Double? = null)

    /** Точка трека: пройдено [d] м к моменту [t] мс. */
    data class TP(val d: Double, val t: Long, val alt: Double?, val lat: Double, val lon: Double)

    data class Split(val index: Int, val fromM: Double, val toM: Double, val timeMs: Long) {
        val lengthM: Double get() = toM - fromM
        /** Темп, секунд на км. */
        val pace: Double get() = if (lengthM > 0) timeMs / 1000.0 / (lengthM / 1000.0) else 0.0
    }

    data class Summary(
        val distanceM: Double,
        val timeMs: Long,
        val gainM: Double?,
        val lossM: Double?,
        val minAlt: Double?,
        val maxAlt: Double?,
    ) {
        /** Средний темп, с/км (0 — нет дистанции). */
        val pace: Double get() = if (distanceM > 1) timeMs / 1000.0 / (distanceM / 1000.0) else 0.0
        val speedKmh: Double get() = if (timeMs > 0) distanceM / 1000.0 / (timeMs / 3_600_000.0) else 0.0
    }

    private const val R = 6_371_000.0

    fun dist(a: P, b: P): Double = dist(a.lat, a.lon, b.lat, b.lon)

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Трек с накопленной дистанцией. Между отрезками (паузами) дистанция не добавляется;
     * точки без времени (старые пробежки) отбрасываются.
     */
    fun track(segments: List<List<P>>): List<TP> {
        val out = ArrayList<TP>()
        var d = 0.0
        segments.forEach { seg ->
            var prev: P? = null
            seg.forEach { p ->
                val t = p.t ?: return@forEach
                prev?.let { d += dist(it, p) }
                out += TP(d, t, p.alt, p.lat, p.lon)
                prev = p
            }
        }
        return out
    }

    /** Время (мс) на отметке [d] м — линейно между точками; null — за пределами трека. */
    fun timeAt(tr: List<TP>, d: Double): Double? {
        if (tr.isEmpty() || d < tr.first().d - 1e-6 || d > tr.last().d + 1e-6) return null
        var lo = 0
        var hi = tr.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (tr[mid].d < d) lo = mid else hi = mid
        }
        val a = tr[lo]
        val b = tr[hi]
        if (b.d - a.d < 1e-9) return a.t.toDouble()
        return a.t + (b.t - a.t) * ((d - a.d) / (b.d - a.d))
    }

    fun summary(tr: List<TP>, totalMs: Long): Summary {
        val (gain, loss) = elevation(tr)
        val alts = tr.mapNotNull { it.alt }
        return Summary(
            distanceM = tr.lastOrNull()?.d ?: 0.0,
            timeMs = totalMs,
            gainM = gain,
            lossM = loss,
            minAlt = alts.minOrNull(),
            maxAlt = alts.maxOrNull(),
        )
    }

    /** Раскладка по отрезкам [stepM]; последний неполный — если он не короче 10% шага. */
    fun splits(tr: List<TP>, stepM: Double): List<Split> {
        if (tr.size < 2) return emptyList()
        val total = tr.last().d
        val out = ArrayList<Split>()
        var from = 0.0
        var i = 1
        while (from < total - 1e-6) {
            val to = minOf(from + stepM, total)
            if (to < from + stepM && to - from < stepM * 0.1) break
            val t0 = timeAt(tr, from) ?: break
            val t1 = timeAt(tr, to) ?: break
            out += Split(i++, from, to, (t1 - t0).roundToInt().toLong())
            from = to
        }
        return out
    }

    /**
     * Лучший отрезок длиной [windowM] где угодно в пробежке (например, 5 км с 1-го по 6-й).
     * Оптимум у кусочно-линейного трека — когда один из концов окна в точке трека,
     * поэтому перебираются оба варианта. Возвращает (время мс, начало м) или null.
     */
    fun best(tr: List<TP>, windowM: Double): Pair<Long, Double>? {
        if (tr.size < 2 || tr.last().d < windowM) return null
        var bestT = Double.MAX_VALUE
        var bestFrom = 0.0
        for (p in tr) {
            // Окно начинается в точке
            timeAt(tr, p.d + windowM)?.let { t1 ->
                val dt = t1 - p.t
                if (dt < bestT) { bestT = dt; bestFrom = p.d }
            }
            // Окно заканчивается в точке
            if (p.d - windowM >= 0) {
                timeAt(tr, p.d - windowM)?.let { t0 ->
                    val dt = p.t - t0
                    if (dt < bestT) { bestT = dt; bestFrom = p.d - windowM }
                }
            }
        }
        return if (bestT == Double.MAX_VALUE) null else bestT.roundToInt().toLong() to bestFrom
    }

    /** График темпа: точка каждые [stepM] м, темп за последние [windowM] м (с/км). */
    fun paceSeries(tr: List<TP>, stepM: Double = 50.0, windowM: Double = 200.0): List<Pair<Double, Double>> {
        if (tr.size < 2) return emptyList()
        val total = tr.last().d
        val out = ArrayList<Pair<Double, Double>>()
        var d = minOf(windowM, total)
        while (d <= total + 1e-6) {
            val from = maxOf(0.0, d - windowM)
            val t0 = timeAt(tr, from)
            val t1 = timeAt(tr, d)
            if (t0 != null && t1 != null && d - from > 1) {
                out += d to (t1 - t0) / 1000.0 / ((d - from) / 1000.0)
            }
            d += stepM
        }
        return out
    }

    /** График высоты по дистанции (сглаженный). */
    fun altSeries(tr: List<TP>): List<Pair<Double, Double>> {
        val pts = tr.filter { it.alt != null }
        if (pts.size < 2) return emptyList()
        val sm = smooth(pts.map { it.alt!! })
        val stepEvery = maxOf(1, pts.size / 300)
        return pts.indices.filter { it % stepEvery == 0 }.map { pts[it].d to sm[it] }
    }

    private fun smooth(v: List<Double>, k: Int = 5): List<Double> = v.indices.map { i ->
        val a = maxOf(0, i - k / 2)
        val b = minOf(v.size - 1, i + k / 2)
        (a..b).sumOf { v[it] } / (b - a + 1)
    }

    /** Набор и сброс высоты со сглаживанием и порогом 2 м (шум GPS/барометра не копится). */
    fun elevation(tr: List<TP>): Pair<Double?, Double?> {
        val alts = tr.mapNotNull { it.alt }
        if (alts.size < 2) return null to null
        val sm = smooth(alts)
        var gain = 0.0
        var loss = 0.0
        var ref = sm.first()
        for (a in sm) {
            val diff = a - ref
            if (abs(diff) >= 2.0) {
                if (diff > 0) gain += diff else loss -= diff
                ref = a
            }
        }
        return gain to loss
    }

    /** Темп «5:12» из секунд на км; «—», если не посчитать. */
    fun pace(secPerKm: Double): String {
        if (secPerKm <= 0 || secPerKm.isNaN() || secPerKm > 3600) return "—"
        val s = secPerKm.roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** «5,23 км» / «850 м». */
    fun distance(m: Double): String =
        if (m < 1000) "${m.roundToInt()} м"
        else String.format(java.util.Locale("ru"), "%.2f км", m / 1000)

    /** Время «1:02:03» / «25:07». */
    fun time(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
