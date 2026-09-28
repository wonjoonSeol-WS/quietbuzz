package io.quietbuzz.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * In-app visible log, separate from Log.i/Log.w (which only go to adb logcat -- useless without a
 * working USB connection). Backed by a StateFlow so the Status screen can show it live and a
 * "Copy" button can grab the full text to paste elsewhere.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 1000
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    @Synchronized
    fun add(message: String) {
        val timestamped = "${LocalTime.now().format(formatter)}  $message"
        _entries.value = (_entries.value + timestamped).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun asText(): String = _entries.value.joinToString("\n")
}
