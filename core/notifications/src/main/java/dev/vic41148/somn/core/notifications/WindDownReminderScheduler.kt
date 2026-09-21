package dev.vic41148.somn.core.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.vic41148.somn.core.data.repository.SleepRepository
import dev.vic41148.somn.core.data.repository.SomnPreferencesRepository
import dev.vic41148.somn.core.domain.usecase.medianBedtimeMinutes
import dev.vic41148.somn.core.domain.usecase.nextWindDownFire
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive wind-down reminder scheduling (spec §6.6). One-time WorkManager
 * chain, not periodic: each fire (and each fresh schedule) recomputes the next
 * fire from the latest bedtime window, so the nudge tracks a drifting bedtime
 * instead of firing at the hour the user happened to set it. Inexact delivery
 * is fine for a nudge, exact alarms stay reserved for the alarm clock.
 */
@Singleton
class WindDownReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sleepRepository: SleepRepository,
    private val preferencesRepository: SomnPreferencesRepository
) {
    companion object {
        const val UNIQUE_WORK = "wind_down_reminder"
    }

    /** No-op unless the toggle is on; safe to call on every app start. */
    suspend fun schedule() {
        if (!preferencesRepository.windDownReminder.first()) return
        val zone = ZoneId.systemDefault()
        val bedtimes = sleepRepository.getRecentMainSleepSessions(14)
            .filter { it.isCompleted }
            .map {
                val wall = Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalTime()
                wall.hour * 60 + wall.minute
            }
        val now = LocalDateTime.now()
        val fire = nextWindDownFire(now, medianBedtimeMinutes(bedtimes))
        val delayMillis = java.time.Duration.between(now, fire).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<WindDownReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}
