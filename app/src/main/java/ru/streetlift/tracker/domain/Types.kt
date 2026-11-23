package ru.streetlift.tracker.domain

/** Тип упражнения — влияет на рекомендации таймера отдыха. */
enum class ExerciseType(val title: String) {
    EXPLOSIVE("Взрывная сила"),
    HEAVY_LEVER("Тяжёлая сила / рычаг"),
    HYPERTROPHY_PUMP("Многоповторка / закачка"),
    ACCESSORY("Подсобка / ноги"),
}

/** Ручное управление плановой разгрузкой на текущий круг. */
enum class DeloadOverride { AUTO, FORCE_ON, FORCE_OFF }

/** Статус упражнения: обычная прогрессия или подбор стартовых параметров на 1 круг. */
enum class ExerciseMode { ACTIVE, CALIBRATION }

/** Справочники стадий: у каждой группы упражнений свой упорядоченный список. */
object StageGroups {
    data class Stage(val name: String, val description: String)

    /** Горизонт, передний вис. */
    const val LEVER = "LEVER"
    /** Отжимания в стойке на руках. */
    const val HANDSTAND = "HANDSTAND"

    val lists: Map<String, List<Stage>> = mapOf(
        LEVER to listOf(
            Stage("Закрытый", "Колени прижаты к груди"),
            Stage("Полузакрытый", "Угол корпус–бедро 90° (колени на уровне таза)"),
            Stage("Полуоткрытый", "Угол корпус–бедро 180° (бедро в линию с корпусом), колени согнуты под 90° (голень ⊥ бедру)"),
            Stage("Ноги врозь (широкие, >60°)", "Прямые ноги максимально широко разведены"),
            Stage("Ноги врозь (узкие, ~30°)", "Прямые ноги разведены под острым углом"),
            Stage("Фулл", "Прямые ноги вместе, тело в одной линии"),
        ),
        HANDSTAND to listOf(
            Stage("У стены", ""),
            Stage("Свободная (ноги врозь)", ""),
            Stage("Свободная (ноги вместе)", ""),
        ),
    )

    fun defaultFor(group: String): String? = when (group) {
        LEVER -> "Полуоткрытый"
        HANDSTAND -> "У стены"
        else -> lists[group]?.firstOrNull()?.name
    }

    /** Финальная стадия группы (Фулл / Свободная ноги вместе). */
    fun finalOf(group: String): String? = lists[group]?.lastOrNull()?.name

    /** Группа по названию упражнения — для старых данных и импорта. */
    fun groupForName(name: String): String? {
        val n = name.lowercase()
        return when {
            "горизонт" in n -> LEVER
            "передн" in n && "вис" in n -> LEVER
            "стойке" in n -> HANDSTAND
            else -> null
        }
    }
}

/** Совместимость со старым кодом: шкала рычага. */
object LeverageStages {
    const val DEFAULT = "Полуоткрытый"
    val builtIn: List<StageGroups.Stage> get() = StageGroups.lists.getValue(StageGroups.LEVER)
}

/** Фазы подводки к старту (по числу дней до старта). */
enum class PeakPhase(val title: String) {
    NONE(""),
    PRE_PEAK("Предпик"),
    TAPER1("Тейпер 1"),
    TONUS("Тейпер 2 — тонус"),
    REST("Полный отдых перед стартом"),
}
