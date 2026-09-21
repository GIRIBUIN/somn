package dev.vic41148.somn.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.vic41148.somn.app.MainActivity
import dev.vic41148.somn.app.R
import dev.vic41148.somn.core.data.database.dao.SleepSessionDao
import dev.vic41148.somn.feature.tracking.service.SleepTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 2x2 last-7-days widget: average score, average sleep, nights tracked and
 * best night, main sleep only. Tap opens the app. Re-renders on
 * tracking-state changes (fresh numbers right after Wake Up) plus the
 * 30-minute system tick.
 */
class WeeklyStatsWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SleepSessionDaoEntryPoint {
        fun sleepSessionDao(): SleepSessionDao
    }

    private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateStats(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTrackingService.ACTION_TRACKING_STATE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WeeklyStatsWidget::class.java)
            )
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun updateStats(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        widgetScope.launch {
            val views = RemoteViews(context.packageName, R.layout.widget_weekly_stats)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    SleepSessionDaoEntryPoint::class.java
                )
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val sessions = entryPoint.sleepSessionDao()
                    .getMainSleepSessionsSince(weekAgo)

                if (sessions.isNotEmpty()) {
                    val avgScore = sessions.map { it.sleepScore }.average().toInt()
                    val avgMinutes = sessions.map { it.sleepDurationMinutes }.average().toInt()
                    val best = sessions.maxOf { it.sleepScore }
                    views.setTextViewText(R.id.widget_weekly_score, "$avgScore")
                    views.setImageViewBitmap(
                        R.id.widget_weekly_ring,
                        WidgetRings.drawScoreRing(
                            context, avgScore, WidgetRings.isNight(context)
                        )
                    )
                    views.setTextViewText(
                        R.id.widget_weekly_avg,
                        "${DailyStatsWidget.formatDuration(avgMinutes)} avg"
                    )
                    val nights = sessions.size
                    views.setTextViewText(
                        R.id.widget_weekly_nights,
                        "$nights night${if (nights == 1) "" else "s"} tracked"
                    )
                    views.setTextViewText(R.id.widget_weekly_best, "Best $best")
                } else {
                    views.setTextViewText(R.id.widget_weekly_score, "--")
                    views.setTextViewText(
                        R.id.widget_weekly_avg,
                        context.getString(R.string.widget_no_data)
                    )
                    views.setTextViewText(R.id.widget_weekly_nights, "")
                    views.setTextViewText(R.id.widget_weekly_best, "")
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_weekly_score, "--")
                views.setTextViewText(
                    R.id.widget_weekly_avg,
                    context.getString(R.string.widget_no_data)
                )
                views.setTextViewText(R.id.widget_weekly_nights, "")
                views.setTextViewText(R.id.widget_weekly_best, "")
            }

            val launch = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                11,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_weekly_root, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
