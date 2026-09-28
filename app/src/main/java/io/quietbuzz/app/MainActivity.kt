package io.quietbuzz.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.quietbuzz.app.companion.CompanionDeviceLinker
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.InstalledAppsRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.ui.AllowlistScreen
import io.quietbuzz.app.ui.DebugScreen
import io.quietbuzz.app.ui.LogScreen
import io.quietbuzz.app.ui.OnboardingScreen
import io.quietbuzz.app.ui.StatusScreen
import io.quietbuzz.app.ui.rememberAppPermissionState
import io.quietbuzz.app.ui.theme.QuietBuzzTheme

private enum class Screen { Status, Allowlist, Log, Debug }

class MainActivity : ComponentActivity() {

    private lateinit var companionDeviceLinker: CompanionDeviceLinker

    private var afterPermissionRequest: (() -> Unit)? = null

    // Granted state is re-read on ON_RESUME by AppPermissionState; the result map isn't needed.
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        afterPermissionRequest?.invoke()
        afterPermissionRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        companionDeviceLinker = CompanionDeviceLinker(this)

        val allowlistRepository = AllowlistRepository(applicationContext)
        val passStateRepository = PassStateRepository(applicationContext)
        val installedAppsRepository = InstalledAppsRepository(applicationContext)

        setContent {
            QuietBuzzTheme {
                var screen by remember { mutableStateOf(Screen.Status) }
                val permissionState = rememberAppPermissionState(companionDeviceLinker)
                val snackbarHostState = remember { SnackbarHostState() }
                // Shown on every launch where a required permission is missing, so a later
                // revocation brings the explanation back instead of leaving a silently broken app.
                var onboardingDone by rememberSaveable {
                    mutableStateOf(permissionState.notificationAccessGranted && permissionState.associationCount > 0)
                }

                if (!onboardingDone) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            OnboardingScreen(
                                permissionState = permissionState,
                                companionDeviceLinker = companionDeviceLinker,
                                snackbarHostState = snackbarHostState,
                                onOpenNotificationAccessSettings = ::openNotificationAccessSettings,
                                onRequestBluetoothThenLink = { link ->
                                    requestThen(
                                        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                                        link,
                                    )
                                },
                                onRequestPostNotifications = {
                                    requestThen(listOf(Manifest.permission.POST_NOTIFICATIONS))
                                },
                                onContinue = { onboardingDone = true },
                            )
                        }
                    }
                    return@QuietBuzzTheme
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == Screen.Status,
                                onClick = { screen = Screen.Status },
                                icon = {},
                                label = { Text(stringResource(R.string.nav_status)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.Allowlist,
                                onClick = { screen = Screen.Allowlist },
                                icon = {},
                                label = { Text(stringResource(R.string.nav_allowlist)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.Log,
                                onClick = { screen = Screen.Log },
                                icon = {},
                                label = { Text(stringResource(R.string.nav_log)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.Debug,
                                onClick = { screen = Screen.Debug },
                                icon = {},
                                label = { Text("Debug") },
                            )
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (screen) {
                            Screen.Status -> StatusScreen(
                                companionDeviceLinker = companionDeviceLinker,
                                passStateRepository = passStateRepository,
                                allowlistRepository = allowlistRepository,
                                permissionState = permissionState,
                                snackbarHostState = snackbarHostState,
                                onOpenNotificationAccessSettings = ::openNotificationAccessSettings,
                            )
                            Screen.Allowlist -> AllowlistScreen(
                                allowlistRepository = allowlistRepository,
                                installedAppsRepository = installedAppsRepository,
                                passStateRepository = passStateRepository,
                                snackbarHostState = snackbarHostState,
                            )
                            Screen.Log -> LogScreen(snackbarHostState = snackbarHostState)
                            Screen.Debug -> DebugScreen(
                                installedAppsRepository = installedAppsRepository,
                                allowlistRepository = allowlistRepository,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestThen(permissions: List<String>, then: () -> Unit = {}) {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            then()
        } else {
            afterPermissionRequest = then
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
