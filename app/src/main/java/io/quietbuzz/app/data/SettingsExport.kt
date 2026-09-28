package io.quietbuzz.app.data

import org.json.JSONArray
import org.json.JSONObject

data class ExportedSettings(
    val allowlist: Set<String>,
    val includeSystemApps: Boolean,
)

/**
 * Portable settings snapshot: just the allowlist and the include-system-apps toggle, not the
 * device-local pass history or original-importance backup, since those aren't meaningful to carry
 * to a reinstall or a second device.
 */
object SettingsExport {
    private const val CURRENT_VERSION = 1

    fun toJson(allowlist: Set<String>, includeSystemApps: Boolean): String {
        val json = JSONObject()
        json.put("version", CURRENT_VERSION)
        json.put("allowlist", JSONArray(allowlist))
        json.put("includeSystemApps", includeSystemApps)
        return json.toString(2)
    }

    fun fromJson(raw: String): ExportedSettings {
        val json = JSONObject(raw)
        val allowlistArray = json.optJSONArray("allowlist") ?: JSONArray()
        val allowlist = (0 until allowlistArray.length()).map { allowlistArray.getString(it) }.toSet()
        val includeSystemApps = json.optBoolean("includeSystemApps", false)
        return ExportedSettings(allowlist, includeSystemApps)
    }
}
