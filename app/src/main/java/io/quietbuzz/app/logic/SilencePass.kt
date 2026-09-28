package io.quietbuzz.app.logic

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import io.quietbuzz.app.data.AllowlistRepository
import io.quietbuzz.app.data.PassStateRepository
import io.quietbuzz.app.model.ChannelBackup
import io.quietbuzz.app.model.PassResult
import io.quietbuzz.app.util.DiagnosticLog
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

private fun importanceName(importance: Int?): String = when (importance) {
    null -> "UNKNOWN"
    NotificationManager.IMPORTANCE_NONE -> "NONE"
    NotificationManager.IMPORTANCE_MIN -> "MIN"
    NotificationManager.IMPORTANCE_LOW -> "LOW"
    NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
    NotificationManager.IMPORTANCE_HIGH -> "HIGH"
    NotificationManager.IMPORTANCE_UNSPECIFIED -> "UNSPECIFIED"
    else -> "($importance)"
}

/** Mirrors to both adb logcat (Log.*) and the in-app DiagnosticLog (visible without USB/adb). */
private fun logi(message: String) {
    Log.i(TAG, message)
    DiagnosticLog.add(message)
}

private fun logd(message: String) {
    Log.d(TAG, message)
    DiagnosticLog.add(message)
}

private fun logw(message: String, e: Throwable? = null) {
    Log.w(TAG, message, e)
    val suffix = e?.let { " [${it.javaClass.simpleName}: ${it.message}]" } ?: ""
    DiagnosticLog.add("WARN $message$suffix")
}

/**
 * The actual channel-rewriting logic. Every method here must run while `listener` is a connected
 * NotificationListenerService instance -- getNotificationChannels/updateNotificationChannel are
 * instance methods gated by the listener's CompanionDeviceManager association (see README), and
 * throw SecurityException the moment that association is gone.
 *
 * Silencing only ever touches vibration -- importance and sound are deliberately left alone. Two
 * reasons: some apps (observed on WeChat) serve their sound as a private content:// URI only they
 * can grant access to, so once cleared, QuietBuzz has no way to set it back; and by product
 * decision, sound suppression is left entirely to the phone's own ringer mode (vibrate mode
 * already plays no notification sound system-wide) rather than QuietBuzz forcing it via
 * importance -- so sound plays normally whenever the ringer mode isn't already vibrate/silent.
 * Vibration is the one thing Android has no native per-app control for, so that's the one field
 * this app actually manages. Restoring still repairs importance/sound where an old backup has
 * them (from before this was the design), using the full ChannelBackup captured at silence time.
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
        val fullBackup = mutableMapOf<String, Map<String, ChannelBackup>>()

        val installedApps = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        logi("=== Full pass starting: ${installedApps.size} installed apps, ${allowlist.size} allowlisted (${allowlist.joinToString()}), includeSystemApps=$includeSystemApps ===")

        for (appInfo in installedApps) {
            val pkg = appInfo.packageName
            if (isExcluded(pkg, ownPackage, allowlist)) {
                continue
            }
            if (!includeSystemApps && appInfo.isSystemApp()) {
                continue
            }
            if (!notificationsAllowed(pkg)) {
                logd("$pkg: notifications off at app level (알림 허용), leaving untouched")
                continue
            }

            val outcome = silencePackage(pkg)
            channelsChanged += outcome.channelsChanged
            failures += outcome.failures
            if (outcome.channelsChanged > 0) {
                appsChanged++
                fullBackup[pkg] = outcome.backupEntries
            }
            if (outcome.associationLost) {
                logw("Aborting pass: association lost while processing $pkg")
                associationLost = true
                break
            }
        }

        passStateRepository.mergeOriginalImportanceBackup(fullBackup)

        logi("=== Full pass finished: $appsChanged apps / $channelsChanged channels changed, $failures failures, associationLost=$associationLost ===")
        PassResult(appsChanged, channelsChanged, failures, associationLost).also {
            passStateRepository.recordResult(it)
        }
    }

    suspend fun runSilenceForPackage(packageName: String): PassResult = withContext(Dispatchers.IO) {
        val allowlist = allowlistRepository.allowlist.first()
        if (isExcluded(packageName, listener.packageName, allowlist)) {
            logi("$packageName: excluded (own package, allowlisted, or OS shell) -- not silenced")
            return@withContext PassResult(0, 0, 0, associationLost = false)
        }
        if (!notificationsAllowed(packageName)) {
            logi("$packageName: notifications off at app level (알림 허용), leaving untouched")
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

    // App-level "알림 허용" off revokes POST_NOTIFICATIONS but leaves every channel's importance
    // as-is, so isBlockedByUser() can't see it. Such an app posts nothing; leave it untouched.
    private fun notificationsAllowed(pkg: String): Boolean =
        packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg) == PackageManager.PERMISSION_GRANTED

    private data class SinglePackageOutcome(
        val channelsChanged: Int,
        val failures: Int,
        val associationLost: Boolean,
        val backupEntries: Map<String, ChannelBackup>,
    )

    // IMPORTANCE_NONE is the per-category on/off switch in 알림 카테고리: the user blocked this
    // channel, so there's nothing to silence. App-level blocks are handled by notificationsAllowed().
    private fun isBlockedByUser(channel: android.app.NotificationChannel): Boolean =
        channel.importance == NotificationManager.IMPORTANCE_NONE

    /**
     * True if there's nothing left for a silence pass to do to this channel. Importance and sound
     * are deliberately not part of this check -- silencing never touches them (see class kdoc), so
     * their values have no bearing on whether the channel still needs a pass.
     */
    private fun isFullySilent(channel: android.app.NotificationChannel): Boolean = !channel.shouldVibrate()

    /** Not suspend: called only from within the withContext(Dispatchers.IO) blocks above. */
    private fun silencePackage(packageName: String): SinglePackageOutcome {
        val channels = try {
            listener.getNotificationChannels(packageName, user)
        } catch (e: SecurityException) {
            logw("Lost association while reading channels for $packageName", e)
            return SinglePackageOutcome(0, 0, associationLost = true, backupEntries = emptyMap())
        } catch (e: Exception) {
            logw("Failed to read channels for $packageName", e)
            return SinglePackageOutcome(0, 1, associationLost = false, backupEntries = emptyMap())
        }

        if (channels.isEmpty()) {
            logd("$packageName: 0 channels (never posted, or no channels created yet)")
        }

        var changed = 0
        var failures = 0
        val backup = mutableMapOf<String, ChannelBackup>()
        val touchedChannelIds = mutableListOf<String>()

        for (channel in channels) {
            if (isBlockedByUser(channel)) {
                logd("$packageName/${channel.id}: already blocked by user (importance NONE), leaving untouched")
                continue
            }
            if (isFullySilent(channel)) {
                logd("$packageName/${channel.id}: already fully silent (${importanceName(channel.importance)}, no vibration), skipping")
                continue
            }

            val original = ChannelBackup(
                importance = channel.importance,
                soundUri = channel.sound?.toString(),
                vibrationEnabled = channel.shouldVibrate(),
                vibrationPattern = channel.vibrationPattern?.joinToString(","),
            )
            try {
                channel.enableVibration(false)
                channel.vibrationPattern = null
                listener.updateNotificationChannel(packageName, user, channel)
                backup[channel.id] = original
                touchedChannelIds += channel.id
                changed++
                logi("$packageName/${channel.id}: vibration disabled (importance and sound left untouched)")
            } catch (e: SecurityException) {
                // Unlike the getNotificationChannels() read above, this is NOT a reliable signal
                // of association loss: a channel whose sound is a private content:// URI the app
                // doesn't grant to QuietBuzz (observed on WeChat) can throw here on any write that
                // includes it, even one that never calls setSound(). A real lost association
                // would also break the next package's read (handled above), so it's safe to just
                // skip this one channel -- untouched, sound and all -- rather than abort the pass.
                logw("Could not update $packageName/${channel.id} -- likely a private sound URI this app doesn't grant to other apps; leaving untouched", e)
                failures++
            } catch (e: Exception) {
                logw("Failed to update channel ${channel.id} for $packageName", e)
                failures++
            }
        }

        if (touchedChannelIds.isNotEmpty()) {
            verifyChannelsPersisted(packageName, touchedChannelIds)
        }

        return SinglePackageOutcome(changed, failures, associationLost = false, backupEntries = backup)
    }

    /**
     * One extra read per package that actually had changes (not per channel) to confirm the
     * update calls really stuck, rather than trusting that updateNotificationChannel not throwing
     * means the values persisted. Logs a warning per mismatch; never throws or affects the result,
     * since this is diagnostic only.
     */
    private fun verifyChannelsPersisted(packageName: String, touchedChannelIds: List<String>) {
        val current = try {
            listener.getNotificationChannels(packageName, user)
        } catch (e: Exception) {
            logw("Could not verify $packageName after update", e)
            return
        }
        for (channelId in touchedChannelIds) {
            val actual = current.firstOrNull { it.id == channelId }
            if (actual == null) {
                logw("VERIFY MISMATCH $packageName/$channelId: channel not found after update")
                continue
            }
            val problems = buildList {
                if (actual.shouldVibrate()) add("vibration still enabled")
            }
            if (problems.isNotEmpty()) {
                logw("VERIFY MISMATCH $packageName/$channelId: ${problems.joinToString(", ")} -- did not fully stick")
            } else {
                logd("$packageName/$channelId: verified vibration disabled")
            }
        }
    }

    private enum class RestoreChannelOutcome { SUCCESS, SUCCESS_WITHOUT_CUSTOM_SOUND, ASSOCIATION_LOST, FAILED }

    /**
     * Some apps serve their notification sound as a private content:// URI through their own
     * FileProvider (observed on WeChat: content://com.tencent.mm.external.fileprovider/...) --
     * QuietBuzz has no grant to read that URI, so asking the system to set a channel's sound back
     * to it throws SecurityException. That's a completely different failure from "the
     * CompanionDeviceManager association is gone" (which also throws SecurityException), but
     * naively treating every SecurityException here as association-loss meant one app with a
     * private-URI sound would silently abort the restore for every app after it in the backup,
     * every single time, since the backup never got cleared. Retrying with the custom sound
     * dropped (falling back to default) isolates which of the two this actually is: if the retry
     * also throws, it's genuinely the association; if not, it was just this one channel's sound.
     */
    private fun restoreOneChannel(
        packageName: String,
        channelId: String,
        channel: android.app.NotificationChannel,
        soundUri: Uri?,
    ): RestoreChannelOutcome {
        try {
            listener.updateNotificationChannel(packageName, user, channel)
            return RestoreChannelOutcome.SUCCESS
        } catch (e: SecurityException) {
            if (soundUri == null) {
                logw("Lost association while restoring $packageName/$channelId", e)
                return RestoreChannelOutcome.ASSOCIATION_LOST
            }
            logw("Could not restore original sound for $packageName/$channelId (${e.message}) -- retrying with default sound instead")
        } catch (e: Exception) {
            logw("Failed to restore channel $channelId for $packageName", e)
            return RestoreChannelOutcome.FAILED
        }

        // Retry without the custom sound URI, to tell apart "this one URI is inaccessible" from
        // "the association is actually gone" (see kdoc above).
        channel.setSound(null, null)
        return try {
            listener.updateNotificationChannel(packageName, user, channel)
            RestoreChannelOutcome.SUCCESS_WITHOUT_CUSTOM_SOUND
        } catch (e: SecurityException) {
            logw("Lost association while restoring $packageName/$channelId", e)
            RestoreChannelOutcome.ASSOCIATION_LOST
        } catch (e: Exception) {
            logw("Failed to restore channel $channelId for $packageName", e)
            RestoreChannelOutcome.FAILED
        }
    }

    suspend fun restoreAll(): PassResult = withContext(Dispatchers.IO) {
        val backup = passStateRepository.getOriginalImportanceBackup()
        var restored = 0
        var failures = 0
        var associationLost = false

        logi("=== Restore starting: ${backup.size} packages in backup ===")

        for ((packageName, channelBackups) in backup) {
            val outcome = restorePackageChannels(packageName, channelBackups)
            restored += outcome.restored
            failures += outcome.failures
            if (outcome.associationLost) {
                associationLost = true
                break
            }
        }

        if (!associationLost) {
            passStateRepository.clearOriginalImportanceBackup()
        }

        logi("=== Restore finished: $restored restored, $failures failures, associationLost=$associationLost ===")
        PassResult(appsChanged = restored, channelsChanged = restored, failures = failures, associationLost = associationLost)
            .also { passStateRepository.recordResult(it) }
    }

    /**
     * Backup-independent recovery for when the backup itself holds the wrong "original" (e.g. LOW
     * recorded by an older build). Raises LOW to DEFAULT, turns vibration on, and gives a sound-
     * less channel the default notification sound. NONE (user-blocked), MIN and HIGH are left alone,
     * as is any existing custom sound. Channels an app deliberately created as LOW get raised too; there's
     * no way to tell those apart without a trustworthy backup, which is why this asks for confirmation.
     */
    // Covers allowlisted apps too: an app allowlisted after an older build silenced it can still be stuck at LOW.
    suspend fun resetAllToDefaults(includeSystemApps: Boolean): PassResult = withContext(Dispatchers.IO) {
        val ownPackage = listener.packageName
        var appsChanged = 0
        var channelsChanged = 0
        var failures = 0
        var associationLost = false

        logi("=== Reset to defaults starting ===")
        for (appInfo in packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))) {
            val pkg = appInfo.packageName
            if (pkg == ownPackage || pkg in ALWAYS_EXCLUDED) continue
            if (!includeSystemApps && appInfo.isSystemApp()) continue
            if (!notificationsAllowed(pkg)) {
                logd("$pkg: notifications off at app level (알림 허용), leaving untouched")
                continue
            }

            val channels = try {
                listener.getNotificationChannels(pkg, user)
            } catch (e: SecurityException) {
                logw("Lost association while reading channels for $pkg", e)
                associationLost = true
                break
            } catch (e: Exception) {
                logw("Failed to read channels for $pkg", e)
                failures++
                continue
            }

            var changedHere = 0
            for (channel in channels) {
                if (isBlockedByUser(channel)) continue
                // Only LOW: that's the one level QuietBuzz ever set. MIN (알림 최소화) is a deliberate choice.
                val raiseImportance = channel.importance == NotificationManager.IMPORTANCE_LOW
                val addSound = channel.sound == null
                if (!raiseImportance && !addSound && channel.shouldVibrate()) continue

                if (raiseImportance) channel.importance = NotificationManager.IMPORTANCE_DEFAULT
                if (addSound) channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, channel.audioAttributes)
                channel.enableVibration(true)
                channel.vibrationPattern = null
                try {
                    listener.updateNotificationChannel(pkg, user, channel)
                    changedHere++
                    logi("$pkg/${channel.id}: reset to DEFAULT importance, vibration on${if (addSound) ", default sound" else ""}")
                } catch (e: Exception) {
                    logw("Could not reset $pkg/${channel.id}; leaving untouched", e)
                    failures++
                }
            }
            if (changedHere > 0) {
                appsChanged++
                channelsChanged += changedHere
            }
        }

        if (!associationLost) {
            passStateRepository.clearOriginalImportanceBackup()
        }
        logi("=== Reset finished: $appsChanged apps / $channelsChanged channels, $failures failures, associationLost=$associationLost ===")
        PassResult(appsChanged, channelsChanged, failures, associationLost).also { passStateRepository.recordResult(it) }
    }

    /** Restores one app from its backup and drops its entry, e.g. right after it's allowlisted. */
    suspend fun restorePackage(packageName: String): PassResult = withContext(Dispatchers.IO) {
        val channelBackups = passStateRepository.getOriginalImportanceBackup()[packageName]
        if (channelBackups.isNullOrEmpty()) {
            logi("$packageName: nothing in backup to restore")
            return@withContext PassResult(0, 0, 0, associationLost = false)
        }

        val outcome = restorePackageChannels(packageName, channelBackups)
        if (!outcome.associationLost) {
            passStateRepository.clearOriginalImportanceBackup(packageName)
        }
        logi("$packageName: restored ${outcome.restored} channels, ${outcome.failures} failures")
        PassResult(
            appsChanged = if (outcome.restored > 0) 1 else 0,
            channelsChanged = outcome.restored,
            failures = outcome.failures,
            associationLost = outcome.associationLost,
        ).also { passStateRepository.recordResult(it) }
    }

    private data class PackageRestoreOutcome(val restored: Int, val failures: Int, val associationLost: Boolean)

    private fun restorePackageChannels(packageName: String, channelBackups: Map<String, ChannelBackup>): PackageRestoreOutcome {
        val channels = try {
            listener.getNotificationChannels(packageName, user)
        } catch (e: SecurityException) {
            logw("Lost association while restoring $packageName", e)
            return PackageRestoreOutcome(0, 0, associationLost = true)
        } catch (e: Exception) {
            logw("Failed to read channels while restoring $packageName", e)
            return PackageRestoreOutcome(0, channelBackups.size, associationLost = false)
        }

        var restored = 0
        var failures = 0
        for ((channelId, original) in channelBackups) {
            val channel = channels.firstOrNull { it.id == channelId } ?: continue
            channel.importance = original.importance
            channel.enableVibration(original.vibrationEnabled)
            channel.vibrationPattern = original.vibrationPattern
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?.toLongArray()
            val soundUri = original.soundUri?.let { Uri.parse(it) }
            channel.setSound(soundUri, channel.audioAttributes)

            when (restoreOneChannel(packageName, channelId, channel, soundUri)) {
                RestoreChannelOutcome.SUCCESS -> {
                    restored++
                    logi("$packageName/$channelId: restored to ${importanceName(original.importance)}, sound=${soundUri ?: "none"}, vibration=${original.vibrationEnabled}")
                }
                RestoreChannelOutcome.SUCCESS_WITHOUT_CUSTOM_SOUND -> {
                    restored++
                    logi("$packageName/$channelId: restored to ${importanceName(original.importance)}, vibration=${original.vibrationEnabled} (original custom sound could not be restored, left at default -- see prior WARN)")
                }
                RestoreChannelOutcome.ASSOCIATION_LOST -> return PackageRestoreOutcome(restored, failures, associationLost = true)
                RestoreChannelOutcome.FAILED -> failures++
            }
        }
        return PackageRestoreOutcome(restored, failures, associationLost = false)
    }
}
