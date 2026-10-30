package com.alamshowkat.lemogamepad

import android.graphics.Color

/** How the face buttons are drawn: PlayStation symbols or Xbox letters. */
enum class FaceLabels { PLAYSTATION, XBOX }

/**
 * Everything the renderer needs for one look. All control colours come from
 * here, so adding a theme never touches the drawing code.
 */
data class PadTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bgTop: Int,
    val bgMid: Int,
    val bgBottom: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val textDim: Int,
    val muted: Int,
    val accent: Int,
    val accent2: Int,
    val ok: Int,
    val warn: Int,
    val cross: Int,
    val circle: Int,
    val square: Int,
    val triangle: Int,
) {
    val isLight: Boolean get() = !dark
}

fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
    alpha.coerceIn(0, 255),
    Color.red(color),
    Color.green(color),
    Color.blue(color),
)

fun blend(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt(),
    )
}

/** Intermediate palette used while a theme switch cross-fades. */
fun blendThemes(a: PadTheme, b: PadTheme, t: Float): PadTheme {
    val k = t.coerceIn(0f, 1f)
    fun c(x: Int, y: Int) = blend(x, y, k)
    return PadTheme(
        id = if (k < 0.5f) a.id else b.id,
        name = if (k < 0.5f) a.name else b.name,
        dark = if (k < 0.5f) a.dark else b.dark,
        bgTop = c(a.bgTop, b.bgTop),
        bgMid = c(a.bgMid, b.bgMid),
        bgBottom = c(a.bgBottom, b.bgBottom),
        surface = c(a.surface, b.surface),
        surfaceAlt = c(a.surfaceAlt, b.surfaceAlt),
        text = c(a.text, b.text),
        textDim = c(a.textDim, b.textDim),
        muted = c(a.muted, b.muted),
        accent = c(a.accent, b.accent),
        accent2 = c(a.accent2, b.accent2),
        ok = c(a.ok, b.ok),
        warn = c(a.warn, b.warn),
        cross = c(a.cross, b.cross),
        circle = c(a.circle, b.circle),
        square = c(a.square, b.square),
        triangle = c(a.triangle, b.triangle),
    )
}

/** Branding shown in-app and in the settings footer. */
object Brand {
    const val NAME = "lemoGamePad ultimate"
    const val MARK = "LEMOGAMEPAD ULTIMATE · ALAM SHOWKAT"
    const val AUTHOR = "Alam showkat"

    /** Keep in sync with `versionName` in android/app/build.gradle.kts. */
    const val VERSION = "1.3.7"
}

object Themes {

    /** The concept: near-black glass with a neon-green ring accent. */
    val NEON = PadTheme(
        id = "neon",
        name = "Neon",
        dark = true,
        bgTop = 0xFF0F1A14.toInt(),
        bgMid = 0xFF070C09.toInt(),
        bgBottom = 0xFF030504.toInt(),
        surface = 0xFF0E1712.toInt(),
        surfaceAlt = 0xFF16251D.toInt(),
        text = 0xFFE9FBF1.toInt(),
        textDim = 0xFFB9CFC2.toInt(),
        muted = 0xFF6F8579.toInt(),
        accent = 0xFF3EF08C.toInt(),
        accent2 = 0xFF2ED9C3.toInt(),
        ok = 0xFF3EF08C.toInt(),
        warn = 0xFFFFC46B.toInt(),
        cross = 0xFF52F2A2.toInt(),
        circle = 0xFF5FE3FF.toInt(),
        square = 0xFFBCFF6E.toInt(),
        triangle = 0xFF8CFFD6.toInt(),
    )

    val MIDNIGHT = PadTheme(
        id = "midnight",
        name = "Midnight",
        dark = true,
        bgTop = 0xFF101A2B.toInt(),
        bgMid = 0xFF0A1120.toInt(),
        bgBottom = 0xFF05080F.toInt(),
        surface = 0xFF101A29.toInt(),
        surfaceAlt = 0xFF172741.toInt(),
        text = 0xFFE8EEF9.toInt(),
        textDim = 0xFFC3CEDF.toInt(),
        muted = 0xFF7E8CA3.toInt(),
        accent = 0xFF4EA8FF.toInt(),
        accent2 = 0xFF7C6CFF.toInt(),
        ok = 0xFF45DFA5.toInt(),
        warn = 0xFFFFC46B.toInt(),
        cross = 0xFF5B9BFF.toInt(),
        circle = 0xFFFF5C72.toInt(),
        square = 0xFFFF7BC4.toInt(),
        triangle = 0xFF45DFA5.toInt(),
    )

    val VIOLET = PadTheme(
        id = "violet",
        name = "Violet",
        dark = true,
        bgTop = 0xFF150F22.toInt(),
        bgMid = 0xFF0C0814.toInt(),
        bgBottom = 0xFF05040A.toInt(),
        surface = 0xFF171126.toInt(),
        surfaceAlt = 0xFF241A3A.toInt(),
        text = 0xFFF0EAFF.toInt(),
        textDim = 0xFFCFC4EA.toInt(),
        muted = 0xFF8B7FA8.toInt(),
        accent = 0xFFB18CFF.toInt(),
        accent2 = 0xFFFF7BD5.toInt(),
        ok = 0xFF6EF0C8.toInt(),
        warn = 0xFFFFCF7A.toInt(),
        cross = 0xFF9E7BFF.toInt(),
        circle = 0xFFFF6FAE.toInt(),
        square = 0xFF6EE7FF.toInt(),
        triangle = 0xFF7CFFC4.toInt(),
    )

    val CRIMSON = PadTheme(
        id = "crimson",
        name = "Crimson",
        dark = true,
        bgTop = 0xFF1A0B0E.toInt(),
        bgMid = 0xFF100608.toInt(),
        bgBottom = 0xFF060203.toInt(),
        surface = 0xFF1B1013.toInt(),
        surfaceAlt = 0xFF2C181D.toInt(),
        text = 0xFFFFEDEF.toInt(),
        textDim = 0xFFE3BFC4.toInt(),
        muted = 0xFFA3808A.toInt(),
        accent = 0xFFFF4D67.toInt(),
        accent2 = 0xFFFF9B4D.toInt(),
        ok = 0xFF57E3A0.toInt(),
        warn = 0xFFFFC46B.toInt(),
        cross = 0xFFFF6B7F.toInt(),
        circle = 0xFFFF9B4D.toInt(),
        square = 0xFFFF7BC4.toInt(),
        triangle = 0xFFFFD166.toInt(),
    )

    val RETRO = PadTheme(
        id = "retro",
        name = "Retro",
        dark = true,
        bgTop = 0xFF20242A.toInt(),
        bgMid = 0xFF16191E.toInt(),
        bgBottom = 0xFF0B0D10.toInt(),
        surface = 0xFF2A2F36.toInt(),
        surfaceAlt = 0xFF363C45.toInt(),
        text = 0xFFE9EDF2.toInt(),
        textDim = 0xFFC0C8D2.toInt(),
        muted = 0xFF8A94A0.toInt(),
        accent = 0xFFC7D2E3.toInt(),
        accent2 = 0xFF6F8FBF.toInt(),
        ok = 0xFF45DFA5.toInt(),
        warn = 0xFFFFC46B.toInt(),
        cross = 0xFF5B9BFF.toInt(),
        circle = 0xFFFF5C72.toInt(),
        square = 0xFFFF7BC4.toInt(),
        triangle = 0xFF45DFA5.toInt(),
    )

    val ARCTIC = PadTheme(
        id = "arctic",
        name = "Arctic",
        dark = false,
        bgTop = 0xFFF2F7FC.toInt(),
        bgMid = 0xFFE2EAF3.toInt(),
        bgBottom = 0xFFCBD8E5.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceAlt = 0xFFEAF1F8.toInt(),
        text = 0xFF16202B.toInt(),
        textDim = 0xFF3E4C5C.toInt(),
        muted = 0xFF6C7C8C.toInt(),
        accent = 0xFF0E9F63.toInt(),
        accent2 = 0xFF2C7BE5.toInt(),
        ok = 0xFF0E9F63.toInt(),
        warn = 0xFFC77A12.toInt(),
        cross = 0xFF1E7FE0.toInt(),
        circle = 0xFFD93F55.toInt(),
        square = 0xFFB85AC9.toInt(),
        triangle = 0xFF0E9F63.toInt(),
    )

    /** Warm sunset: copper background glow with amber and coral controls. */
    val SOLAR = PadTheme(
        id = "solar",
        name = "Solar",
        dark = true,
        bgTop = 0xFF1C1207.toInt(),
        bgMid = 0xFF100A04.toInt(),
        bgBottom = 0xFF070302.toInt(),
        surface = 0xFF1B1209.toInt(),
        surfaceAlt = 0xFF2A1C0F.toInt(),
        text = 0xFFFFF3E4.toInt(),
        textDim = 0xFFEAD4B8.toInt(),
        muted = 0xFFA38B70.toInt(),
        accent = 0xFFFFB43E.toInt(),
        accent2 = 0xFFFF7A3D.toInt(),
        ok = 0xFF6FE3B0.toInt(),
        warn = 0xFFFFD166.toInt(),
        cross = 0xFFFFC24B.toInt(),
        circle = 0xFFFF7A55.toInt(),
        square = 0xFFFFD07A.toInt(),
        triangle = 0xFF8BE28F.toInt(),
    )

    /** High-contrast graphite: pure greys with a white key light. */
    val MONO = PadTheme(
        id = "mono",
        name = "Mono",
        dark = true,
        bgTop = 0xFF141414.toInt(),
        bgMid = 0xFF0B0B0B.toInt(),
        bgBottom = 0xFF030303.toInt(),
        surface = 0xFF121212.toInt(),
        surfaceAlt = 0xFF212121.toInt(),
        text = 0xFFF5F5F5.toInt(),
        textDim = 0xFFD0D0D0.toInt(),
        muted = 0xFF8A8A8A.toInt(),
        accent = 0xFFEDEDED.toInt(),
        accent2 = 0xFF9E9E9E.toInt(),
        ok = 0xFF8BE28F.toInt(),
        warn = 0xFFFFC46B.toInt(),
        cross = 0xFFEDEDED.toInt(),
        circle = 0xFFBFBFBF.toInt(),
        square = 0xFF9E9E9E.toInt(),
        triangle = 0xFFDCDCDC.toInt(),
    )

    val all = listOf(NEON, MIDNIGHT, VIOLET, CRIMSON, SOLAR, RETRO, ARCTIC, MONO)

    fun byId(id: String?): PadTheme = all.firstOrNull { it.id == id } ?: NEON
}
