package io.quietbuzz.app.util

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.model.PendingAction
import io.quietbuzz.app.service.SilenceListenerService

/**
 * Every trigger (manual buttons, the install receiver, the nightly worker) needs the same thing:
 * run against the live listener instance if one is connected, otherwise queue a fallback action
 * and force a rebind so onListenerConnected picks it up later. Centralized here so that logic
 * exists in exactly one place instead of being re-derived at each call site.
 */
object SilenceDispatch {

    /** Returns true if [onLive] ran immediately, false if [fallbackAction] was queued instead. */
    suspend fun runOrQueue(
        context: Context,
        passStateRepository: PassStateRepository,
        fallbackAction: PendingAction,
        onLive: suspend (SilenceListenerService) -> Unit,
    ): Boolean {
        val live = SilenceListenerServiceHolder.instance.value
        return if (live != null) {
            DiagnosticLog.add("Dispatch: listener live, running $fallbackAction directly")
            onLive(live)
            true
        } else {
            DiagnosticLog.add("Dispatch: listener NOT live, queuing $fallbackAction and requesting rebind")
            passStateRepository.setPendingAction(fallbackAction)
            NotificationListenerService.requestRebind(
                ComponentName(context, SilenceListenerService::class.java),
            )
            false
        }
    }
}
