package com.example.piphotos.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.piphotos.BackupPreferences
import com.example.piphotos.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background media synchronization worker orchestrated by WorkManager.
 *
 * Runs as a foreground service with a dataSync notification, checkpoints progress
 * after each file in the Room sync ledger, and streams uploads via OkHttp.
 */
class MediaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pairedNode = SyncNodePreferences.getPairedNode(applicationContext)
            ?: return@withContext Result.success()

        SyncNodePreferences.setSyncInProgress(applicationContext, true)

        try {
            // Set initial foreground notification
            try {
                setForeground(createForegroundInfo(0, 0, "Preparing media backup..."))
            } catch (e: Exception) {
                // Foreground service start may throw if app is in background without exemption
            }

            val syncRepo = SyncRepository.getInstance(applicationContext)
            val outcome = syncRepo.syncAll(
                pairedNode = pairedNode,
                isCancelled = { isStopped },
                onProgressUpdate = { current, total, name ->
                    try {
                        setForegroundAsync(createForegroundInfo(current, total, name))
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            )

            when (outcome) {
                is SyncOutcome.Success -> {
                    Result.success(
                        workDataOf(
                            KEY_RESULT_UPLOADED to outcome.uploadedCount,
                            KEY_RESULT_SKIPPED to outcome.skippedCount
                        )
                    )
                }
                is SyncOutcome.Cancelled -> {
                    Result.retry()
                }
                is SyncOutcome.Interrupted -> {
                    val err = outcome.error
                    if (err.canRetry && runAttemptCount < 5) {
                        Result.retry()
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_REASON to err.message))
                    }
                }
            }
        } catch (e: Exception) {
            val syncErr = SyncError.fromThrowable(e)
            if (syncErr.canRetry && runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_REASON to syncErr.message))
            }
        } finally {
            SyncNodePreferences.setSyncInProgress(applicationContext, false)
        }
    }

    private fun createForegroundInfo(current: Int, total: Int, name: String): ForegroundInfo {
        createNotificationChannel()

        val title = if (total > 0) "Backing up photos ($current of $total)" else "Syncing with Vault..."
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(name)
            .setSmallIcon(R.drawable.ic_nav_backup)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Media Backup",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload progress when backing up media to your private storage node"
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "piphotos_periodic_sync"
        const val WORK_NAME_IMMEDIATE = "piphotos_immediate_sync"

        const val NOTIFICATION_CHANNEL_ID = "piphotos_backup"
        const val NOTIFICATION_ID = 4001

        const val KEY_RESULT_UPLOADED = "result_uploaded"
        const val KEY_RESULT_SKIPPED = "result_skipped"
        const val KEY_ERROR_REASON = "error_reason"

        fun schedulePeriodicSync(context: Context) {
            val wifiOnly = BackupPreferences.isWifiOnly(context)
            val chargingOnly = BackupPreferences.isChargingOnly(context)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()

            val request = PeriodicWorkRequestBuilder<MediaSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueImmediateSync(context: Context, force: Boolean = false) {
            val wifiOnly = if (force) false else BackupPreferences.isWifiOnly(context)
            val chargingOnly = if (force) false else BackupPreferences.isChargingOnly(context)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
        }
    }
}
