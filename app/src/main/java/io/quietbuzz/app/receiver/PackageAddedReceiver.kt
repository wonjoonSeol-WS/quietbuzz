package io.quietbuzz.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.model.PendingAction
import io.quietbuzz.app.util.SilenceDispatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Catches new app installs (not updates -- EXTRA_REPLACING guards that) so they get an immediate
 * silence pass instead of waiting for you to notice and tap "Run now" yourself.
 */
class PackageAddedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val packageName = intent.data?.schemeSpecificPart ?: return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SilenceDispatch.runOrQueue(
                    appContext,
                    PassStateRepository(appContext),
                    PendingAction.SilenceOne(packageName),
                ) { it.runSilenceForPackageNow(packageName) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
