package com.example.piphotos.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.piphotos.MainActivity
import com.example.piphotos.R

object StorageAlertNotificationHelper {

    const val CHANNEL_ID = "storage_alerts"
    private const val CHANNEL_NAME = "Storage alerts"
    private const val NOTIFICATION_ID = 2001

    private var lastNotifiedState: String? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts about vault storage quota limits and backup pauses"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun notifyIfNeeded(context: Context, state: QuotaState) {
        ensureChannel(context)
        val currentStateKey = when (state.status) {
            is QuotaStatus.Warn90 -> "WARN_90"
            is QuotaStatus.Full -> "FULL"
            else -> "NORMAL"
        }

        // Only notify on transitions into WARN_90, FULL, or resumption from FULL to NORMAL
        val shouldNotify = when {
            currentStateKey == "WARN_90" && lastNotifiedState != "WARN_90" -> true
            currentStateKey == "FULL" && lastNotifiedState != "FULL" -> true
            currentStateKey == "NORMAL" && lastNotifiedState == "FULL" -> true
            else -> false
        }

        if (!shouldNotify) return

        val (title, message) = when (currentStateKey) {
            "WARN_90" -> Pair(
                "Storage almost full",
                "You have used 90% of your allocated storage. Tap to manage or ask the owner for more."
            )
            "FULL" -> Pair(
                "Storage full · Backups paused",
                "Your vault storage limit has been reached. Media backups are currently paused."
            )
            else -> Pair(
                "Backups resumed",
                "Vault storage space is now available. Your media backup has resumed."
            )
        }

        lastNotifiedState = currentStateKey

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_backup)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission denied on Android 13+
            // Gracefully ignore, fallback to in-app banner
        } catch (_: Exception) {
            // Silent fallback to avoid crash
        }
    }

    fun resetState() {
        lastNotifiedState = null
    }
}
