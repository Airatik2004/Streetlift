package ru.streetlift.tracker.domain

import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SkipEntity

/** Предыдущий цикл для вкладки «Цикл»: что было по каждому дню (тренировка или пропуск). */
object PastCycle {

    /** Событие дня цикла: сделанная тренировка или записанный пропуск. */
    data class Event(val dayId: Long, val at: Long, val round: Int, val session: SessionEntity? = null, val skip: SkipEntity? = null) {
        /** JSON-снимок плана (RollbackData) или null, если не сохранился. */
        val plan: String? get() = session?.rollback ?: skip?.plan
        val cycleInMeso: Int? get() = session?.shownCycle ?: skip?.cycleInMeso
        val priority: String? get() = session?.priority ?: skip?.priority
        val isDeload: Boolean get() = session?.isDeload ?: skip?.isDeload ?: false
        val altSlot: Int get() = session?.effectiveAltSlot ?: skip?.altSlot ?: 0
    }

    data class Rounds(val previous: List<Event>, val current: List<Event>)

    fun events(finished: List<SessionEntity>, skips: List<SkipEntity>, dayIds: Set<Long>): List<Event> =
        (
            finished.mapNotNull { s -> s.dayId?.takeIf { it in dayIds }?.let { Event(it, s.startedAt, s.roundNumber, session = s) } } +
                skips.mapNotNull { k -> k.dayId?.takeIf { it in dayIds }?.let { Event(it, k.skippedAt, k.roundNumber, skip = k) } }
            ).sortedBy { it.at }

    /**
     * События текущего цикла [round] и предыдущего. Берётся только «хвост» истории подряд:
     * до сброса программы номера циклов другие, туда не заходим.
     */
    fun rounds(events: List<Event>, round: Int): Rounds {
        val tail = events.sortedByDescending { it.at }
        val current = tail.takeWhile { it.round == round }
        val previous = tail.drop(current.size).takeWhile { it.round == round - 1 }
        return Rounds(previous.reversed(), current.reversed())
    }

    /**
     * Для дня без записи в прошлом цикле (пропуск до v30 или день, перескоченный через «Другой день»):
     * план не менялся до следующего выполнения этого дня — берём его снимок; null — следующего ещё не было.
     */
    fun nextPlanFor(dayId: Long, current: List<Event>): String? =
        current.firstOrNull { it.dayId == dayId && it.plan != null }?.plan
}
