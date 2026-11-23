package ru.streetlift.tracker.domain

import kotlin.math.roundToInt

/**
 * Рекорды и графики: всё считается отдельно для каждого упражнения, стадии рычага и веса.
 * Рекорд — лучший один подход (повторы или секунды) при данном весе.
 */
object Records {

    /** Один подход: из тренировки ([sessionId]) или введённый вручную ([manualId]). */
    data class Entry(
        val name: String,
        val stage: String?,
        val weight: Float,
        val value: Int,
        val isSeconds: Boolean,
        val epochDay: Long,
        val sessionId: Long? = null,
        val manualId: Long? = null,
        /** Прыжки на тумбу: значение — высота, см. */
        val isHeight: Boolean = false,
    ) {
        val weightKey: Int get() = key(weight)
    }

    data class Best(val value: Int, val epochDay: Long, val manualId: Long?)

    data class Row(val weight: Float, val months3: Best?, val year: Best?, val all: Best)

    data class Point(val epochDay: Long, val value: Int, val manual: Boolean)

    fun key(w: Float): Int = (w * 100f).roundToInt()

    /** Лучший подход: больше значение; при равенстве — раньше поставленный. */
    private fun best(list: List<Entry>): Best? =
        list.maxWithOrNull(compareBy<Entry> { it.value }.thenByDescending { it.epochDay })
            ?.let { Best(it.value, it.epochDay, it.manualId) }

    /** Стадии упражнения (null — без стадии / не указана), по порядку появления. */
    fun stages(entries: List<Entry>, name: String): List<String?> =
        entries.filter { it.name == name }.sortedBy { it.epochDay }.map { it.stage }.distinct()

    /** Веса на стадии — от тяжёлого к лёгкому. */
    fun weights(entries: List<Entry>, name: String, stage: String?): List<Float> =
        entries.filter { it.name == name && it.stage == stage }
            .distinctBy { it.weightKey }.map { it.weight }.sortedDescending()

    /** Таблица рекордов: строка на каждый вес — за 3 месяца, год и всё время. */
    fun table(entries: List<Entry>, name: String, stage: String?, today: Long): List<Row> {
        val from3 = java.time.LocalDate.ofEpochDay(today).minusMonths(3).toEpochDay()
        val fromY = java.time.LocalDate.ofEpochDay(today).minusYears(1).toEpochDay()
        return entries.filter { it.name == name && it.stage == stage }
            .groupBy { it.weightKey }
            .map { (_, list) ->
                Row(
                    weight = list.first().weight,
                    months3 = best(list.filter { it.epochDay >= from3 }),
                    year = best(list.filter { it.epochDay >= fromY }),
                    all = best(list)!!,
                )
            }
            .sortedByDescending { it.weight }
    }

    /** Точки графика при одном весе: лучший подход каждой тренировки и ручные рекорды, в периоде [from]..[to]. */
    fun series(entries: List<Entry>, name: String, stage: String?, weight: Float, from: Long? = null, to: Long? = null): List<Point> =
        history(entries, name, stage, weight, from, to).map { Point(it.epochDay, it.best, it.manualId != null) }.sortedBy { it.epochDay }

    /** Строка истории при весе: тренировка (подходы по порядку) или ручной рекорд. */
    data class HistoryRow(val epochDay: Long, val values: List<Int>, val sessionId: Long?, val manualId: Long?) {
        val best: Int get() = values.max()
    }

    /** История упражнения при весе за период — новые сверху. */
    fun history(entries: List<Entry>, name: String, stage: String?, weight: Float, from: Long? = null, to: Long? = null): List<HistoryRow> {
        val k = key(weight)
        val list = entries.filter {
            it.name == name && it.stage == stage && it.weightKey == k &&
                (from == null || it.epochDay >= from) && (to == null || it.epochDay <= to)
        }
        val sessions = list.filter { it.sessionId != null }.groupBy { it.sessionId }.map { (sid, s) ->
            HistoryRow(s.minOf { it.epochDay }, s.map { it.value }, sid, null)
        }
        val manual = list.filter { it.manualId != null }.map { HistoryRow(it.epochDay, listOf(it.value), null, it.manualId) }
        return (sessions + manual).sortedWith(compareByDescending<HistoryRow> { it.epochDay }.thenByDescending { it.sessionId ?: 0 })
    }

    /**
     * Новые рекорды тренировки [sessionId]: лучший подход при весе выше прежнего лучшего
     * (по всем прошлым тренировкам и ручным записям). Первый раз на весе — не рекорд.
     */
    fun newRecords(entries: List<Entry>, sessionId: Long): List<Entry> {
        val mine = entries.filter { it.sessionId == sessionId }
        val others = entries.filter { it.sessionId != sessionId }
        return mine.groupBy { Triple(it.name, it.stage, it.weightKey) }.mapNotNull { (k, list) ->
            val top = list.maxBy { it.value }
            val prev = others.filter { it.name == k.first && it.stage == k.second && it.weightKey == k.third }.maxOfOrNull { it.value }
                ?: return@mapNotNull null
            top.takeIf { it.value > prev }
        }
    }
}
