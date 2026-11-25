package ru.streetlift.tracker.timer

/**
 * Результат удержания: если «Стоп» нажат после сигнала цели [cap], пишется цель —
 * время, пока подходишь к телефону, не засчитывается. Сорвался раньше — как есть.
 */
fun holdResult(elapsedSec: Int, cap: Int?): Int = if (cap != null && cap > 0 && elapsedSec >= cap) cap else elapsedSec
