package ru.streetlift.tracker.run

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.streetlift.tracker.domain.RunPlan
import ru.streetlift.tracker.domain.RunStats

/**
 * Пробежка по плану: отрезки (разминка, работа, отдых, заминка) по дистанции, целевой темп каждого,
 * объявление смены отрезка голосом/сигналом.
 */
object RunWorkout {
    data class Active(val item: RunPlan.Item, val paces: List<Double>)

    data class State(
        val active: Active? = null,
        val index: Int = 0,
        /** С какой дистанции пробежки начался текущий отрезок, м. */
        val segStartM: Double = 0.0,
        val announcedStart: Boolean = false,
    ) {
        val seg: RunPlan.Seg? get() = active?.item?.segs?.getOrNull(index)
        val pace: Double? get() = active?.paces?.getOrNull(index)
        val finished: Boolean get() = active != null && index >= active.item.segs.size

        /** Номер рабочего отрезка («2 из 5») для интервалов. */
        fun workNumber(): Pair<Int, Int>? {
            val segs = active?.item?.segs ?: return null
            val total = segs.count { it.kind == RunPlan.SegKind.WORK }
            if (total < 2) return null
            val done = segs.take(index + 1).count { it.kind == RunPlan.SegKind.WORK }
            return done to total
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(item: RunPlan.Item, paces: List<Double>) {
        _state.value = State(Active(item, paces))
    }

    fun clear() {
        _state.value = State()
    }

    /** Фраза про отрезок: «Работа, 800 метров, темп 4:10». */
    fun phrase(seg: RunPlan.Seg, pace: Double?, s: State): String {
        val num = s.workNumber()?.takeIf { seg.kind == RunPlan.SegKind.WORK }?.let { " ${it.first} из ${it.second}" } ?: ""
        val dist = if (seg.m >= 1000 && seg.m % 1000 == 0) "${seg.m / 1000} километр${ending(seg.m / 1000)}"
        else if (seg.m >= 1000) RunStats.distance(seg.m.toDouble()).replace(" км", " километра") else "${seg.m} метров"
        val p = pace?.let { RunStats.pace(it) }?.let { ", темп $it" } ?: ""
        return "${seg.kind.title}$num, $dist$p"
    }

    private fun ending(n: Int): String = when {
        n % 100 in 11..14 -> "ов"
        n % 10 == 1 -> ""
        n % 10 in 2..4 -> "а"
        else -> "ов"
    }

    /** Новая дистанция пробежки → текст объявления, если начался новый отрезок (или первый). */
    fun onDistance(distM: Double): String? {
        var s = _state.value
        val a = s.active ?: return null
        val segs = a.item.segs
        var msg: String? = null
        if (!s.announcedStart) {
            s = s.copy(announcedStart = true)
            segs.firstOrNull()?.let { msg = phrase(it, a.paces.firstOrNull(), s) }
        }
        while (s.index < segs.size && distM - s.segStartM >= segs[s.index].m) {
            s = s.copy(segStartM = s.segStartM + segs[s.index].m, index = s.index + 1)
            msg = if (s.index < segs.size) phrase(segs[s.index], a.paces.getOrNull(s.index), s) else "План выполнен, можно заканчивать"
        }
        _state.value = s
        return msg
    }
}
