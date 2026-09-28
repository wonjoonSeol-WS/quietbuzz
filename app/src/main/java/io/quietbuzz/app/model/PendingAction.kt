package io.quietbuzz.app.model

/**
 * What the listener should do the next time it (re)connects. This is the fallback path only:
 * the everyday triggers (install events, channel changes, manual buttons) call directly into
 * the live [io.quietbuzz.app.util.SilenceListenerServiceHolder] instance. A pending action only
 * matters when that instance is momentarily null (listener not currently bound) and we had to
 * fall back to `NotificationListenerService.requestRebind`.
 */
sealed class PendingAction {
    data object None : PendingAction()
    data object SilenceAll : PendingAction()
    data class SilenceOne(val packageName: String) : PendingAction()
    data object RestoreAll : PendingAction()
    data class RestoreOne(val packageName: String) : PendingAction()
    data object ResetAll : PendingAction()
}
