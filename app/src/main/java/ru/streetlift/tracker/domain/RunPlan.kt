package ru.streetlift.tracker.domain

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Подготовка к забегу: оценка формы, реалистичная цель к дате и план пробежек по неделям.
 *
 * Форма — VDOT по формулам Дэниелса/Гилберта из лучших отрезков пробежек (1/3/5/10 км, полумарафон)
 * за последние 8 недель. Прогноз времени на дистанцию — обратный расчёт по VDOT; для полумарафона
 * и марафона — поправка в медленную сторону (формулы оптимистичны для длинных дистанций).
 *
 * Рост формы — модель с допущениями: прирост VDOT в неделю зависит от уровня и числа пробежек
 * и затухает со временем. Поэтому цель — коридор, который сужается к дате.
 */
object RunPlan {

    enum class Dist(val m: Int, val title: String, val longCapKm: Double, val peakWeekKm: Double, val taperWeeks: Int) {
        K3(3000, "3 км", 10.0, 30.0, 1),
        K5(5000, "5 км", 12.0, 40.0, 1),
        K10(10000, "10 км", 16.0, 50.0, 1),
        HALF(21097, "Полумарафон", 20.0, 60.0, 2),
        MARATHON(42195, "Марафон", 32.0, 75.0, 2);

        /** Поправка прогноза в медленную сторону. */
        val slowFactor: Double get() = when (this) {
            MARATHON -> 1.05
            HALF -> 1.02
            else -> 1.0
        }

        companion object {
            fun byM(m: Int?): Dist? = entries.firstOrNull { it.m == m }
        }
    }

    /** Предел недель для прогноза: дальше — прогноз не даём. */
    const val MAX_WEEKS = 52
    /** Сколько пробежек в неделю программа предлагает сначала. */
    const val DEFAULT_RUNS = 6

    // ---------------- VDOT ----------------

    private fun vo2(vMperMin: Double) = -4.60 + 0.182258 * vMperMin + 0.000104 * vMperMin * vMperMin
    private fun pctMax(tMin: Double) = 0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)

    /** VDOT по результату: дистанция (м) за время (с). */
    fun vdot(distM: Double, timeSec: Double): Double {
        val t = timeSec / 60.0
        return vo2(distM / t) / pctMax(t)
    }

    /** Время (с) на дистанцию при форме [v], с поправкой для длинных дистанций. */
    fun predictSec(d: Dist, v: Double): Double = rawPredictSec(d.m.toDouble(), v) * d.slowFactor

    private fun rawPredictSec(distM: Double, v: Double): Double {
        var lo = distM / 1000.0 * 120.0 // 2:00/км
        var hi = distM / 1000.0 * 1200.0 // 20:00/км
        repeat(60) {
            val mid = (lo + hi) / 2
            if (vdot(distM, mid) > v) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Форма, нужная для времени [timeSec] на дистанции (с учётом поправки). */
    fun vdotFor(d: Dist, timeSec: Double): Double = vdot(d.m.toDouble(), timeSec / d.slowFactor)

    /** Темп (с/км) при доле [frac] от VDOT. */
    fun paceAt(v: Double, frac: Double): Double {
        val target = v * frac
        // 0,000104 v² + 0,182258 v − 4,6 − target = 0
        val a = 0.000104; val b = 0.182258; val c = -4.60 - target
        val speed = (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
        return 60_000.0 / speed
    }

    enum class Zone(val title: String, val frac: Double) {
        EASY("лёгкий", 0.70), TEMPO("темповый", 0.88), INTERVAL("интервальный", 0.98), RACE("соревновательный", 0.0)
    }

    /** Темп зоны (с/км); для старта — темп цели. */
    fun zonePace(z: Zone, v: Double, d: Dist, goalSec: Double?): Double =
        if (z == Zone.RACE) (goalSec ?: predictSec(d, v)) / (d.m / 1000.0) else paceAt(v, z.frac)

    // ---------------- Форма по пробежкам ----------------

    /** Пробежка для оценки: день, дистанция и лучшие отрезки (окно, м → время, мс). */
    data class RunData(val epochDay: Long, val distanceM: Double, val efforts: Map<Int, Long>)

    val EFFORT_WINDOWS = listOf(1000, 3000, 5000, 10000, 21097)

    /** Форма: лучший VDOT по отрезкам за 8 недель (1 км — с поправкой, он завышает выносливость). */
    fun fitness(runs: List<RunData>, today: Long): Double? =
        runs.filter { today - it.epochDay <= 56 }.flatMap { r ->
            r.efforts.map { (w, ms) -> vdot(w.toDouble(), ms / 1000.0) * if (w < 3000) 0.95 else 1.0 }
        }.maxOrNull()

    /** Средний недельный объём за 4 недели, км. */
    fun recentWeeklyKm(runs: List<RunData>, today: Long): Double =
        runs.filter { today - it.epochDay in 0..27 }.sumOf { it.distanceM } / 1000.0 / 4.0

    // ---------------- Рост формы и коридор цели ----------------

    /** Прирост VDOT за неделю: чем выше уровень, тем медленнее; меньше пробежек — меньше прирост. */
    fun weeklyGain(v: Double, runsPerWeek: Int): Double {
        val level = when {
            v < 35 -> 0.5
            v < 45 -> 0.3
            v < 55 -> 0.18
            else -> 0.1
        }
        val freq = (runsPerWeek / 5.0).pow(0.6).coerceAtMost(1.15)
        return level * freq
    }

    /** Прирост за [weeks] недель с затуханием; последние недели (снижение перед стартом) прироста не дают. */
    fun gain(v: Double, runsPerWeek: Int, weeks: Double, d: Dist): Double {
        val w = (weeks - d.taperWeeks).coerceAtLeast(0.0)
        return weeklyGain(v, runsPerWeek) * w / (1.0 + w / 26.0)
    }

    data class Corridor(val fastSec: Double, val midSec: Double, val slowSec: Double)

    /**
     * Реалистичный коридор времени к дате: от осторожного (половина модельного прироста) до смелого (×1,4),
     * плюс неточность оценки формы, которая уменьшается ближе к старту.
     */
    fun corridor(d: Dist, v: Double, runsPerWeek: Int, daysLeft: Long): Corridor {
        val weeks = daysLeft / 7.0
        val g = gain(v, runsPerWeek, weeks, d)
        val u = 0.01 + 0.01 * min(weeks, 12.0) / 12.0
        return Corridor(
            fastSec = predictSec(d, v + g * 1.4) * (1 - u),
            midSec = predictSec(d, v + g),
            slowSec = predictSec(d, v + g * 0.5) * (1 + u),
        )
    }

    /** Сколько недель нужно на цель [goalSec] при [runsPerWeek] пробежках (null — больше года). */
    fun weeksNeeded(d: Dist, v: Double, goalSec: Double, runsPerWeek: Int): Int? {
        val need = vdotFor(d, goalSec) - v
        if (need <= 0) return 0
        for (w in 1..MAX_WEEKS) if (gain(v, runsPerWeek, w.toDouble(), d) >= need) return w
        return null
    }

    // ---------------- План ----------------

    enum class Type(val title: String) { EASY("Лёгкая"), LONG("Длинная"), TEMPO("Темповая"), INTERVALS("Интервалы"), RACE("Старт") }

    enum class SegKind(val title: String) { WARMUP("Разминка"), WORK("Работа"), RECOVERY("Отдых трусцой"), COOLDOWN("Заминка"), STEADY("Бег") }

    data class Seg(val kind: SegKind, val m: Int, val zone: Zone)

    data class Item(val epochDay: Long, val type: Type, val segs: List<Seg>) {
        val km: Double get() = segs.sumOf { it.m } / 1000.0
        val workSegs: List<Seg> get() = segs.filter { it.kind == SegKind.WORK }

        /** Короткое описание: «Интервалы 5 × 800 м» / «Лёгкая 6 км». */
        fun title(): String = when (type) {
            Type.INTERVALS -> "Интервалы ${workSegs.size} × ${workSegs.firstOrNull()?.m ?: 0} м"
            Type.TEMPO -> "Темповая ${RunStats.distance(workSegs.sumOf { it.m }.toDouble())} в темпе"
            else -> "${type.title} ${RunStats.distance(km * 1000)}"
        }
    }

    private const val WARM_M = 2000
    private const val COOL_M = 1500
    private const val REC_M = 400

    private fun km(x: Double): Int = (x * 2).roundToInt().coerceAtLeast(2) * 500 // кратно 500 м, от 1 км

    /**
     * План с [start] (включительно) до старта [race]: по неделям, [runs] пробежек в неделю.
     * Объём растёт ~8% в неделю от [baseKm], каждая 4-я неделя легче, перед стартом снижение.
     */
    fun build(start: Long, race: Long, d: Dist, runs: Int, baseKm: Double): List<Item> {
        val r = runs.coerceIn(1, 7)
        val items = mutableListOf<Item>()
        val totalDays = race - start
        if (totalDays <= 0) return listOf(raceItem(race, d))
        val weeks = ceil(totalDays / 7.0).toInt()
        var vol = max(baseKm, r * 3.0)
        val peak = max(d.peakWeekKm * min(1.0, r / 5.0), r * 4.0)
        for (w in 0 until weeks) {
            val weekStart = start + w * 7L
            val weeksToRace = weeks - w
            var v = when {
                weeksToRace <= 1 -> vol * 0.5
                weeksToRace <= d.taperWeeks -> vol * 0.75
                (w + 1) % 4 == 0 -> vol * 0.8
                else -> vol
            }
            v = v.coerceAtMost(peak)
            val offsets = (0 until r).map { (it * 7.0 / r).roundToInt() }.distinct()
            val days = offsets.map { weekStart + it }.filter { it < race - 1 || (it < race && r == 1) }
            if (days.isEmpty()) continue
            // Роли: длинная — последний день недели, качество — в середине (при 5+ — ещё одна)
            val longDay = if (days.size >= 2) days.last() else null
            val q1 = if (days.size >= 3) days[days.size / 2 - (if (days.size % 2 == 0) 1 else 0)] else null
            // Второе качество — подальше от первого и от длинных (своей и прошлой недели)
            val q2 = if (days.size >= 5 && q1 != null) {
                days.filter { it != longDay && it != q1 }.maxByOrNull { dd ->
                    minOf(kotlin.math.abs(dd - q1), (longDay ?: dd + 7) - dd, dd - (weekStart - 1))
                }
            } else null
            val longKm = if (longDay != null) min(v * 0.3, d.longCapKm).coerceAtLeast(4.0) else 0.0
            val quality = mutableMapOf<Long, Item>()
            if (q1 != null && weeksToRace > 1) {
                val intervalsFirst = d.m <= 10000
                val t1 = if ((w % 2 == 0) == intervalsFirst) Type.INTERVALS else Type.TEMPO
                quality[q1] = qualityItem(q1, t1, d, v)
                if (q2 != null && q2 != q1) quality[q2] = qualityItem(q2, if (t1 == Type.INTERVALS) Type.TEMPO else Type.INTERVALS, d, v)
            }
            val usedKm = longKm + quality.values.sumOf { it.km }
            val easyDays = days.filter { it != longDay && it !in quality }
            val easyKm = if (easyDays.isEmpty()) 0.0 else ((v - usedKm) / easyDays.size).coerceAtLeast(3.0)
            days.forEach { day ->
                items += when {
                    day == longDay -> Item(day, Type.LONG, listOf(Seg(SegKind.STEADY, km(longKm), Zone.EASY)))
                    day in quality -> quality.getValue(day)
                    else -> Item(day, Type.EASY, listOf(Seg(SegKind.STEADY, km(easyKm), Zone.EASY)))
                }
            }
            if (weeksToRace > d.taperWeeks && (w + 1) % 4 != 0) vol = min(vol * 1.08, peak)
        }
        items += raceItem(race, d)
        return items.sortedBy { it.epochDay }
    }

    private fun raceItem(race: Long, d: Dist) = Item(race, Type.RACE, listOf(Seg(SegKind.STEADY, d.m, Zone.RACE)))

    private fun qualityItem(day: Long, t: Type, d: Dist, weekKm: Double): Item = when (t) {
        Type.INTERVALS -> {
            val repM = when (d) {
                Dist.K3 -> 400
                Dist.K5 -> 800
                else -> 1000
            }
            val reps = ((weekKm * 0.08 * 1000) / repM).roundToInt().coerceIn(3, if (repM == 400) 10 else 6)
            val segs = buildList {
                add(Seg(SegKind.WARMUP, WARM_M, Zone.EASY))
                repeat(reps) { i ->
                    add(Seg(SegKind.WORK, repM, Zone.INTERVAL))
                    if (i < reps - 1) add(Seg(SegKind.RECOVERY, REC_M, Zone.EASY))
                }
                add(Seg(SegKind.COOLDOWN, COOL_M, Zone.EASY))
            }
            Item(day, Type.INTERVALS, segs)
        }
        else -> {
            val tempoM = km((weekKm * 0.12).coerceIn(2.0, 10.0))
            Item(
                day, Type.TEMPO,
                listOf(Seg(SegKind.WARMUP, WARM_M, Zone.EASY), Seg(SegKind.WORK, tempoM, Zone.TEMPO), Seg(SegKind.COOLDOWN, COOL_M, Zone.EASY)),
            )
        }
    }

    /** Пробежка засчитана: в этот день есть бег не короче 70% плана (старт — 90%). */
    fun isDone(item: Item, runs: List<RunData>): Boolean {
        val need = item.km * 1000 * if (item.type == Type.RACE) 0.9 else 0.7
        return runs.any { it.epochDay == item.epochDay && it.distanceM >= need }
    }

    /** Время «ч:мм:сс» / «мм:сс». */
    fun time(sec: Double): String {
        val s = sec.roundToInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Разбор времени «мм:сс» или «ч:мм:сс». */
    fun parseTime(t: String): Int? {
        val p = t.trim().split(':').map { it.trim().toIntOrNull() ?: return null }
        return when (p.size) {
            2 -> if (p[1] in 0..59) p[0] * 60 + p[1] else null
            3 -> if (p[1] in 0..59 && p[2] in 0..59) p[0] * 3600 + p[1] * 60 + p[2] else null
            else -> null
        }?.takeIf { it > 0 }
    }

    /** Поддержка силовых на время подготовки к бегу: меньше подходов, больше запас. */
    fun support(plan: SessionPlan): SessionPlan =
        if (plan.isCalibration) plan
        else plan.copy(sets = max(1, ceil(plan.sets * 0.6).toInt()), rirMin = max(plan.rirMin, 2), rirMax = max(plan.rirMax, 3))
}
