package io.quietbuzz.app.logic

import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Process
import android.service.notification.NotificationListenerService
import android.util.Log
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.model.PassResult
import io.quietbuzz.app.util.isSystemApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SilencePass"

/**
 * Packages excluded even when "include system apps" is on: "android" and SystemUI are the OS
 * shell itself, not an app whose notifications make sense to silence.
 */
private val ALWAYS_EXCLUDED = setOf("android", "com.android.systemui")

/**
 * The actual channel-rewriting logic. Every method here must run while `listener` is a connected
 * NotificationListenerService instance -- getNotificationChannels/updateNotificationChannel are
 * instance methods gated by the listener's CompanionDeviceManager association (see README), and
 * throw SecurityException the moment that association is gone.
 */
class SilencePass(
    private val listener: NotificationListenerService,
    private val packageManager: PackageManager,
    private val allowlistRepository: AllowlistRepository,
    private val passStateRepository: PassStateRepository,
) {
    private val user = Process.myUserHandle()

    suspend fun runSilenceAll(includeSystemApps: Boolean): PassResult = withContext(Dispatchers.IO) {
        val allowlist = allowlistRepository.allowlist.first()
        val ownPackage = listener.packageName

        var appsChanged = 0
        var channelsChanged = 0
        var failures = 0
        var associationLost = false
        val fullBackup = mutableMapOf<String, Map<String, Int>>()

        val installedApps = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName
            if (isExcluded(pkg, ownPackage, allowlist)) continue
            if (!includeSystemApps && appInfo.isSystemApp()) continue

            val outcome = silencePackage(pkg)
            channelsChanged += outcome.channelsChanged
            failures += outcome.failures
            if (outcome.channelsChanged > 0) {
                appsChanged++
                fullBackup[pkg] = outcome.backupEntries
            }
            if (outcome.associationLost) {
                associationLost = true
                break
            }
        }

        passStateRepository.mergeOriginalImportanceBackup(fullBackup)

        PassResult(appsChanged, channelsChanged, failures, associationLost).also {
            passStateRepository.recordResult(it)
        }
    }

    suspend fun runSilenceForPackage(packageName: String): PassResult = withContext(Dispatchers.IO) {
        val allowlist = allowlistRepository.allowlist.first()
        if (isExcluded(packageName, listener.packageName, allowlist)) {
            return@withContext PassResult(0, 0, 0, associationLost = false)
        }

        val outcome = silencePackage(packageName)
        if (outcome.backupEntries.isNotEmpty()) {
            passStateRepository.mergeOriginalImportanceBackup(mapOf(packageName to outcome.backupEntries))
        }

        PassResult(
            appsChanged = if (outcome.channelsChanged > 0) 1 else 0,
            channelsChanged = outcome.channelsChanged,
            failures = outcome.failures,
            associationLost = outcome.associationLost,
        ).also { passStateRepository.recordResult(it) }
    }

    private fun isExcluded(pkg: String, ownPackage: String, allowlist: Set<String>): Boolean =
        pkg == ownPackage || pkg in allowlist || pkg in ALWAYS_EXCLUDED

    private data class SinglePackageOutcome(
        val channelsChanged: Int,
        val failures: Int,
        val associationLost: Boolean,
        val backupEntries: Map<String, Int>,
    )

    /** Not suspend: called only from within the withContext(Dispatchers.IO) blocks above. */
    private fun silencePackage(packageName: String): SinglePackageOutcome {
        val channels = try {
            listener.getNotificationChannels(packageName, user)
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost CompanionDeviceManager association while reading channels for $packageName", e)
            return SinglePackageOutcome(0, 0, associationLost = true, backupEntries = emptyMap())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read channels for $packageName", e)
            return SinglePackageOutcome(0, 1, associationLost = false, backupEntries = emptyMap())
        }

        var changed = 0
        var failures = 0
        val backup = mutableMapOf<String, Int>()
        for (channel in channels) {
            if (channel.importance <= NotificationManager.IMPORTANCE_LOW) continue
            try {
                val originalImportance = channel.importance
                channel.importance = NotificationManager.IMPORTANCE_LOW
                listener.updateNotificationChannel(packageName, user, channel)
                backup[channel.id] = originalImportance
                changed++
            } catch (e: SecurityException) {
                Log.w(TAG, "Lost CompanionDeviceManager association while updating $packageName/${channel.id}", e)
                return SinglePackageOutcome(changed, failures, associationLost = true, backupEntries = backup)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update channel ${channel.id} for $packageName", e)
                failures++
            }
        }
        return SinglePackageOutcome(changed, failures, associationLost = false, backupEntries = backup)
    }

    suspend fun restoreAll(): PassResult = withContext(Dispatchers.IO) {
        val backup = passStateRepository.getOriginalImportanceBackup()
        var restored = 0
        var failures = 0
        var associationLost = false

        packages@ for ((packageName, channelBackups) in backup) {
            val channels = try {
                listener.getNotificationChannels(packageName, user)
            } catch (e: SecurityException) {
                Log.w(TAG, "Lost CompanionDeviceManager association while restoring $packageName", e)
                associationLost = true
                break@packages
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read channels while restoring $packageName", e)
                failures += channelBackups.size
                continue@packages
            }

            for ((channelId, originalImportance) in channelBackups) {
                val channel = channels.firstOrNull { it.id == channelId } ?: continue
                try {
                    channel.importance = originalImportance
                    listener.updateNotificationChannel(packageName, user, channel)
                    restored++
                } catch (e: SecurityException) {
                    Log.w(TAG, "Lost CompanionDeviceManager association while restoring $packageName/$channelId", e)
                    associationLost = true
                    break@packages
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore channel $channelId for $packageName", e)
                    failures++
                }
            }
        }

        if (!associationLost) {
            passStateRepository.clearOriginalImportanceBackup()
        }

        PassResult(appsChanged = restored, channelsChanged = restored, failures = failures, associationLost = associationLost)
            .also { passStateRepository.recordResult(it) }
    }
}
