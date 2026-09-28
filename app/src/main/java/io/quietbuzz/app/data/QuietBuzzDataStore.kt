package io.quietbuzz.app.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.quietBuzzDataStore by preferencesDataStore(name = "quietbuzz_prefs")
