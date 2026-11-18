package com.alamshowkat.lemogamepad

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A manual quick-switch snapshot of the parts of the pad that change how a
 * game feels. Connection settings (host, port, transport, player slot) and the
 * screen preview are deliberately excluded: profiles never redirect the link.
 */
internal data class GameProfileData(
    val touchpadEnabled: Boolean,
    val touchpadMouse: Boolean,
    val touchpadSensitivity: Int,
    val gyroSteer: Boolean,
    val gyroAim: Boolean,
    val gyroRevertX: Boolean,
    val gyroRevertY: Boolean,
    val steerSensitivity: Int,
    val steerDeadzone: Int,
    val steerCurve: Int,
    val aimSensitivity: Int,
    val deadzone: Int,
    val stickCurve: Int,
    val haptics: Boolean,
    val hapticStrength: Int,
    val inputRate: String,
    val faceLabels: String,
    val padAlpha: Int,
    val faceSpacing: Int,
    val adaptiveQuality: Boolean,
    val layout: String?,
)

/** JSON codec for [GameProfileData]; missing fields fall back to app defaults. */
internal object GameProfileCodec {
    fun encode(data: GameProfileData): String = JSONObject()
        .put("touchpadEnabled", data.touchpadEnabled)
        .put("touchpadMouse", data.touchpadMouse)
        .put("touchpadSensitivity", data.touchpadSensitivity)
        .put("gyroSteer", data.gyroSteer)
        .put("gyroAim", data.gyroAim)
        .put("gyroRevertX", data.gyroRevertX)
        .put("gyroRevertY", data.gyroRevertY)
        .put("steerSensitivity", data.steerSensitivity)
        .put("steerDeadzone", data.steerDeadzone)
        .put("steerCurve", data.steerCurve)
        .put("aimSensitivity", data.aimSensitivity)
        .put("deadzone", data.deadzone)
        .put("stickCurve", data.stickCurve)
        .put("haptics", data.haptics)
        .put("hapticStrength", data.hapticStrength)
        .put("inputRate", data.inputRate)
        .put("faceLabels", data.faceLabels)
        .put("padAlpha", data.padAlpha)
        .put("faceSpacing", data.faceSpacing)
        .put("adaptiveQuality", data.adaptiveQuality)
        .also { json -> data.layout?.let { json.put("layout", it) } }
        .toString()

    fun decode(raw: String): GameProfileData? = try {
        val json = JSONObject(raw)
        GameProfileData(
            touchpadEnabled = json.optBoolean("touchpadEnabled", false),
            touchpadMouse = json.optBoolean("touchpadMouse", false),
            touchpadSensitivity = json.optInt("touchpadSensitivity", 50),
            gyroSteer = json.optBoolean("gyroSteer", false),
            gyroAim = json.optBoolean("gyroAim", false),
            gyroRevertX = json.optBoolean("gyroRevertX", false),
            gyroRevertY = json.optBoolean("gyroRevertY", false),
            steerSensitivity = json.optInt("steerSensitivity", 55),
            steerDeadzone = json.optInt("steerDeadzone", 5),
            steerCurve = json.optInt("steerCurve", 25),
            aimSensitivity = json.optInt("aimSensitivity", 55),
            deadzone = json.optInt("deadzone", 12),
            stickCurve = json.optInt("stickCurve", 0),
            haptics = json.optBoolean("haptics", true),
            hapticStrength = json.optInt("hapticStrength", 100),
            inputRate = json.optString("inputRate", InputRate.STANDARD.id),
            faceLabels = json.optString("faceLabels", FaceLabels.PLAYSTATION.name),
            padAlpha = json.optInt("padAlpha", 100),
            faceSpacing = json.optInt("faceSpacing", 100),
            adaptiveQuality = json.optBoolean("adaptiveQuality", false),
            layout = if (json.isNull("layout")) null else json.optString("layout", "").takeIf { it.isNotBlank() },
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Named game profiles over SharedPreferences. Like [LayoutStore], the ordered
 * list is a JSON array of `{name,data}` entries and the active name is kept
 * separately. Switching is always explicit; nothing here watches the foreground
 * app.
 */
internal class GameProfileStore(private val prefs: SharedPreferences) {

    data class Saved(val name: String, val data: String)

    private val listKey = PrefsKeys.GAME_PROFILES
    private val activeKey = PrefsKeys.GAME_PROFILE_ACTIVE

    /** Saved profiles, oldest first. Malformed entries are ignored. */
    fun saved(): List<Saved> {
        val raw = prefs.getString(listKey, null) ?: return emptyList()
        val out = ArrayList<Saved>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val data = obj.optString("data")
                if (name.isNotEmpty() && data.isNotBlank()) out += Saved(name, data)
            }
        } catch (_: Exception) {
            // A corrupt list should never take the settings sheet down.
            return emptyList()
        }
        return out
    }

    fun find(name: String): Saved? =
        saved().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Name of the last explicitly applied profile, or null when none is active. */
    fun activeName(): String? {
        val name = prefs.getString(activeKey, null)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return find(name)?.name
    }

    /**
     * Stores [data] under [name] and makes it active. Returns true when an
     * existing profile with the same name was replaced; the original spelling
     * is kept so the list does not churn on case-only edits.
     */
    fun save(name: String, data: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || data.isBlank()) return false
        val list = saved().toMutableList()
        val index = list.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        val replaced = index >= 0
        val entry = if (replaced) Saved(list[index].name, data) else Saved(clean, data)
        if (replaced) list[index] = entry else list += entry
        persist(list)
        prefs.edit().putString(activeKey, entry.name).apply()
        return replaced
    }

    /** Makes a saved profile active without touching the live pad settings. */
    fun activate(name: String): Boolean {
        val entry = find(name) ?: return false
        prefs.edit().putString(activeKey, entry.name).apply()
        return true
    }

    fun remove(name: String) {
        val wasActive = activeName()?.equals(name, ignoreCase = true) == true
        persist(saved().filterNot { it.name.equals(name.trim(), ignoreCase = true) })
        if (wasActive) clearActive()
    }

    fun clearActive() {
        prefs.edit().putString(activeKey, "").apply()
    }

    private fun persist(list: List<Saved>) {
        val array = JSONArray()
        for (entry in list) {
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("data", entry.data),
            )
        }
        prefs.edit().putString(listKey, array.toString()).apply()
    }
}
