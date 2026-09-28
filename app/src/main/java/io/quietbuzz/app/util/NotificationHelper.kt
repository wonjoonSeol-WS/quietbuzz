package io.quietbuzz.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.quietbuzz.app.MainActivity

private const val ALERTS_CHANNEL_ID = "quietbuzz_alerts"
private const val RELINK_NOTIFICATION_ID = 1001

/** QuietBuzz's own alert notification -- used only to tell you the CompanionDeviceManager
 * association was lost and QuietBuzz needs to be re-linked before it can silence anything. */
object NotificationHelper {

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "QuietBuzz alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    fun postRelinkPrompt(context: Context) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("QuietBuzz needs re-linking")
            .setContentText("Tap to re-link a device so QuietBuzz can keep silencing notifications.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val canPost = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (canPost) {
            NotificationManagerCompat.from(context).notify(RELINK_NOTIFICATION_ID, notification)
        }
    }
}
