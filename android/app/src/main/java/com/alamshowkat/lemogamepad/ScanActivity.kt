package com.alamshowkat.lemogamepad

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Camera QR scanner used to pair with the PC in one tap.
 *
 * Deliberately uses the (deprecated but dependency-free) Camera1 API and the
 * bundled ZXing core decoder, so the app keeps working without AndroidX or
 * Google Play Services.
 */
class ScanActivity : Activity() {

    private lateinit var root: FrameLayout
    private var scanner: QrScannerView? = null
    private var hint: TextView? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val title = TextView(this).apply {
            text = "SCAN PAIRING CODE"
            setTextColor(Color.WHITE)
            textSize = 13f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
        )

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(6)
                marginEnd = dp(6)
            },
        )

        hint = TextView(this).apply {
            text = "Point the camera at the QR code in the lemoGamePad-server window"
            setTextColor(Color.argb(210, 230, 240, 235))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(16))
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            attachScanner()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun attachScanner() {
        if (scanner != null) return
        val sv = QrScannerView(this)
        sv.onResult = { raw -> handleCode(raw) }
        sv.onFailure = { message -> hint?.text = message }
        scanner = sv
        root.addView(sv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ScanOverlay(this), 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun handleCode(raw: String) {
        if (handled) return
        val parsed = parse(raw)
        if (parsed == null) {
            Toast.makeText(this, "Not a lemoGamePad pairing code", Toast.LENGTH_SHORT).show()
            scanner?.resumeAfterMiss()
            return
        }
        handled = true
        val (host, port, name) = parsed
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port).putExtra(EXTRA_NAME, name),
        )
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            attachScanner()
        } else {
            hint?.text = "Camera permission is needed to scan. Enter the PC address manually in Settings."
        }
    }

    override fun onPause() {
        super.onPause()
        scanner?.pause()
    }

    override fun onResume() {
        super.onResume()
        scanner?.resume()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        private const val REQ_CAMERA = 91

        /**
         * Accepts "vpad://connect?h=1.2.3.4&p=9876&n=PC", "h=...&p=..."
         * and plain "1.2.3.4:9876".
         */
        fun parse(text: String): Triple<String, Int, String>? {
            var s = text.trim()
            if (s.isEmpty()) return null
            for (prefix in listOf("vpad://", "lemogamepad://", "lemogamepad-ultimate://")) {
                if (s.startsWith(prefix, ignoreCase = true)) {
                    s = s.substring(prefix.length)
                    break
                }
            }
            s = s.removePrefix("connect")
            s = s.trimStart('?', '/')

            if (s.contains('=')) {
                val values = HashMap<String, String>()
                for (part in s.split('&')) {
                    val kv = part.split('=', limit = 2)
                    if (kv.size == 2) values[kv[0].lowercase()] = Uri.decode(kv[1])
                }
                val host = values["h"] ?: values["host"] ?: return null
                val port = values["p"]?.toIntOrNull() ?: values["port"]?.toIntOrNull() ?: Proto.PORT
                val name = values["n"] ?: values["name"] ?: ""
                if (host.isBlank()) return null
                return Triple(host, port.coerceIn(1, 65535), name)
            }

            val bits = s.split(':')
            if (bits.size == 2 && bits[0].isNotBlank()) {
                val port = bits[1].toIntOrNull() ?: return null
                return Triple(bits[0], port.coerceIn(1, 65535), "")
            }
            return null
        }
    }
}

/** Camera preview that decodes QR frames off the UI thread. */
@Suppress("DEPRECATION")
private class QrScannerView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Camera.PreviewCallback {

    var onResult: ((String) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null

    private var camera: Camera? = null
    private var decoding = false
    private var frameCount = 0
    private var wantPreview = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
            ),
        )
    }
    private val worker = Executors.newSingleThreadExecutor()

    init {
        holder.addCallback(this)
        @Suppress("DEPRECATION")
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        wantPreview = true
        open()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (camera == null && wantPreview) open()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        wantPreview = false
        close()
    }

    private fun open() {
        if (camera != null) return
        try {
            val c = Camera.open()
            camera = c
            c.setPreviewDisplay(holder)
            c.setDisplayOrientation(displayOrientation())

            val params = c.parameters
            val sizes = params.supportedPreviewSizes ?: emptyList()
            val chosen = sizes
                .filter { it.width in 640..1280 }
                .minByOrNull { abs(it.width - 960) }
                ?: sizes.lastOrNull()
            if (chosen != null) {
                params.setPreviewSize(chosen.width, chosen.height)
            }
            try {
                if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
            } catch (_: Exception) {
            }
            try {
                c.parameters = params
            } catch (_: Exception) {
            }

            c.setPreviewCallback(this)
            c.startPreview()
            onFailure?.invoke("Point the camera at the QR code in the lemoGamePad-server window")
        } catch (e: Exception) {
            camera = null
            onFailure?.invoke("Camera unavailable. Enter the PC address manually in Settings.")
        }
    }

    private fun close() {
        val c = camera ?: return
        camera = null
        try {
            c.setPreviewCallback(null)
            c.stopPreview()
        } catch (_: Exception) {
        }
        try {
            c.release()
        } catch (_: Exception) {
        }
    }

    fun pause() {
        wantPreview = false
        close()
    }

    fun resume() {
        wantPreview = true
        if (holder.surface?.isValid == true) open()
    }

    fun resumeAfterMiss() {
        decoding = false
        if (camera == null && wantPreview && holder.surface?.isValid == true) open()
    }

    /** Rotates the on-screen preview to match the current device rotation. */
    @Suppress("DEPRECATION")
    private fun displayOrientation(): Int {
        val info = Camera.CameraInfo()
        try {
            Camera.getCameraInfo(0, info)
        } catch (_: Exception) {
            return 0
        }
        val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation
            ?: Surface.ROTATION_0
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        val c = camera ?: return
        val bytes = data ?: return
        if (decoding) return
        frameCount++
        if (frameCount % 2 != 0) return

        val size = try {
            c.parameters.previewSize
        } catch (_: Exception) {
            null
        } ?: return
        if (size.width <= 0 || size.height <= 0) return
        if (bytes.size < size.width * size.height) return

        decoding = true
        val copy = bytes.copyOf()
        val w = size.width
        val h = size.height

        worker.execute {
            try {
                val source = PlanarYUVLuminanceSource(copy, w, h, 0, 0, w, h, false)
                val binary = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decodeWithState(binary)
                post {
                    if (isAttachedToWindow) {
                        close()
                        onResult?.invoke(result.text)
                    }
                }
            } catch (_: Exception) {
                // No code in this frame.
            } finally {
                reader.reset()
                decoding = false
            }
        }
    }

    @Suppress("unused")
    private fun distanceToCentre(x: Float, y: Float): Float = hypot(x - width / 2f, y - height / 2f)
}

/** Dimmed frame with animated scan line and neon corner brackets. */
private class ScanOverlay(context: Context) : View(context) {

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val box = RectF()

    private val accent = Color.rgb(62, 240, 140)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val side = min(w, h) * 0.52f
        val cx = w / 2f
        val cy = h / 2f
        box.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)

        // Dim everything except the scan square.
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRoundRect(box, dp(18f), dp(18f), Path.Direction.CW)
        scrim.color = Color.argb(120, 0, 0, 0)
        canvas.drawPath(path, scrim)

        stroke.color = Color.argb(150, 255, 255, 255)
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(box, dp(18f), dp(18f), stroke)

        // Corner brackets.
        stroke.color = accent
        stroke.strokeWidth = dp(3f)
        val arm = side * 0.13f
        val r = dp(18f)
        val l = box.left
        val t = box.top
        val rr = box.right
        val b = box.bottom
        canvas.drawLine(l + r, t, l + r + arm, t, stroke)
        canvas.drawLine(l, t + r, l, t + r + arm, stroke)
        canvas.drawLine(rr - r, t, rr - r - arm, t, stroke)
        canvas.drawLine(rr, t + r, rr, t + r + arm, stroke)
        canvas.drawLine(l + r, b, l + r + arm, b, stroke)
        canvas.drawLine(l, b - r, l, b - r - arm, stroke)
        canvas.drawLine(rr - r, b, rr - r - arm, b, stroke)
        canvas.drawLine(rr, b - r, rr, b - r - arm, stroke)

        // Slow sweeping line, like the Scanner app.
        val t01 = (SystemClock.uptimeMillis() % 2400L) / 2400f
        val sweep = t01.let { if (it < 0.5f) it * 2f else (1f - it) * 2f }
        val ly = box.top + dp(6f) + (box.height() - dp(12f)) * sweep
        stroke.color = Color.argb(190, 62, 240, 140)
        stroke.strokeWidth = dp(2f)
        canvas.drawLine(box.left + dp(10f), ly, box.right - dp(10f), ly, stroke)

        postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
