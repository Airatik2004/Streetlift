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
                90 to "Многоповторка / закачка: 1:30 — короткий отдых держит памп"
            ExerciseType.ACCESSORY ->
                120 to "Подсобка: 2:00"
        }
        return if (deload) {
            RestRecommendation((base - DELOAD_CUT_SECONDS).coerceAtLeast(30), "$reason · разгрузка: −0:30")
        } else {
            RestRecommendation(base, reason)
        }
    }
}
