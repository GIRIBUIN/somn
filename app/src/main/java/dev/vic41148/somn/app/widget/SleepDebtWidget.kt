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
import dev.vic41148.somn.core.data.repository.UserProfileRepository
import dev.vic41148.somn.core.domain.usecase.SleepDebtUseCase
import dev.vic41148.somn.feature.tracking.service.SleepTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 4x2 sleep-debt widget (spec §4.3): running debt in hours, an AutoSleep-style
 * fill bar, and the single most-actionable recovery tip. Positive banked sleep
 * reads calm, never "winning". Tap opens the app. Re-renders on
 * tracking-state changes plus the 30-minute system tick, so the debt ages
 * without a new session.
 */
class SleepDebtWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DebtEntryPoint {
        fun sleepRepository(): SleepRepository
        fun userProfileRepository(): UserProfileRepository
    }

    private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val debtUseCase = SleepDebtUseCase()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateDebt(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTrackingService.ACTION_TRACKING_STATE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SleepDebtWidget::class.java)
            )
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun updateDebt(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        widgetScope.launch {
            val views = RemoteViews(context.packageName, R.layout.widget_sleep_debt)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    DebtEntryPoint::class.java
                )
                val sessions = entryPoint.sleepRepository().getRecentMainSleepSessions(14)
                val profile = entryPoint.userProfileRepository().getProfile()
                val targetMinutes = ((profile?.targetSleepHours ?: 8f) * 60).toInt()
                val (debt, plan) = debtUseCase.calculate(sessions, targetMinutes)

                views.setTextViewText(
                    R.id.widget_debt_value,
                    formatDebt(debt.totalDebtMinutes) +
                        if (debt.totalDebtMinutes <= 0) " · Ahead" else " · Behind"
                )
                views.setProgressBar(
                    R.id.widget_debt_bar, BAR_MAX,
                    debt.totalDebtMinutes.coerceIn(0, BAR_MAX), false
                )
                views.setTextViewText(R.id.widget_debt_tip, tipFor(debt.totalDebtMinutes, plan.additionalMinutesPerNight))
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_debt_value, "--")
                views.setTextViewText(
                    R.id.widget_debt_tip,
                    context.getString(R.string.widget_no_data)
                )
            }

            val launch = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                12,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_debt_root, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        /** 10h scale: the bar fills as debt grows, empty means caught up. */
        const val BAR_MAX = 600

        fun formatDebt(totalMinutes: Int): String {
            if (totalMinutes <= 0) return "None"
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        fun tipFor(totalDebtMinutes: Int, additionalPerNight: Int): String = when {
            totalDebtMinutes <= 0 -> "No debt. Bank this rhythm."
            additionalPerNight > 0 -> {
                val h = additionalPerNight / 60
                val m = additionalPerNight % 60
                val extra = if (h > 0) "${h}h ${m}m" else "${m}m"
                "An extra $extra tonight pays it down fastest."
            }
            else -> "An earlier bedtime tonight helps most."
        }
    }
}
