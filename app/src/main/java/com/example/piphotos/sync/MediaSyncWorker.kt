package com.example.piphotos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.piphotos.BackupPreferences
import com.example.piphotos.media.LocalMediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background media synchronization worker orchestrated by WorkManager.
 *
 * Enforces unmetered network (Wi-Fi) and device charging constraints based on
 * user preferences, computes SHA-256 digests for content deduplication, and transfers
 * un-synced photos and videos.
 */
class MediaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pairedNode = SyncNodePreferences.getPairedNode(applicationContext) ?: return@withContext Result.success()

        SyncNodePreferences.setSyncInProgress(applicationContext, true)
        try {
            val index = LocalMediaIndex.getInstance(applicationContext)
            val allItems = index.getAllItems()

            val includeVideos = BackupPreferences.isIncludeVideos(applicationContext)
            val filteredItems = if (includeVideos) allItems else allItems.filter { !it.isVideo }

            var uploadedCount = 0
            var skippedCount = 0
            val total = filteredItems.size

            for ((i, item) in filteredItems.withIndex()) {
                if (isStopped) {
                    return@withContext Result.retry()
                }

                // 1. Compute content-addressable SHA-256
                val sha256 = MediaHashCalculator.computeSha256(applicationContext, item.contentUri)
                if (sha256 != null) {
                    // 2. Query node for existence (deduplication check)
                    val existsOnNode = checkBlobExists(pairedNode.endpointUrl, pairedNode.authToken, sha256)
                    if (existsOnNode) {
                        skippedCount++
                    } else {
                        // 3. Upload blob if missing
                        val uploaded = uploadBlob(pairedNode.endpointUrl, pairedNode.authToken, sha256, item)
                        if (uploaded) uploadedCount++
                    }
                }

                // Report progress every 5 items
                if (i % 5 == 0 || i == total - 1) {
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_CURRENT to (i + 1),
                            KEY_PROGRESS_TOTAL to total,
                            KEY_PROGRESS_UPLOADED to uploadedCount,
                            KEY_PROGRESS_SKIPPED to skippedCount
                        )
                    )
                }
            }

            SyncNodePreferences.setLastSyncTimestamp(applicationContext, System.currentTimeMillis())
            Result.success(
                workDataOf(
                    KEY_RESULT_UPLOADED to uploadedCount,
                    KEY_RESULT_SKIPPED to skippedCount
                )
            )
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            SyncNodePreferences.setSyncInProgress(applicationContext, false)
        }
    }

    private fun checkBlobExists(endpoint: String, token: String, sha256: String): Boolean {
        return try {
            val url = URL("$endpoint/api/v1/blobs/$sha256")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    private fun uploadBlob(endpoint: String, token: String, sha256: String, item: com.example.piphotos.media.LocalMediaItem): Boolean {
        return try {
            val url = URL("$endpoint/api/v1/blobs/$sha256")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", item.mimeType)
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            applicationContext.contentResolver.openInputStream(item.contentUri)?.use { input ->
                conn.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "piphotos_periodic_sync"
        const val WORK_NAME_IMMEDIATE = "piphotos_immediate_sync"

        const val KEY_PROGRESS_CURRENT = "current"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_UPLOADED = "uploaded"
        const val KEY_PROGRESS_SKIPPED = "skipped"

        const val KEY_RESULT_UPLOADED = "result_uploaded"
        const val KEY_RESULT_SKIPPED = "result_skipped"

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

        fun enqueueImmediateSync(context: Context) {
            val wifiOnly = BackupPreferences.isWifiOnly(context)
            val chargingOnly = BackupPreferences.isChargingOnly(context)

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

