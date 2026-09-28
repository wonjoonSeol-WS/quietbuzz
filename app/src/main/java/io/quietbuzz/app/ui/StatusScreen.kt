package io.quietbuzz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.quietbuzz.app.R
import io.quietbuzz.app.companion.CompanionDeviceLinker
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.data.PassSummary
import io.quietbuzz.app.model.PendingAction
import io.quietbuzz.app.util.SilenceDispatch
import io.quietbuzz.app.util.SilenceListenerServiceHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatusScreen(
    companionDeviceLinker: CompanionDeviceLinker,
    passStateRepository: PassStateRepository,
    allowlistRepository: AllowlistRepository,
    permissionState: AppPermissionState,
    snackbarHostState: SnackbarHostState,
    onOpenNotificationAccessSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isListenerConnected by SilenceListenerServiceHolder.instance.collectAsState()
    val associationLost by passStateRepository.associationLost.collectAsState(initial = false)
    val includeSystemApps by passStateRepository.includeSystemApps.collectAsState(initial = false)
    val lastPassSummary by passStateRepository.lastPassSummary.collectAsState(
        initial = PassSummary(null, 0, 0, 0),
    )
    val allowlist by allowlistRepository.allowlist.collectAsState(initial = emptySet())
    var pendingConfirm by remember { mutableStateOf<BulkAction?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)

        StatusRow(
            stringResource(R.string.status_notification_access),
            if (permissionState.notificationAccessGranted) stringResource(R.string.status_granted) else stringResource(R.string.status_not_granted),
        )
        StatusRow(
            stringResource(R.string.status_device_linked),
            if (permissionState.associationCount > 0) {
                stringResource(R.string.status_yes_with_count, permissionState.associationCount)
            } else {
                stringResource(R.string.status_no)
            },
        )
        StatusRow(
            stringResource(R.string.status_listener_connected),
            if (isListenerConnected != null) stringResource(R.string.status_listener_yes) else stringResource(R.string.status_listener_no),
        )
        StatusRow(stringResource(R.string.status_allowlist_size), stringResource(R.string.status_apps_count, allowlist.size))
        StatusRow(
            stringResource(R.string.status_last_pass),
            lastPassSummary.lastRunAtMillis?.let { millis ->
                buildString {
                    append(formatTimestamp(millis))
                    append(", ")
                    append(stringResource(R.string.status_last_pass_summary, lastPassSummary.appsChanged, lastPassSummary.channelsChanged))
                    if (lastPassSummary.failures > 0) append(stringResource(R.string.status_failures_suffix, lastPassSummary.failures))
                }
            } ?: stringResource(R.string.status_never),
        )

        if (associationLost) {
            Text(
                stringResource(R.string.status_association_lost),
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        Button(onClick = {
            companionDeviceLinker.linkDevice(
                onCreated = {
                    permissionState.refreshNow(context, companionDeviceLinker)
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.status_linked_confirm)) }
                },
                onFailure = { error ->
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.status_link_failed, error.toString())) }
                },
            )
        }) { Text(stringResource(R.string.status_link_device)) }

        OutlinedButton(onClick = onOpenNotificationAccessSettings) {
            Text(stringResource(R.string.status_open_notification_settings))
        }

        HorizontalDivider()

        Button(onClick = { pendingConfirm = BulkAction.Run }) { Text(stringResource(R.string.status_run_now)) }

        OutlinedButton(onClick = { pendingConfirm = BulkAction.Restore }) { Text(stringResource(R.string.status_restore_all)) }
        ButtonHint(stringResource(R.string.status_restore_all_hint))

        OutlinedButton(onClick = { pendingConfirm = BulkAction.Reset }) { Text(stringResource(R.string.status_reset_all)) }
        ButtonHint(stringResource(R.string.status_reset_all_hint))

        pendingConfirm?.let { action ->
            AlertDialog(
                onDismissRequest = { pendingConfirm = null },
                title = { Text(stringResource(action.title)) },
                text = { Text(stringResource(action.body)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirm = null
                        scope.launch {
                            val includeSystemAppsNow = passStateRepository.includeSystemApps.first()
                            val ranNow = SilenceDispatch.runOrQueue(context, passStateRepository, action.pending) {
                                when (action) {
                                    BulkAction.Run -> it.runSilenceAllNow(includeSystemAppsNow)
                                    BulkAction.Restore -> it.restoreAllNow()
                                    BulkAction.Reset -> it.resetAllToDefaultsNow(includeSystemAppsNow)
                                }
                            }
                            snackbarHostState.showSnackbar(context.getString(if (ranNow) action.done else action.queued))
                        }
                    }) { Text(stringResource(action.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirm = null }) { Text(stringResource(R.string.status_reset_cancel)) }
                },
            )
        }

        HorizontalDivider()

        CategoryExplainer()

        HorizontalDivider()

        Text(stringResource(R.string.status_advanced), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.status_include_system_apps))
            Switch(
                checked = includeSystemApps,
                onCheckedChange = { scope.launch { passStateRepository.setIncludeSystemApps(it) } },
            )
        }
    }
}

/** The three Status buttons that change every app at once, each confirmed before it runs. */
private enum class BulkAction(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val confirm: Int,
    @StringRes val done: Int,
    @StringRes val queued: Int,
    val pending: PendingAction,
) {
    Run(R.string.status_run_title, R.string.status_run_body, R.string.status_run_confirm, R.string.status_pass_complete, R.string.status_queued_run, PendingAction.SilenceAll),
    Restore(R.string.status_restore_title, R.string.status_restore_body, R.string.status_restore_confirm, R.string.status_restored_confirm, R.string.status_queued_restore, PendingAction.RestoreAll),
    Reset(R.string.status_reset_title, R.string.status_reset_body, R.string.status_reset_confirm, R.string.status_reset_done, R.string.status_queued_reset, PendingAction.ResetAll),
}

@Composable
private fun ButtonHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatTimestamp(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}
