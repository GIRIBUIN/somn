package dev.vic41148.somn.core.domain.usecase

import java.time.LocalDateTime

/**
 * Adaptive wind-down reminder timing (spec §6.6): the nudge fires at the user's
 * own revealed bedtime minus a lead, never at a fixed hour. Bedtimes are
 * minutes-since-midnight of main-sleep session starts. Pure functions, the
 * scheduler owns persistence and delivery.
 */

/** How far before the inferred bedtime the nudge fires. */
const val WIND_DOWN_LEAD_MINUTES = 45

/** Fallback fire time when fewer than [WIND_DOWN_MIN_NIGHTS] reveal a window. */
const val WIND_DOWN_FALLBACK_MINUTES = 21 * 60 + 30

/** Minimum logged nights before the app claims to know the bedtime. */
const val WIND_DOWN_MIN_NIGHTS = 3

/**
 * Circular-median bedtime, null when the window is unrevealed. Bedtimes cluster
 * around midnight (23:30 beside 00:30), so a naive linear median lands at noon;
 * when the spread straddles midnight the early side shifts past 24h first.
 */
fun medianBedtimeMinutes(bedtimesMinutes: List<Int>): Int? {
    if (bedtimesMinutes.size < WIND_DOWN_MIN_NIGHTS) return null
    val norm = bedtimesMinutes.map { ((it % 1440) + 1440) % 1440 }.sorted()
    val shifted =
        if (norm.last() - norm.first() > 720) norm.map { if (it < 720) it + 1440 else it }.sorted()
        else norm
    val n = shifted.size
    val median = if (n % 2 == 1) shifted[n / 2]
    else (shifted[n / 2 - 1] + shifted[n / 2] + 1) / 2
    return median % 1440
}

/** Next fire: today at (bedtime − lead) if still ahead, else tomorrow. */
fun nextWindDownFire(
    now: LocalDateTime,
    bedtimeMinutes: Int?,
    leadMinutes: Int = WIND_DOWN_LEAD_MINUTES
): LocalDateTime {
    val target = ((bedtimeMinutes ?: WIND_DOWN_FALLBACK_MINUTES) - leadMinutes + 1440) % 1440
    val today = now.toLocalDate().atTime(target / 60, target % 60)
    return if (today.isAfter(now)) today else today.plusDays(1)
}
