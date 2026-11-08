package com.alamshowkat.lemogamepad

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named copies of the on-screen control layout.
 *
 * The live layout is still stored in [PrefsKeys.LAYOUT], so an upgrade from an
 * older version keeps the arrangement the user already had. This store only
 * adds a name on top of it:
 *
 * - [PrefsKeys.LAYOUTS] holds the ordered list of saved layouts as JSON.
 * - [PrefsKeys.LAYOUT_ACTIVE] names the saved layout currently in use, or is
 *   empty when the pad is on a custom/unsaved arrangement.
 *
 * Editing a named layout updates that saved entry in place (see [updateActive]);
 * use "Save as" to create a separate copy first.
 */
class LayoutStore(private val prefs: SharedPreferences) {

    data class Saved(val name: String, val data: String)

    /** Saved layouts, oldest first. Malformed entries are ignored. */
    fun saved(): List<Saved> {
        val raw = prefs.getString(PrefsKeys.LAYOUTS, null) ?: return emptyList()
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
            // A corrupt list should not take the settings sheet down. The live
            // layout is stored separately, so the user only loses the names.
            return emptyList()
        }
        return out
    }

    fun find(name: String): Saved? = saved().firstOrNull { it.name == name }

    /** Name of the saved layout currently in use, or null for a custom one. */
    fun activeName(): String? {
        val name = prefs.getString(PrefsKeys.LAYOUT_ACTIVE, null)?.trim().orEmpty()
        if (name.isEmpty()) return null
        // A deleted or half-written entry must not leave the sheet claiming a
        // name that no longer exists.
        return if (find(name) == null) null else name
    }

    /** Current live layout data, named or not. */
    fun activeData(): String? = prefs.getString(PrefsKeys.LAYOUT, null)

    /**
     * Stores [data] under [name], makes it active and returns true when an
     * existing layout with the same name (case-insensitive) was replaced.
     * Name matching keeps the spelling the user first chose.
     */
    fun save(name: String, data: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val list = saved().toMutableList()
        val index = list.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        val replaced = index >= 0
        val entry = if (replaced) Saved(list[index].name, data) else Saved(clean, data)
        if (replaced) list[index] = entry else list += entry
        persist(list)
        prefs.edit()
            .putString(PrefsKeys.LAYOUT, data)
            .putString(PrefsKeys.LAYOUT_ACTIVE, entry.name)
            .apply()
        return replaced
    }

    /** Writes the live data back into the active saved layout after an edit. */
    fun updateActive(data: String) {
        val name = activeName() ?: return
        val list = saved().toMutableList()
        val index = list.indexOfFirst { it.name == name }
        if (index < 0) return
        list[index] = Saved(name, data)
        persist(list)
        prefs.edit().putString(PrefsKeys.LAYOUT, data).apply()
    }

    /** Deletes a saved layout. The live arrangement is left untouched. */
    fun remove(name: String) {
        val wasActive = activeName() == name
        val list = saved().filterNot { it.name == name }
        persist(list)
        if (wasActive) clearActive()
    }

    /** Makes a saved layout active and returns false when it no longer exists. */
    fun activate(name: String): Boolean {
        val entry = find(name) ?: return false
        prefs.edit()
            .putString(PrefsKeys.LAYOUT, entry.data)
            .putString(PrefsKeys.LAYOUT_ACTIVE, entry.name)
            .apply()
        return true
    }

    /** Marks the live arrangement as custom (not tied to a saved name). */
    fun clearActive() {
        prefs.edit().putString(PrefsKeys.LAYOUT_ACTIVE, "").apply()
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
        prefs.edit().putString(PrefsKeys.LAYOUTS, array.toString()).apply()
    }
}
