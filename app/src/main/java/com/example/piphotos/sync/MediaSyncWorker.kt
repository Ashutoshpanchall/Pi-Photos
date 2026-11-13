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
import com.example.piphotos.media.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed class TransferResult {
    object Success : TransferResult()
    object Exists : TransferResult()
    data class Failure(val reason: String, val canRetry: Boolean) : TransferResult()
}

/**
 * Background media synchronization worker orchestrated by WorkManager.
 *
 * Enforces unmetered network (Wi-Fi) and device charging constraints based on
 * user preferences, computes SHA-256 digests for content deduplication, and transfers
 * un-synced photos and videos belonging exclusively to user-selected monitored folders.
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
            val index = LocalMediaIndex.getInstance(applicationContext)
            val allItems = index.getAllItems()

            // 1. Filter by video preference
            val includeVideos = BackupPreferences.isIncludeVideos(applicationContext)
            val typeFilteredItems = if (includeVideos) allItems else allItems.filter { !it.isVideo }

            // 2. Filter strictly by user-enabled monitored folders
            val cameraEnabled = BackupPreferences.isCameraFolderEnabled(applicationContext)
            val screenshotsEnabled = BackupPreferences.isScreenshotsFolderEnabled(applicationContext)
            val whatsAppEnabled = BackupPreferences.isWhatsAppFolderEnabled(applicationContext)
            val downloadsEnabled = BackupPreferences.isDownloadsFolderEnabled(applicationContext)
            val customFolders = BackupPreferences.getCustomFolders(applicationContext).map { it.second.lowercase() }

            val filteredItems = typeFilteredItems.filter { item ->
                val bName = item.bucketName.lowercase()
                when {
                    bName == "camera" || bName.contains("dcim") -> cameraEnabled
                    bName.contains("screenshot") -> screenshotsEnabled
                    bName.contains("whatsapp") -> whatsAppEnabled
                    bName == "download" || bName == "downloads" -> downloadsEnabled
                    customFolders.any { bName.contains(it) } -> true
                    else -> false
                }
            }

            if (filteredItems.isEmpty()) {
                SyncNodePreferences.setLastSyncTimestamp(applicationContext, System.currentTimeMillis())
                return@withContext Result.success(
                    workDataOf(
                        KEY_RESULT_UPLOADED to 0,
                        KEY_RESULT_SKIPPED to 0,
                        KEY_RESULT_MESSAGE to "No media in selected folders"
                    )
                )
            }

            var uploadedCount = 0
            var skippedCount = 0
            var totalUploadedBytes = 0L
            val total = filteredItems.size
            val syncStartTimeMs = System.currentTimeMillis()

            for ((i, item) in filteredItems.withIndex()) {
                if (isStopped) {
                    return@withContext Result.retry()
                }

                // 1. Compute content-addressable SHA-256
                val sha256 = MediaHashCalculator.computeSha256(applicationContext, item.contentUri)
                if (sha256 != null) {
                    // 2. Query node for existence (deduplication check)
                    val existsResult = checkBlobExists(pairedNode.endpointUrl, pairedNode.authToken, sha256)
                    when (existsResult) {
                        is TransferResult.Exists -> {
                            skippedCount++
                        }
                        is TransferResult.Failure -> {
                            // Node connection error during check
                            return@withContext reportErrorAndRetry(existsResult.reason, existsResult.canRetry)
                        }
                        else -> {
                            // 3. Upload blob if missing
                            val uploadResult = uploadBlob(pairedNode.endpointUrl, pairedNode.authToken, sha256, item)
                            when (uploadResult) {
                                is TransferResult.Success -> {
                                    uploadedCount++
                                    totalUploadedBytes += if (item.sizeBytes > 0) item.sizeBytes else 2 * 1024 * 1024L
                                }
                                is TransferResult.Failure -> {
                                    return@withContext reportErrorAndRetry(uploadResult.reason, uploadResult.canRetry)
                                }
                                else -> {}
                            }
                        }
                    }
                }

                // Calculate real-time speed and ETA
                val elapsedSec = ((System.currentTimeMillis() - syncStartTimeMs) / 1000.0).coerceAtLeast(0.5)
                val speedBytesPerSec = (totalUploadedBytes / elapsedSec).toLong()
                val remainingItems = (total - (i + 1)).coerceAtLeast(0)
                val avgSize = if (uploadedCount > 0) totalUploadedBytes / uploadedCount else 2 * 1024 * 1024L
                val etaSeconds = if (speedBytesPerSec > 1024) (remainingItems * avgSize) / speedBytesPerSec else -1L

                setProgress(
                    workDataOf(
                        KEY_PROGRESS_CURRENT to (i + 1),
                        KEY_PROGRESS_TOTAL to total,
                        KEY_PROGRESS_UPLOADED to uploadedCount,
                        KEY_PROGRESS_SKIPPED to skippedCount,
                        KEY_PROGRESS_SPEED_BYTES_PER_SEC to speedBytesPerSec,
                        KEY_PROGRESS_ETA_SECONDS to etaSeconds
                    )
                )
            }

            SyncNodePreferences.setLastSyncTimestamp(applicationContext, System.currentTimeMillis())
            Result.success(
                workDataOf(
                    KEY_RESULT_UPLOADED to uploadedCount,
                    KEY_RESULT_SKIPPED to skippedCount
                )
            )
        } catch (e: Exception) {
            val friendlyReason = when (e) {
                is ConnectException -> "Cannot connect to storage node. Ensure phone and Pi are on the same Wi-Fi."
                is SocketTimeoutException -> "Connection timed out during media transfer."
                is UnknownHostException -> "Node host address unresolved. Check local network."
                else -> e.localizedMessage ?: "Synchronization interrupted."
            }
            reportErrorAndRetry(friendlyReason, runAttemptCount < 3)
        } finally {
            SyncNodePreferences.setSyncInProgress(applicationContext, false)
        }
    }

    private fun reportErrorAndRetry(reason: String, canRetry: Boolean): Result {
        return if (canRetry) {
            Result.retry()
        } else {
            Result.failure(workDataOf(KEY_ERROR_REASON to reason))
        }
    }

    private fun checkBlobExists(endpoint: String, token: String, sha256: String): TransferResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$endpoint/api/v1/blobs/$sha256")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val responseCode = conn.responseCode
            when (responseCode) {
                HttpURLConnection.HTTP_OK -> TransferResult.Exists
                HttpURLConnection.HTTP_NOT_FOUND -> TransferResult.Success
                401, 403 -> TransferResult.Failure("Authentication failed on storage node.", false)
                507 -> TransferResult.Failure("Storage node disk is full.", false)
                else -> TransferResult.Failure("Node responded with HTTP $responseCode", true)
            }
        } catch (e: ConnectException) {
            TransferResult.Failure("Cannot connect to node at $endpoint. Ensure you are on the same Wi-Fi.", true)
        } catch (e: SocketTimeoutException) {
            TransferResult.Failure("Connection timed out while querying node.", true)
        } catch (e: UnknownHostException) {
            TransferResult.Failure("Node address not found on network.", true)
        } catch (e: Exception) {
            TransferResult.Failure("Network check failed: ${e.message}", true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun uploadBlob(
        endpoint: String,
        token: String,
        sha256: String,
        item: LocalMediaItem
    ): TransferResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$endpoint/api/v1/blobs/$sha256")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 6000
            conn.readTimeout = 45000
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
            } ?: return TransferResult.Failure("Cannot read local file: ${item.id}", false)

            val responseCode = conn.responseCode
            when (responseCode) {
                in 200..299 -> TransferResult.Success
                401, 403 -> TransferResult.Failure("Node rejected upload: Unauthorized.", false)
                507 -> TransferResult.Failure("Node storage drive is full.", false)
                else -> TransferResult.Failure("Node upload error (HTTP $responseCode)", true)
            }
        } catch (e: ConnectException) {
            TransferResult.Failure("Disconnected from node during upload. Ensure phone is on same Wi-Fi.", true)
        } catch (e: SocketTimeoutException) {
            TransferResult.Failure("Upload timed out. Resuming when Wi-Fi stabilizes.", true)
        } catch (e: Exception) {
            TransferResult.Failure("Upload error: ${e.message}", true)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "piphotos_periodic_sync"
        const val WORK_NAME_IMMEDIATE = "piphotos_immediate_sync"

        const val KEY_PROGRESS_CURRENT = "current"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_UPLOADED = "uploaded"
        const val KEY_PROGRESS_SKIPPED = "skipped"
        const val KEY_PROGRESS_SPEED_BYTES_PER_SEC = "speed_bps"
        const val KEY_PROGRESS_ETA_SECONDS = "eta_sec"

        const val KEY_RESULT_UPLOADED = "result_uploaded"
        const val KEY_RESULT_SKIPPED = "result_skipped"
        const val KEY_RESULT_MESSAGE = "result_message"
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
