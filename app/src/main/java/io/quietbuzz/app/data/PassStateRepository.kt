package io.quietbuzz.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.quietbuzz.app.model.ChannelBackup
import io.quietbuzz.app.model.PassResult
import io.quietbuzz.app.model.PendingAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class PassSummary(
    val lastRunAtMillis: Long?,
    val appsChanged: Int,
    val channelsChanged: Int,
    val failures: Int,
)

/**
 * Persists everything the listener needs to survive a process death between connects: the
 * fallback pending action (see PendingAction), user-facing settings, last-run stats for the
 * status screen, and the original-channel-state backups that make "Restore all" possible.
 *
 * The backup is a nested JSON object (packageName -> channelId -> ChannelBackup) via org.json
 * (bundled in the Android platform, no extra dependency needed). Nested rather than a single
 * delimited "pkg|channelId" string key: a channel ID is an arbitrary app-supplied string that can
 * itself contain "|", which made an earlier flat-key format ambiguous to split back apart.
 */
class PassStateRepository(private val context: Context) {

    private object Keys {
        val PENDING_ACTION_TYPE = stringPreferencesKey("pending_action_type")
        val PENDING_ACTION_PACKAGE = stringPreferencesKey("pending_action_package")
        val INCLUDE_SYSTEM_APPS = booleanPreferencesKey("include_system_apps")
        val LAST_RUN_AT = longPreferencesKey("last_run_at")
        val LAST_APPS_CHANGED = intPreferencesKey("last_apps_changed")
        val LAST_CHANNELS_CHANGED = intPreferencesKey("last_channels_changed")
        val LAST_FAILURES = intPreferencesKey("last_failures")
        val ASSOCIATION_LOST = booleanPreferencesKey("association_lost")
        val ORIGINAL_IMPORTANCE_BACKUP = stringPreferencesKey("original_importance_backup_json")
    }

    val includeSystemApps: Flow<Boolean> = context.quietBuzzDataStore.data.map { it[Keys.INCLUDE_SYSTEM_APPS] ?: false }

    suspend fun setIncludeSystemApps(value: Boolean) {
        context.quietBuzzDataStore.edit { it[Keys.INCLUDE_SYSTEM_APPS] = value }
    }

    val associationLost: Flow<Boolean> = context.quietBuzzDataStore.data.map { it[Keys.ASSOCIATION_LOST] ?: false }

    val lastPassSummary: Flow<PassSummary> = context.quietBuzzDataStore.data.map { prefs ->
        PassSummary(
            lastRunAtMillis = prefs[Keys.LAST_RUN_AT],
            appsChanged = prefs[Keys.LAST_APPS_CHANGED] ?: 0,
            channelsChanged = prefs[Keys.LAST_CHANNELS_CHANGED] ?: 0,
            failures = prefs[Keys.LAST_FAILURES] ?: 0,
        )
    }

    suspend fun recordResult(result: PassResult) {
        context.quietBuzzDataStore.edit { prefs ->
            prefs[Keys.LAST_RUN_AT] = System.currentTimeMillis()
            prefs[Keys.LAST_APPS_CHANGED] = result.appsChanged
            prefs[Keys.LAST_CHANNELS_CHANGED] = result.channelsChanged
            prefs[Keys.LAST_FAILURES] = result.failures
            prefs[Keys.ASSOCIATION_LOST] = result.associationLost
        }
    }

    suspend fun setPendingAction(action: PendingAction) {
        context.quietBuzzDataStore.edit { prefs ->
            prefs[Keys.PENDING_ACTION_TYPE] = when (action) {
                is PendingAction.None -> "NONE"
                is PendingAction.SilenceAll -> "SILENCE_ALL"
                is PendingAction.SilenceOne -> "SILENCE_ONE"
                is PendingAction.RestoreAll -> "RESTORE_ALL"
                is PendingAction.RestoreOne -> "RESTORE_ONE"
                is PendingAction.ResetAll -> "RESET_ALL"
            }
            when (action) {
                is PendingAction.SilenceOne -> prefs[Keys.PENDING_ACTION_PACKAGE] = action.packageName
                is PendingAction.RestoreOne -> prefs[Keys.PENDING_ACTION_PACKAGE] = action.packageName
                else -> prefs.remove(Keys.PENDING_ACTION_PACKAGE)
            }
        }
    }

    /** Reads the pending action and atomically resets it to None. */
    suspend fun consumePendingAction(): PendingAction {
        var result: PendingAction = PendingAction.None
        context.quietBuzzDataStore.edit { prefs ->
            result = when (prefs[Keys.PENDING_ACTION_TYPE]) {
                "SILENCE_ALL" -> PendingAction.SilenceAll
                "SILENCE_ONE" -> prefs[Keys.PENDING_ACTION_PACKAGE]?.let { PendingAction.SilenceOne(it) }
                    ?: PendingAction.SilenceAll
                "RESTORE_ALL" -> PendingAction.RestoreAll
                "RESTORE_ONE" -> prefs[Keys.PENDING_ACTION_PACKAGE]?.let { PendingAction.RestoreOne(it) }
                    ?: PendingAction.None
                "RESET_ALL" -> PendingAction.ResetAll
                else -> PendingAction.None
            }
            prefs[Keys.PENDING_ACTION_TYPE] = "NONE"
            prefs.remove(Keys.PENDING_ACTION_PACKAGE)
        }
        return result
    }

    /**
     * Merges [entries] (packageName -> channelId -> original channel state) into the persisted
     * backup in one DataStore write, regardless of how many packages/channels are in the batch --
     * callers accumulate in memory across an entire pass and call this once at the end, rather
     * than once per channel.
     *
     * An existing entry is never overwritten: if a channel gets silenced again (e.g. its vibration
     * was re-enabled after the first pass), its current state is not the pre-QuietBuzz original,
     * and replacing the first entry would make Restore all restore the wrong thing.
     */
    suspend fun mergeOriginalImportanceBackup(entries: Map<String, Map<String, ChannelBackup>>) {
        if (entries.isEmpty()) return
        context.quietBuzzDataStore.edit { prefs ->
            val root = JSONObject(prefs[Keys.ORIGINAL_IMPORTANCE_BACKUP] ?: "{}")
            for ((packageName, channelBackups) in entries) {
                val pkgJson = root.optJSONObject(packageName) ?: JSONObject()
                for ((channelId, backup) in channelBackups) {
                    if (!pkgJson.has(channelId)) {
                        pkgJson.put(channelId, backup.toJson())
                    }
                }
                root.put(packageName, pkgJson)
            }
            prefs[Keys.ORIGINAL_IMPORTANCE_BACKUP] = root.toString()
        }
    }

    /**
     * packageName -> (channelId -> original channel state). Tolerates the old format (a plain
     * Int importance value, from before ChannelBackup existed) by treating it as importance-only
     * with no captured sound/vibration state, rather than crashing on an in-flight app's existing
     * backup data.
     */
    suspend fun getOriginalImportanceBackup(): Map<String, Map<String, ChannelBackup>> {
        val raw = context.quietBuzzDataStore.data.first()[Keys.ORIGINAL_IMPORTANCE_BACKUP] ?: "{}"
        val root = JSONObject(raw)
        return root.keys().asSequence().associateWith { pkg ->
            val pkgJson = root.getJSONObject(pkg)
            pkgJson.keys().asSequence().mapNotNull { channelId ->
                channelBackupFromAny(pkgJson.get(channelId))?.let { channelId to it }
            }.toMap()
        }
    }

    private fun ChannelBackup.toJson(): JSONObject = JSONObject().apply {
        put("importance", importance)
        put("soundUri", soundUri ?: JSONObject.NULL)
        put("vibrationEnabled", vibrationEnabled)
        put("vibrationPattern", vibrationPattern ?: JSONObject.NULL)
    }

    private fun channelBackupFromAny(value: Any): ChannelBackup? = when (value) {
        is JSONObject -> ChannelBackup(
            importance = value.getInt("importance"),
            soundUri = if (value.isNull("soundUri")) null else value.getString("soundUri"),
            vibrationEnabled = value.optBoolean("vibrationEnabled", false),
            vibrationPattern = if (value.isNull("vibrationPattern")) null else value.getString("vibrationPattern"),
        )
        is Number -> ChannelBackup(
            importance = value.toInt(),
            soundUri = null,
            vibrationEnabled = false,
            vibrationPattern = null,
        )
        else -> null
    }

    suspend fun clearOriginalImportanceBackup() {
        context.quietBuzzDataStore.edit { prefs -> prefs.remove(Keys.ORIGINAL_IMPORTANCE_BACKUP) }
    }

    suspend fun clearOriginalImportanceBackup(packageName: String) {
        context.quietBuzzDataStore.edit { prefs ->
            val root = JSONObject(prefs[Keys.ORIGINAL_IMPORTANCE_BACKUP] ?: return@edit)
            root.remove(packageName)
            prefs[Keys.ORIGINAL_IMPORTANCE_BACKUP] = root.toString()
        }
    }
}
