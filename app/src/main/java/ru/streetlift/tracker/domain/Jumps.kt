package ru.streetlift.tracker.domain

/**
 * Прыжки на тумбу. Высота меняется только шагом 15 см (тумбы 15/30/45/60 ставятся друг на друга).
 *
 * Обычный режим (без веса): на высоте 4 × 3 → 4 × 4 → 4 × 5, затем +15 см, снова 4 × 3.
 *
 * Не взял новую высоту X — блок «пробивки» весом между двумя контрольными точками:
 * X−30 и X−15 (освоенная) с весами заходов по очереди: (X−30)+w1, (X−15)+w1, (X−30)+w2, (X−15)+w2 …,
 * затем X без веса. На каждом шаге 4 × 3 → 4 × 4 → 4 × 5.
 * Веса: 1-й блок 2,5 / 5 / 7,5; повторный на той же высоте 5 / 7,5 / 10 / 12,5; дальше 7,5 / 10 / 12,5 / 15.
 *
 * Не закрыл план — прогресс слишком быстрый: одна тренировка отката на то, что уже получалось
 * (та же высота с прошлым весом 4 × 5, либо на прыжок меньше), затем промежуточный вес
 * (посередине, кратно 1,25) 4 × 3 → 4 × 5, затем снова тот вес. Вторая неудача подряд — усталость:
 * следующая тренировка прыжков — разгрузка, после неё тот же план. Делить некуда — сразу разгрузка.
 *
 * Разгрузка: 3 × 3, на 15 см ниже, без веса.
 */
object Jumps {
    const val SETS = 4
    const val MIN_REPS = 3
    const val MAX_REPS = 5
    const val STEP_CM = 15
    const val MIN_CM = 15
    const val WEIGHT_STEP = 1.25f
    const val MAX_KG = 15f

    /** Веса заходов блока: 0 — первый блок на этой высоте, 1 — повторный, 2+ — дальше. */
    val BLOCK_WEIGHTS: List<List<Float>> = listOf(
        listOf(2.5f, 5f, 7.5f),
        listOf(5f, 7.5f, 10f, 12.5f),
        listOf(7.5f, 10f, 12.5f, 15f),
    )

    fun blockWeights(block: Int): List<Float> = BLOCK_WEIGHTS[block.coerceIn(0, BLOCK_WEIGHTS.lastIndex)]

    /** Шагов в блоке: по две высоты на каждый вес. */
    fun blockSteps(block: Int): Int = blockWeights(block).size * 2

    /** Округление высоты вниз до кратного 15, не ниже 15. */
    fun snapHeight(cm: Int): Int = ((cm / STEP_CM) * STEP_CM).coerceAtLeast(MIN_CM)

    data class State(
        /** Рабочая высота и вес текущего плана. */
        val heightCm: Int,
        val weightKg: Float = 0f,
        val reps: Int = MIN_REPS,
        /** Высота, которую пробиваем блоком (цель); null — обычный режим. */
        val plateauCm: Int? = null,
        /** Неудач подряд в текущей «пробивке» (0/1): вторая — усталость, разгрузка. */
        val fails: Int = 0,
        /** Номер блока на высоте [blockCm] (0 — первый). */
        val block: Int = 0,
        /** Высота, для которой был последний блок; повторное плато на ней — блок тяжелее. */
        val blockCm: Int? = null,
        /** Шаг внутри блока (0 — нижняя высота с первым весом). */
        val step: Int = 0,
        /** Текущая тренировка — откат на то, что уже получалось; после неё — [nextKg] × [nextReps]. */
        val rollback: Boolean = false,
        val nextKg: Float? = null,
        val nextReps: Int? = null,
        /** Идёт промежуточный вес; после 4 × 5 на нём — этот вес, 4 × 3. */
        val retryKg: Float? = null,
        /** Следующая тренировка прыжков — разгрузка (усталость). */
        val deloadNext: Boolean = false,
    )

    data class Log(val heightCm: Int, val weightKg: Float, val reps: Int)

    data class Result(val state: State, val message: String)

    /** План тренировки: подходы, прыжков в подходе, высота и вес. Разгрузка — ниже и без веса. */
    fun plan(s: State, deload: Boolean): Triple<Int, Int, State> =
        if (deload) Triple(3, MIN_REPS, s.copy(heightCm = (s.heightCm - STEP_CM).coerceAtLeast(MIN_CM), weightKg = 0f, reps = MIN_REPS))
        else Triple(SETS, s.reps, s)

    /** Высота и вес шага [step] блока [block] при цели [target]. */
    fun stepPlan(target: Int, block: Int, step: Int): Pair<Int, Float> {
        val w = blockWeights(block)[step / 2]
        val h = if (step % 2 == 0) target - 2 * STEP_CM else target - STEP_CM
        return h.coerceAtLeast(MIN_CM) to w
    }

    /** Начать блок пробивки высоты [target]. */
    private fun startBlock(s: State, target: Int): State {
        val block = if (s.blockCm == target) (s.block + 1).coerceAtMost(BLOCK_WEIGHTS.lastIndex) else 0
        val (h, w) = stepPlan(target, block, 0)
        return State(h, w, MIN_REPS, plateauCm = target, block = block, blockCm = target, step = 0)
    }

    /** Вес, который на этой высоте уже получался до текущего шага блока (0 — без веса). */
    private fun prevWeight(s: State): Float {
        if (s.plateauCm == null) return 0f
        val k = s.step / 2
        return if (k == 0) 0f else blockWeights(s.block)[k - 1]
    }

    /** Итог тренировки → следующее состояние. */
    fun next(s: State, logs: List<Log>, deload: Boolean): Result {
        if (logs.isEmpty()) return Result(s, "прыжков нет — план тот же")
        if (deload) return Result(s.copy(deloadNext = false), "разгрузка — дальше прежний план: ${describe(s)}")
        val done = logs.size >= SETS && logs.all {
            it.reps >= s.reps && it.heightCm >= s.heightCm && it.weightKg >= s.weightKg - 0.01f
        }
        if (!done) return onFail(s)
        val n = onSuccess(s)
        return Result(n, "закрыто → ${describe(n)}")
    }

    private fun onSuccess(s: State): State {
        val h = s.heightCm
        // Откат получился — дальше то, что запланировали после него
        if (s.rollback) {
            return s.copy(rollback = false, weightKg = s.nextKg ?: s.weightKg, reps = s.nextReps ?: MIN_REPS, nextKg = null, nextReps = null)
        }
        // Промежуточный вес: 4 × 3 → 4 × 5, потом тот вес, что не получился
        if (s.retryKg != null) {
            return if (s.reps < MAX_REPS) s.copy(reps = s.reps + 1)
            else s.copy(weightKg = s.retryKg, reps = MIN_REPS, retryKg = null)
        }
        // Обычный шаг закрыт — неудачи забыты
        val ok = s.copy(fails = 0)
        if (ok.reps < MAX_REPS) return ok.copy(reps = ok.reps + 1)
        val target = ok.plateauCm
        if (target == null) {
            // Без веса 4 × 5 — выше на 15 см; новая высота выше прошлого плато — блоки с нуля
            val nh = h + STEP_CM
            return State(nh, 0f, MIN_REPS, block = if (ok.blockCm != null && nh > ok.blockCm) 0 else ok.block,
                blockCm = if (ok.blockCm != null && nh > ok.blockCm) null else ok.blockCm)
        }
        val nextStep = ok.step + 1
        if (nextStep < blockSteps(ok.block)) {
            val (nh, nw) = stepPlan(target, ok.block, nextStep)
            return ok.copy(heightCm = nh, weightKg = nw, reps = MIN_REPS, step = nextStep)
        }
        // Блок пройден — пробуем цель без веса
        return State(target, 0f, MIN_REPS, block = ok.block, blockCm = target)
    }

    private fun onFail(s: State): Result {
        // Вторая неудача подряд — усталость: разгрузка, после неё тот же план
        if (s.fails >= 1) {
            return Result(s.copy(fails = 0, deloadNext = true), "второй раз не закрыто — усталость: следующая тренировка прыжков — разгрузка, потом ${describe(s)}")
        }
        // Новая высота без веса не взята — блок пробивки весом
        if (s.plateauCm == null && s.weightKg <= 0f && s.reps == MIN_REPS && !s.rollback && s.retryKg == null &&
            s.heightCm - STEP_CM >= MIN_CM
        ) {
            val n = startBlock(s, s.heightCm)
            return Result(n, "${s.heightCm} см не взяты — блок с весом: ${describe(n)}")
        }
        // Не хватило прыжков — откат на прыжок меньше, потом снова
        if (s.reps > MIN_REPS) {
            val n = s.copy(fails = 1, rollback = true, reps = s.reps - 1, nextKg = s.weightKg, nextReps = s.reps)
            return Result(n, "не закрыто — откат: ${describe(n)}, потом снова ${s.reps} прыжка")
        }
        // Новый вес не пошёл — откат на прошлый вес, потом промежуточный
        val failedKg = s.retryKg ?: s.weightKg
        val base = if (s.retryKg != null) s.weightKg else prevWeight(s)
        val mid = Math.round((base + failedKg) / 2f / WEIGHT_STEP) * WEIGHT_STEP
        if (failedKg > 0f && mid > base + 0.01f && mid < failedKg - 0.01f && !s.rollback) {
            val n = s.copy(
                fails = 1, rollback = true, weightKg = base, reps = MAX_REPS,
                nextKg = mid, nextReps = MIN_REPS, retryKg = failedKg,
            )
            return Result(n, "не закрыто — откат: ${describe(n)}, потом промежуточный ${Fmt.weight(mid)}")
        }
        // Делить некуда — разгрузка
        return Result(s.copy(fails = 0, deloadNext = true), "не закрыто — следующая тренировка прыжков — разгрузка, потом ${describe(s)}")
    }

    /** По калибровке: максимальная высота, где были прыжки (кратно 15), без веса. */
    fun fromCalibration(logs: List<Log>, current: State?): State {
        val h = logs.filter { it.reps > 0 }.maxOfOrNull { it.heightCm } ?: current?.heightCm ?: 60
        return State(snapHeight(h))
    }

    fun describe(s: State): String =
        "${s.heightCm} см" + (if (s.weightKg > 0f) " + ${Fmt.weight(s.weightKg)}" else "") + " · $SETS × ${s.reps}" +
            when {
                s.rollback -> " (откат)"
                s.retryKg != null -> " (промежуточный)"
                else -> ""
            } +
            (s.plateauCm?.let { " · блок ${s.block + 1}, шаг ${s.step + 1} из ${blockSteps(s.block)}, цель $it см" } ?: "") +
            (if (s.deloadNext) " · сначала разгрузка" else "")
}

/**
 * План прыжков на тренировку поверх общего плана [base] (разгрузка/подводка из него).
 * Высота не задана — калибровка: 3 подхода по 3 прыжка на максимальной высоте, без веса.
 */
fun jumpPlan(state: Jumps.State?, base: SessionPlan): SessionPlan {
    if (state == null || base.isCalibration) {
        return base.copy(
            weightKg = 0f, sets = 3, repsMin = Jumps.MIN_REPS, repsMax = Jumps.MIN_REPS, targetReps = Jumps.MIN_REPS,
            rirMin = 2, rirMax = 2, isCalibration = true, heightCm = state?.heightCm,
        )
    }
    val (sets, reps, s) = Jumps.plan(state, base.isDeload || state.deloadNext)
    return base.copy(
        weightKg = s.weightKg, sets = sets, repsMin = reps, repsMax = reps, targetReps = reps,
        rirMin = 2, rirMax = 2, heightCm = s.heightCm, isDeload = base.isDeload || state.deloadNext, isEmergencyDeload = false,
    )
}
