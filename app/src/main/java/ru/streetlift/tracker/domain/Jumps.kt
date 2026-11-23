package ru.streetlift.tracker.domain

/**
 * Прыжки на тумбу: растёт высота. На высоте — 4 × 3 → 4 × 4 → 4 × 5; затем та же высота
 * +2,5 кг 4 × 4 → +5 кг 4 × 4; затем +5 см без веса. Две неудачи подряд — плато:
 * +5 кг и на 10 см ниже, с весом +5 см за удачу до высоты плато, там вес снимается.
 */
object Jumps {
    const val SETS = 4
    const val MIN_REPS = 3
    const val MAX_REPS = 5
    const val STEP_CM = 5
    const val LIGHT_KG = 2.5f
    const val HEAVY_KG = 5f
    const val DROP_CM = 10
    const val MIN_CM = 20
    /** Прыжков в подходе на ступенях с весом. */
    const val WEIGHTED_REPS = 4

    data class State(
        val heightCm: Int,
        val weightKg: Float = 0f,
        val reps: Int = MIN_REPS,
        /** Высота плато, которую пробиваем с +5 кг; null — обычный режим. */
        val plateauCm: Int? = null,
        /** Неудач подряд. */
        val fails: Int = 0,
    )

    data class Log(val heightCm: Int, val weightKg: Float, val reps: Int)

    data class Result(val state: State, val message: String)

    /** План тренировки: подходы, прыжков в подходе, высота и вес. Разгрузка — ниже и легче. */
    fun plan(s: State, deload: Boolean): Triple<Int, Int, State> =
        if (deload) Triple(3, MIN_REPS, s.copy(heightCm = (s.heightCm - DROP_CM).coerceAtLeast(MIN_CM), weightKg = 0f, reps = MIN_REPS))
        else Triple(SETS, s.reps, s)

    /** Итог тренировки → следующее состояние. */
    fun next(s: State, logs: List<Log>, deload: Boolean): Result {
        if (logs.isEmpty()) return Result(s, "прыжков нет — план тот же")
        if (deload) return Result(s, "разгрузка — план без изменений")
        val done = logs.size >= SETS && logs.all { it.reps >= s.reps && it.heightCm >= s.heightCm && it.weightKg >= s.weightKg - 0.01f }
        val h = s.heightCm
        if (done) {
            val n = when {
                // Пробиваем плато с весом: +5 см до высоты плато, там вес снимается
                s.plateauCm != null ->
                    if (h + STEP_CM >= s.plateauCm) State(s.plateauCm, 0f, MIN_REPS)
                    else s.copy(heightCm = h + STEP_CM, fails = 0)
                // Ступени с весом на той же высоте: +2,5 кг 4 × 4 → +5 кг 4 × 4 → +5 см без веса
                s.weightKg >= HEAVY_KG - 0.01f -> State(h + STEP_CM, 0f, MIN_REPS)
                s.weightKg > 0f -> s.copy(weightKg = HEAVY_KG, reps = WEIGHTED_REPS, fails = 0)
                s.reps < MAX_REPS -> s.copy(reps = s.reps + 1, fails = 0)
                else -> s.copy(weightKg = LIGHT_KG, reps = WEIGHTED_REPS, fails = 0)
            }
            return Result(n, "закрыто → ${describe(n)}")
        }
        if (s.fails + 1 < 2) return Result(s.copy(fails = s.fails + 1), "не закрыто — повтор того же плана")
        // Две неудачи подряд
        val n = if (s.plateauCm != null) {
            // С весом тоже встали — вес снимается, на 5 см ниже
            State((h - STEP_CM).coerceAtLeast(MIN_CM), 0f, MIN_REPS)
        } else {
            State((h - DROP_CM).coerceAtLeast(MIN_CM), HEAVY_KG, MIN_REPS, plateauCm = h)
        }
        return Result(n, "плато на $h см → ${describe(n)}")
    }

    /** По калибровке: высота последнего подхода, где были прыжки. */
    fun fromCalibration(logs: List<Log>, current: State?): State {
        val h = logs.lastOrNull { it.reps > 0 }?.heightCm ?: current?.heightCm ?: 50
        return State(h, 0f, MIN_REPS)
    }

    fun describe(s: State): String =
        "${s.heightCm} см" + (if (s.weightKg > 0f) " · ${Fmt.weight(s.weightKg)}" else "") + " · $SETS × ${s.reps}" +
            (s.plateauCm?.let { " (пробиваем $it см)" } ?: "")
}

/**
 * План прыжков на тренировку поверх общего плана [base] (разгрузка/подводка из него).
 * Высота не задана — калибровка: 3 подхода по 3 прыжка, высоту выбираете сами.
 */
fun jumpPlan(state: Jumps.State?, base: SessionPlan): SessionPlan {
    if (state == null || base.isCalibration) {
        return base.copy(
            weightKg = 0f, sets = 3, repsMin = Jumps.MIN_REPS, repsMax = Jumps.MIN_REPS, targetReps = Jumps.MIN_REPS,
            rirMin = 2, rirMax = 2, isCalibration = true, heightCm = state?.heightCm,
        )
    }
    val (sets, reps, s) = Jumps.plan(state, base.isDeload)
    return base.copy(
        weightKg = s.weightKg, sets = sets, repsMin = reps, repsMax = reps, targetReps = reps,
        rirMin = 2, rirMax = 2, heightCm = s.heightCm, isEmergencyDeload = false,
    )
}
