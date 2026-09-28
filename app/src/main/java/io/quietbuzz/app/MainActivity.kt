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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.quietbuzz.app.companion.CompanionDeviceLinker
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.InstalledAppsRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.ui.AllowlistScreen
import io.quietbuzz.app.ui.StatusScreen
import io.quietbuzz.app.ui.theme.QuietBuzzTheme

private enum class Screen { Status, Allowlist }

class MainActivity : ComponentActivity() {

    private lateinit var companionDeviceLinker: CompanionDeviceLinker

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* screens re-check granted state themselves; nothing to do with the result map here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        companionDeviceLinker = CompanionDeviceLinker(this)
        requestStartupPermissions()

        val allowlistRepository = AllowlistRepository(applicationContext)
        val passStateRepository = PassStateRepository(applicationContext)
        val installedAppsRepository = InstalledAppsRepository(applicationContext)

        setContent {
            QuietBuzzTheme {
                var screen by remember { mutableStateOf(Screen.Status) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == Screen.Status,
                                onClick = { screen = Screen.Status },
                                icon = {},
                                label = { Text("Status") },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.Allowlist,
                                onClick = { screen = Screen.Allowlist },
                                icon = {},
                                label = { Text("Allowlist") },
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
                                onOpenNotificationAccessSettings = ::openNotificationAccessSettings,
                            )
                            Screen.Allowlist -> AllowlistScreen(
                                allowlistRepository = allowlistRepository,
                                installedAppsRepository = installedAppsRepository,
                                passStateRepository = passStateRepository,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestStartupPermissions() {
        val needed = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
