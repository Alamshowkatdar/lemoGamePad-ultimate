package com.alamshowkat.lemogamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Sparkline of recent round-trip times.
 *
 * A single "12 ms" readout cannot distinguish a clean link from one that is
 * usually fast and spikes to 300 ms. Plotting the last ~30 seconds makes the
 * difference obvious and turns "it feels laggy" into something measurable.
 */
class LatencyGraph(context: Context) : View(context) {

    private var samples: IntArray = IntArray(0)
    private var accent: Int = Color.rgb(62, 240, 140)
    private var muted: Int = Color.GRAY

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.8f)
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val path = Path()
    private val fillPath = Path()
    private val box = RectF()

    fun setSamples(values: IntArray, accentColor: Int, mutedColor: Int) {
        val changed = values.size != samples.size ||
            (values.isNotEmpty() && samples.isNotEmpty() && values.last() != samples.last())
        samples = values
        accent = accentColor
        muted = mutedColor
        if (changed) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(dp(58f).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padX = dp(8f)
        val padTop = dp(9f)
        val padBottom = dp(9f)
        box.set(padX, padTop, w - padX, h - padBottom)
        val radius = dp(9f)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, area.apply { color = withAlpha(accent, 14) })

        grid.color = withAlpha(muted, 34)
        grid.strokeWidth = dp(1f)
        for (i in 1..2) {
            val y = box.top + box.height() * i / 3f
            canvas.drawLine(box.left, y, box.right, y, grid)
        }

        if (samples.size < 2) {
            text.color = withAlpha(muted, 220)
            text.textSize = dp(10f)
            val message = if (samples.isEmpty()) "waiting for link…" else "collecting samples…"
            canvas.drawText(message, box.left, box.centerY() + dp(3.5f), text)
            return
        }

        var peak = 0
        var low = Int.MAX_VALUE
        for (sample in samples) {
            peak = max(peak, sample)
            low = min(low, sample)
        }
        // Keep a little headroom and never amplify a perfectly flat 5 ms line
        // into a dramatic mountain range.
        val top = max(peak.toFloat(), max(low + 8f, 30f))
        val bottom = 0f

        val stepX = box.width() / (samples.size - 1).toFloat()
        fun yOf(value: Int): Float =
            box.bottom - (value.coerceIn(0, top.toInt()) / top) * box.height()

        path.reset()
        fillPath.reset()
        for (i in samples.indices) {
            val x = box.left + stepX * i
            val y = yOf(samples[i])
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, box.bottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(box.right, box.bottom)
        fillPath.close()

        area.color = withAlpha(accent, 46)
        canvas.drawPath(fillPath, area)
        line.color = accent
        canvas.drawPath(path, line)

        val lastX = box.right
        val lastY = yOf(samples.last())
        dot.color = accent
        canvas.drawCircle(lastX, lastY, dp(2.8f), dot)
        dot.color = withAlpha(accent, 70)
        canvas.drawCircle(lastX, lastY, dp(6.5f), dot)

        // Peak label, top right: the number that matters when diagnosing spikes.
        text.color = withAlpha(muted, 235)
        text.textSize = dp(8.5f)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("PEAK ${peak} ms", box.right, box.top + dp(7f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
