package ru.streetlift.tracker.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** Резина: название и помощь в кг. */
data class Band(val name: String, val kg: Float)

/** Сочетание резин: помощь (отрицательный вес) и что взять. */
data class BandCombo(val weight: Float, val bands: List<Band>) {
    val label: String get() = bands.joinToString(" + ") { it.name }
}

/**
 * Резины для помощи. Отрицательный вес упражнения — помощь резин в кг;
 * допустимые значения — суммы любых сочетаний резин (при одинаковой сумме — меньше резин).
 */
object Bands {
    val DEFAULT = listOf(
        Band("Зелёная", 75f),
        Band("Фиолетовая", 45f),
        Band("Чёрная", 30f),
        Band("Красная", 20f),
    )

    /** Текущий набор (обновляется из настроек). */
    @Volatile
    var current: List<Band> = DEFAULT
        set(value) {
            field = value
            cache = null
        }

    @Volatile
    private var cache: List<BandCombo>? = null

    /** Все сочетания по возрастанию веса (от самой большой помощи к самой маленькой). */
    fun combos(bands: List<Band> = current): List<BandCombo> {
        if (bands === current) cache?.let { return it }
        val list = bands.filter { it.kg > 0f }
        val best = HashMap<Int, List<Band>>()
        for (mask in 1 until (1 shl list.size)) {
            val chosen = list.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val key = (chosen.sumOf { it.kg.toDouble() } * 100).roundToInt()
            val prev = best[key]
            if (prev == null || chosen.size < prev.size) best[key] = chosen
        }
        val res = best.entries.sortedBy { it.key }.reversed()
            .map { BandCombo(-it.key / 100f, it.value) }
        if (bands === current) cache = res
        return res
    }

    /** Лестница помощи: отрицательные значения по возрастанию. */
    fun ladder(bands: List<Band> = current): List<Float> = combos(bands).map { it.weight }

    /** Что взять для этого веса; null — не резина или значения нет в лестнице. */
    fun describe(weight: Float, bands: List<Band> = current): String? {
        if (weight >= 0f) return null
        return combos(bands).firstOrNull { abs(it.weight - weight) < 0.01f }?.label
    }

    /** Хранение в настройках: «Зелёная:75,Фиолетовая:45». */
    fun encode(bands: List<Band>): String = bands.joinToString(",") { "${it.name}:${Fmt.step(it.kg)}" }

    fun decode(s: String?): List<Band> {
        val parsed = s.orEmpty().split(',').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val kg = part.substring(i + 1).trim().replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            Band(part.substring(0, i).trim(), kg)
        }
        return if (s == null) DEFAULT else parsed
    }
}

/**
 * Шкала нагрузки упражнения: резины (если разрешены) → свой вес → +шаг…
 * [assists] — лестница помощи (отрицательные, по возрастанию); пустая — ниже 0 нельзя;
 * null — старое поведение (знаковый вес шагами), для совместимости.
 */
data class LoadScale(val step: Float, val assists: List<Float>?) {
    private val st get() = if (step > 0f) step else 2.5f

    /** Ступень тяжелее. */
    fun up(w: Float): Float = when {
        assists != null && w < 0f -> assists.firstOrNull { it > w + 0.001f } ?: 0f
        else -> ProgressionEngine.roundWeight(w + st)
    }

    /** Ступень легче. */
    fun down(w: Float): Float = when {
        assists == null -> ProgressionEngine.roundWeight(w - st)
        w > 0.001f -> ProgressionEngine.roundWeight(w - st).coerceAtLeast(0f)
        assists.isEmpty() -> 0f
        else -> assists.lastOrNull { it < w - 0.001f } ?: w
    }

    /** Привести к допустимому значению (резины — ближайшее сочетание, без резин — не ниже 0). */
    fun snap(w: Float): Float = when {
        assists == null || w >= 0f -> w
        assists.isEmpty() -> 0f
        else -> assists.minByOrNull { abs(it - w) } ?: 0f
    }

    /** Ручной ввод: отягощение — к ближайшему кратному шагу, помощь — к ближайшему сочетанию резин. */
    fun snapManual(w: Float): Float =
        if (w > 0f) ProgressionEngine.roundWeight(kotlin.math.round(w / st) * st) else snap(w)

    /** Ближайшее допустимое значение к [target], строго легче [w]; null — легче некуда. Только при [assists] != null. */
    fun lighterNear(w: Float, target: Float): Float? {
        val cands = ArrayList<Float>(assists.orEmpty())
        cands += 0f
        if (target > 0f) {
            val k = kotlin.math.round(target / st).toInt()
            for (d in -1..1) if (k + d > 0) cands += ProgressionEngine.roundWeight((k + d) * st)
        }
        return cands.filter { it < w - 0.001f }.minByOrNull { abs(it - target) }
    }
}
