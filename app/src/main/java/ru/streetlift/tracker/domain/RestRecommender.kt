package ru.streetlift.tracker.domain

data class RestRecommendation(val seconds: Int, val reason: String)

/** Рекомендации времени отдыха по типу упражнения и режиму подходов. */
object RestRecommender {
    const val DELOAD_CUT_SECONDS = 30

    fun recommend(type: ExerciseType, alternating: Boolean, deload: Boolean): RestRecommendation {
        val (base, reason) = when (type) {
            ExerciseType.HEAVY_LEVER, ExerciseType.EXPLOSIVE ->
                if (alternating) {
                    150 to "Чередование тяжёлых подходов: 2:30 + подход второго упражнения ≈ 5 мин отдыха для каждой группы мышц"
                } else if (type == ExerciseType.HEAVY_LEVER) {
                    240 to "Прямые подходы тяжёлой силы: 3:30–4:00 на восстановление"
                } else {
                    210 to "Прямые взрывные подходы: 3:30–4:00, чтобы сохранить мощность"
                }
            ExerciseType.HYPERTROPHY_PUMP ->
                120 to "Многоповторка: 2:00 — чтобы повторы не проседали ниже коридора к последним подходам"
            ExerciseType.ACCESSORY ->
                150 to "Подсобка (ноги): 2:30 — крупные мышцы и суставы восстанавливаются дольше"
            ExerciseType.ISOLATION ->
                90 to "Изоляция: 1:30 — мелкая мышца восстанавливается быстро"
            ExerciseType.STATIC_HOLD ->
                180 to "Статика: 3:00 — удержание на пределе требует полного восстановления"
        }
        return if (deload) {
            RestRecommendation((base - DELOAD_CUT_SECONDS).coerceAtLeast(30), "$reason · разгрузка: −0:30")
        } else {
            RestRecommendation(base, reason)
        }
    }
}
