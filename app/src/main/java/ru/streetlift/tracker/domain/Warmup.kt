package ru.streetlift.tracker.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Разминка тем же движением перед рабочими подходами. В историю, рекорды и прогрессию не идёт.
 *
 * - с отягощением W: свой вес × 5, ~50% W × 3, ~75% W × 2 (кратно 1,25; повторяющиеся веса убираются);
 * - свой вес: подход с резиной посильнее × 5 (если резин нет — половина цели без веса);
 * - с резиной: на ступень помощи сильнее × 5;
 * - прыжки: 3 прыжка на 15 см ниже, без веса;
 * - статика, изоляция (задняя дельта), разгрузка — без разминки (в разгрузке — один лёгкий подход).
 */
object Warmup {
    data class WSet(val weightKg: Float, val reps: Int, val heightCm: Int? = null)

    fun sets(
        type: ExerciseType,
        isSeconds: Boolean,
        isJump: Boolean,
        plan: SessionPlan,
        scale: LoadScale,
    ): List<WSet> {
        if (isSeconds || type == ExerciseType.ISOLATION) return emptyList()
        if (isJump) {
            val h = plan.heightCm ?: return emptyList()
            return listOf(WSet(0f, 3, max(Jumps.MIN_CM, h - Jumps.STEP_CM)))
        }
        val w = plan.weightKg
        val all = when {
            w > 5f -> listOf(
                WSet(0f, 5),
                WSet(snapUnder(w * 0.5f, w, scale), 3),
                WSet(snapUnder(w * 0.75f, w, scale), 2),
            )
            w > 0f -> listOf(WSet(0f, 5))
            w < 0f || scale.assists != null -> {
                val easier = scale.down(w)
                if (easier < w - 0.001f) listOf(WSet(easier, 5)) else listOf(WSet(w, max(1, min(5, plan.targetReps / 2))))
            }
            else -> listOf(WSet(0f, max(1, min(5, plan.targetReps / 2))))
        }.distinctBy { it.weightKg }.filter { it.weightKg < w + 0.001f || w <= 0f }
        return if (plan.isDeload) all.take(1) else all
    }

    /** Вес кратно шагу, строго легче рабочего. */
    private fun snapUnder(target: Float, work: Float, scale: LoadScale): Float {
        var v = scale.snap(ProgressionEngine.roundWeight(target))
        if (v >= work - 0.001f) v = scale.down(work)
        return v.coerceAtLeast(0f)
    }
}
