package com.alamshowkat.lemogamepad

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Shareable layout payloads.
 *
 * The format is deliberately plain text so the browser pad can copy the same
 * code to the clipboard and Android can render it as a QR with the bundled
 * ZXing core jar. No extra dependency, server endpoint or account is needed.
 *
 *   LMGB-LAYOUT-1
 *   <name>
 *   <layoutSnapshot() text>
 */
internal data class SharedLayout(val name: String, val data: String)

internal object LayoutQr {
    private const val PREFIX = "LMGB-LAYOUT-1"
    private const val MAX_NAME = 40

    fun encode(name: String, data: String): String {
        val cleanName = name.trim().replace('\n', ' ').take(MAX_NAME).ifEmpty { "SHARED" }
        return "$PREFIX\n$cleanName\n$data"
    }

    /** Accepts both a QR scan result and a pasted browser share code. */
    fun decode(text: String): SharedLayout? {
        val parts = text.trim().split('\n', limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val name = parts[1].trim().take(MAX_NAME).ifEmpty { "SHARED" }
        val data = parts[2].trim()
        // A layout snapshot is "key:x,y,s,h" chunks separated by ';'. This is
        // enough to reject pairing QR codes and random clipboard contents
        // without trying to parse every numeric field.
        if (!data.contains(':') || !data.contains(',')) return null
        return SharedLayout(name, data)
    }

    /** Renders a white-on-black QR bitmap, including a four-module quiet zone. */
    fun bitmap(text: String, size: Int = 720): Bitmap? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = 4
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // setPixel() would make ~500k JNI calls for the default 720px code.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        out
    } catch (_: Exception) {
        null
    }
}
