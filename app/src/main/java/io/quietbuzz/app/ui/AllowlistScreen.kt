package io.quietbuzz.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.InstalledApp
import io.quietbuzz.app.data.InstalledAppsRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.data.SettingsExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AllowlistScreen(
    allowlistRepository: AllowlistRepository,
    installedAppsRepository: InstalledAppsRepository,
    passStateRepository: PassStateRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowlist by allowlistRepository.allowlist.collectAsState(initial = emptySet())

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var onlyWithNotificationsOn by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

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
                statusMessage = "Exported ${allowlist.size} apps."
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
                        statusMessage = "Imported ${imported.allowlist.size} apps."
                    } catch (e: Exception) {
                        statusMessage = "Import failed: not a valid QuietBuzz settings file."
                    }
                } else {
                    statusMessage = "Import failed: couldn't read that file."
                }
            }
        }
    }

    val filtered = remember(installedApps, allowlist, showSystemApps, onlyWithNotificationsOn, query) {
        installedApps.filter { app ->
            (showSystemApps || !app.isSystemApp) &&
                (!onlyWithNotificationsOn || app.notificationsLikelyOn || app.packageName in allowlist) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Allowlist", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            OutlinedButton(onClick = { exportLauncher.launch("quietbuzz-settings.json") }) {
                Text("Export")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text("Import")
            }
        }
        statusMessage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search installed apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = onlyWithNotificationsOn, onCheckedChange = { onlyWithNotificationsOn = it })
            Text("Only apps with notifications on")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
            Text("Show system apps")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("${allowlist.size} allowed -- ${filtered.size} shown", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in allowlist
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    if (isChecked) allowlistRepository.add(app.packageName)
                                    else allowlistRepository.remove(app.packageName)
                                }
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
