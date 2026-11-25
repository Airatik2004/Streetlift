package ru.streetlift.tracker.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Выход на пик в пауэрлифтинге (вкладка «Пауэр»): одно движение или сумма.
 *
 * Движения живут только на время подготовки — в программе их нет, записи в истории под своими именами.
 * Максимум считается по Эпли от веса на штанге (свой вес не входит), первая тренировка каждого
 * движения — калибровка по ориентиру пользователя («жим 80 × 5»).
 *
 * Частота — сколько можно переварить:
 * - одно движение — каждую тренировку, тяжёлая/лёгкая по очереди (тяга — тяжёлая раз в три);
 * - троеборье — жим каждую тренировку, присед и тяга через раз;
 * - жим + тяга — жим каждую тренировку, тяга через раз.
 * В сумме цель — максимум суммы: каждое движение в своей частоте, без отдельной «специализации».
 */
object Power {

    enum class Lift(val id: Long, val title: String, val short: String, val step: Float, val bar: Float) {
        SQUAT(-101, "Присед со штангой", "присед", 2.5f, 20f),
        BENCH(-102, "Жим лёжа", "жим", 2.5f, 20f),
        DEADLIFT(-103, "Становая тяга", "тяга", 2.5f, 20f),
        CURL(-104, "Строгий подъём на бицепс", "бицепс", 1.25f, 10f);

        companion object {
            fun byId(id: Long): Lift? = entries.firstOrNull { it.id == id }
        }
    }

    enum class Kind(val title: String, val lifts: List<Lift>) {
        BENCH("Жим лёжа", listOf(Lift.BENCH)),
        SQUAT("Присед", listOf(Lift.SQUAT)),
        DEADLIFT("Становая тяга", listOf(Lift.DEADLIFT)),
        CURL("Строгий подъём на бицепс", listOf(Lift.CURL)),
        TOTAL("Троеборье", listOf(Lift.SQUAT, Lift.BENCH, Lift.DEADLIFT)),
        PUSH_PULL("Двоеборье: жим + тяга", listOf(Lift.BENCH, Lift.DEADLIFT));

        val isTotal: Boolean get() = lifts.size > 1

        /** Нормативы WRPF (с допинг-контролем); для приседа и сумм таблиц не нашли — только прогноз. */
        val table: Standards.Table?
            get() = when (this) {
                BENCH -> Standards.WRPF_BENCH
                DEADLIFT -> Standards.WRPF_DEADLIFT
                CURL -> Standards.WRPF_CURL
                else -> null
            }

        companion object {
            fun byId(id: String?): Kind? = entries.firstOrNull { it.name == id }
        }
    }

    /** Ориентир: вес × повторы (до отказа или около того). */
    data class Ref(val weightKg: Float, val reps: Int) {
        val e1rm: Float get() = Competition.e1rm(0f, Competition.LiftSet(weightKg, reps, 0))
    }

    fun encodeRefs(refs: Map<Lift, Ref>): String =
        refs.entries.joinToString(";") { (l, r) -> "${l.name}:${r.weightKg}x${r.reps}" }

    fun decodeRefs(s: String): Map<Lift, Ref> = s.split(';').mapNotNull { part ->
        val (name, v) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val lift = Lift.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        val (w, r) = v.split('x').takeIf { it.size == 2 } ?: return@mapNotNull null
        val ref = Ref(w.toFloatOrNull() ?: return@mapNotNull null, r.toIntOrNull() ?: return@mapNotNull null)
        lift to ref
    }.toMap()

    /** «80 × 5», «80x5», «80 5», «82,5*3» → ориентир; null — не разобрать. */
    fun parseRef(text: String): Ref? {
        val p = text.trim().replace(',', '.').split(Regex("[xх×*\\s]+", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        if (p.size != 2) return null
        val w = p[0].toFloatOrNull()?.takeIf { it > 0f && it < 1000f } ?: return null
        val r = p[1].toIntOrNull()?.takeIf { it in 1..30 } ?: return null
        return Ref(w, r)
    }

    /**
     * Движения тренировки номер [n] (0 — первая тренировка подготовки), в соревновательном порядке.
     */
    fun liftsFor(kind: Kind, n: Int): List<Lift> = when (kind) {
        Kind.TOTAL -> listOf(if (n % 2 == 0) Lift.SQUAT else Lift.DEADLIFT, Lift.BENCH)
        Kind.PUSH_PULL -> if (n % 2 == 0) listOf(Lift.BENCH, Lift.DEADLIFT) else listOf(Lift.BENCH)
        else -> kind.lifts
    }.sortedBy { it.ordinal }

    /**
     * Тяжёлая ли тренировка движения: [k] — сколько тренировок с ним уже было (0 — калибровка).
     * После калибровки — лёгкая, потом по очереди. Тяга каждую тренировку (одно движение) — тяжёлая раз в три.
     */
    fun isHeavy(kind: Kind, lift: Lift, k: Int): Boolean =
        if (kind == Kind.DEADLIFT && lift == Lift.DEADLIFT) k % 3 == 0 else k % 2 == 0

    /** Вес кратно шагу (к ближайшему), не меньше пустого грифа. */
    fun round(lift: Lift, w: Float): Float =
        ProgressionEngine.roundWeight(max(lift.bar, (w / lift.step).roundToInt() * lift.step))

    /**
     * План движения на тренировку. [e1rm] — текущий максимум (null — калибровка по [ref]).
     * Тяжёлая: подготовка 4×3 ~87%, снижение 3×2 ~92%, тонус 2×1 ~85%. Лёгкая: 3×3 ~70% (в снижении 2×3).
     */
    fun plan(kind: Kind, lift: Lift, k: Int, e1rm: Float?, ref: Ref?, phase: PeakPhase): SessionPlan? {
        if (phase == PeakPhase.NONE) return null
        if (e1rm == null) {
            val r = ref ?: return null
            return SessionPlan(
                weightKg = round(lift, r.weightKg), sets = 1, repsMin = r.reps, repsMax = r.reps, targetReps = r.reps,
                rirMin = 0, rirMax = 1, isDeload = false, isEmergencyDeload = false, isCalibration = true,
                peakPhase = phase, isPeakTarget = true,
            )
        }
        val p = if (phase == PeakPhase.REST) PeakPhase.TONUS else phase
        val heavy = isHeavy(kind, lift, k)
        data class S(val pct: Float, val sets: Int, val reps: Int, val rir: Int)
        val s = when {
            p == PeakPhase.TONUS -> S(0.85f, 2, 1, 3)
            heavy && p == PeakPhase.PRE_PEAK -> S(0.87f, 4, 3, 1)
            heavy -> S(0.92f, 3, 2, 1)
            p == PeakPhase.PRE_PEAK -> S(0.70f, 3, 3, 3)
            else -> S(0.70f, 2, 3, 3)
        }
        return SessionPlan(
            weightKg = round(lift, s.pct * e1rm), sets = s.sets, repsMin = s.reps, repsMax = s.reps, targetReps = s.reps,
            rirMin = s.rir, rirMax = s.rir + 1, isDeload = false, isEmergencyDeload = false,
            peakPhase = phase, isPeakTarget = true,
        )
    }

    /** Подпись дня движения: «калибровка», «тяжёлая», «лёгкая», «тонус». */
    fun label(kind: Kind, lift: Lift, k: Int, calibrated: Boolean, phase: PeakPhase): String = when {
        !calibrated -> "калибровка"
        phase == PeakPhase.TONUS || phase == PeakPhase.REST -> "тонус"
        isHeavy(kind, lift, k) -> "тяжёлая"
        else -> "лёгкая"
    }

    /** Разминка со штангой: ~50% × 5, ~70% × 3, ~85% × 1 (в разгрузке — первые 2). Строго легче рабочего. */
    fun warmup(lift: Lift, plan: SessionPlan): List<Warmup.WSet> {
        val w = plan.weightKg
        // Строго легче рабочего (если некуда — не меньше грифа); одинаковые веса не убираем
        fun under(f: Float): Float {
            val v = round(lift, w * f)
            return if (v >= w - 0.001f) max(lift.bar, ProgressionEngine.roundWeight(w - lift.step)) else v
        }
        val list = listOf(Warmup.WSet(under(0.5f), 5), Warmup.WSet(under(0.7f), 3), Warmup.WSet(under(0.85f), 1))
        return Warmup.limit(list, plan.isDeload)
    }

    /** Максимум на 1 раз по оценке (кратно шагу вниз). */
    fun oneRm(lift: Lift, e1rm: Float): Float = ProgressionEngine.roundWeight(floor(e1rm / lift.step) * lift.step)
}
