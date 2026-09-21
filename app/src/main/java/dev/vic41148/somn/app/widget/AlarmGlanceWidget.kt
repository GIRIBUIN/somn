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
import dev.vic41148.somn.core.data.repository.AlarmRepository
import dev.vic41148.somn.core.domain.model.Alarm
import dev.vic41148.somn.feature.tracking.service.SleepTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 2x1 smart-alarm glance (spec §4.5): next enabled alarm large, its smart
 * window as a subtle range underneath. No enabled alarm shows a set-one CTA
 * instead of an empty box. Tap opens the app. Re-renders on tracking-state
 * changes plus the 30-minute system tick, so Today/Tomorrow stays honest.
 */
class AlarmGlanceWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun alarmRepository(): AlarmRepository
    }

    private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAlarm(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTrackingService.ACTION_TRACKING_STATE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AlarmGlanceWidget::class.java)
            )
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun updateAlarm(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        widgetScope.launch {
            val views = RemoteViews(context.packageName, R.layout.widget_alarm_glance)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AlarmEntryPoint::class.java
                )
                val next = nextEnabled(entryPoint.alarmRepository().getEnabledAlarms())
                if (next == null) {
                    views.setTextViewText(
                        R.id.widget_alarm_time,
                        context.getString(R.string.widget_alarm_none)
                    )
                    views.setTextViewText(
                        R.id.widget_alarm_window,
                        context.getString(R.string.widget_alarm_none_hint)
                    )
                } else {
                    views.setTextViewText(R.id.widget_alarm_time, next.alarm.timeFormatted)
                    views.setTextViewText(
                        R.id.widget_alarm_window,
                        windowLabel(next)
                    )
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_alarm_time, "--")
                views.setTextViewText(
                    R.id.widget_alarm_window,
                    context.getString(R.string.widget_no_data)
                )
            }

            val launch = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                13,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_alarm_root, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        data class NextAlarm(val alarm: Alarm, val isToday: Boolean)

        /** Same wrap math as AlarmRepository.getNextAlarm, plus the Today/Tomorrow flag. */
        fun nextEnabled(alarms: List<Alarm>, nowMinutes: Int = nowMinutes()): NextAlarm? {
            val next = alarms.filter { it.isEnabled }.minByOrNull {
                ((it.hour * 60 + it.minute - nowMinutes) + 1440) % 1440
            } ?: return null
            return NextAlarm(next, next.hour * 60 + next.minute > nowMinutes)
        }

        fun windowLabel(next: NextAlarm): String {
            val start = (next.alarm.hour * 60 + next.alarm.minute -
                next.alarm.wakeWindowMinutes + 1440) % 1440
            val day = if (next.isToday) "Today" else "Tomorrow"
            val label = next.alarm.label.ifBlank { null }
            return buildString {
                append("$day · ${formatClock(start)} – ${next.alarm.timeFormatted}")
                if (label != null) append(" · $label")
            }
        }

        fun formatClock(totalMinutes: Int): String {
            val wrapped = ((totalMinutes % 1440) + 1440) % 1440
            val h24 = wrapped / 60
            val h = if (h24 % 12 == 0) 12 else h24 % 12
            return "%d:%02d %s".format(h, wrapped % 60, if (h24 < 12) "AM" else "PM")
        }

        private fun nowMinutes(): Int {
            val cal = java.util.Calendar.getInstance()
            return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                cal.get(java.util.Calendar.MINUTE)
        }
    }
}
