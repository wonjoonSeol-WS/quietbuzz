package io.quietbuzz.app.companion

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Wraps CompanionDeviceManager.associate(). The association itself is just a permission token:
 * package + device MAC recorded by the system. No Bluetooth connection is held, no code runs on
 * the paired device, and it doesn't need to be present again afterwards -- it exists purely so
 * NotificationListenerService.getNotificationChannels/updateNotificationChannel stop throwing
 * SecurityException (see README). Deliberately never calls setDeviceProfile(): that would ask
 * for a specific role like WATCH, which needs extra justification we don't need here.
 *
 * No device-name filter: the request has no addDeviceFilter(), so the system shows every nearby
 * device it can find and you pick one yourself -- it doesn't matter which, since nothing about
 * the chosen device is used afterwards.
 */
class CompanionDeviceLinker(private val activity: ComponentActivity) {

    private val deviceManager: CompanionDeviceManager =
        activity.getSystemService(CompanionDeviceManager::class.java)

    private val intentSenderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // Result is observed through the associate() callback's onAssociationCreated/onFailure
        // below, not through this launcher's own result -- nothing to do here.
    }

    val currentAssociations: List<AssociationInfo>
        get() = deviceManager.myAssociations

    fun linkDevice(
        onCreated: (AssociationInfo) -> Unit,
        onFailure: (CharSequence) -> Unit,
    ) {
        val request = AssociationRequest.Builder().setSingleDevice(false).build()

        deviceManager.associate(
            request,
            ContextCompat.getMainExecutor(activity),
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    onCreated(associationInfo)
                }

                override fun onFailure(error: CharSequence?) {
                    onFailure(error ?: "Unknown failure")
                }
            },
        )
    }
}
