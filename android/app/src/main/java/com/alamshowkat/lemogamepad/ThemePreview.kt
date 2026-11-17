package com.alamshowkat.lemogamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Small, self-contained mock-up of the pad in one [PadTheme].
 *
 * The settings sheet keeps the real controller visible behind it, but this
 * preview makes a palette readable at a glance: background, glow, d-pad, both
 * sticks, shoulders and the four iconic face buttons in their fixed colours.
 */
class ThemePreview(context: Context) : View(context) {

    var theme: PadTheme = Themes.MIDNIGHT
        set(value) {
            field = value
            invalidate()
        }

    var faceLabels: FaceLabels = FaceLabels.PLAYSTATION
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = theme

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(t.bgTop, t.bgMid, t.bgBottom),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        if (t.glow > 0.01f) {
            fill.shader = RadialGradient(
                w * 0.5f, -h * 0.1f, h * 1.15f,
                intArrayOf(
                    withAlpha(t.accent, ((if (t.dark) 46 else 24) * t.glow).toInt()),
                    withAlpha(t.accent2, 0),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        // Everything scales from the height so the preview works in any card.
        val unit = min(h * 0.11f, w * 0.075f)

        // D-pad, bottom left.
        val dx = w * 0.14f
        val dy = h * 0.66f
        val dr = unit * 1.30f
        fill.color = t.surface
        canvas.drawCircle(dx, dy, dr, fill)
        fill.color = t.surfaceAlt
        rect.set(dx - dr * 0.30f, dy - dr * 0.80f, dx + dr * 0.30f, dy - dr * 0.20f)
        canvas.drawRoundRect(rect, dr * 0.10f, dr * 0.10f, fill)
        rect.set(dx - dr * 0.30f, dy + dr * 0.20f, dx + dr * 0.30f, dy + dr * 0.80f)
        canvas.drawRoundRect(rect, dr * 0.10f, dr * 0.10f, fill)
        rect.set(dx - dr * 0.80f, dy - dr * 0.30f, dx - dr * 0.20f, dy + dr * 0.30f)
        canvas.drawRoundRect(rect, dr * 0.10f, dr * 0.10f, fill)
        rect.set(dx + dr * 0.20f, dy - dr * 0.30f, dx + dr * 0.80f, dy + dr * 0.30f)
        canvas.drawRoundRect(rect, dr * 0.10f, dr * 0.10f, fill)
        fill.color = t.accent
        canvas.drawCircle(dx, dy, dr * 0.09f, fill)

        // Sticks.
        drawStick(canvas, w * 0.36f, h * 0.72f, unit, t.accent)
        drawStick(canvas, w * 0.62f, h * 0.72f, unit, t.accent2)

        // Face cluster, right side.
        val fx = w * 0.86f
        val fy = h * 0.60f
        val spread = unit * 1.55f
        val fr = unit * 0.86f
        for (i in 0 until 4) {
            val ox = when (i) {
                1 -> spread
                3 -> -spread
                else -> 0f
            }
            val oy = when (i) {
                0 -> -spread
                2 -> spread
                else -> 0f
            }
            val color = Iconic.face(faceLabels, i)
            fill.color = blend(t.surface, color, 0.30f)
            canvas.drawCircle(fx + ox, fy + oy, fr, fill)
            stroke.color = withAlpha(color, 220)
            stroke.strokeWidth = fr * 0.06f
            canvas.drawCircle(fx + ox, fy + oy, fr - fr * 0.03f, stroke)
            if (faceLabels == FaceLabels.PLAYSTATION) {
                drawGlyph(canvas, i, fx + ox, fy + oy, fr, color)
            } else {
                text.textSize = fr * 1.0f
                text.color = color
                val letter = when (i) {
                    0 -> "Y"
                    1 -> "B"
                    2 -> "A"
                    else -> "X"
                }
                canvas.drawText(letter, fx + ox, fy + oy + fr * 0.34f, text)
            }
        }

        // Shoulders and centre pills.
        fill.color = t.surface
        rect.set(w * 0.035f, h * 0.06f, w * 0.24f, h * 0.19f)
        canvas.drawRoundRect(rect, h * 0.035f, h * 0.035f, fill)
        rect.set(w * 0.76f, h * 0.06f, w * 0.965f, h * 0.19f)
        canvas.drawRoundRect(rect, h * 0.035f, h * 0.035f, fill)
        stroke.color = withAlpha(t.text, 40)
        stroke.strokeWidth = 1.4f
        rect.set(w * 0.035f, h * 0.06f, w * 0.24f, h * 0.19f)
        canvas.drawRoundRect(rect, h * 0.035f, h * 0.035f, stroke)
        rect.set(w * 0.76f, h * 0.06f, w * 0.965f, h * 0.19f)
        canvas.drawRoundRect(rect, h * 0.035f, h * 0.035f, stroke)

        // Accent rule under the shoulders gives each theme a signature colour.
        stroke.color = withAlpha(t.accent, 150)
        stroke.strokeWidth = h * 0.012f
        canvas.drawLine(w * 0.04f, h * 0.93f, w * 0.30f, h * 0.93f, stroke)
        stroke.color = withAlpha(t.accent2, 120)
        canvas.drawLine(w * 0.34f, h * 0.93f, w * 0.46f, h * 0.93f, stroke)
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, unit: Float, accent: Int) {
        val t = theme
        val r = unit * 1.28f
        fill.color = t.surface
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = withAlpha(accent, 150)
        stroke.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r - r * 0.04f, stroke)
        fill.color = t.surfaceAlt
        canvas.drawCircle(cx, cy, r * 0.52f, fill)
        stroke.color = withAlpha(accent, 210)
        stroke.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r * 0.52f, stroke)
        fill.color = accent
        canvas.drawCircle(cx, cy, r * 0.08f, fill)

        // Cross marks, like the real control.
        stroke.color = withAlpha(t.text, 30)
        stroke.strokeWidth = 1.2f
        for (i in 0 until 4) {
            val a = Math.toRadians(45.0 + i * 90.0).toFloat()
            val c = cos(a)
            val s = sin(a)
            canvas.drawLine(
                cx + c * r * 0.80f, cy + s * r * 0.80f,
                cx + c * r * 0.93f, cy + s * r * 0.93f, stroke,
            )
        }
    }

    private fun drawGlyph(canvas: Canvas, i: Int, cx: Float, cy: Float, r: Float, color: Int) {
        glyph.color = color
        glyph.strokeWidth = r * 0.20f
        val g = r * 0.38f
        when (i) {
            0 -> {
                path.reset()
                path.moveTo(cx, cy - g * 1.12f)
                path.lineTo(cx + g * 1.02f, cy + g * 0.72f)
                path.lineTo(cx - g * 1.02f, cy + g * 0.72f)
                path.close()
                canvas.drawPath(path, glyph)
            }
            1 -> canvas.drawCircle(cx, cy, g, glyph)
            2 -> {
                canvas.drawLine(cx - g, cy - g, cx + g, cy + g, glyph)
                canvas.drawLine(cx + g, cy - g, cx - g, cy + g, glyph)
            }
            else -> {
                rect.set(cx - g, cy - g, cx + g, cy + g)
                canvas.drawRect(rect, glyph)
            }
        }
    }
}
