package io.quietbuzz.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.quietbuzz.app.companion.CompanionDeviceLinker
import io.quietbuzz.app.util.isNotificationAccessGranted
import io.quietbuzz.app.util.isPostNotificationsGranted

/**
 * Single source of truth for the three permission-y things QuietBuzz depends on, shared by
 * MainActivity (to decide onboarding vs. the main app), StatusScreen, and OnboardingScreen, so
 * none of them tracks its own independent copy that could drift out of sync with the others.
 */
class AppPermissionState(
    notificationAccessGranted: Boolean,
    associationCount: Int,
    postNotificationsGranted: Boolean,
) {
    var notificationAccessGranted by mutableStateOf(notificationAccessGranted)
        private set
    var associationCount by mutableStateOf(associationCount)
        private set
    var postNotificationsGranted by mutableStateOf(postNotificationsGranted)
        private set

    val isFullySetUp: Boolean
        get() = notificationAccessGranted && associationCount > 0 && postNotificationsGranted

    /**
     * Re-reads every value immediately, for callers that just performed an in-app action (e.g.
     * completing a device link) and can't wait for the next ON_RESUME to see it reflected.
     */
    fun refreshNow(context: Context, companionDeviceLinker: CompanionDeviceLinker) {
        notificationAccessGranted = isNotificationAccessGranted(context)
        associationCount = companionDeviceLinker.currentAssociations.size
        postNotificationsGranted = isPostNotificationsGranted(context)
    }
}

@Composable
fun rememberAppPermissionState(companionDeviceLinker: CompanionDeviceLinker): AppPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val state = remember {
        AppPermissionState(
            notificationAccessGranted = isNotificationAccessGranted(context),
            associationCount = companionDeviceLinker.currentAssociations.size,
            postNotificationsGranted = isPostNotificationsGranted(context),
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refreshNow(context, companionDeviceLinker)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}
