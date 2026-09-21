package dev.vic41148.somn.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.vic41148.somn.app.MainActivity
import dev.vic41148.somn.app.R
import dev.vic41148.somn.feature.tracking.service.SleepTrackingService
import dev.vic41148.somn.feature.tracking.service.TrackingState

/**
 * 1x1 home-screen sleep toggle, the widget twin of the Home moon button.
 *
 * Idle shows the moon + "Sleep", tap auto-starts with moon defaults and lands
 * on tracking. Tracking shows "Live", tap re-enters the tracking screen.
 * Stopping stays in-app on purpose: session completion owns the final-epoch flush,
 * a raw stop intent would leave the night to the incomplete-session recovery path.
 * Re-renders on [SleepTrackingService.ACTION_TRACKING_STATE_CHANGED] plus the
 * 30-minute system tick.
 */
class TrackingToggleWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateToggle(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTrackingService.ACTION_TRACKING_STATE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TrackingToggleWidget::class.java)
            )
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun updateToggle(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        // Same-process read: a dead process means no live service, IDLE is correct.
        val tracking = SleepTrackingService.trackingState.value == TrackingState.TRACKING

        val views = RemoteViews(context.packageName, R.layout.widget_tracking_toggle)
        // Notification icon is white-on-transparent: tint per night mode so it reads
        // on both the dark and the light widget background. setColorFilter via
        // RemoteViews.setInt is the only tint path RemoteViews offers.
        val night = (context.resources.configuration.uiMode
                and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        views.setInt(
            R.id.widget_toggle_icon,
            "setColorFilter",
            if (night) 0xFFFFFFFF.toInt() else 0xFF1A1C1E.toInt()
        )
        views.setTextViewText(
            R.id.widget_toggle_label,
            if (tracking) "Live" else "Sleep"
        )
        views.setTextViewText(
            R.id.widget_toggle_hint,
            if (tracking) "Tracking now — tap to open"
            else context.getString(R.string.widget_toggle_hint_idle)
        )

        val launch = Intent(context, MainActivity::class.java).apply {
            if (tracking) {
                putExtra(SleepTrackingService.EXTRA_OPEN_TRACKING, true)
            } else {
                putExtra(SleepTrackingService.EXTRA_START_TRACKING, true)
            }
        }
        // Distinct request codes per state: same component + same code would recycle
        // the stale extra and auto-start (or skip) on the wrong tap.
        val pending = PendingIntent.getActivity(
            context,
            if (tracking) 1 else 0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_root, pending)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
