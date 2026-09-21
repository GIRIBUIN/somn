package dev.vic41148.somn.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Score-ring bitmaps for the stats widgets. RemoteViews cannot host Compose
 * Canvas, and a shape-ring ProgressBar cannot change arc colour at runtime,
 * so the ring is drawn once per update with the exact score colour.
 * Colours mirror [ScoreTier]/score ramp thresholds (80/60/40) with fixed
 * hexes that read on both the dark and the light widget background.
 */
object WidgetRings {

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun scoreArcColor(score: Int): Int = when {
        score >= 80 -> 0xFF43A047.toInt()
        score >= 60 -> 0xFF9CCC65.toInt()
        score >= 40 -> 0xFFFFC107.toInt()
        else -> 0xFFEF5350.toInt()
    }

    fun drawScoreRing(context: Context, score: Int, night: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (72 * density).toInt().coerceAtLeast(1)
        val strokePx = (9 * density).coerceAtLeast(2f)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = strokePx / 2f + 1f
        val arcSize = sizePx - inset * 2f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = if (night) 0xFF3F3F46.toInt() else 0xFFE2E2E2.toInt()
        }
        canvas.drawArc(inset, inset, inset + arcSize, inset + arcSize, 0f, 360f, false, track)

        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            color = scoreArcColor(score)
        }
        val sweep = (score.coerceIn(0, 100) / 100f * 360f).coerceAtLeast(0.5f)
        canvas.drawArc(inset, inset, inset + arcSize, inset + arcSize, -90f, sweep, false, arc)
        return bitmap
    }
}
