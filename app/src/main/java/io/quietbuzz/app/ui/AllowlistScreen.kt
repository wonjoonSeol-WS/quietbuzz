package io.quietbuzz.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.quietbuzz.app.R
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.InstalledApp
import io.quietbuzz.app.data.InstalledAppsRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.data.SettingsExport
import io.quietbuzz.app.model.PendingAction
import io.quietbuzz.app.util.SilenceDispatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AllowlistScreen(
    allowlistRepository: AllowlistRepository,
    installedAppsRepository: InstalledAppsRepository,
    passStateRepository: PassStateRepository,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowlist by allowlistRepository.allowlist.collectAsState(initial = emptySet())

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var onlyWithNotificationsOn by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        installedApps = installedAppsRepository.listInstalledApps()
        loading = false
    }

    // CreateDocument/OpenDocument open the system file picker, which lists Google Drive (or any
    // other cloud provider app you have installed) as a save/open location -- no Drive API, no
    // sign-in flow needed just to carry the allowlist to a reinstall or a second device.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val includeSystemApps = passStateRepository.includeSystemApps.first()
                val json = SettingsExport.toJson(allowlist, includeSystemApps)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                snackbarHostState.showSnackbar(context.getString(R.string.allowlist_exported, allowlist.size))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (raw != null) {
                    try {
                        val imported = SettingsExport.fromJson(raw)
                        allowlistRepository.setAllowlist(imported.allowlist)
                        passStateRepository.setIncludeSystemApps(imported.includeSystemApps)
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.allowlist_imported, imported.allowlist.size),
                        )
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(context.getString(R.string.allowlist_import_failed_invalid))
                    }
                } else {
                    snackbarHostState.showSnackbar(context.getString(R.string.allowlist_import_failed_read))
                }
            }
        }
    }

    val filtered = remember(installedApps, allowlist, showSystemApps, onlyWithNotificationsOn, query) {
        // A non-blank query is a deliberate "find this app" search -- it bypasses the two
        // display-declutter filters below rather than being silently gated by them, so typing an
        // app's exact name always finds it if it's installed, regardless of the notifications-on
        // heuristic or the system-app toggle.
        val searching = query.isNotBlank()
        installedApps.filter { app ->
            (searching || showSystemApps || !app.isSystemApp) &&
                (searching || !onlyWithNotificationsOn || app.notificationsLikelyOn || app.packageName in allowlist) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }
    }
    val (selected, others) = remember(filtered, allowlist) {
        filtered.partition { it.packageName in allowlist }
    }

    // Allowlisting restores the app right away and un-allowlisting silences it right away, rather
    // than waiting for the next Run now / Restore all.
    fun setAllowlisted(app: InstalledApp, allowlisted: Boolean) {
        scope.launch {
            val pkg = app.packageName
            if (allowlisted) {
                allowlistRepository.add(pkg)
                val ranNow = SilenceDispatch.runOrQueue(context, passStateRepository, PendingAction.RestoreOne(pkg)) {
                    it.restorePackageNow(pkg)
                }
                snackbarHostState.showSnackbar(
                    if (ranNow) context.getString(R.string.allowlist_restored_one, app.label)
                    else context.getString(R.string.status_queued_restore),
                )
            } else {
                allowlistRepository.remove(pkg)
                val ranNow = SilenceDispatch.runOrQueue(context, passStateRepository, PendingAction.SilenceOne(pkg)) {
                    it.runSilenceForPackageNow(pkg)
                }
                snackbarHostState.showSnackbar(
                    if (ranNow) context.getString(R.string.allowlist_silenced_one, app.label)
                    else context.getString(R.string.status_queued_run),
                )
            }
        }
    }

    fun openAppNotificationSettings(pkg: String) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.allowlist_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            OutlinedButton(onClick = { exportLauncher.launch("quietbuzz-settings.json") }) {
                Text(stringResource(R.string.allowlist_export))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text(stringResource(R.string.allowlist_import))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.allowlist_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = onlyWithNotificationsOn, onCheckedChange = { onlyWithNotificationsOn = it })
            Text(stringResource(R.string.allowlist_only_notifications_on))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
            Text(stringResource(R.string.allowlist_show_system_apps))
        }

        Text(
            stringResource(R.string.allowlist_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.allowlist_summary, allowlist.size, filtered.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                if (selected.isNotEmpty()) {
                    item(key = "header-allowed") {
                        SectionHeader(stringResource(R.string.allowlist_section_allowed, selected.size))
                    }
                    items(selected, key = { it.packageName }) { app ->
                        AppRow(
                            app,
                            checked = true,
                            onCheckedChange = { setAllowlisted(app, it) },
                            onOpenSettings = { openAppNotificationSettings(app.packageName) },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "header-all") {
                        SectionHeader(stringResource(R.string.allowlist_section_all_apps))
                    }
                    items(others, key = { it.packageName }) { app ->
                        AppRow(
                            app,
                            checked = false,
                            onCheckedChange = { setAllowlisted(app, it) },
                            onOpenSettings = { openAppNotificationSettings(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(app.packageName, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.allowlist_open_app_settings)) }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val shape = RoundedCornerShape(8.dp)
    val icon = app.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(shape),
        )
    } else {
        Box(modifier = Modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
