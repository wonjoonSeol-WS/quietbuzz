package io.quietbuzz.app.util

import io.quietbuzz.app.service.SilenceListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live reference to the currently-connected listener instance, set in onListenerConnected and
 * cleared in onListenerDisconnected. Safe as a singleton because the service and the rest of the
 * app share one process (no android:process override in the manifest), and because Android only
 * ever runs one instance of a given service class per process.
 *
 * Everyday callers (manual buttons, the install receiver) call directly through this instead of
 * going through requestRebind + a persisted pending action, since the listener stays bound rather
 * than unbinding after each pass. "Connected right now" is simply `instance.value != null`.
 */
object SilenceListenerServiceHolder {
    private val _instance = MutableStateFlow<SilenceListenerService?>(null)
    val instance: StateFlow<SilenceListenerService?> = _instance

    fun onConnected(service: SilenceListenerService) {
        _instance.value = service
    }

    fun onDisconnected() {
        _instance.value = null
    }
}
