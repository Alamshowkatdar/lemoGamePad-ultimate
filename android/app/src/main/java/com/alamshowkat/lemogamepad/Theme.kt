package com.alamshowkat.lemogamepad

import android.graphics.Color

/** How the face buttons are drawn: PlayStation symbols or Xbox letters. */
enum class FaceLabels { PLAYSTATION, XBOX }

/**
 * The colours of the real controllers. These never follow a theme: an Xbox A
 * button is green and a PlayStation Cross is blue in every palette, so muscle
 * memory keeps working when the user swaps looks.
 */
object Iconic {
    // PlayStation: index 0 top, 1 right, 2 bottom, 3 left.
    const val TRIANGLE = 0xFF3DBE7A.toInt()
    const val CIRCLE = 0xFFE5484D.toInt()
    const val CROSS = 0xFF4C8DFF.toInt()
    const val SQUARE = 0xFFE77FB3.toInt()

    // Xbox: index 0 top (Y), 1 right (B), 2 bottom (A), 3 left (X).
    const val Y = 0xFFF3B700.toInt()
    const val B = 0xFFE5484D.toInt()
    const val A = 0xFF6CCB2F.toInt()
    const val X = 0xFF3B82F6.toInt()

    fun face(labels: FaceLabels, index: Int): Int = when (labels) {
        FaceLabels.PLAYSTATION -> when (index) {
            0 -> TRIANGLE
            1 -> CIRCLE
            2 -> CROSS
            else -> SQUARE
        }
        FaceLabels.XBOX -> when (index) {
            0 -> Y
            1 -> B
            2 -> A
            else -> X
        }
    }
}

/**
 * Everything the renderer needs for one look. All control colours come from
 * here, so adding a theme never touches the drawing code.
 *
 * [border], [shadow] and [tracking] tune the visual weight of a palette:
 * frosted themes use thin borders and soft shadows, arcade themes use thick
 * borders and wide letter spacing.
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
    /** Press animation style, resolved through [Motions]. */
    val motion: String = "pulse",
    /** True when the palette keeps the background pure black (OLED panels). */
    val pitchBlack: Boolean = false,
    /** Strength of the decorative background glow; 0 removes it entirely. */
    val glow: Float = 1f,
    /** Multiplier for control outlines. */
    val border: Float = 1f,
    /** Multiplier for glow/halo strength. */
    val shadow: Float = 1f,
    /** Multiplier for label letter spacing. */
    val tracking: Float = 1f,
    /** Background decoration: glow, grid, scan, speed, frost, none. */
    val backdrop: String = "glow",
    /** One-line description shown in the preview. */
    val mood: String = "",
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
        motion = if (k < 0.5f) a.motion else b.motion,
        pitchBlack = if (k < 0.5f) a.pitchBlack else b.pitchBlack,
        glow = a.glow + (b.glow - a.glow) * k,
        border = a.border + (b.border - a.border) * k,
        shadow = a.shadow + (b.shadow - a.shadow) * k,
        tracking = a.tracking + (b.tracking - a.tracking) * k,
        backdrop = if (k < 0.5f) a.backdrop else b.backdrop,
        mood = if (k < 0.5f) a.mood else b.mood,
    )
}

/** How a palette animates a press. Mirrors the browser MOTIONS table. */
data class Motion(val style: String, val ms: Float, val spread: Float)

object Motions {
    // Longer tails and a wider reach make each press read as a deliberate
    // flourish rather than a tiny flick, while staying under one frame budget.
    val PULSE = Motion("pulse", 460f, 1.70f)
    val WAVE = Motion("wave", 600f, 1.90f)
    val HALO = Motion("halo", 700f, 1.50f)
    val SHOCK = Motion("shock", 390f, 2.00f)
    val BURST = Motion("burst", 500f, 1.85f)
    val STEP = Motion("step", 480f, 1.70f)
    val RIPPLE = Motion("ripple", 720f, 2.10f)
    val LINE = Motion("line", 420f, 1.55f)
    val VOID = Motion("void", 340f, 1.40f)

    fun of(theme: PadTheme): Motion = when (theme.motion) {
        "wave" -> WAVE
        "halo" -> HALO
        "shock" -> SHOCK
        "burst" -> BURST
        "step" -> STEP
        "ripple" -> RIPPLE
        "line" -> LINE
        "void" -> VOID
        else -> PULSE
    }
}

/** Branding shown in-app and in the settings footer. */
object Brand {
    const val NAME = "lemoGamePad ultimate"
    const val MARK = "LEMOGAMEPAD ULTIMATE · ALAM SHOWKAT"
    const val AUTHOR = "Alam showkat"

    /** Keep in sync with `versionName` in android/app/build.gradle.kts. */
    const val VERSION = "2.2.3"
}

object Themes {

    val MIDNIGHT = PadTheme(
        id = "midnight",
        name = "Midnight Black",
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
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "wave",
        border = 1f,
        shadow = 1f,
        tracking = 1f,
        backdrop = "glow",
        mood = "Deep blue glass with a quiet key light.",
    )

    val CYBERPUNK = PadTheme(
        id = "cyberpunk",
        name = "Cyberpunk",
        dark = true,
        bgTop = 0xFF140628.toInt(),
        bgMid = 0xFF0B0320.toInt(),
        bgBottom = 0xFF04010D.toInt(),
        surface = 0xFF150B2C.toInt(),
        surfaceAlt = 0xFF251148.toInt(),
        text = 0xFFF5EAFF.toInt(),
        textDim = 0xFFD4BDF0.toInt(),
        muted = 0xFF8D74AD.toInt(),
        accent = 0xFFFF3BD6.toInt(),
        accent2 = 0xFF38E8FF.toInt(),
        ok = 0xFF4CFFB0.toInt(),
        warn = 0xFFFFE14D.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "shock",
        border = 1.35f,
        shadow = 1.35f,
        tracking = 1.12f,
        backdrop = "grid",
        mood = "Neon pink and cyan over a midnight grid.",
    )

    val ARCTIC = PadTheme(
        id = "arctic",
        name = "Arctic",
        dark = false,
        bgTop = 0xFFF4F9FD.toInt(),
        bgMid = 0xFFE4EDF5.toInt(),
        bgBottom = 0xFFCCDAE7.toInt(),
        surface = 0xF2FFFFFF.toInt(),
        surfaceAlt = 0xFFEAF2F9.toInt(),
        text = 0xFF16202B.toInt(),
        textDim = 0xFF3E4C5C.toInt(),
        muted = 0xFF6C7C8C.toInt(),
        accent = 0xFF0E9F8F.toInt(),
        accent2 = 0xFF2C7BE5.toInt(),
        ok = 0xFF0E9F63.toInt(),
        warn = 0xFFC77A12.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "ripple",
        border = 0.8f,
        shadow = 0.65f,
        tracking = 1.08f,
        backdrop = "frost",
        mood = "Frosted glass, icy blue and soft daylight.",
    )

    val AMOLED = PadTheme(
        id = "amoled",
        name = "AMOLED",
        dark = true,
        bgTop = 0xFF000000.toInt(),
        bgMid = 0xFF000000.toInt(),
        bgBottom = 0xFF000000.toInt(),
        surface = 0xFF040404.toInt(),
        surfaceAlt = 0xFF0C0C0C.toInt(),
        text = 0xFFF5F7FA.toInt(),
        textDim = 0xFFC2C8D0.toInt(),
        muted = 0xFF707880.toInt(),
        accent = 0xFFFFB52E.toInt(),
        accent2 = 0xFFFF5B45.toInt(),
        ok = 0xFF7DFFB0.toInt(),
        warn = 0xFFFFD166.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "void",
        pitchBlack = true,
        glow = 0f,
        border = 0.9f,
        shadow = 0f,
        tracking = 1.05f,
        backdrop = "none",
        mood = "True black pixels with a single warm accent.",
    )

    val RACINGRED = PadTheme(
        id = "racingred",
        name = "Racing Red",
        dark = true,
        bgTop = 0xFF1B1B1E.toInt(),
        bgMid = 0xFF111114.toInt(),
        bgBottom = 0xFF070708.toInt(),
        surface = 0xFF17181B.toInt(),
        surfaceAlt = 0xFF24262A.toInt(),
        text = 0xFFFFF2F3.toInt(),
        textDim = 0xFFE3C3C6.toInt(),
        muted = 0xFFA08084.toInt(),
        accent = 0xFFFF2D3D.toInt(),
        accent2 = 0xFFFF8A1F.toInt(),
        ok = 0xFF5CE39A.toInt(),
        warn = 0xFFFFC857.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "burst",
        border = 1.15f,
        shadow = 1.2f,
        tracking = 1.04f,
        backdrop = "speed",
        mood = "Carbon fibre, telemetry red and a fast horizon.",
    )

    val XBOX = PadTheme(
        id = "xbox",
        name = "Xbox",
        dark = true,
        bgTop = 0xFF0C1A10.toInt(),
        bgMid = 0xFF08120B.toInt(),
        bgBottom = 0xFF040805.toInt(),
        surface = 0xFF0F1C12.toInt(),
        surfaceAlt = 0xFF1A2E1E.toInt(),
        text = 0xFFF1FBEF.toInt(),
        textDim = 0xFFC7E0C4.toInt(),
        muted = 0xFF7F9C7E.toInt(),
        accent = 0xFF7CDB2F.toInt(),
        accent2 = 0xFF107C10.toInt(),
        ok = 0xFF7CDB2F.toInt(),
        warn = 0xFFFFC857.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "halo",
        border = 1.05f,
        shadow = 1.15f,
        tracking = 1f,
        backdrop = "glow",
        mood = "Xbox green, dark carbon and a soft central light.",
    )

    val PLAYSTATION = PadTheme(
        id = "playstation",
        name = "PlayStation",
        dark = true,
        bgTop = 0xFF111B3A.toInt(),
        bgMid = 0xFF0A1128.toInt(),
        bgBottom = 0xFF040713.toInt(),
        surface = 0xFF0F1730.toInt(),
        surfaceAlt = 0xFF1A2648.toInt(),
        text = 0xFFF2F6FF.toInt(),
        textDim = 0xFFC3D0EA.toInt(),
        muted = 0xFF7E8FB3.toInt(),
        accent = 0xFF4C8DFF.toInt(),
        accent2 = 0xFFB6C9FF.toInt(),
        ok = 0xFF4FD8A4.toInt(),
        warn = 0xFFFFC857.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "wave",
        border = 1.05f,
        shadow = 1.1f,
        tracking = 1.06f,
        backdrop = "glow",
        mood = "PlayStation blue, white type and a deep navy field.",
    )

    val MINIMAL = PadTheme(
        id = "minimal",
        name = "Minimal",
        dark = false,
        bgTop = 0xFFF7F8FA.toInt(),
        bgMid = 0xFFEFF1F4.toInt(),
        bgBottom = 0xFFE3E6EB.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceAlt = 0xFFF2F4F7.toInt(),
        text = 0xFF15171C.toInt(),
        textDim = 0xFF3C414B.toInt(),
        muted = 0xFF7A8291.toInt(),
        accent = 0xFF2B6CF6.toInt(),
        accent2 = 0xFF15171C.toInt(),
        ok = 0xFF14935C.toInt(),
        warn = 0xFFB8730A.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "line",
        border = 0.8f,
        shadow = 0.5f,
        tracking = 1.02f,
        backdrop = "none",
        mood = "Paper white, one blue accent and no distractions.",
    )

    val RETROARCADE = PadTheme(
        id = "retroarcade",
        name = "Retro Arcade",
        dark = true,
        bgTop = 0xFF1D0B33.toInt(),
        bgMid = 0xFF120722.toInt(),
        bgBottom = 0xFF070310.toInt(),
        surface = 0xFF1D0F35.toInt(),
        surfaceAlt = 0xFF2E1A52.toInt(),
        text = 0xFFFFF4DA.toInt(),
        textDim = 0xFFE6D3AE.toInt(),
        muted = 0xFFA08BB5.toInt(),
        accent = 0xFFFFD23F.toInt(),
        accent2 = 0xFFFF4FD8.toInt(),
        ok = 0xFF6BFFB8.toInt(),
        warn = 0xFFFFB347.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "step",
        border = 1.25f,
        shadow = 1.1f,
        tracking = 1.16f,
        backdrop = "scan",
        mood = "Cabinet purple, coin-op yellow and CRT scanlines.",
    )

    val STEALTH = PadTheme(
        id = "stealth",
        name = "Stealth",
        dark = true,
        bgTop = 0xFF1A1D21.toInt(),
        bgMid = 0xFF101215.toInt(),
        bgBottom = 0xFF070809.toInt(),
        surface = 0xFF141619.toInt(),
        surfaceAlt = 0xFF1F2328.toInt(),
        text = 0xFFDDE2E8.toInt(),
        textDim = 0xFFB2BAC4.toInt(),
        muted = 0xFF6F7782.toInt(),
        accent = 0xFF8FA3B8.toInt(),
        accent2 = 0xFF5A697A.toInt(),
        ok = 0xFF6FE3B0.toInt(),
        warn = 0xFFD9B26B.toInt(),
        cross = Iconic.CROSS,
        circle = Iconic.CIRCLE,
        square = Iconic.SQUARE,
        triangle = Iconic.TRIANGLE,
        motion = "pulse",
        glow = 0.45f,
        border = 0.9f,
        shadow = 0.8f,
        tracking = 1.02f,
        backdrop = "none",
        mood = "Graphite, low light and no wasted colour.",
    )

    val all = listOf(
        MIDNIGHT,
        CYBERPUNK,
        ARCTIC,
        AMOLED,
        RACINGRED,
        XBOX,
        PLAYSTATION,
        MINIMAL,
        RETROARCADE,
        STEALTH,
    )

    /**
     * Older versions shipped neon/violet/crimson/solar/retro/mono. Keep those
     * ids working so an upgraded install keeps a recognisable palette.
     */
    fun byId(id: String?): PadTheme = when (id) {
        "neon", "violet" -> CYBERPUNK
        "crimson" -> RACINGRED
        "solar" -> RETROARCADE
        "retro" -> RETROARCADE
        "mono" -> MINIMAL
        else -> all.firstOrNull { it.id == id } ?: MIDNIGHT
    }
}
