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

    /**
     * Точки графика при одном весе: по тренировке — лучший подход ([sum] = false)
     * или сумма повторов ([sum] = true). Ручные рекорды — отдельные точки (только для лучшего подхода).
     */
    fun series(entries: List<Entry>, name: String, stage: String?, weight: Float, sum: Boolean): List<Point> {
        val k = key(weight)
        val list = entries.filter { it.name == name && it.stage == stage && it.weightKey == k }
        val fromSessions = list.filter { it.sessionId != null }.groupBy { it.sessionId }.map { (_, s) ->
            Point(s.minOf { it.epochDay }, if (sum) s.sumOf { it.value } else s.maxOf { it.value }, manual = false)
        }
        val manual = if (sum) emptyList() else list.filter { it.manualId != null }.map { Point(it.epochDay, it.value, manual = true) }
        return (fromSessions + manual).sortedBy { it.epochDay }
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
