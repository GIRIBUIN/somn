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
 * 4x1 last-night strip: score plus one combined duration and efficiency line.
 * Tap opens the app. Re-renders on tracking-state changes (fresh numbers right
 * after Wake Up) plus the 30-minute system tick.
 */
class DailyStatsWidget : AppWidgetProvider() {

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
                ComponentName(context, DailyStatsWidget::class.java)
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
            val views = RemoteViews(context.packageName, R.layout.widget_daily_stats)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    SleepSessionDaoEntryPoint::class.java
                )
                val session = entryPoint.sleepSessionDao()
                    .getRecentMainSleepSessions(1).firstOrNull()

                if (session != null) {
                    views.setTextViewText(R.id.widget_daily_score, "${session.sleepScore}")
                    views.setImageViewBitmap(
                        R.id.widget_daily_ring,
                        WidgetRings.drawScoreRing(
                            context, session.sleepScore, WidgetRings.isNight(context)
                        )
                    )
                    views.setTextViewText(
                        R.id.widget_daily_duration,
                        "${formatDuration(session.sleepDurationMinutes)} " +
                            "· ${session.sleepEfficiency.toInt()}%"
                    )
                    val wake = (100f - session.deepSleepPercent -
                        session.lightSleepPercent - session.remSleepPercent)
                        .coerceAtLeast(0f)
                    views.setImageViewBitmap(
                        R.id.widget_daily_stages,
                        WidgetCharts.drawStageBar(
                            context,
                            session.deepSleepPercent,
                            session.lightSleepPercent,
                            session.remSleepPercent,
                            wake
                        )
                    )
                    views.setTextViewText(
                        R.id.widget_daily_legend,
                        "Deep ${session.deepSleepPercent.toInt()}% " +
                            "· REM ${session.remSleepPercent.toInt()}%"
                    )
                } else {
                    views.setTextViewText(R.id.widget_daily_score, "--")
                    views.setTextViewText(
                        R.id.widget_daily_duration,
                        context.getString(R.string.widget_no_data)
                    )
                    views.setTextViewText(R.id.widget_daily_legend, "")
                    views.setImageViewBitmap(
                        R.id.widget_daily_stages,
                        WidgetCharts.drawStageBar(context, 0f, 0f, 0f, 0f)
                    )
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_daily_score, "--")
                views.setTextViewText(
                    R.id.widget_daily_duration,
                    context.getString(R.string.widget_no_data)
                )
                views.setTextViewText(R.id.widget_daily_legend, "")
            }

            val launch = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                10,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_daily_root, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        fun formatDuration(totalMinutes: Int): String {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
        }
    }
}
