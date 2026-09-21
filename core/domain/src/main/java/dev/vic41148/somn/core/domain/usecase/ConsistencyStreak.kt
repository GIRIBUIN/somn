package dev.vic41148.somn.core.domain.usecase

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Consistency Streak (spec §6): counts "did you log a night", never "was the score good".
 * A streak is the only gamified number Somn shows, so it is deliberately forgiving:
 * missed nights auto-consume Grace Nights, an exhausted pool steps the count down by
 * halving instead of zeroing, and Rest Mode days are skipped entirely. Pure functions.
 *
 * The grace ledger records *which dates* were forgiven, never a count: a spent count
 * derived from the same computation it feeds back into oscillates forever (used=2
 * shrinks the pool, the recompute forgives 1, the pool grows, ...). Dates are
 * monotonic, the write-back converges after a single union and the number sits still.
 */

/** Grace Nights refill on this schedule: no purchase, no currency, no ads to watch. */
const val GRACE_NIGHTS_PER_MONTH = 3

private val yearMonthKey = DateTimeFormatter.ofPattern("yyyy-MM")

fun YearMonth.toGraceMonthKey(): String = format(yearMonthKey)

data class ConsistencyResult(
    /** The displayed number: current run plus any banked step-down credit. */
    val streak: Int,
    /** Consecutive logged nights back from today, grace-forgiven gaps excluded from the count. */
    val currentRun: Int,
    /** Gap dates forgiven inside the current run (display: "1 grace night used"). */
    val graceUsed: Int,
    /** floor(previous run / 2) kept after an unforgiven break, 0 when the run is unbroken. */
    val bankedNights: Int,
    /** Forgiven gap dates not already in [spentDates]: the only thing written back. */
    val newlySpent: Set<LocalDate> = emptySet()
)

/**
 * Walks back from [today]: logged days extend the run, missing days consume grace,
 * frozen (Rest Mode) days are skipped without costing grace. A gap already in
 * [spentDates] is forgiven free (paid before); a fresh gap is forgiven while the
 * month's distinct spend stays within [GRACE_NIGHTS_PER_MONTH]. The first unforgiven
 * gap ends the run; the run before the whole gap contributes half, rounded down.
 * An unlogged [today] means "tonight hasn't happened yet", never a miss.
 */
fun computeConsistency(
    loggedDays: Set<LocalDate>,
    today: LocalDate,
    spentDates: Set<LocalDate> = emptySet(),
    frozenDays: Set<LocalDate> = emptySet()
): ConsistencyResult {
    if (loggedDays.isEmpty()) return ConsistencyResult(0, 0, 0, 0)
    val oldest = loggedDays.minOrNull()!!
    val spend = spentDates.toMutableSet()
    val forgiven = mutableSetOf<LocalDate>()
    var run = 0
    var cursor = today
    if (cursor !in loggedDays && cursor !in frozenDays) cursor = cursor.minusDays(1)
    while (!cursor.isBefore(oldest)) {
        when {
            cursor in frozenDays -> cursor = cursor.minusDays(1)
            cursor in loggedDays -> {
                run++
                cursor = cursor.minusDays(1)
            }
            cursor in spend || (spend + cursor).size <= GRACE_NIGHTS_PER_MONTH -> {
                spend += cursor
                forgiven += cursor
                cursor = cursor.minusDays(1)
            }
            else -> break
        }
    }
    // Step-down credit: the run before the whole breaking gap, halved. Gaps older
    // than that stop mattering, a rough patch costs half the banked run, never all.
    var prevRun = 0
    var scan = cursor.minusDays(1)
    while (!scan.isBefore(oldest) && scan !in loggedDays && scan !in frozenDays) {
        scan = scan.minusDays(1)
    }
    while (!scan.isBefore(oldest) && (scan in loggedDays || scan in frozenDays)) {
        if (scan in loggedDays) prevRun++
        scan = scan.minusDays(1)
    }
    val banked = prevRun / 2
    return ConsistencyResult(run + banked, run, forgiven.size, banked, forgiven - spentDates)
}

/**
 * This month's spent dates: a new calendar month refills, otherwise the stored
 * CSV. Corrupt entries are dropped, never fatal.
 */
fun spentDatesForMonth(
    storedMonthKey: String?,
    storedCsv: String?,
    now: YearMonth = YearMonth.now()
): Set<LocalDate> {
    if (storedMonthKey != now.toGraceMonthKey()) return emptySet()
    if (storedCsv.isNullOrBlank()) return emptySet()
    return storedCsv.split(",").mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }.toSet()
}

/** Deterministic ledger encoding, oldest first, for the DataStore CSV. */
fun spentDatesToCsv(dates: Set<LocalDate>): String =
    dates.sorted().joinToString(",") { it.toString() }

/** Rolling 7-day ring data (spec §6.4): oldest → newest ending today, showing up only. */
data class WeeklyConsistency(
    val nightsLogged: Int,
    val daysLogged: List<Boolean>
)

fun weeklyConsistency(
    loggedDays: Set<LocalDate>,
    today: LocalDate = LocalDate.now()
): WeeklyConsistency {
    val flags = (6 downTo 0).map { today.minusDays(it.toLong()) in loggedDays }
    return WeeklyConsistency(flags.count { it }, flags)
}

/** Effort milestones (spec §6.5): nights LOGGED thresholds, never score-gated. Derived, no storage. */
data class ConsistencyMilestone(val id: String, val nightsRequired: Int, val label: String)

val CONSISTENCY_MILESTONES = listOf(
    ConsistencyMilestone("first-night", 1, "First night logged"),
    ConsistencyMilestone("seven-nights", 7, "7 nights logged"),
    ConsistencyMilestone("thirty-nights", 30, "30 nights logged"),
    ConsistencyMilestone("hundred-nights", 100, "100 nights logged"),
    ConsistencyMilestone("year-nights", 365, "365 nights logged")
)

fun earnedMilestones(totalNightsLogged: Int): List<ConsistencyMilestone> =
    CONSISTENCY_MILESTONES.filter { totalNightsLogged >= it.nightsRequired }
