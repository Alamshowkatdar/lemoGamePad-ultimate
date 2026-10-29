package com.alamshowkat.lemogamepad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Network
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Small, draggable picture-in-picture view that shows the PC screen.
 *
 * The PC streams length-prefixed JPEG frames over TCP (video port = UDP port + 1).
 * Tap toggles small/large, dragging moves it, and the corner button closes it.
 */
enum class ScreenQuality(val wireId: Int, val label: String, val shortLabel: String) {
    LOW(0, "LOW", "LOW"),
    BALANCED(1, "BALANCED", "BAL"),
    HIGH(2, "HIGH", "HIGH");

    companion object {
        fun fromId(id: Int): ScreenQuality = values().firstOrNull { it.wireId == id } ?: BALANCED
    }
}

class ScreenView(context: Context) : View(context) {

    var onClose: (() -> Unit)? = null
    var onToggleSize: (() -> Unit)? = null
    var onMoved: ((Float, Float) -> Unit)? = null
    var onQualityUnsupported: (() -> Unit)? = null

    /** Supplies the tether/Wi-Fi network the TCP stream must leave through. */
    var networkProvider: (() -> Network?)? = null

    /** Fallback for tether links Android never exposes as a Network. */
    var localAddressProvider: (() -> String?)? = null

    var accent: Int = Color.rgb(62, 240, 140)

    private var streamHost: String? = null
    private var streamPort = 0

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var frame: Bitmap? = null
    @Volatile private var hint: String = "PC SCREEN"
    @Volatile private var quality: ScreenQuality = ScreenQuality.BALANCED
    @Volatile private var streamFps = 0
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var streamGeneration = 0

    private var worker: Thread? = null
    private var small = true
    private var warnedQualityUnsupported = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val path = Path()
    private val clip = RectF()
    private val dst = RectF()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        isClickable = true
    }

    // ------------------------------------------------------------------ stream
    fun setEndpoint(host: String?, port: Int) {
        if (host == streamHost && port == streamPort) {
            if (host != null && !running) startStreaming()
            return
        }
        stopStreaming()
        streamHost = host
        streamPort = port
        if (host != null) startStreaming()
    }

    fun startStreaming() {
        val host = streamHost ?: return
        if (running) return
        running = true
        val generation = ++streamGeneration
        hint = "CONNECTING"
        postInvalidate()
        worker = thread(name = "vpad-screen", isDaemon = true) { loop(host, streamPort, generation) }
    }

    fun stopStreaming() {
        running = false
        streamGeneration++
        val socket = activeSocket
        activeSocket = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        worker?.interrupt()
        worker = null
        connected = false
        frame = null
        streamFps = 0
        hint = "PC SCREEN"
        postInvalidate()
    }

    fun setQuality(value: ScreenQuality) {
        if (quality == value) return
        quality = value
        postInvalidate()
        val host = streamHost
        if (host != null && running) {
            stopStreaming()
            startStreaming()
        }
    }

    fun setSmall(value: Boolean, animate: Boolean) {
        small = value
        val target = if (value) SMALL_SCALE else 1f
        animate().cancel()
        if (animate && isAttachedToWindow) {
            animate()
                .scaleX(target).scaleY(target)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            scaleX = target
            scaleY = target
        }
    }

    private fun loop(host: String, port: Int, generation: Int) {
        var backoff = 700L
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

        while (running && generation == streamGeneration) {
            var sock: Socket? = null
            try {
                val s = Socket()
                sock = s
                activeSocket = s
                // Android 10+ keeps ordinary sockets on the default network;
                // pin the PiP stream to the same link as the pad.
                var pinned = false
                try {
                    val net = networkProvider?.invoke()
                    if (net != null) {
                        net.bindSocket(s)
                        pinned = true
                    }
                } catch (_: Exception) {
                }
                if (!pinned) {
                    // Tether interfaces often have no Network object, so bind
                    // the client to that interface's own address instead.
                    try {
                        val local = localAddressProvider?.invoke()
                        if (local != null) s.bind(InetSocketAddress(local, 0))
                    } catch (_: Exception) {
                    }
                }
                s.connect(InetSocketAddress(host, port), 2500)
                s.tcpNoDelay = true
                s.soTimeout = 5000

                // Older PC servers ignore this hint and keep their default
                // stream. New servers acknowledge it before sending frames.
                val requestedQuality = quality
                s.getOutputStream().apply {
                    write(byteArrayOf(0x4c, 0x47, 0x56, 0x51, requestedQuality.wireId.toByte()))
                    flush()
                }

                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                var nextLength: Int? = input.readInt()
                val qualitySupported = nextLength == QUALITY_ACK
                if (qualitySupported) {
                    val accepted = input.readUnsignedByte()
                    nextLength = null
                    if (accepted != requestedQuality.wireId) {
                        throw IllegalStateException("PC declined stream quality")
                    }
                } else if (!warnedQualityUnsupported) {
                    warnedQualityUnsupported = true
                    post { onQualityUnsupported?.invoke() }
                }

                connected = true
                hint = "LIVE"
                backoff = 700L
                postInvalidate()

                var fpsWindowStart = SystemClock.elapsedRealtime()
                var fpsFrames = 0
                while (running && generation == streamGeneration) {
                    var len = nextLength ?: input.readInt()
                    nextLength = null
                    if (len <= 0 || len > MAX_FRAME_BYTES) break
                    var bytes = ByteArray(len)
                    input.readFully(bytes)

                    // TCP can deliver older images in a burst after a brief stall.
                    // Drain every complete queued frame and decode only the newest,
                    // so a slow decoder does not keep showing seconds-old gameplay.
                    while (input.available() >= Int.SIZE_BYTES) {
                        val queuedLength = input.readInt()
                        if (queuedLength <= 0 || queuedLength > MAX_FRAME_BYTES) {
                            len = queuedLength
                            nextLength = queuedLength
                            break
                        }
                        if (input.available() < queuedLength) {
                            nextLength = queuedLength
                            break
                        }
                        len = queuedLength
                        bytes = ByteArray(len)
                        input.readFully(bytes)
                    }
                    if (len <= 0 || len > MAX_FRAME_BYTES) break
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, len, opts)
                    if (decoded != null && running && generation == streamGeneration) {
                        frame = decoded
                        fpsFrames++
                        val now = SystemClock.elapsedRealtime()
                        val windowMs = now - fpsWindowStart
                        if (windowMs >= 1000L) {
                            streamFps = (fpsFrames * 1000L / windowMs).toInt()
                            fpsFrames = 0
                            fpsWindowStart = now
                        }
                        postInvalidateOnAnimation()
                    }
                }
            } catch (_: Exception) {
                // PC not streaming yet, or the link dropped: retry with backoff.
            } finally {
                if (activeSocket === sock) {
                    activeSocket = null
                    connected = false
                    hint = if (running) "RECONNECTING" else "PC SCREEN"
                    postInvalidate()
                }
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }

            if (running && generation == streamGeneration) SystemClock.sleep(backoff)
            if (backoff < 4000L) backoff *= 2
        }
    }

    // ------------------------------------------------------------------- draw
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = dp(11f)

        path.reset()
        path.addRoundRect(clip.apply { set(0f, 0f, w, h) }, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        fill.color = Color.argb(238, 5, 8, 7)
        canvas.drawRect(0f, 0f, w, h, fill)

        val bmp = frame
        if (bmp != null) {
            val scale = max(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
        } else {
            // Placeholder: subtle rings + status text.
            stroke.color = withAlpha(accent, 40)
            stroke.strokeWidth = dp(1f)
            canvas.drawCircle(w / 2f, h / 2f, min(w, h) * 0.18f, stroke)
            canvas.drawCircle(w / 2f, h / 2f, min(w, h) * 0.28f, stroke)

            text.color = withAlpha(accent, 190)
            text.textSize = h * 0.10f
            text.textAlign = Paint.Align.CENTER
            val fm = text.fontMetrics
            canvas.drawText(hint, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
            text.textAlign = Paint.Align.LEFT
        }
        canvas.restore()
        if (bmp != null) drawQualityBadge(canvas, w, h)

        stroke.color = withAlpha(accent, if (connected) 210 else 80)
        stroke.strokeWidth = dp(1.4f)
        canvas.drawRoundRect(0.8f, 0.8f, w - 0.8f, h - 0.8f, radius, radius, stroke)

        // "PC" chip
        val chipH = h * 0.14f
        val chipW = chipH * 2.1f
        fill.color = Color.argb(170, 0, 0, 0)
        canvas.drawRoundRect(dp(5f), dp(5f), dp(5f) + chipW, dp(5f) + chipH, chipH / 2f, chipH / 2f, fill)
        text.color = withAlpha(accent, 235)
        text.textSize = chipH * 0.55f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("PC", dp(5f) + chipW / 2f, dp(5f) + chipH * 0.70f, text)
        text.textAlign = Paint.Align.LEFT

        // live dot
        val dotR = h * 0.022f
        fill.color = if (connected) accent else withAlpha(accent, 90)
        canvas.drawCircle(dp(5f) + chipW + dotR * 2.2f, dp(5f) + chipH / 2f, dotR, fill)

        // close button
        val cx = w - closeInset()
        val cy = closeInset()
        val cr = h * 0.085f
        fill.color = Color.argb(150, 0, 0, 0)
        canvas.drawCircle(cx, cy, cr, fill)
        stroke.color = withAlpha(accent, 175)
        stroke.strokeWidth = dp(1.1f)
        canvas.drawCircle(cx, cy, cr, stroke)
        stroke.color = withAlpha(Color.WHITE, 220)
        stroke.strokeWidth = dp(1.4f)
        canvas.drawLine(cx - cr * 0.36f, cy - cr * 0.36f, cx + cr * 0.36f, cy + cr * 0.36f, stroke)
        canvas.drawLine(cx + cr * 0.36f, cy - cr * 0.36f, cx - cr * 0.36f, cy + cr * 0.36f, stroke)
    }

    private fun closeInset(): Float = height * 0.085f + dp(8f)

    private fun drawQualityBadge(canvas: Canvas, w: Float, h: Float) {
        val label = if (streamFps > 0) "${quality.shortLabel} ${streamFps} FPS" else quality.shortLabel
        text.textSize = max(dp(8f), h * 0.075f)
        text.textAlign = Paint.Align.LEFT
        val badgeH = max(dp(15f), h * 0.13f)
        val badgeW = text.measureText(label) + dp(10f)
        val left = dp(5f)
        val top = h - badgeH - dp(5f)
        fill.color = Color.argb(175, 0, 0, 0)
        canvas.drawRoundRect(left, top, left + badgeW, top + badgeH, badgeH / 2f, badgeH / 2f, fill)
        text.color = Color.WHITE
        canvas.drawText(label, left + dp(5f), top + badgeH * 0.68f, text)
    }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                dragging = false
                animate().scaleX(scaleX * 0.97f).scaleY(scaleY * 0.97f).setDuration(90L).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > dp(7f)) dragging = true
                if (dragging) {
                    val pw = (parent as? View)?.width ?: 0
                    val ph = (parent as? View)?.height ?: 0
                    // Clamp the *visible* (scaled) box, not the layout box, so a
                    // shrunken PiP can still be pushed into every corner.
                    val vw = width * scaleX
                    val vh = height * scaleY
                    val overX = (width - vw) / 2f
                    val overY = (height - vh) / 2f
                    x = (startX + dx).coerceIn(-overX, max(-overX, pw - vw - overX))
                    y = (startY + dy).coerceIn(-overY, max(-overY, ph - vh - overY))
                }
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(if (small) SMALL_SCALE else 1f).scaleY(if (small) SMALL_SCALE else 1f)
                    .setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
                if (dragging) {
                    dragging = false
                    val pw = (parent as? View)?.width ?: 0
                    val ph = (parent as? View)?.height ?: 0
                    if (pw > 0 && ph > 0) onMoved?.invoke((x + width / 2f) / pw, (y + height / 2f) / ph)
                } else if (hypot(event.x - (width - closeInset()), event.y - closeInset()) <= dp(22f)) {
                    onClose?.invoke()
                } else {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animate().scaleX(if (small) SMALL_SCALE else 1f).scaleY(if (small) SMALL_SCALE else 1f)
                    .setDuration(140L).start()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        small = !small
        animate().scaleX(if (small) SMALL_SCALE else 1f).scaleY(if (small) SMALL_SCALE else 1f)
            .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
        onToggleSize?.invoke()
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val QUALITY_ACK = -1
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        const val SMALL_SCALE = 0.62f
    }
}
