package com.alamshowkat.lemogamepad

import android.content.SharedPreferences

/**
 * Keeps saved preferences readable across upgrades and partial restores.
 *
 * The app has never shipped a schema version: older builds, a half-finished
 * backup restore, or a manual edit can leave a value with the wrong type or
 * outside the range the settings UI produces. A single bad `getBoolean` would
 * otherwise crash onCreate and strand the user on a blank screen. This guard
 * rewrites only invalid values and never clears a valid preference, a token,
 * a layout or a game profile.
 */
internal object SettingsGuard {
    private const val SCHEMA_KEY = "settings_schema_version"
    private const val SCHEMA_VERSION = 1

    /** Called before anything reads preferences. Safe to run on every launch. */
    fun normalize(prefs: SharedPreferences) {
        val editor = prefs.edit()

        int(prefs, editor, PrefsKeys.PORT, Proto.PORT, 1, 65_534)
        int(prefs, editor, PrefsKeys.PLAYER_SLOT, 0, 0, Proto.MAX_PLAYERS)
        // -1 means every section is collapsed; 0-4 selects the open one.
        int(prefs, editor, PrefsKeys.SETTINGS_SECTION, 0, -1, 4)
        int(prefs, editor, PrefsKeys.DEADZONE, 12, 0, 40)
        int(prefs, editor, PrefsKeys.STICK_CURVE, 0, 0, 100)
        int(prefs, editor, PrefsKeys.HAPTIC_STRENGTH, 100, 0, 100)
        int(prefs, editor, PrefsKeys.STEER_SENS, 55, 0, 100)
        int(prefs, editor, PrefsKeys.STEER_DEADZONE, 5, 0, 40)
        int(prefs, editor, PrefsKeys.STEER_CURVE, 25, 0, 100)
        int(prefs, editor, PrefsKeys.AIM_SENS, 55, 0, 100)
        int(prefs, editor, PrefsKeys.TOUCHPAD_SENS, 50, 0, 100)
        int(prefs, editor, PrefsKeys.PAD_ALPHA, 100, 35, 100)
        int(prefs, editor, PrefsKeys.FACE_SPACING, 100, 75, 160)
        editor.putInt(
            PrefsKeys.SCREEN_QUALITY,
            ScreenQuality.fromId(int(prefs, editor, PrefsKeys.SCREEN_QUALITY, 1, 0, 2)).wireId,
        )

        for (key in BOOLEANS) bool(prefs, editor, key, key in DEFAULT_TRUE)

        val theme = Themes.byId(string(prefs, editor, PrefsKeys.THEME, Themes.MIDNIGHT.id)).id
        editor.putString(PrefsKeys.THEME, theme)
        val faces = string(prefs, editor, PrefsKeys.FACES, "ps")
        editor.putString(PrefsKeys.FACES, if (faces.equals("xbox", ignoreCase = true)) "xbox" else "ps")
        val transport = string(prefs, editor, PrefsKeys.TRANSPORT, "").lowercase()
        val safeTransport = if (transport == "wifi" || transport == "usb") transport else ""
        editor.putString(PrefsKeys.TRANSPORT, safeTransport)
        editor.putString(
            PrefsKeys.INPUT_RATE,
            InputRate.fromId(string(prefs, editor, PrefsKeys.INPUT_RATE, InputRate.STANDARD.id)).id,
        )
        editor.putString(
            PrefsKeys.HOST,
            string(prefs, editor, PrefsKeys.HOST, "").trim().take(255),
        )
        // Remembered only to order the manual host picker. Never auto-connects.
        editor.putString(
            PrefsKeys.BT_LAST_HOST,
            string(prefs, editor, PrefsKeys.BT_LAST_HOST, "").trim().take(64),
        )

        long(prefs, editor, PrefsKeys.LAST_WHATSNEW_CODE, 0L, 0L, Long.MAX_VALUE)
        float(prefs, editor, PrefsKeys.SCREEN_X, 0.5f, 0f, 1f)
        float(prefs, editor, PrefsKeys.SCREEN_Y, 0.30f, 0f, 1f)
        editor.putInt(SCHEMA_KEY, SCHEMA_VERSION)
        editor.apply()

        // JSON-backed stores have their own parsers; re-encode once so corrupt
        // entries are dropped and out-of-range profile values are clamped.
        GameProfileStore(prefs).sanitize()
    }

    private val BOOLEANS = listOf(
        PrefsKeys.HAPTICS,
        PrefsKeys.GAME_RUMBLE,
        PrefsKeys.MOTION,
        PrefsKeys.TOUCHPAD_ENABLED,
        PrefsKeys.TOUCHPAD_MOUSE,
        PrefsKeys.GYRO_STEER,
        PrefsKeys.GYRO_AIM,
        PrefsKeys.GYRO_REVERT_X,
        PrefsKeys.GYRO_REVERT_Y,
        PrefsKeys.LAYOUT_SNAP,
        PrefsKeys.LAYOUT_GUIDES,
        PrefsKeys.LAYOUT_SAFE_AREA,
        PrefsKeys.LAYOUT_MULTI,
        PrefsKeys.BATTERY_SAVER,
        PrefsKeys.SETTINGS_DENSE,
        PrefsKeys.SCREEN,
        PrefsKeys.SCREEN_SMALL,
        PrefsKeys.ADAPTIVE_QUALITY,
        PrefsKeys.ONBOARDED,
        PrefsKeys.WIFI_GUIDE_SEEN,
        PrefsKeys.CONNECTION_DOCTOR_SEEN,
    )

    private val DEFAULT_TRUE = setOf(
        PrefsKeys.HAPTICS,
        PrefsKeys.GAME_RUMBLE,
        PrefsKeys.MOTION,
        PrefsKeys.LAYOUT_GUIDES,
        PrefsKeys.SCREEN_SMALL,
    )

    fun bool(prefs: SharedPreferences, key: String, default: Boolean): Boolean = try {
        prefs.getBoolean(key, default)
    } catch (_: Exception) {
        default
    }

    fun int(prefs: SharedPreferences, key: String, default: Int): Int = try {
        prefs.getInt(key, default)
    } catch (_: Exception) {
        default
    }

    fun string(prefs: SharedPreferences, key: String, default: String): String = try {
        prefs.getString(key, default) ?: default
    } catch (_: Exception) {
        default
    }

    fun long(prefs: SharedPreferences, key: String, default: Long): Long = try {
        prefs.getLong(key, default)
    } catch (_: Exception) {
        default
    }

    fun float(prefs: SharedPreferences, key: String, default: Float): Float = try {
        prefs.getFloat(key, default)
    } catch (_: Exception) {
        default
    }

    private fun bool(prefs: SharedPreferences, editor: SharedPreferences.Editor, key: String, default: Boolean): Boolean {
        val safe = bool(prefs, key, default)
        editor.putBoolean(key, safe)
        return safe
    }

    private fun int(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        key: String,
        default: Int,
        min: Int,
        max: Int,
    ): Int {
        val safe = int(prefs, key, default).coerceIn(min, max)
        editor.putInt(key, safe)
        return safe
    }

    private fun string(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        key: String,
        default: String,
    ): String {
        val safe = string(prefs, key, default) ?: default
        editor.putString(key, safe)
        return safe
    }

    private fun long(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        key: String,
        default: Long,
        min: Long,
        max: Long,
    ): Long {
        val safe = long(prefs, key, default).coerceIn(min, max)
        editor.putLong(key, safe)
        return safe
    }

    private fun float(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        key: String,
        default: Float,
        min: Float,
        max: Float,
    ): Float {
        val safe = float(prefs, key, default).coerceIn(min, max)
        editor.putFloat(key, safe)
        return safe
    }
}
