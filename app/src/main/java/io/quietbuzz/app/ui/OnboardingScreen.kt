package io.quietbuzz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.quietbuzz.app.R
import io.quietbuzz.app.companion.CompanionDeviceLinker
import kotlinx.coroutines.launch

/**
 * Explains each permission before its system prompt appears. Notification access and a linked
 * device are required to continue; QuietBuzz's own notification permission is optional, since it
 * only powers the "please re-link" alert.
 */
@Composable
fun OnboardingScreen(
    permissionState: AppPermissionState,
    companionDeviceLinker: CompanionDeviceLinker,
    snackbarHostState: SnackbarHostState,
    onOpenNotificationAccessSettings: () -> Unit,
    onRequestBluetoothThenLink: (link: () -> Unit) -> Unit,
    onRequestPostNotifications: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requiredDone = permissionState.notificationAccessGranted && permissionState.associationCount > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyMedium)

        PermissionCard(
            title = stringResource(R.string.onboarding_notification_access_title),
            body = stringResource(R.string.onboarding_notification_access_body),
            done = permissionState.notificationAccessGranted,
            doneLabel = stringResource(R.string.onboarding_status_granted),
            notDoneLabel = stringResource(R.string.onboarding_status_not_granted),
            hint = stringResource(R.string.onboarding_restricted_settings_hint),
            actionLabel = stringResource(R.string.onboarding_action_open_settings),
            onAction = onOpenNotificationAccessSettings,
        )

        PermissionCard(
            title = stringResource(R.string.onboarding_bluetooth_title),
            body = stringResource(R.string.onboarding_bluetooth_body),
            done = permissionState.associationCount > 0,
            doneLabel = stringResource(R.string.onboarding_status_linked),
            notDoneLabel = stringResource(R.string.onboarding_status_not_linked),
            actionLabel = stringResource(R.string.onboarding_action_link_device),
            onAction = {
                onRequestBluetoothThenLink {
                    companionDeviceLinker.linkDevice(
                        onCreated = { permissionState.refreshNow(context, companionDeviceLinker) },
                        onFailure = { error ->
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.onboarding_link_failed, error.toString()))
                            }
                        },
                    )
                }
            },
        )

        PermissionCard(
            title = stringResource(R.string.onboarding_notifications_title),
            body = stringResource(R.string.onboarding_notifications_body),
            done = permissionState.postNotificationsGranted,
            doneLabel = stringResource(R.string.onboarding_status_granted),
            notDoneLabel = stringResource(R.string.onboarding_status_not_granted),
            actionLabel = stringResource(R.string.onboarding_action_allow_notifications),
            onAction = onRequestPostNotifications,
        )

        CategoryExplainer(modifier = Modifier.padding(vertical = 8.dp))

        if (!requiredDone) {
            Text(
                stringResource(R.string.onboarding_continue_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onContinue, enabled = requiredDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_continue))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    done: Boolean,
    doneLabel: String,
    notDoneLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    hint: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (done) doneLabel else notDoneLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
