package ru.streetlift.tracker.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Прогноз силового результата к дате. На план тренировок не влияет — только показывает, куда ведёт текущий темп.
 *
 * Берётся лучший за каждую неделю оценочный максимум (свой вес + отягощение, по Эпли с запасом)
 * за последние 12 недель, через них проводится прямая (тренд). К дате — тренд с затуханием
 * (рост со временем замедляется), вокруг — коридор из разброса недель и дальности прогноза.
 * Нужны данные хотя бы за 3 разные недели; дальше года прогноз не даётся.
 */
object Forecast {
    const val WINDOW_DAYS = 84L
    const val MAX_DAYS = 364L
    const val MIN_WEEKS = 3

    data class Pt(val day: Long, val v: Double)

    data class Result(
        /** Оценка сейчас (по тренду). */
        val now: Double,
        val low: Double,
        val mid: Double,
        val high: Double,
        /** Изменение за неделю по тренду. */
        val perWeek: Double,
        /** По скольким неделям построено. */
        val weeks: Int,
    )

    fun of(points: List<Pt>, today: Long, target: Long): Result? {
        if (target < today || target - today > MAX_DAYS) return null
        val weekly = points.filter { today - it.day in 0..WINDOW_DAYS }
            .groupBy { (today - it.day) / 7 }
            .map { (ago, ps) -> -ago.toDouble() to ps.maxOf { it.v } }
            .sortedBy { it.first }
        if (weekly.size < MIN_WEEKS) return null
        val n = weekly.size
        val mx = weekly.sumOf { it.first } / n
        val my = weekly.sumOf { it.second } / n
        val sxx = weekly.sumOf { (it.first - mx) * (it.first - mx) }
        val sxy = weekly.sumOf { (it.first - mx) * (it.second - my) }
        val rawSlope = if (sxx > 0) sxy / sxx else 0.0
        val fitNow = my - rawSlope * mx // значение тренда в x = 0 (эта неделя)
        val now = fitNow.coerceAtLeast(weekly.last().second * 0.97)
        // Тренд ограничен разумным: от −1% до +1,5% в неделю
        val slope = rawSlope.coerceIn(-0.01 * now, 0.015 * now)
        val resid = weekly.map { it.second - (fitNow + rawSlope * it.first) }
        val sd = if (n > 2) sqrt(resid.sumOf { it * it } / (n - 2)) else 0.0
        val h = (target - today) / 7.0
        val mid = now + slope * h / (1.0 + h / 26.0)
        val band = maxOf(sd * sqrt(1.0 + h / 4.0) + abs(slope) * h * 0.3, now * 0.01)
        return Result(now, mid - band, mid, mid + band, slope, n)
    }
}
