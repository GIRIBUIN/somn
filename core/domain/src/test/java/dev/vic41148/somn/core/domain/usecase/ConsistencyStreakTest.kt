package dev.vic41148.somn.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ConsistencyStreakTest {

    private val today = LocalDate.of(2026, 9, 19)

    private fun days(vararg offsets: Long): Set<LocalDate> =
        offsets.map { today.minusDays(it) }.toSet()

    @Test
    fun `empty history is zero everywhere`() {
        assertThat(computeConsistency(emptySet(), today))
            .isEqualTo(ConsistencyResult(0, 0, 0, 0))
    }

    @Test
    fun `unbroken run counts logged nights`() {
        val r = computeConsistency(days(0, 1, 2), today)
        assertThat(r.streak).isEqualTo(3)
        assertThat(r.currentRun).isEqualTo(3)
        assertThat(r.graceUsed).isEqualTo(0)
        assertThat(r.bankedNights).isEqualTo(0)
    }

    @Test
    fun `single missed night auto-consumes one grace night`() {
        // Logged 0,1,3,4: day 2 forgiven, run still counts the 4 logged nights.
        val r = computeConsistency(days(0, 1, 3, 4), today)
        assertThat(r.currentRun).isEqualTo(4)
        assertThat(r.graceUsed).isEqualTo(1)
        assertThat(r.streak).isEqualTo(4)
        assertThat(r.bankedNights).isEqualTo(0)
    }

    @Test
    fun `exhausted pool breaks the run and banks half the previous run`() {
        // 10-night run, 5-day gap (pool is 3), 2-night run.
        val logged = (0L..1L).map { today.minusDays(it) } +
            (7L..16L).map { today.minusDays(it) }
        val r = computeConsistency(logged.toSet(), today)
        assertThat(r.currentRun).isEqualTo(2)
        assertThat(r.graceUsed).isEqualTo(3)
        assertThat(r.bankedNights).isEqualTo(5)
        assertThat(r.streak).isEqualTo(7)
    }

    @Test
    fun `odd previous run halves down`() {
        val logged = setOf(today) + (5L..9L).map { today.minusDays(it) }
        val r = computeConsistency(logged, today)
        assertThat(r.currentRun).isEqualTo(1)
        assertThat(r.bankedNights).isEqualTo(2)
        assertThat(r.streak).isEqualTo(3)
    }

    @Test
    fun `unlogged today is tonight not happened, not a miss`() {
        val r = computeConsistency(days(1, 2, 3), today)
        assertThat(r.streak).isEqualTo(3)
        assertThat(r.graceUsed).isEqualTo(0)
    }

    @Test
    fun `frozen days skip without costing grace or breaking`() {
        // Logged 0,1,3,4 with day 2 frozen: no grace spent, run of 4.
        val r = computeConsistency(days(0, 1, 3, 4), today, frozenDays = setOf(today.minusDays(2)))
        assertThat(r.currentRun).isEqualTo(4)
        assertThat(r.graceUsed).isEqualTo(0)
        assertThat(r.streak).isEqualTo(4)
    }

    @Test
    fun `spent pool breaks on first fresh gap with no bank`() {
        val spent = setOf(today.minusDays(10), today.minusDays(11), today.minusDays(12))
        val r = computeConsistency(days(0, 2), today, spentDates = spent)
        assertThat(r.currentRun).isEqualTo(1)
        assertThat(r.graceUsed).isEqualTo(0)
        assertThat(r.newlySpent).isEmpty()
        assertThat(r.streak).isEqualTo(1)
    }

    @Test
    fun `forgiven dates come back as newly spent for the ledger`() {
        val r = computeConsistency(days(0, 1, 3, 4), today)
        assertThat(r.newlySpent).containsExactly(today.minusDays(2))
    }

    @Test
    fun `already spent gap is forgiven free without touching the pool`() {
        val spent = setOf(today.minusDays(2))
        // Day 2 missing but already paid: the fresh gaps 5 and 6 still get the full pool.
        val logged = days(0, 1, 3, 4, 7, 8, 9, 10)
        val r = computeConsistency(logged, today, spentDates = spent)
        assertThat(r.currentRun).isEqualTo(8)
        assertThat(r.graceUsed).isEqualTo(3)
        assertThat(r.newlySpent).containsExactly(today.minusDays(5), today.minusDays(6))
        assertThat(r.streak).isEqualTo(8)
    }

    @Test
    fun `monthly cap breaks the fourth scattered miss`() {
        // Three older misses already spent this month: one fresh gap breaks.
        val spent = setOf(today.minusDays(10), today.minusDays(13), today.minusDays(16))
        val logged = days(0, 1, 3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 17, 18) + spent
        val r = computeConsistency(logged, today, spentDates = spent)
        assertThat(r.currentRun).isEqualTo(2)
        assertThat(r.newlySpent).isEmpty()
    }

    @Test
    fun `month rollover refills the pool`() {
        assertThat(
            spentDatesForMonth("2026-08", "2026-08-05", YearMonth.of(2026, 9))
        ).isEmpty()
    }

    @Test
    fun `same month restores spent dates and drops corrupt entries`() {
        assertThat(
            spentDatesForMonth("2026-09", "2026-09-05,2026-09-12", YearMonth.of(2026, 9))
        ).containsExactly(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 12))
        assertThat(
            spentDatesForMonth("2026-09", "nope,2026-09-01", YearMonth.of(2026, 9))
        ).containsExactly(LocalDate.of(2026, 9, 1))
        assertThat(spentDatesToCsv(setOf(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 5))))
            .isEqualTo("2026-09-05,2026-09-12")
    }

    @Test
    fun `weekly ring counts the last seven days oldest to newest`() {
        val w = weeklyConsistency(days(0, 1, 3, 6, 9), today)
        assertThat(w.nightsLogged).isEqualTo(4)
        assertThat(w.daysLogged).hasSize(7)
        // Oldest (-6) logged, -5 and -4 missed, -3 logged, -2 missed, -1 and 0 logged.
        assertThat(w.daysLogged).containsExactly(true, false, false, true, false, true, true)
    }

    @Test
    fun `milestones reward showing up at effort thresholds`() {
        assertThat(earnedMilestones(0)).isEmpty()
        assertThat(earnedMilestones(1).map { it.id }).containsExactly("first-night")
        assertThat(earnedMilestones(7).map { it.id })
            .containsExactly("first-night", "seven-nights")
        assertThat(earnedMilestones(365)).hasSize(CONSISTENCY_MILESTONES.size)
    }
}
