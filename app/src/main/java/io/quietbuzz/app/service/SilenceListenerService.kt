package io.quietbuzz.app.service

import android.service.notification.NotificationListenerService
import android.util.Log
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.logic.SilencePass
import io.quietbuzz.app.model.PassResult
import io.quietbuzz.app.model.PendingAction
import io.quietbuzz.app.util.NotificationHelper
import io.quietbuzz.app.util.SilenceListenerServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "SilenceListener"

/**
 * Stays bound for as long as notification access is granted -- this is the normal lifecycle for
 * any NotificationListenerService (event-driven callbacks, no polling, no foreground-service
 * icon), so there's no battery cost to leaving it connected rather than unbinding between passes.
 *
 * Live coverage is deliberately limited to new installs (see PackageAddedReceiver) plus the
 * manual "Run now" button -- an existing app adding a new channel later isn't caught until you
 * notice and run a pass yourself. That's an accepted tradeoff for keeping this service's only
 * job "run a full or single-package pass," with no per-channel-event reasoning to maintain.
 */
class SilenceListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var allowlistRepository: AllowlistRepository
    private lateinit var passStateRepository: PassStateRepository
    private lateinit var pass: SilencePass

    override fun onCreate() {
        super.onCreate()
        allowlistRepository = AllowlistRepository(applicationContext)
        passStateRepository = PassStateRepository(applicationContext)
        pass = SilencePass(this, packageManager, allowlistRepository, passStateRepository)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        SilenceListenerServiceHolder.onConnected(this)

        serviceScope.launch {
            allowlistRepository.seedDefaultsIfNeeded()
            val includeSystemApps = passStateRepository.includeSystemApps.first()
            val result = when (val pending = passStateRepository.consumePendingAction()) {
                is PendingAction.RestoreAll -> pass.restoreAll()
                is PendingAction.SilenceOne -> pass.runSilenceForPackage(pending.packageName)
                is PendingAction.SilenceAll, PendingAction.None -> pass.runSilenceAll(includeSystemApps)
            }
            handleAssociationState(result)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")
        SilenceListenerServiceHolder.onDisconnected()
    }

    override fun onDestroy() {
        super.onDestroy()
        SilenceListenerServiceHolder.onDisconnected()
        serviceScope.cancel()
    }

    private fun handleAssociationState(result: PassResult) {
        // PassStateRepository.recordResult (called inside every SilencePass method) already
        // persists result.associationLost -- nothing more to save here, just the user-facing alert.
        if (result.associationLost) {
            NotificationHelper.postRelinkPrompt(applicationContext)
        }
    }

    // Entry points for callers that already hold a live instance via SilenceListenerServiceHolder
    // (the UI's manual buttons, the install receiver).
    suspend fun runSilenceAllNow(includeSystemApps: Boolean): PassResult =
        pass.runSilenceAll(includeSystemApps).also { handleAssociationState(it) }

    suspend fun runSilenceForPackageNow(packageName: String): PassResult =
        pass.runSilenceForPackage(packageName).also { handleAssociationState(it) }

    suspend fun restoreAllNow(): PassResult =
        pass.restoreAll().also { handleAssociationState(it) }
}
