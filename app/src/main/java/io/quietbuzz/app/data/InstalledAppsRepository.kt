package io.quietbuzz.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.quietbuzz.app.util.isSystemApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val notificationsLikelyOn: Boolean,
)

/**
 * Backs the allowlist picker's "installed apps" list. `notificationsLikelyOn` is a heuristic,
 * not a guarantee: on API 33+ we check the granted POST_NOTIFICATIONS runtime permission, which
 * is the strongest public signal available to a normal (non-listener) query. Pre-33-targeting
 * apps don't declare that permission at all -- notifications default to on for them unless the
 * user disabled them in Settings, which isn't readable without an active listener connection, so
 * we default those to "likely on" rather than hiding them from the picker.
 *
 * Uses getInstalledPackages(GET_PERMISSIONS) once rather than a per-app checkPermission/
 * getPackageInfo call: for ~150-300 installed apps that's one bulk Binder call instead of up to
 * 2 x N of them.
 */
class InstalledAppsRepository(private val context: Context) {

    suspend fun listInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        packages
            .filter { it.packageName != context.packageName }
            .map { packageInfo ->
                val appInfo = packageInfo.applicationInfo
                InstalledApp(
                    packageName = packageInfo.packageName,
                    label = appInfo?.loadLabel(pm)?.toString() ?: packageInfo.packageName,
                    isSystemApp = appInfo?.isSystemApp() ?: false,
                    notificationsLikelyOn = notificationsLikelyOn(packageInfo),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun notificationsLikelyOn(packageInfo: PackageInfo): Boolean {
        val permissions = packageInfo.requestedPermissions ?: return true
        val flags = packageInfo.requestedPermissionsFlags ?: return true
        val index = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
        if (index == -1) return true // doesn't declare it -- defaults to on for a pre-33-style app
        return (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }
}
