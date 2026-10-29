package com.alamshowkat.lemogamepad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Node kinds. File-level so the nested Node class can use them.
private const val K_DPAD = 0
private const val K_STICK_L = 1
private const val K_STICK_R = 2
private const val K_FACE = 3
private const val K_PILL = 4
private const val K_PS = 5
private const val K_SHOULDER = 6

/**
 * Full screen touch controller, restyled around [PadTheme].
 *
 * Layout is a list of draggable nodes stored as screen fractions, so a custom
 * layout survives rotation and different screen sizes. In edit mode a node can
 * be moved, pinched to resize, or held to hide it.
 */
@SuppressLint("ViewConstructor")
class GamepadView(context: Context) : View(context) {

    val state = PadState()

    var onOpenSettings: (() -> Unit)? = null
    var onHaptic: ((Int) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null
    var onToast: ((String) -> Unit)? = null

    var deadzone: Float = 0.12f
        set(value) {
            field = value.coerceIn(0f, 0.4f)
        }

    @Volatile
    var hapticsEnabled = true

    // Theme switching is a 300 ms cross-fade, so the whole pad melts from one
    // palette into the next instead of snapping.
    private var themeTarget: PadTheme = Themes.NEON
    private var themeFrom: PadTheme = Themes.NEON
    private var themeNow: PadTheme = Themes.NEON
    private var themeT = 1f

    var theme: PadTheme
        get() = themeNow
        set(value) {
            if (value.id == themeTarget.id) return
            themeFrom = themeNow
            themeTarget = value
            themeT = 0f
            startAnimating()
        }

    /** The palette the pad is fading towards: what the user picked. */
    val selectedTheme: PadTheme get() = themeTarget

    var faceLabels: FaceLabels = FaceLabels.PLAYSTATION
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var editMode = false
        set(value) {
            if (field == value) return
            field = value
            if (value) resetInputs()
            removeCallbacks(longPressRunnable)
            editNode = null
            editPointer = -1
            pinchPointer = -1
            invalidate()
        }

    // ------------------------------------------------------------- control model
    private class Node(
        val key: String,
        val kind: Int,
        val bit: Int,
        val label: String,
        var fx: Float,
        var fy: Float,
        val faceIdx: Int = -1,
        val homeDx: Float = 0f,
        val homeDy: Float = 0f,
    ) {
        var defFx = fx
        var defFy = fy
        var scale = 1f
        var hidden = false

        /** True once the user moves or resizes a face button. */
        var customized = false

        // resolved geometry, recalculated on every size/layout change
        var cx = 0f
        var cy = 0f
        var hw = 0f
        var hh = 0f
        var r = 0f

        // per-control state
        var pressed = false
        var pointer = -1
        var up = false
        var down = false
        var left = false
        var right = false
        var kx = 0f
        var ky = 0f

        // 0 = idle, 1 = fully pressed. Drives the springy press animation.
        var pressT = 0f

        /** Velocity of [pressT] so presses spring instead of easing linearly. */
        var pressVel = 0f

        var bw = 0f
        var bh = 0f

        fun extW(): Float = when (kind) {
            K_PILL, K_PS, K_SHOULDER -> hw
            else -> r
        }

        fun extH(): Float = when (kind) {
            K_PILL, K_PS, K_SHOULDER -> hh
            else -> r
        }

        fun hit(x: Float, y: Float): Boolean = when (kind) {
            K_PILL, K_PS, K_SHOULDER -> x >= cx - hw * 1.1f && x <= cx + hw * 1.1f &&
                y >= cy - hh * 1.15f && y <= cy + hh * 1.15f
            K_FACE -> hypot(x - cx, y - cy) <= r * 1.4f
            else -> hypot(x - cx, y - cy) <= r * 1.5f
        }
    }

    private val nodes = ArrayList<Node>(16)
    private val playOrder = ArrayList<Node>(16)
    private val faceNodes = ArrayList<Node>(4)
    private lateinit var dpadNode: Node
    private lateinit var leftStick: Node
    private lateinit var rightStick: Node

    // --------------------------------------------------------------- paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val bgPaint = Paint()
    private val rect = RectF()
    private val path = Path()

    private val boldFace: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val labelFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // --------------------------------------------------------------- geometry
    private var W = 0f
    private var H = 0f
    private var bgShader: Shader? = null
    private var glowShader: Shader? = null

    private val hudRect = RectF()
    private var hudPointer = -1
    private var hudPressed = false

    var statusText: String = "TAP TO CONNECT"
        private set
    private var statusKind = STATUS_IDLE

    // --------------------------------------------------------------- edit mode
    private var editNode: Node? = null
    private var editPointer = -1
    private var pinchPointer = -1
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pinchStartDist = 1f
    private var pinchStartScale = 1f

    /** Old 1.0 layout: face cluster as one node [fx, fy, scale, hidden]. */
    private var legacyFace: FloatArray? = null

    // ------------------------------------------------------------- animation
    private var animating = false
    private var lastAnimAt = 0L
    private var statusPulse = 0f

    // One-time fade/scale entrance when the pad first appears.
    private var enterT = 1f
    private var entered = false

    private val longPressRunnable = Runnable {
        val n = editNode ?: return@Runnable
        if (moved) return@Runnable
        n.hidden = true
        onHaptic?.invoke(20)
        onToast?.invoke("${n.label} hidden · SHOW ALL restores it")
        invalidate()
    }

    init {
        buildNodes()
    }

    private fun buildNodes() {
        dpadNode = Node("dpad", K_DPAD, -1, "D-PAD", 0.115f, 0.585f)
        leftStick = Node("ls", K_STICK_L, -1, "LEFT STICK", 0.265f, 0.75f)
        rightStick = Node("rs", K_STICK_R, -1, "RIGHT STICK", 0.705f, 0.75f)

        // A / B / X / Y (or the PS symbols) are four independent controls now.
        // homeDx/homeDy are offsets in H units, so the cluster keeps even pixel
        // spacing on every aspect ratio until the user moves a button.
        val tri = Node("tri", K_FACE, Proto.TRIANGLE, "TRIANGLE", 0.855f, 0.470f, 0, 0f, -FACE_SPREAD)
        val cir = Node("cir", K_FACE, Proto.CIRCLE, "CIRCLE", 0.913f, 0.585f, 1, FACE_SPREAD, 0f)
        val cro = Node("cro", K_FACE, Proto.CROSS, "CROSS", 0.855f, 0.700f, 2, 0f, FACE_SPREAD)
        val squ = Node("squ", K_FACE, Proto.SQUARE, "SQUARE", 0.797f, 0.585f, 3, -FACE_SPREAD, 0f)

        val l2 = Node("l2", K_SHOULDER, Proto.L2, "L2", 0.115f, 0.105f)
        val l1 = Node("l1", K_SHOULDER, Proto.L1, "L1", 0.115f, 0.2475f)
        val r2 = Node("r2", K_SHOULDER, Proto.R2, "R2", 0.885f, 0.105f)
        val r1 = Node("r1", K_SHOULDER, Proto.R1, "R1", 0.885f, 0.2475f)
        val select = Node("select", K_PILL, Proto.SELECT, "SELECT", 0.435f, 0.935f).apply {
            bw = 0.078f; bh = 0.042f
        }
        val start = Node("start", K_PILL, Proto.START, "START", 0.565f, 0.935f).apply {
            bw = 0.078f; bh = 0.042f
        }
        val ps = Node("ps", K_PS, Proto.PS, "PS", 0.5f, 0.80f).apply {
            bw = 0.05f; bh = 0.05f
        }
        val l3 = Node("l3", K_PILL, Proto.L3, "L3", 0.265f, 0.955f).apply {
            bw = 0.055f; bh = 0.033f
        }
        val r3 = Node("r3", K_PILL, Proto.R3, "R3", 0.705f, 0.955f).apply {
            bw = 0.055f; bh = 0.033f
        }

        nodes.clear()
        faceNodes.clear()
        nodes += dpadNode
        nodes += leftStick
        nodes += rightStick
        nodes += tri
        nodes += cir
        nodes += cro
        nodes += squ
        faceNodes += tri
        faceNodes += cir
        faceNodes += cro
        faceNodes += squ
        nodes += l2
        nodes += l1
        nodes += r2
        nodes += r1
        nodes += select
        nodes += start
        nodes += ps
        nodes += l3
        nodes += r3
        rebuildPlayOrder()
    }

    /** Small controls are hit-tested before the sticks so L3/R3 stay reachable. */
    private fun rebuildPlayOrder() {
        playOrder.clear()
        playOrder += dpadNode
        for (n in nodes) {
            if (n.kind == K_SHOULDER || n.kind == K_PILL || n.kind == K_PS) playOrder += n
        }
        playOrder += faceNodes
        playOrder += leftStick
        playOrder += rightStick
    }
    // --------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()
        if (W <= 0f || H <= 0f) return
        rebuildShaders()
        layoutControls()
        publish(redraw = false)
        if (!entered) {
            entered = true
            enterT = 0f
            startAnimating()
        }
    }

    private fun rebuildShaders() {
        if (W <= 0f || H <= 0f) return
        bgShader = LinearGradient(
            0f, 0f, 0f, H,
            intArrayOf(theme.bgTop, theme.bgMid, theme.bgBottom),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowShader = RadialGradient(
            W * 0.5f, -H * 0.04f, H * 1.05f,
            intArrayOf(withAlpha(theme.accent, if (theme.dark) 40 else 20), withAlpha(theme.accent2, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun layoutControls() {
        if (W <= 0f || H <= 0f) return
        val a = aspectScale()

        // Face cluster anchor (same spot the old single cluster used).
        val baseX = min(W * 0.855f, W - H * 0.20f)
        val baseY = H * 0.585f

        // Migrate a layout saved by 1.0, where all four buttons were one node.
        legacyFace?.let { old ->
            val lscale = old[2]
            for (n in faceNodes) {
                n.scale = lscale
                n.hidden = old[3] > 0.5f
                n.fx = (old[0] * W + n.homeDx * H * a * lscale) / W
                n.fy = (old[1] * H + n.homeDy * H * a * lscale) / H
                n.customized = true
            }
            legacyFace = null
        }

        for (n in nodes) {
            when (n.kind) {
                K_DPAD -> n.r = H * 0.145f * a * n.scale
                K_STICK_L, K_STICK_R -> n.r = H * 0.14f * a * n.scale
                K_FACE -> {
                    n.r = H * 0.058f * a * n.scale
                    if (!n.customized) {
                        n.fx = (baseX + n.homeDx * H * a) / W
                        n.fy = (baseY + n.homeDy * H * a) / H
                        n.defFx = n.fx
                        n.defFy = n.fy
                    }
                }
                K_PILL -> {
                    n.hw = H * n.bw * n.scale
                    n.hh = H * n.bh * n.scale
                }
                K_PS -> {
                    n.r = H * n.bw * n.scale
                    n.hw = n.r
                    n.hh = n.r
                }
                K_SHOULDER -> {
                    n.hw = W * 0.0975f * n.scale
                    n.hh = H * 0.0625f * n.scale
                }
            }

            // Keep every control fully on screen, even after a custom drag.
            val pad = H * 0.012f
            val ex = n.extW() + pad
            val ey = n.extH() + pad
            n.cx = (n.fx * W).coerceIn(ex, max(ex, W - ex))
            n.cy = (n.fy * H).coerceIn(ey, max(ey, H - ey))
        }
    }

    // ------------------------------------------------------------------ draw
    override fun onDraw(canvas: Canvas) {
        if (W <= 0f || H <= 0f) return

        if (animating) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastAnimAt == 0L) 16f else (now - lastAnimAt).toFloat().coerceIn(1f, 48f)
            lastAnimAt = now
            stepAnimations(dt)
        }

        bgPaint.shader = bgShader
        canvas.drawPaint(bgPaint)
        glowPaint.shader = glowShader
        canvas.drawPaint(glowPaint)
        glowPaint.shader = null

        if (enterT < 1f) {
            val k = 1f - (1f - enterT) * (1f - enterT) * (1f - enterT)
            canvas.saveLayerAlpha(0f, 0f, W, H, (255 * k).toInt())
            canvas.translate(W / 2f, H / 2f)
            val s = 0.982f + 0.018f * k
            canvas.scale(s, s)
            canvas.translate(-W / 2f, -H / 2f)
        }

        for (n in nodes) {
            if (n.hidden) {
                if (editMode) drawPlaceholder(canvas, n)
                continue
            }
            when (n.kind) {
                K_DPAD -> drawDpad(canvas, n)
                K_STICK_L, K_STICK_R -> drawStick(canvas, n)
                K_FACE -> drawFace(canvas, n)
                else -> drawPill(canvas, n)
            }
        }

        if (enterT < 1f) canvas.restore()

        if (editMode) {
            drawEditOverlay(canvas)
        } else {
            drawHud(canvas)
            drawWatermark(canvas)
        }

        if (animating) postInvalidateOnAnimation()
    }

    /** Press springs, theme cross-fade and the one-shot status ripple. */
    private fun stepAnimations(dt: Float) {
        animating = false
        // Normalise to 60 Hz frames so the spring behaves the same on any
        // refresh rate. dt is in milliseconds.
        val step = (dt / 16.667f).coerceIn(0.25f, 3f)

        for (n in nodes) {
            val target = if (n.pressed || n.pointer != -1) 1f else 0f
            val delta = target - n.pressT
            if (abs(delta) < 0.003f && abs(n.pressVel) < 0.003f) {
                n.pressT = target
                n.pressVel = 0f
                continue
            }
            // Lightly under-damped spring: a quick overshoot that settles in
            // about 120 ms, so the pad feels physical rather than laggy.
            n.pressVel += (delta * PRESS_STIFFNESS - n.pressVel * PRESS_DAMPING) * step
            n.pressT = (n.pressT + n.pressVel * step).coerceIn(-0.02f, 1.15f)
            animating = true
        }

        if (themeT < 1f) {
            themeT = (themeT + dt / 300f).coerceAtMost(1f)
            // Smootherstep: no visible kick when the fade starts or ends.
            val e = themeT * themeT * themeT * (themeT * (themeT * 6f - 15f) + 10f)
            themeNow = if (themeT >= 1f) themeTarget else blendThemes(themeFrom, themeTarget, e)
            rebuildShaders()
            animating = true
        }

        if (enterT < 1f) {
            enterT = (enterT + dt / 460f).coerceAtMost(1f)
            animating = true
        }

        if (statusPulse > 0f) {
            statusPulse = (statusPulse - dt / 620f).coerceAtLeast(0f)
            animating = true
        }
    }

    private fun startAnimating() {
        if (animating) return
        animating = true
        lastAnimAt = 0L
        invalidate()
    }

    private fun drawWatermark(canvas: Canvas) {
        val size = H * 0.0132f
        val tracking = size * 0.18f
        val w = trackedWidth(Brand.MARK, size, tracking, true)
        drawTracked(
            canvas, Brand.MARK, W * 0.018f + w / 2f, H - size * 1.7f,
            size, withAlpha(theme.muted, 150), tracking, true,
        )
    }

    private fun drawPlaceholder(canvas: Canvas, n: Node) {
        val ex = n.extW() + dp(7f)
        val ey = n.extH() + dp(7f)
        rect.set(n.cx - ex, n.cy - ey, n.cx + ex, n.cy + ey)
        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.surfaceAlt, 55)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
        dashPaint.color = withAlpha(theme.muted, 160)
        dashPaint.strokeWidth = dp(1.2f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), dashPaint)
        dashPaint.pathEffect = null
        drawTracked(
            canvas, displayLabel(n), n.cx, n.cy, min(H * 0.019f, ey * 0.6f),
            withAlpha(theme.muted, 220), H * 0.0035f, true,
        )
    }

    private fun drawDpad(canvas: Canvas, n: Node) {
        val r = n.r
        val accent = theme.accent

        fillPaint.shader = null
        fillPaint.color = theme.surface
        canvas.drawCircle(n.cx, n.cy, r, fillPaint)

        strokePaint.color = withAlpha(theme.text, 30)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(n.cx, n.cy, r - dp(0.6f), strokePaint)

        strokePaint.color = withAlpha(accent, 80)
        strokePaint.strokeWidth = dp(2f)
        rect.set(n.cx - r + dp(2f), n.cy - r + dp(2f), n.cx + r - dp(2f), n.cy + r - dp(2f))
        canvas.drawArc(rect, 205f, 130f, false, strokePaint)

        val armT = r * 0.30f

        fun arm(cx: Float, cy: Float, hw: Float, hh: Float, pressed: Boolean, dx: Float, dy: Float) {
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            if (pressed) {
                glowPaint.color = withAlpha(accent, 55)
                rect.set(cx - hw - dp(4f), cy - hh - dp(4f), cx + hw + dp(4f), cy + hh + dp(4f))
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, glowPaint)
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            fillPaint.color = if (pressed) accent else theme.surfaceAlt
            canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, fillPaint)
            strokePaint.color = if (pressed) withAlpha(accent, 220) else withAlpha(theme.text, 26)
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, strokePaint)
            drawArrow(canvas, cx, cy, r, dx, dy, if (pressed) theme.bgBottom else withAlpha(theme.text, 150))
        }

        arm(n.cx, n.cy - r * 0.55f, armT, r * 0.36f, n.up, 0f, -1f)
        arm(n.cx, n.cy + r * 0.55f, armT, r * 0.36f, n.down, 0f, 1f)
        arm(n.cx - r * 0.55f, n.cy, r * 0.36f, armT, n.left, -1f, 0f)
        arm(n.cx + r * 0.55f, n.cy, r * 0.36f, armT, n.right, 1f, 0f)

        fillPaint.color = theme.surfaceAlt
        canvas.drawCircle(n.cx, n.cy, r * 0.19f, fillPaint)
        fillPaint.color = if (n.pointer != -1) accent else withAlpha(accent, 130)
        canvas.drawCircle(n.cx, n.cy, r * 0.055f, fillPaint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, color: Int) {
        path.reset()
        val ax = cx + dx * r * 0.1f
        val ay = cy + dy * r * 0.1f
        val s = r * 0.11f
        if (dy != 0f) {
            val dir = if (dy < 0) -1f else 1f
            path.moveTo(ax, ay + s * dir * 1.2f)
            path.lineTo(ax - s, ay - s * dir * 0.5f)
            path.lineTo(ax + s, ay - s * dir * 0.5f)
        } else {
            val dir = if (dx < 0) -1f else 1f
            path.moveTo(ax + s * dir * 1.2f, ay)
            path.lineTo(ax - s * dir * 0.5f, ay - s)
            path.lineTo(ax - s * dir * 0.5f, ay + s)
        }
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    private fun drawStick(canvas: Canvas, n: Node) {
        val active = n.pointer != -1
        val accent = if (n.kind == K_STICK_L) theme.accent else theme.accent2
        val r = n.r

        fillPaint.shader = null
        fillPaint.color = theme.surface
        canvas.drawCircle(n.cx, n.cy, r, fillPaint)

        if (active) {
            strokePaint.color = withAlpha(accent, 40)
            strokePaint.strokeWidth = dp(7f)
            canvas.drawCircle(n.cx, n.cy, r - dp(3f), strokePaint)
        }
        strokePaint.color = withAlpha(accent, if (active) 230 else 70)
        strokePaint.strokeWidth = if (active) dp(2.4f) else dp(1.3f)
        canvas.drawCircle(n.cx, n.cy, r - dp(1f), strokePaint)

        strokePaint.color = withAlpha(theme.text, 26)
        strokePaint.strokeWidth = dp(1f)
        for (i in 0 until 4) {
            val a = Math.toRadians(45.0 + i * 90.0).toFloat()
            val c = cos(a)
            val s = sin(a)
            canvas.drawLine(
                n.cx + c * r * 0.82f, n.cy + s * r * 0.82f,
                n.cx + c * r * 0.93f, n.cy + s * r * 0.93f,
                strokePaint,
            )
        }

        strokePaint.color = withAlpha(theme.text, 18)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawLine(n.cx - r * 0.92f, n.cy, n.cx + r * 0.92f, n.cy, strokePaint)
        canvas.drawLine(n.cx, n.cy - r * 0.92f, n.cx, n.cy + r * 0.92f, strokePaint)

        val kx = n.cx + n.kx * r
        val ky = n.cy + n.ky * r
        val kr = r * 0.52f

        if (active) {
            glowPaint.color = withAlpha(accent, 50)
            canvas.drawCircle(kx, ky, kr * 1.3f, glowPaint)
        }
        fillPaint.color = if (active) blend(theme.surfaceAlt, accent, 0.20f) else theme.surfaceAlt
        canvas.drawCircle(kx, ky, kr, fillPaint)
        strokePaint.color = if (active) withAlpha(accent, 240) else withAlpha(theme.text, 55)
        strokePaint.strokeWidth = if (active) dp(2.2f) else dp(1.3f)
        canvas.drawCircle(kx, ky, kr, strokePaint)

        fillPaint.color = if (active) accent else withAlpha(accent, 170)
        canvas.drawCircle(kx, ky, kr * 0.16f, fillPaint)

        if (!active) {
            drawTracked(
                canvas,
                if (n.kind == K_STICK_L) "L STICK" else "R STICK",
                n.cx, n.cy + r * 0.72f, r * 0.105f,
                withAlpha(theme.text, 95),
                r * 0.028f, false,
            )
        }
    }
    private fun drawFace(canvas: Canvas, n: Node) {
        val i = n.faceIdx
        val accent = faceColor(i)
        val p = n.pressT
        val r = n.r * (1f + 0.055f * p)
        val cx = n.cx
        val cy = n.cy

        if (p > 0.01f) {
            glowPaint.color = withAlpha(accent, (70 * p).toInt())
            canvas.drawCircle(cx, cy, n.r * (1.10f + 0.22f * p), glowPaint)
        }
        fillPaint.shader = null
        fillPaint.color = blend(theme.surface, accent, 0.30f * p)
        canvas.drawCircle(cx, cy, r, fillPaint)

        strokePaint.color = withAlpha(accent, (150 + 105 * p).toInt())
        strokePaint.strokeWidth = dp(1.6f + 1.2f * p)
        canvas.drawCircle(cx, cy, r - dp(0.7f), strokePaint)

        // Faint inner ring adds the glassy depth the concept art has.
        strokePaint.color = withAlpha(theme.text, (18 + 24 * p).toInt())
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r * 0.80f, strokePaint)

        val ink = if (p > 0.55f) theme.bgBottom else accent
        if (faceLabels == FaceLabels.PLAYSTATION) {
            drawGlyph(canvas, i, cx, cy, r, ink)
        } else {
            val letter = when (i) {
                0 -> "Y"
                1 -> "B"
                2 -> "A"
                else -> "X"
            }
            drawTracked(canvas, letter, cx, cy, r * 0.95f, ink, 0f, true)
        }
    }

    private fun faceColor(i: Int): Int = when (i) {
        0 -> theme.triangle
        1 -> theme.circle
        2 -> theme.cross
        else -> theme.square
    }

    private fun drawGlyph(canvas: Canvas, i: Int, cx: Float, cy: Float, r: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = r * 0.20f
        val g = r * 0.40f
        when (i) {
            0 -> {
                path.reset()
                path.moveTo(cx, cy - g * 1.12f)
                path.lineTo(cx + g * 1.02f, cy + g * 0.72f)
                path.lineTo(cx - g * 1.02f, cy + g * 0.72f)
                path.close()
                canvas.drawPath(path, glyphPaint)
            }
            1 -> canvas.drawCircle(cx, cy, g, glyphPaint)
            2 -> {
                canvas.drawLine(cx - g, cy - g, cx + g, cy + g, glyphPaint)
                canvas.drawLine(cx + g, cy - g, cx - g, cy + g, glyphPaint)
            }
            else -> {
                rect.set(cx - g, cy - g, cx + g, cy + g)
                canvas.drawRect(rect, glyphPaint)
            }
        }
    }

    private fun drawPill(canvas: Canvas, n: Node) {
        val accent = theme.accent
        val pressed = n.pressed
        fillPaint.shader = null

        if (n.kind == K_PS) {
            if (pressed) {
                glowPaint.color = withAlpha(accent, 70)
                canvas.drawCircle(n.cx, n.cy, n.r * 1.22f, glowPaint)
            }
            fillPaint.color = if (pressed) blend(theme.surface, accent, 0.45f) else theme.surface
            canvas.drawCircle(n.cx, n.cy, n.r, fillPaint)
            strokePaint.color = if (pressed) accent else withAlpha(theme.text, 48)
            strokePaint.strokeWidth = if (pressed) dp(2.2f) else dp(1.3f)
            canvas.drawCircle(n.cx, n.cy, n.r - dp(0.6f), strokePaint)
            drawTracked(
                canvas, n.label, n.cx, n.cy, n.r * 0.60f,
                if (pressed) theme.bgBottom else theme.textDim, n.r * 0.08f, true,
            )
            return
        }

        rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        val radius = if (n.hh > n.hw) min(n.hw, n.hh) * 0.55f else n.hh * 0.55f

        if (pressed) {
            glowPaint.color = withAlpha(accent, 55)
            rect.set(
                n.cx - n.hw - dp(3f), n.cy - n.hh - dp(3f),
                n.cx + n.hw + dp(3f), n.cy + n.hh + dp(3f),
            )
            canvas.drawRoundRect(rect, radius + dp(3f), radius + dp(3f), glowPaint)
            rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        }
        fillPaint.color = if (pressed) blend(theme.surface, accent, 0.42f) else theme.surface
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        strokePaint.color = if (pressed) accent else withAlpha(theme.text, 40)
        strokePaint.strokeWidth = if (pressed) dp(2f) else dp(1.2f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val label = displayLabel(n)
        val size = fitTrackedSize(label, n.hw * 1.6f, min(n.hh * 1.05f, n.hw * 0.60f))
        drawTracked(
            canvas, label, n.cx, n.cy, size,
            if (pressed) theme.bgBottom else theme.textDim, size * 0.09f, true,
        )
    }

    private fun displayLabel(n: Node): String {
        if (faceLabels == FaceLabels.XBOX) {
            return when (n.key) {
                "l1" -> "LB"
                "l2" -> "LT"
                "r1" -> "RB"
                "r2" -> "RT"
                else -> n.label
            }
        }
        return n.label
    }

    // ------------------------------------------------------------------- HUD
    private fun drawHud(canvas: Canvas) {
        val h = H * 0.066f
        var textSize = H * 0.026f
        var tracking = textSize * 0.10f
        var tw = trackedWidth(statusText, textSize, tracking, true)

        // Long server names must not push the pill past the screen edges.
        val maxText = W * 0.82f - h * 1.42f
        if (tw > maxText && tw > 0f) {
            textSize *= maxText / tw
            tracking = textSize * 0.10f
            tw = trackedWidth(statusText, textSize, tracking, true)
        }

        val w = h * 0.62f + tw + h * 0.80f
        hudRect.set(W / 2f - w / 2f, H * 0.030f, W / 2f + w / 2f, H * 0.030f + h)

        if (hudPressed) {
            glowPaint.color = withAlpha(theme.accent, 50)
            rect.set(hudRect.left - dp(4f), hudRect.top - dp(4f), hudRect.right + dp(4f), hudRect.bottom + dp(4f))
            canvas.drawRoundRect(rect, h / 2f, h / 2f, glowPaint)
        }

        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.surface, 232)
        canvas.drawRoundRect(hudRect, h / 2f, h / 2f, fillPaint)
        strokePaint.color = if (hudPressed) withAlpha(theme.accent, 220) else withAlpha(theme.text, 34)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(hudRect, h / 2f, h / 2f, strokePaint)

        val dotX = hudRect.left + h * 0.38f
        val dotR = h * 0.10f
        glowPaint.color = withAlpha(statusColor(), 70)
        canvas.drawCircle(dotX, hudRect.centerY(), dotR * 1.9f, glowPaint)
        fillPaint.color = statusColor()
        canvas.drawCircle(dotX, hudRect.centerY(), dotR, fillPaint)

        // One-shot ripple when the status text changes.
        if (statusPulse > 0f) {
            strokePaint.color = withAlpha(statusColor(), (170 * statusPulse).toInt())
            strokePaint.strokeWidth = dp(1.2f)
            canvas.drawCircle(
                dotX, hudRect.centerY(),
                dotR * (1.3f + 3.0f * (1f - statusPulse)), strokePaint,
            )
        }

        drawTracked(
            canvas, statusText, hudRect.left + h * 0.62f + tw / 2f, hudRect.centerY(),
            textSize, theme.text, tracking, true,
        )
        drawGear(
            canvas, hudRect.right - h * 0.40f, hudRect.centerY(),
            h * 0.19f, withAlpha(theme.text, 150),
        )
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, r * 0.55f, strokePaint)
        for (i in 0 until 6) {
            val a = (Math.PI / 3.0 * i).toFloat()
            val c = cos(a)
            val s = sin(a)
            canvas.drawLine(
                cx + c * r * 0.75f, cy + s * r * 0.75f,
                cx + c * r * 1.05f, cy + s * r * 1.05f,
                strokePaint,
            )
        }
    }

    // --------------------------------------------------------------- text
    private fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean): Float {
        textPaint.textSize = size
        textPaint.typeface = if (bold) boldFace else labelFace
        var w = 0f
        for (i in text.indices) {
            w += textPaint.measureText(text, i, i + 1)
            if (i < text.length - 1) w += tracking
        }
        return w
    }

    private fun fitTrackedSize(text: String, maxWidth: Float, maxHeight: Float): Float {
        if (text.isEmpty()) return maxHeight
        val w = trackedWidth(text, maxHeight, maxHeight * 0.09f, true)
        if (w <= maxWidth || w <= 0f) return maxHeight
        return (maxHeight * maxWidth / w).coerceAtLeast(1f)
    }

    private fun drawTracked(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        tracking: Float,
        bold: Boolean,
    ) {
        if (text.isEmpty() || size <= 0f) return
        textPaint.textSize = size
        textPaint.typeface = if (bold) boldFace else labelFace
        textPaint.color = color
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        var x = cx - trackedWidth(text, size, tracking, bold) / 2f
        for (i in text.indices) {
            val cw = textPaint.measureText(text, i, i + 1)
            canvas.drawText(text, i, i + 1, x, baseline, textPaint)
            x += cw + tracking
        }
    }

    // --------------------------------------------------------------- edit overlay
    private fun drawEditOverlay(canvas: Canvas) {
        for (n in nodes) {
            if (n.hidden) continue
            val ex = n.extW() + dp(7f)
            val ey = n.extH() + dp(7f)
            rect.set(n.cx - ex, n.cy - ey, n.cx + ex, n.cy + ey)
            val selected = n === editNode

            if (selected) {
                fillPaint.shader = null
                fillPaint.color = withAlpha(theme.accent, 24)
                canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
            }
            dashPaint.color = withAlpha(if (selected) theme.accent else theme.muted, if (selected) 230 else 105)
            dashPaint.strokeWidth = if (selected) dp(1.8f) else dp(1.1f)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), dashPaint)
            dashPaint.pathEffect = null

            val label = "${n.label}  ${(n.scale * 100f).toInt()}%"
            val size = H * 0.017f
            val tracking = size * 0.09f
            val tw = trackedWidth(label, size, tracking, true)
            val lx = rect.centerX().coerceIn(tw / 2f + dp(4f), W - tw / 2f - dp(4f))
            val ly = if (rect.top > H * 0.10f) rect.top - dp(10f) else rect.bottom + dp(14f)
            drawTracked(canvas, label, lx, ly, size, withAlpha(theme.text, 195), tracking, true)
        }

        drawTracked(
            canvas, "DRAG TO MOVE   ·   PINCH TO RESIZE   ·   HOLD TO HIDE",
            W / 2f, H * 0.44f, H * 0.019f, withAlpha(theme.muted, 225), H * 0.0048f, true,
        )
    }
    // ---------------------------------------------------------------- status
    fun setStatus(text: String, kind: Int) {
        if (text == statusText && kind == statusKind) return
        // The text carries live RTT and battery, so it changes constantly.
        // Only a state change (idle -> linking -> connected) deserves the
        // ripple, otherwise the dot would pulse non-stop.
        val stateChanged = kind != statusKind
        statusText = text
        statusKind = kind
        if (stateChanged) statusPulse = 1f
        startAnimating()
        if (!editMode) invalidate()
    }

    private fun statusColor(): Int = when (statusKind) {
        STATUS_OK -> theme.ok
        STATUS_WARN -> theme.warn
        else -> theme.muted
    }

    fun resetInputs() {
        for (n in nodes) {
            n.pressed = false
            n.pointer = -1
            n.up = false
            n.down = false
            n.left = false
            n.right = false
            n.kx = 0f
            n.ky = 0f
        }
        hudPointer = -1
        hudPressed = false
        publish()
        startAnimating()
    }

    // ----------------------------------------------------------------- touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            handleEditTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                grab(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    dragNode(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                releaseNode(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> resetInputs()
        }
        return true
    }

    private fun grab(id: Int, x: Float, y: Float) {
        // The four face buttons sit close together, so hit-testing picks the
        // nearest centre instead of whichever node happens to come first.
        val face = nearestFace(x, y)
        if (face != null && face.pointer == -1) {
            face.pointer = id
            face.pressed = true
            tap()
            publish()
            invalidate()
            return
        }

        for (n in playOrder) {
            if (n.kind == K_FACE) continue
            if (n.hidden || n.pointer != -1 || !n.hit(x, y)) continue
            n.pointer = id
            when (n.kind) {
                K_DPAD -> moveDpad(n, x, y)
                K_STICK_L, K_STICK_R -> moveStick(n, x, y)
                else -> {
                    n.pressed = true
                    tap()
                    publish()
                }
            }
            invalidate()
            return
        }
        if (hudPointer == -1 && hudRect.contains(x, y)) {
            hudPointer = id
            hudPressed = true
            tap()
            invalidate()
        }
    }

    private fun dragNode(id: Int, x: Float, y: Float) {
        for (n in playOrder) {
            if (n.pointer != id) continue
            when (n.kind) {
                K_DPAD -> moveDpad(n, x, y)
                K_STICK_L, K_STICK_R -> moveStick(n, x, y)
                K_FACE -> rollFace(n, id, x, y)
                else -> {
                    val inside = n.hit(x, y)
                    if (inside != n.pressed) {
                        n.pressed = inside
                        publish()
                    }
                }
            }
            return
        }
        if (hudPointer == id) {
            hudPressed = hudRect.contains(x, y)
            invalidate()
        }
    }

    private fun releaseNode(id: Int, x: Float, y: Float) {
        for (n in playOrder) {
            if (n.pointer != id) continue
            n.pointer = -1
            when (n.kind) {
                K_STICK_L, K_STICK_R -> {
                    n.kx = 0f
                    n.ky = 0f
                }
                K_DPAD -> {
                    n.up = false
                    n.down = false
                    n.left = false
                    n.right = false
                }
                else -> n.pressed = false
            }
            publish()
            return
        }
        if (hudPointer == id) {
            hudPointer = -1
            hudPressed = false
            invalidate()
            if (hudRect.contains(x, y)) onOpenSettings?.invoke()
        }
    }

    /**
     * A thumb that stays down rolls across the four separate buttons: the
     * press moves to whichever button centre is nearest the finger.
     */
    private fun rollFace(n: Node, id: Int, x: Float, y: Float) {
        val target = nearestFace(x, y, except = n)
        if (target === n) {
            if (!n.pressed) {
                n.pressed = true
                tap()
                publish()
            }
            return
        }
        if (target != null) {
            n.pressed = false
            n.pointer = -1
            target.pointer = id
            target.pressed = true
            tap()
            publish()
        } else if (n.pressed) {
            n.pressed = false
            publish()
        }
    }

    /** Nearest visible face button within reach, or null. */
    private fun nearestFace(x: Float, y: Float, except: Node? = null): Node? {
        var best: Node? = null
        var bestD = Float.MAX_VALUE
        for (n in faceNodes) {
            if (n.hidden) continue
            // Skip buttons another finger already holds, otherwise a second
            // thumb landing on the cluster steals the press and gets nothing.
            if (n.pointer != -1 && n !== except) continue
            val d = hypot(x - n.cx, y - n.cy)
            if (d <= n.r * 1.55f && d < bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    private fun moveStick(n: Node, x: Float, y: Float) {
        var dx = x - n.cx
        var dy = y - n.cy
        val d = hypot(dx, dy)
        if (d > n.r) {
            dx = dx / d * n.r
            dy = dy / d * n.r
        }
        n.kx = dx / n.r
        n.ky = dy / n.r
        publish()
    }

    private fun moveDpad(n: Node, x: Float, y: Float) {
        val dx = x - n.cx
        val dy = y - n.cy
        val t = n.r * 0.33f
        val up = dy < -t
        val down = dy > t
        val left = dx < -t
        val right = dx > t
        if (up != n.up || down != n.down || left != n.left || right != n.right) {
            n.up = up
            n.down = down
            n.left = left
            n.right = right
            publish()
        }
    }

    // --------------------------------------------------------------- edit touch
    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                val n = nodeAt(x, y) ?: return
                editNode = n
                editPointer = event.getPointerId(i)
                if (n.hidden) {
                    n.hidden = false
                    onToast?.invoke("${n.label} restored")
                }
                dragOffX = n.cx - x
                dragOffY = n.cy - y
                downX = x
                downY = y
                moved = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 520L)
                onHaptic?.invoke(8)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (editNode != null && pinchPointer == -1) {
                    val i = event.actionIndex
                    pinchPointer = event.getPointerId(i)
                    val ei = event.findPointerIndex(editPointer)
                    if (ei >= 0) {
                        pinchStartDist = hypot(
                            event.getX(i) - event.getX(ei),
                            event.getY(i) - event.getY(ei),
                        ).coerceAtLeast(1f)
                    }
                    pinchStartScale = editNode?.scale ?: 1f
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val n = editNode ?: return
                if (pinchPointer != -1) {
                    val pi = event.findPointerIndex(pinchPointer)
                    val ei = event.findPointerIndex(editPointer)
                    if (pi >= 0 && ei >= 0) {
                        val d = hypot(
                            event.getX(pi) - event.getX(ei),
                            event.getY(pi) - event.getY(ei),
                        )
                        n.scale = (pinchStartScale * (d / pinchStartDist)).coerceIn(0.55f, 1.9f)
                        if (n.kind == K_FACE) n.customized = true
                        layoutControls()
                        invalidate()
                    }
                    return
                }
                val ei = event.findPointerIndex(editPointer)
                if (ei < 0) return
                val x = event.getX(ei)
                val y = event.getY(ei)
                if (!moved && hypot(x - downX, y - downY) > dp(10f)) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
                if (moved) {
                    n.fx = ((x + dragOffX) / W).coerceIn(0f, 1f)
                    n.fy = ((y + dragOffY) / H).coerceIn(0f, 1f)
                    if (n.kind == K_FACE) n.customized = true
                    layoutControls()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == pinchPointer) {
                    pinchPointer = -1
                } else if (id == editPointer) {
                    finishEdit()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == pinchPointer) pinchPointer = -1
                if (id == editPointer) finishEdit()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                editNode = null
                editPointer = -1
                pinchPointer = -1
                invalidate()
            }
        }
    }

    private fun finishEdit() {
        removeCallbacks(longPressRunnable)
        editNode = null
        editPointer = -1
        pinchPointer = -1
        moved = false
        layoutControls()
        onLayoutChanged?.invoke()
        invalidate()
    }

    private fun nodeAt(x: Float, y: Float): Node? {
        for (i in nodes.indices.reversed()) {
            val n = nodes[i]
            val ex = n.extW() + dp(10f)
            val ey = n.extH() + dp(10f)
            if (x >= n.cx - ex && x <= n.cx + ex && y >= n.cy - ey && y <= n.cy + ey) return n
        }
        return null
    }

    // ---------------------------------------------------------------- publish
    private fun publish(redraw: Boolean = true) {
        var b = 0
        for (n in nodes) {
            when (n.kind) {
                K_DPAD -> {
                    if (n.up) b = b or (1 shl Proto.UP)
                    if (n.down) b = b or (1 shl Proto.DOWN)
                    if (n.left) b = b or (1 shl Proto.LEFT)
                    if (n.right) b = b or (1 shl Proto.RIGHT)
                }
                else -> if (n.pressed) b = b or (1 shl n.bit)
            }
        }

        state.buttons = b
        val (lx, ly) = axes(leftStick)
        val (rx, ry) = axes(rightStick)
        state.lx = lx
        state.ly = ly
        state.rx = rx
        state.ry = ry
        state.l2 = if (b and (1 shl Proto.L2) != 0) 255 else 0
        state.r2 = if (b and (1 shl Proto.R2) != 0) 255 else 0
        state.bump()

        if (redraw) {
            invalidate()
            startAnimating()
        }
    }

    private fun axes(n: Node): Pair<Int, Int> {
        val mag = hypot(n.kx, n.ky)
        if (mag <= deadzone) return 0 to 0
        val scaled = ((mag - deadzone) / (1f - deadzone)).coerceAtMost(1f)
        val ux = n.kx / mag * scaled
        val uy = n.ky / mag * scaled
        return (ux * 32767f).toInt() to (-uy * 32767f).toInt()
    }

    // ----------------------------------------------------------------- layout persistence
    fun layoutSnapshot(): String {
        val sb = StringBuilder()
        for (n in nodes) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(n.key).append(':')
                .append((n.fx * 10000f).toInt()).append(',')
                .append((n.fy * 10000f).toInt()).append(',')
                .append((n.scale * 1000f).toInt()).append(',')
                .append(if (n.hidden) 1 else 0)
        }
        return sb.toString()
    }

    fun applyLayout(data: String?) {
        if (data.isNullOrBlank()) return
        val parsed = HashMap<String, IntArray>()
        for (chunk in data.split(';')) {
            val pair = chunk.split(':', limit = 2)
            if (pair.size != 2) continue
            val v = pair[1].split(',')
            if (v.size < 3) continue
            parsed[pair[0]] = intArrayOf(
                v[0].toIntOrNull() ?: 0,
                v[1].toIntOrNull() ?: 0,
                v[2].toIntOrNull() ?: 1000,
                if (v.getOrNull(3) == "1") 1 else 0,
            )
        }

        // 1.0 saved the four face buttons as a single "face" node.
        parsed["face"]?.let { old ->
            legacyFace = floatArrayOf(
                old[0] / 10000f,
                old[1] / 10000f,
                (old[2] / 1000f).coerceIn(0.55f, 1.9f),
                old[3].toFloat(),
            )
        }

        for ((key, v) in parsed) {
            if (key == "face") continue
            val n = nodes.firstOrNull { it.key == key } ?: continue
            n.fx = (v[0] / 10000f).coerceIn(0f, 1f)
            n.fy = (v[1] / 10000f).coerceIn(0f, 1f)
            n.scale = (v[2] / 1000f).coerceIn(0.55f, 1.9f)
            n.hidden = v[3] == 1
            n.customized = true
        }
        layoutControls()
        invalidate()
    }

    fun resetLayout() {
        legacyFace = null
        for (n in nodes) {
            n.scale = 1f
            n.hidden = false
            if (n.kind == K_FACE) {
                n.customized = false
            } else {
                n.fx = n.defFx
                n.fy = n.defFy
            }
        }
        layoutControls()
        invalidate()
    }

    fun showAll() {
        for (n in nodes) n.hidden = false
        invalidate()
    }

    // ----------------------------------------------------------------- misc
    private fun tap() {
        if (hapticsEnabled) onHaptic?.invoke(12)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Squarer screens (4:3 tablets) shrink the big controls to avoid collisions. */
    private fun aspectScale(): Float = min(1f, (W / H) / 1.75f)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_OK = 1
        const val STATUS_WARN = 2

        /** Face buttons are laid out ±this many screen-heights from the anchor. */
        private const val FACE_SPREAD = 0.115f

        /** Press spring, tuned per 60 Hz frame (slightly under-damped). */
        private const val PRESS_STIFFNESS = 0.30f
        private const val PRESS_DAMPING = 0.70f
    }
}
