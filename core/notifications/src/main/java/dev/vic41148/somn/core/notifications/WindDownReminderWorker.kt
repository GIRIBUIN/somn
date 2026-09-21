package dev.vic41148.somn.core.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.vic41148.somn.core.data.repository.SleepRepository
import dev.vic41148.somn.core.data.repository.SomnPreferencesRepository
import dev.vic41148.somn.core.domain.usecase.medianBedtimeMinutes
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/**
 * Fires the wind-down nudge, then chains the next night. Copy carries no
 * score, no streak, no verdict (spec §6.6 ban list): just the usual bedtime
 * when one is revealed, or a plain invitation when it is not. Rest Mode
 * nights get no nudge, sick nights should not be optimized.
 */
@HiltWorker
class WindDownReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val sleepRepository: SleepRepository,
    private val preferencesRepository: SomnPreferencesRepository,
    private val notificationEngine: NotificationEngine,
    private val scheduler: WindDownReminderScheduler
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Chain first: the next night is scheduled even when tonight is skipped.
            scheduler.schedule()
            if (!preferencesRepository.windDownReminder.first()) return Result.success()
            if (preferencesRepository.restModeSince.first() != null) return Result.success()

            val zone = ZoneId.systemDefault()
            val bedtimes = sleepRepository.getRecentMainSleepSessions(14)
                .filter { it.isCompleted }
                .map {
                    val wall = Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalTime()
                    wall.hour * 60 + wall.minute
                }
            val bedtime = medianBedtimeMinutes(bedtimes)
            notificationEngine.showNotification(
                // 2003: 2001 owns the alarm FGS, 2002 the weekly report, ids never share.
                id = 2003,
                channelId = NotificationEngine.CHANNEL_REMINDERS,
                title = "Time to wind down \uD83C\uDF19",
                content = if (bedtime != null) {
                    "Your usual bedtime is around ${formatClock(bedtime)}. " +
                        "Screens off, lights low, a short wind-down beats scrolling."
                } else {
                    "Screens off, lights low. A short wind-down now beats scrolling."
                }
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun formatClock(totalMinutes: Int): String {
            val wrapped = ((totalMinutes % 1440) + 1440) % 1440
            val h24 = wrapped / 60
            val h = if (h24 % 12 == 0) 12 else h24 % 12
            return "%d:%02d %s".format(h, wrapped % 60, if (h24 < 12) "AM" else "PM")
        }
    }
}
