package dev.vic41148.somn.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Week-bar bitmaps for the rhythm widget. Same constraint as [WidgetRings]:
 * RemoteViews cannot host Compose Canvas, so the 7 bars are drawn once per
 * update. Bar height = duration fraction, color = score tier (or neutral when
 * the gamification kill-switch hides scores), unlogged nights are short nubs.
 */
object WidgetCharts {

    data class WeekBar(val fraction: Float, val score: Int?)

    fun drawWeekBars(context: Context, bars: List<WeekBar>, night: Boolean, neutral: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (252 * density).toInt().coerceAtLeast(1)
        val heightPx = (64 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val slot = widthPx / 7f
        val barWidth = (slot * 0.44f).coerceAtLeast(2f)
        val radius = barWidth / 2f
        bars.take(7).forEachIndexed { index, bar ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when {
                    bar.score == null -> if (night) 0xFF52525B.toInt() else 0xFFD4D4D8.toInt()
                    neutral -> if (night) 0xFF93C5FD.toInt() else 0xFF2563EB.toInt()
                    else -> WidgetRings.scoreArcColor(bar.score)
                }
            }
            val height = if (bar.score == null) 5 * density
            else (12 + 44 * bar.fraction.coerceIn(0f, 1f)) * density
            val left = slot * index + (slot - barWidth) / 2f
            val rect = RectF(left, heightPx - height, left + barWidth, heightPx.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        return bitmap
    }

    /**
     * Stacked stage bar (Deep/REM/Light/Wake) for the Last Night widget, same
     * Okabe-Ito stage ramp as the in-app hypnogram. Rounded outer via clip,
     * square inner dividers. Percents on the 0-100 scale, wake clamped >= 0.
     */
    fun drawStageBar(
        context: Context,
        deepPct: Float,
        lightPct: Float,
        remPct: Float,
        wakePct: Float
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (252 * density).toInt().coerceAtLeast(1)
        val heightPx = (10 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = heightPx / 2f
        canvas.save()
        canvas.clipPath(
            android.graphics.Path().apply {
                addRoundRect(
                    RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
                    radius, radius,
                    android.graphics.Path.Direction.CW
                )
            }
        )
        val segments = listOf(
            deepPct.coerceAtLeast(0f) to 0xFF0072B2.toInt(),
            remPct.coerceAtLeast(0f) to 0xFFCC79A7.toInt(),
            lightPct.coerceAtLeast(0f) to 0xFF56B4E9.toInt(),
            wakePct.coerceAtLeast(0f) to 0xFFD55E00.toInt()
        )
        val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.01f)
        var left = 0f
        segments.forEach { (pct, color) ->
            val width = widthPx * (pct / total)
            if (width >= 0.5f) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                canvas.drawRect(left, 0f, left + width, heightPx.toFloat(), paint)
                left += width
            }
        }
        canvas.restore()
        return bitmap
    }
}
