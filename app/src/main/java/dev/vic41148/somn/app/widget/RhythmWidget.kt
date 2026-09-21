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
import dev.vic41148.somn.core.data.repository.SleepRepository
import dev.vic41148.somn.core.data.repository.SomnPreferencesRepository
import dev.vic41148.somn.feature.tracking.service.SleepTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 4x2 weekly-rhythm widget (spec §4.4): 7 day bars plus an x/7 ring count,
 * the widget-level expression of the Consistency Streak. Bars show duration,
 * color shows the stage mix via the score tier, unlogged nights are nubs.
 * When the gamification kill-switch is off the bars go neutral blue. Tap
 * opens the app. Re-renders on tracking-state changes plus the system tick.
 */
class RhythmWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RhythmEntryPoint {
        fun sleepRepository(): SleepRepository
        fun preferencesRepository(): SomnPreferencesRepository
    }

    private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateRhythm(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTrackingService.ACTION_TRACKING_STATE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, RhythmWidget::class.java)
            )
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun updateRhythm(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        widgetScope.launch {
            val views = RemoteViews(context.packageName, R.layout.widget_rhythm)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    RhythmEntryPoint::class.java
                )
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now()
                val weekAgo = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
                val sessions = entryPoint.sleepRepository()
                    .getMainSleepSessionsSince(weekAgo)
                    .filter { it.isCompleted }
                val byDay = sessions.groupBy {
                    Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate()
                }.mapValues { (_, list) -> list.maxByOrNull { it.startTimeMillis }!! }
                val bars = (6 downTo 0).map { ago ->
                    val session = byDay[today.minusDays(ago.toLong())]
                    WidgetCharts.WeekBar(
                        fraction = if (session == null) 0f
                        else (session.sleepDurationMinutes / 480f).coerceIn(0f, 1f),
                        score = session?.sleepScore
                    )
                }
                val night = WidgetRings.isNight(context)
                // Kill-switch read is best-effort: a dead DataStore keeps scores visible
                // rather than blanking the widget, same leniency as corrupted prefs in-app.
                val showScores = runCatching {
                    entryPoint.preferencesRepository().showStreaksScores.first()
                }.getOrDefault(true)
                views.setImageViewBitmap(
                    R.id.widget_rhythm_bars,
                    WidgetCharts.drawWeekBars(context, bars, night, neutral = !showScores)
                )
                val logged = bars.count { it.score != null }
                views.setTextViewText(
                    R.id.widget_rhythm_count,
                    context.getString(R.string.widget_rhythm_count, logged)
                )
            } catch (e: Exception) {
                views.setTextViewText(
                    R.id.widget_rhythm_count,
                    context.getString(R.string.widget_no_data)
                )
            }

            val launch = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                14,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_rhythm_root, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
