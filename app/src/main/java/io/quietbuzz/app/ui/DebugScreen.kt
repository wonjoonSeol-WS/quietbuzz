package io.quietbuzz.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.InstalledApp
import io.quietbuzz.app.data.InstalledAppsRepository
import io.quietbuzz.app.util.DiagnosticLog
import io.quietbuzz.app.util.SilenceListenerServiceHolder
import kotlinx.coroutines.launch

/**
 * Raw, unbacked-up per-channel flag toggles for one app at a time -- exists purely to figure out
 * which of importance / sound / vibration is actually what Samsung's "소리 및 진동" toggle reflects,
 * by flipping one flag, then checking that Settings page. Deliberately bypasses SilencePass/backup
 * entirely: nothing here is restorable by "모두 복원," so it can (and does) null out a custom sound
 * if you flip that switch -- this is a test bench, not something to leave changes in.
 */
@Composable
fun DebugScreen(installedAppsRepository: InstalledAppsRepository, allowlistRepository: AllowlistRepository) {
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    // NotificationChannel is a mutable Android object, not an immutable value -- applyChange()
    // mutates one in place before writing it back into this list, so the "old" and "new" list
    // would contain the exact same (already-changed) object and compare structurally equal,
    // silently defeating Compose's default change detection. neverEqualPolicy forces every
    // reassignment here to recompose regardless.
    var channels by remember { mutableStateOf<List<NotificationChannel>>(emptyList(), neverEqualPolicy()) }
    var query by remember { mutableStateOf("") }
    val listener by SilenceListenerServiceHolder.instance.collectAsState()
    val allowlist by allowlistRepository.allowlist.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apps = installedAppsRepository.listInstalledApps()
    }

    fun fetchChannels() {
        val pkg = selectedPackage
        val live = listener
        channels = if (pkg != null && live != null) {
            try {
                live.getNotificationChannels(pkg, Process.myUserHandle()).toList()
            } catch (e: Exception) {
                DiagnosticLog.add("WARN Debug: failed to read channels for $pkg (${e.message})")
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(selectedPackage, listener) {
        fetchChannels()
    }

    // Deliberately does NOT re-fetch from the OS after a successful write: the system appears to
    // persist a channel update asynchronously, so an immediate read-back races ahead of it and
    // returns the pre-write value (confirmed on-device -- leaving and returning to this screen,
    // which re-fetches after a natural delay via the LaunchedEffect above, shows the real value).
    // Trusting the object we just wrote and patching it into local state avoids that race entirely.
    fun applyChange(channel: NotificationChannel) {
        val pkg = selectedPackage ?: return
        val live = listener ?: return
        try {
            live.updateNotificationChannel(pkg, Process.myUserHandle(), channel)
            DiagnosticLog.add(
                "Debug: $pkg/${channel.id} -> importance=${channel.importance}, " +
                    "sound=${channel.sound ?: "null"}, vibration=${channel.shouldVibrate()}",
            )
            channels = channels.map { if (it.id == channel.id) channel else it }
        } catch (e: Exception) {
            DiagnosticLog.add("WARN Debug: failed to update $pkg/${channel.id} (${e.message})")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Flag debugger", style = MaterialTheme.typography.titleMedium)
        Text(
            "Flip importance / sound / vibration independently per channel, then check the app's " +
                "notification settings page to see which one moves it. Changes here are immediate, " +
                "raw, and NOT backed up -- \"모두 복원\" won't undo anything done on this screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))

        val selectedApp = apps.firstOrNull { it.packageName == selectedPackage }
        if (selectedApp != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(selectedApp.label, style = MaterialTheme.typography.titleMedium)
                    Text(selectedApp.packageName, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { selectedPackage = null; query = "" }) { Text("Change") }
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search installed apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            val matches = remember(apps, query) {
                if (query.isBlank()) {
                    emptyList()
                } else {
                    apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(matches, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selectedPackage = app.packageName }
                            .padding(vertical = 8.dp),
                    ) {
                        Column {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (query.isNotBlank() && matches.isEmpty()) {
                    item { Text("No installed app matches \"$query\".", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (listener == null) {
            Text(
                "Listener not connected. Open notification access settings once so it binds.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        selectedPackage?.let { pkg ->
            val isAllowlisted = pkg in allowlist
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isAllowlisted) {
                        "On allowlist -- QuietBuzz never touches this app's channels"
                    } else {
                        "NOT on allowlist -- QuietBuzz will silence this app again on the next pass or reinstall, undoing anything set here or in the app's own settings"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAllowlisted) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
                Switch(
                    checked = isAllowlisted,
                    onCheckedChange = { checked ->
                        scope.launch {
                            if (checked) allowlistRepository.add(pkg) else allowlistRepository.remove(pkg)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Switches below show what we last wrote, not necessarily current device truth.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { fetchChannels() }) { Text("Refresh from device") }
        }
        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(channels, key = { it.id }) { channel ->
                ChannelDebugRow(channel = channel, onChange = ::applyChange)
            }
        }
    }
}

@Composable
private fun ChannelDebugRow(channel: NotificationChannel, onChange: (NotificationChannel) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(channel.id, style = MaterialTheme.typography.titleSmall)
            Text(channel.name?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text(
                "importance = ${importanceLabel(channel.importance)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (channel.importance == NotificationManager.IMPORTANCE_NONE) MaterialTheme.colorScheme.error else Color.Unspecified,
            )

            // Polarity is deliberately "on (blue) = loud/enabled" for all three, matching what a
            // switch normally means -- checked never means "this is suppressed."
            FlagRow(
                label = "Importance: DEFAULT (off = LOW)",
                checked = channel.importance > NotificationManager.IMPORTANCE_LOW,
                onCheckedChange = { checked ->
                    channel.importance = if (checked) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
                    onChange(channel)
                },
            )
            FlagRow(
                label = "Sound",
                checked = channel.sound != null,
                onCheckedChange = { checked ->
                    val soundUri: Uri? = if (checked) Settings.System.DEFAULT_NOTIFICATION_URI else null
                    channel.setSound(soundUri, channel.audioAttributes)
                    onChange(channel)
                },
            )
            FlagRow(
                label = "Vibration",
                checked = channel.shouldVibrate(),
                onCheckedChange = { checked ->
                    channel.enableVibration(checked)
                    // Per NotificationChannel.setVibrationPattern()'s own contract, null means
                    // "use this device's default vibration pattern" -- it is the correct way to
                    // ask for default behavior, not a fallback to guess a pattern for ourselves.
                    channel.vibrationPattern = null
                    onChange(channel)
                },
            )
        }
    }
}

private fun importanceLabel(importance: Int): String = when (importance) {
    NotificationManager.IMPORTANCE_NONE -> "NONE (category turned off)"
    NotificationManager.IMPORTANCE_MIN -> "MIN"
    NotificationManager.IMPORTANCE_LOW -> "LOW"
    NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
    NotificationManager.IMPORTANCE_HIGH -> "HIGH (pop-up)"
    else -> importance.toString()
}

@Composable
private fun FlagRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
