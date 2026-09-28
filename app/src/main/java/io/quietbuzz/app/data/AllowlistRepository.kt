package io.quietbuzz.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Best-effort default package names, provided by the user and not independently verified against
 * a device. Confirm these in Settings > Apps once the allowlist editor is available, since a
 * mismatch just means that app doesn't get silenced (it's a fail-open no-op, not a crash).
 */
val DEFAULT_ALLOWLIST = setOf(
    "com.Slack",
    "com.pagerduty.android",
    "com.samsung.android.dialer",
    "com.samsung.android.messaging",
    "com.kakao.talk",
)

class AllowlistRepository(private val context: Context) {

    private object Keys {
        val ALLOWLIST = stringSetPreferencesKey("allowlist_packages")
        val SEEDED = booleanPreferencesKey("allowlist_seeded")
    }

    val allowlist: Flow<Set<String>> = context.quietBuzzDataStore.data.map { prefs ->
        prefs[Keys.ALLOWLIST] ?: emptySet()
    }

    suspend fun seedDefaultsIfNeeded() {
        context.quietBuzzDataStore.edit { prefs ->
            if (prefs[Keys.SEEDED] != true) {
                prefs[Keys.ALLOWLIST] = DEFAULT_ALLOWLIST
                prefs[Keys.SEEDED] = true
            }
        }
    }

    suspend fun setAllowlist(packages: Set<String>) {
        context.quietBuzzDataStore.edit { prefs ->
            prefs[Keys.ALLOWLIST] = packages
        }
    }

    /** Atomic read-modify-write: two concurrent add/remove calls (e.g. rapid checkbox taps) can't
     * clobber each other the way a separate read-then-setAllowlist would. */
    suspend fun add(packageName: String) {
        context.quietBuzzDataStore.edit { prefs ->
            prefs[Keys.ALLOWLIST] = (prefs[Keys.ALLOWLIST] ?: emptySet()) + packageName
        }
    }

    suspend fun remove(packageName: String) {
        context.quietBuzzDataStore.edit { prefs ->
            prefs[Keys.ALLOWLIST] = (prefs[Keys.ALLOWLIST] ?: emptySet()) - packageName
        }
    }
}
