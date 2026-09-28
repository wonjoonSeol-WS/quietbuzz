package io.quietbuzz.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    onOpenNotificationAccessSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var associationCount by remember { mutableStateOf(companionDeviceLinker.currentAssociations.size) }
    val isListenerConnected by SilenceListenerServiceHolder.instance.collectAsState()
    val associationLost by passStateRepository.associationLost.collectAsState(initial = false)
    val includeSystemApps by passStateRepository.includeSystemApps.collectAsState(initial = false)
    val lastPassSummary by passStateRepository.lastPassSummary.collectAsState(
        initial = PassSummary(null, 0, 0, 0),
    )
    val allowlist by allowlistRepository.allowlist.collectAsState(initial = emptySet())
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // A plain LaunchedEffect(Unit) only runs once per composition and never re-fires when you
    // come back from a different screen (e.g. Settings) to this same Activity instance, so
    // "granted"/association count would go stale after granting access there. ON_RESUME catches
    // that return trip; it also covers the very first display, so nothing else is needed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted = isNotificationAccessGranted(context)
                associationCount = companionDeviceLinker.currentAssociations.size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("QuietBuzz", style = MaterialTheme.typography.titleLarge)

        StatusRow("Notification access", if (notificationAccessGranted) "Granted" else "Not granted")
        StatusRow("Device linked", if (associationCount > 0) "Yes ($associationCount)" else "No")
        StatusRow(
            "Listener connected right now",
            if (isListenerConnected != null) "Yes" else "No (normal when the app is idle)",
        )
        StatusRow("Allowlist size", "${allowlist.size} apps")
        StatusRow(
            "Last pass",
            buildString {
                append(formatTimestamp(lastPassSummary.lastRunAtMillis))
                append(" -- ")
                append("${lastPassSummary.appsChanged} apps / ${lastPassSummary.channelsChanged} channels changed")
                if (lastPassSummary.failures > 0) append(", ${lastPassSummary.failures} failures")
            },
        )

        if (associationLost) {
            Text(
                "Association lost -- re-link a device below before running a pass.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        HorizontalDivider()

        Button(onClick = {
            companionDeviceLinker.linkDevice(
                onCreated = {
                    associationCount = companionDeviceLinker.currentAssociations.size
                    statusMessage = "Linked."
                },
                onFailure = { statusMessage = "Link failed: $it" },
            )
        }) { Text("Link a device") }

        OutlinedButton(onClick = onOpenNotificationAccessSettings) {
            Text("Open notification access settings")
        }

        HorizontalDivider()

        Button(onClick = {
            scope.launch {
                val includeSystemAppsNow = passStateRepository.includeSystemApps.first()
                val ranNow = SilenceDispatch.runOrQueue(
                    context,
                    passStateRepository,
                    PendingAction.SilenceAll,
                ) { it.runSilenceAllNow(includeSystemAppsNow) }
                statusMessage = if (ranNow) {
                    "Silence pass complete."
                } else {
                    "Listener not connected -- queued, will run once it reconnects."
                }
            }
        }) { Text("Run now") }

        OutlinedButton(onClick = {
            scope.launch {
                val ranNow = SilenceDispatch.runOrQueue(
                    context,
                    passStateRepository,
                    PendingAction.RestoreAll,
                ) { it.restoreAllNow() }
                statusMessage = if (ranNow) {
                    "Restored."
                } else {
                    "Listener not connected -- queued, will restore once it reconnects."
                }
            }
        }) { Text("Restore all") }

        HorizontalDivider()

        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Include system apps in passes")
            Switch(
                checked = includeSystemApps,
                onCheckedChange = { scope.launch { passStateRepository.setIncludeSystemApps(it) } },
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun isNotificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun formatTimestamp(millis: Long?): String {
    if (millis == null) return "Never"
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}
