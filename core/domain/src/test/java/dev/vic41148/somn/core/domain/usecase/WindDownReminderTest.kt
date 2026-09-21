package dev.vic41148.somn.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

class WindDownReminderTest {

    @Test
    fun `fewer than three nights reveals nothing`() {
        assertThat(medianBedtimeMinutes(listOf(1380, 1390))).isNull()
        assertThat(medianBedtimeMinutes(emptyList())).isNull()
    }

    @Test
    fun `odd sample medians normally`() {
        // 22:30, 23:00, 23:30 → 23:00.
        assertThat(medianBedtimeMinutes(listOf(1350, 1380, 1410))).isEqualTo(1380)
    }

    @Test
    fun `midnight straddle medians around midnight, not noon`() {
        // 23:30, 00:00, 00:30 → 00:00, never ~12:00.
        assertThat(medianBedtimeMinutes(listOf(1410, 0, 30))).isEqualTo(0)
    }

    @Test
    fun `even sample rounds to the later minute`() {
        // 22:00, 23:00, 23:30, 00:00 → middle two 23:00/23:30 → 23:15.
        assertThat(medianBedtimeMinutes(listOf(1320, 1380, 1410, 0))).isEqualTo(1395)
    }

    @Test
    fun `fire is today when the lead window is still ahead`() {
        val now = LocalDateTime.of(2026, 9, 19, 20, 0)
        // Bedtime 23:00, lead 45 → 22:15 today.
        assertThat(nextWindDownFire(now, 1380))
            .isEqualTo(LocalDateTime.of(2026, 9, 19, 22, 15))
    }

    @Test
    fun `fire rolls to tomorrow once tonight passed`() {
        val now = LocalDateTime.of(2026, 9, 19, 23, 0)
        assertThat(nextWindDownFire(now, 1380))
            .isEqualTo(LocalDateTime.of(2026, 9, 20, 22, 15))
    }

    @Test
    fun `no window falls back to half past nine`() {
        val now = LocalDateTime.of(2026, 9, 19, 20, 0)
        // 21:30 − 45 → 20:45 today.
        assertThat(nextWindDownFire(now, null))
            .isEqualTo(LocalDateTime.of(2026, 9, 19, 20, 45))
    }
}
