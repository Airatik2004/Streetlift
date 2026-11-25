package ru.streetlift.tracker.domain

import kotlin.math.max

/**
 * Разминка тем же движением перед рабочими подходами. В историю, рекорды и прогрессию не идёт.
 * Всегда 3 подхода, в разгрузке (и при плохом самочувствии) — первые 2. Одинаковые веса после
 * округления не убираются.
 *
 * - с отягощением W: свой вес × 5, ~50% W × 3, ~75% W × 2 (кратно шагу, строго легче рабочего);
 * - с резиной: на две ступени помощи сильнее × 5, на одну × 3, на одну × 2;
 * - свой вес без резин: ~30%, ~50%, ~30% цели повторов;
 * - прыжки: на 15 см ниже рабочей высоты, 3 прыжка без веса, с 5 кг, с 10 кг;
 * - статика и изоляция (задняя дельта) — без разминки.
 */
object Warmup {
    data class WSet(val weightKg: Float, val reps: Int, val heightCm: Int? = null)

    const val SETS = 3
    const val DELOAD_SETS = 2
    val JUMP_WEIGHTS = listOf(0f, 5f, 10f)

    fun sets(
        type: ExerciseType,
        isSeconds: Boolean,
        isJump: Boolean,
        plan: SessionPlan,
        scale: LoadScale,
    ): List<WSet> {
        if (isSeconds || type == ExerciseType.ISOLATION) return emptyList()
        val w = plan.weightKg
        val all = when {
            isJump -> {
                val h = max(Jumps.MIN_CM, (plan.heightCm ?: return emptyList()) - Jumps.STEP_CM)
                JUMP_WEIGHTS.map { WSet(it, 3, h) }
            }
            w > 0f -> listOf(
                WSet(0f, 5),
                WSet(snapUnder(w * 0.5f, w, scale), 3),
                WSet(snapUnder(w * 0.75f, w, scale), 2),
            )
            w < 0f || scale.assists != null -> {
                val e1 = scale.down(w)
                if (e1 < w - 0.001f) {
                    val e2 = scale.down(e1).let { if (it < e1 - 0.001f) it else e1 }
                    listOf(WSet(e2, 5), WSet(e1, 3), WSet(e1, 2))
                } else byReps(scale.snap(w), plan.targetReps)
            }
            else -> byReps(0f, plan.targetReps)
        }
        return limit(all, plan.isDeload)
    }

    /** 3 подхода, в разгрузке — первые 2. */
    fun limit(all: List<WSet>, deload: Boolean): List<WSet> = if (deload) all.take(DELOAD_SETS) else all.take(SETS)

    /** Нагрузку не облегчить — меняем только повторы: ~30%, ~50%, ~30% цели. */
    private fun byReps(w: Float, target: Int): List<WSet> =
        listOf(0.3f, 0.5f, 0.3f).map { WSet(w, max(1, kotlin.math.round(target * it).toInt())) }

    /** Вес кратно шагу, строго легче рабочего. */
    private fun snapUnder(target: Float, work: Float, scale: LoadScale): Float {
        val st = if (scale.step > 0f) scale.step else 2.5f
        var v = ProgressionEngine.roundWeight(kotlin.math.floor(target / st + 0.001f) * st)
        if (v >= work - 0.001f) v = scale.down(work)
        return v.coerceAtLeast(0f)
    }
}
