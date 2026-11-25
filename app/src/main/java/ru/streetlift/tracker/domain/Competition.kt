package ru.streetlift.tracker.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Выход на пик к соревнованиям: оценка текущего результата в дисциплине и план пиковых тренировок.
 *
 * Оценка — по формуле Эпли с учётом запаса: максимум полной нагрузки (свой вес + отягощение)
 * = нагрузка × (1 + (повторы + RIR) / 30). Для повторов с фиксированным весом сначала берутся
 * прямые подходы с этим весом (±1,25 кг), без них — пересчёт из максимума. Оценка приблизительная,
 * точнее всего по тяжёлым подходам на 1–6 повторов.
 */
object Competition {
    /** Сколько дней истории учитывается в оценке. */
    const val HISTORY_DAYS = 56L

    data class LiftSet(val weightKg: Float, val reps: Int, val rir: Int)

    /** Максимум полной нагрузки по одному подходу. */
    fun e1rm(bodyWeight: Float, s: LiftSet): Float =
        (bodyWeight + s.weightKg) * (1f + (s.reps + s.rir.coerceIn(0, 5)) / 30f)

    fun bestE1rm(bodyWeight: Float, sets: List<LiftSet>): Float? =
        sets.filter { it.reps > 0 }.maxOfOrNull { e1rm(bodyWeight, it) }

    /** Максимальный добавочный вес на 1 раз (кратно 1,25 вниз). */
    fun maxAdded(bodyWeight: Float, e1rm: Float): Float =
        floor((e1rm - bodyWeight) / 1.25f) * 1.25f

    /** Повторы «до отказа» с весом [w]: прямые подходы с этим весом, иначе пересчёт из максимума. */
    fun repsAt(bodyWeight: Float, w: Float, e1rm: Float?, sets: List<LiftSet>): Int? {
        val direct = sets.filter { abs(it.weightKg - w) <= 1.26f && it.reps > 0 }.maxOfOrNull { it.reps + it.rir.coerceIn(0, 5) }
        if (direct != null) return direct
        val m = e1rm ?: return null
        return floor(30f * (m / (bodyWeight + w) - 1f)).toInt().coerceAtLeast(0)
    }

    /** Оценка по одному упражнению дисциплины: максимум нагрузки и прогноз повторов с весом дисциплины. */
    data class LiftEstimate(val lift: CompLift, val e1rm: Float?, val reps: Int?, val maxAdded: Float?)

    data class Estimate(val lifts: List<LiftEstimate>, val result: Float?)

    fun estimate(d: Discipline, bodyWeight: Float, setsByLift: Map<CompLift, List<LiftSet>>): Estimate {
        val lifts = d.lifts.mapIndexed { i, lift ->
            val sets = setsByLift[lift].orEmpty()
            val m = bestE1rm(bodyWeight, sets)
            val w = d.weights.getOrNull(i) ?: 0f
            LiftEstimate(
                lift, m,
                reps = if (d.kind == CompKind.MAX) null else repsAt(bodyWeight, w, m, sets),
                maxAdded = m?.let { maxAdded(bodyWeight, it) },
            )
        }
        val result = when (d.kind) {
            CompKind.MAX -> if (lifts.all { it.maxAdded != null }) lifts.sumOf { it.maxAdded!!.toDouble() }.toFloat() else null
            else -> if (lifts.all { it.reps != null }) lifts.sumOf { it.reps!! }.toFloat() else null
        }
        return Estimate(lifts, result)
    }

    /** Снимок для плана: «id|e1rm|повторы;…» по пиковым упражнениям. */
    data class Snap(val exerciseId: Long, val e1rm: Float?, val reps: Int?)

    fun encode(snaps: List<Snap>): String =
        snaps.joinToString(";") { "${it.exerciseId}|${it.e1rm ?: ""}|${it.reps ?: ""}" }

    fun decode(s: String): Map<Long, Snap> = s.split(';').mapNotNull { part ->
        val p = part.split('|')
        val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Snap(id, p.getOrNull(1)?.toFloatOrNull(), p.getOrNull(2)?.toIntOrNull())
    }.associateBy { it.exerciseId }

    /** Что нужно плану по пиковому упражнению на соревнованиях. */
    data class Target(val kind: CompKind, val weightKg: Float, val e1rm: Float?, val reps: Int?, val bodyWeight: Float?)

    /**
     * План пиковой тренировки. null — данных для расчёта нет (тогда работает обычная подводка).
     * MAX: % от максимума полной нагрузки (подготовка 4×3 ~87%, снижение 3×2 ~92%, тонус 2×1 ~85%).
     * REPS: соревновательный вес, повторы — доля от прогноза (70% / 60% / 40%).
     */
    fun sessionPlan(t: Target, phase: PeakPhase, scale: LoadScale): SessionPlan? {
        val p = if (phase == PeakPhase.REST) PeakPhase.TONUS else phase
        return when (t.kind) {
            CompKind.MAX -> {
                val m = t.e1rm ?: return null
                val bw = t.bodyWeight ?: return null
                val (pct, sets, reps, rir) = when (p) {
                    PeakPhase.PRE_PEAK -> Quad(0.87f, 4, 3, 1)
                    PeakPhase.TAPER1 -> Quad(0.92f, 3, 2, 1)
                    PeakPhase.TONUS -> Quad(0.85f, 2, 1, 3)
                    else -> return null
                }
                val w = scale.snap(ProgressionEngine.roundWeight(pct * m - bw))
                SessionPlan(
                    weightKg = w, sets = sets, repsMin = reps, repsMax = reps, targetReps = reps,
                    rirMin = rir, rirMax = rir + 1, isDeload = false, isEmergencyDeload = false,
                    peakPhase = phase, isPeakTarget = true,
                )
            }
            CompKind.REPS, CompKind.REPS_BW -> {
                val r = t.reps ?: return null
                val (share, sets, rir) = when (p) {
                    PeakPhase.PRE_PEAK -> Triple(0.7f, 4, 2)
                    PeakPhase.TAPER1 -> Triple(0.6f, 3, 3)
                    PeakPhase.TONUS -> Triple(0.4f, 2, 4)
                    else -> return null
                }
                val reps = max(1, (r * share).roundToInt())
                SessionPlan(
                    // Соревновательный вес как есть (16, 24, 32… не кратны шагу 1,25)
                    weightKg = t.weightKg, sets = sets, repsMin = reps, repsMax = reps, targetReps = reps,
                    rirMin = rir, rirMax = rir + 1, isDeload = false, isEmergencyDeload = false,
                    peakPhase = phase, isPeakTarget = true,
                )
            }
        }
    }

    private data class Quad(val a: Float, val b: Int, val c: Int, val d: Int)
}
