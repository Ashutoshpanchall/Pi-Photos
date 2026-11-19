package com.example.piphotos.sync

import android.content.Context
import androidx.room.InvalidationTracker
import com.example.piphotos.BackupPreferences
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.SyncLedgerEntity
import com.example.piphotos.db.SyncLedgerSummary
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.net.OffsetMismatchException
import com.example.piphotos.net.PiHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncLedgerState(
    val totalCount: Int = 0,
    val doneCount: Int = 0,
    val pendingCount: Int = 0,
    val hashingCount: Int = 0,
    val uploadingCount: Int = 0,
    val failedCount: Int = 0,
    val isSyncing: Boolean = false,
    val currentFileName: String? = null,
    val currentFileIndex: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val lastError: String? = null
) {
    val isAllBackedUp: Boolean get() = totalCount > 0 && doneCount >= totalCount
    val percentComplete: Int get() = if (totalCount > 0) ((doneCount.toDouble() / totalCount.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
}

sealed class SyncOutcome {
    data class Success(val uploadedCount: Int, val skippedCount: Int) : SyncOutcome()
    data class Interrupted(val error: SyncError) : SyncOutcome()
    object Cancelled : SyncOutcome()
}

class SyncRepository private constructor(
    private val context: Context,
    private val httpClient: PiHttpClient = PiHttpClient(context)
) {
    private val db = AppDatabase.getInstance(context)
    private val ledgerDao = db.syncLedgerDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow(SyncLedgerState())
    val syncState: StateFlow<SyncLedgerState> = _syncState.asStateFlow()

    init {
        scope.launch {
            refreshSummary()
        }
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("sync_ledger")) {
            override fun onInvalidated(tables: Set<String>) {
                scope.launch {
                    refreshSummary()
                }
            }
        })
    }

    private fun refreshSummary() {
        val summary = ledgerDao.summary
        updateStateFromSummary(summary)
    }

    private fun updateStateFromSummary(summary: SyncLedgerSummary?) {
        if (summary == null) return
        val current = _syncState.value
        _syncState.value = current.copy(
            totalCount = summary.totalCount,
            doneCount = summary.doneCount,
            pendingCount = summary.pendingCount,
            hashingCount = summary.hashingCount,
            uploadingCount = summary.uploadingCount,
            failedCount = summary.failedCount
        )
    }

    suspend fun syncAll(
        pairedNode: PairedNode,
        isCancelled: () -> Boolean,
        onProgressUpdate: (current: Int, total: Int, name: String) -> Unit
    ): SyncOutcome = withContext(Dispatchers.IO) {
        _syncState.value = _syncState.value.copy(isSyncing = true, lastError = null)

        try {
            // 1. Gather monitored local media items
            val allLocalItems = LocalMediaIndex.getInstance(context).getAllItems()
            val filteredItems = filterMonitoredItems(allLocalItems)

            if (filteredItems.isEmpty()) {
                _syncState.value = _syncState.value.copy(isSyncing = false)
                return@withContext SyncOutcome.Success(uploadedCount = 0, skippedCount = 0)
            }

            // 2. Reconcile with Room ledger (insert new, reset modified generations)
            reconcileLedgerWithMedia(filteredItems)

            // 3. Hash only new or changed files
            hashPendingItems(filteredItems, isCancelled)
            if (isCancelled()) return@withContext SyncOutcome.Cancelled

            // 4. Batch existence check against node (POST /api/v1/upload/check)
            performBatchExistenceCheck(pairedNode)
            if (isCancelled()) return@withContext SyncOutcome.Cancelled

            // 5. Stream upload remaining files
            var uploadedCount = 0
            var skippedCount = 0
            val pendingUploads = ledgerDao.pendingOrFailedItems
            val totalToUpload = pendingUploads.size

            var sessionBytesTransferred = 0L
            val sessionStartTimeMs = System.currentTimeMillis()

            for ((index, ledgerItem) in pendingUploads.withIndex()) {
                if (isCancelled()) {
                    _syncState.value = _syncState.value.copy(isSyncing = false)
                    return@withContext SyncOutcome.Cancelled
                }

                val mediaItem = filteredItems.find { it.id == ledgerItem.mediaId }
                if (mediaItem == null || ledgerItem.sha256.isNullOrEmpty()) {
                    continue
                }

                val ext = if (mediaItem.isVideo) "mp4" else if (mediaItem.mimeType.contains("png")) "png" else "jpg"
                val filename = "media_${mediaItem.id}.$ext"
                val folder = mediaItem.bucketName.ifEmpty { "Default" }
                val dateTakenEpoch = if (mediaItem.dateTakenMs > 0) mediaItem.dateTakenMs / 1000L else mediaItem.dateModifiedSec

                _syncState.value = _syncState.value.copy(
                    currentFileName = filename,
                    currentFileIndex = index + 1
                )
                onProgressUpdate(index + 1, totalToUpload, filename)
                ledgerDao.updateStatus(ledgerItem.mediaId, "UPLOADING", null)

                var fileOffset = ledgerItem.partOffset
                var uploadSuccess = false
                var currentAttempt = ledgerItem.attempts

                while (!uploadSuccess && currentAttempt < 5) {
                    if (isCancelled()) return@withContext SyncOutcome.Cancelled

                    val uploadResult = httpClient.uploadStreaming(
                        endpointUrl = pairedNode.endpointUrl,
                        authToken = pairedNode.authToken,
                        sha256 = ledgerItem.sha256!!,
                        deviceId = pairedNode.deviceId,
                        folder = folder,
                        filename = filename,
                        takenEpochSec = dateTakenEpoch,
                        totalSize = ledgerItem.size,
                        resumeOffset = fileOffset,
                        contentUri = mediaItem.contentUri,
                        mimeType = mediaItem.mimeType,
                        expectedFingerprint = pairedNode.tlsFingerprint.ifEmpty { null },
                        onBytesSent = { bytesSent ->
                            sessionBytesTransferred += bytesSent
                            fileOffset += bytesSent
                            ledgerDao.updatePartOffset(ledgerItem.mediaId, fileOffset)

                            // Calculate honest speed & ETA
                            val elapsedSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000.0).coerceAtLeast(0.5)
                            val speedBps = (sessionBytesTransferred / elapsedSec).toLong()

                            val currentSummary = ledgerDao.summary
                            val remainingBytes = (currentSummary.totalBytes - currentSummary.uploadedBytes).coerceAtLeast(0L)
                            val etaSec = if (speedBps > 1024) remainingBytes / speedBps else -1L

                            _syncState.value = _syncState.value.copy(
                                speedBytesPerSec = speedBps,
                                etaSeconds = etaSec
                            )
                        }
                    )

                    if (uploadResult.isSuccess) {
                        uploadSuccess = true
                        uploadedCount++
                        ledgerDao.markDone(ledgerItem.mediaId, System.currentTimeMillis())
                    } else {
                        val ex = uploadResult.exceptionOrNull()!!
                        if (ex is OffsetMismatchException) {
                            // Server provided actual stored offset — update and retry
                            fileOffset = ex.serverOffset
                            ledgerDao.updatePartOffset(ledgerItem.mediaId, fileOffset)
                            continue
                        }

                        val syncErr = SyncError.fromThrowable(ex)
                        currentAttempt++

                        if (!syncErr.canRetry || currentAttempt >= 5) {
                            ledgerDao.markFailed(ledgerItem.mediaId, syncErr.message, currentAttempt)
                            _syncState.value = _syncState.value.copy(lastError = syncErr.message)
                            // If storage full or auth failure, halt immediately
                            if (syncErr is SyncError.DiskFull || syncErr is SyncError.QuotaExceeded || syncErr is SyncError.Auth) {
                                return@withContext SyncOutcome.Interrupted(syncErr)
                            }
                            break
                        } else {
                            // Exponential backoff
                            delay((1L shl currentAttempt) * 500L)
                        }
                    }
                }
            }

            SyncNodePreferences.setLastSyncTimestamp(context, System.currentTimeMillis())
            SyncOutcome.Success(uploadedCount, skippedCount)
        } catch (e: Exception) {
            val syncErr = SyncError.fromThrowable(e)
            _syncState.value = _syncState.value.copy(lastError = syncErr.message)
            SyncOutcome.Interrupted(syncErr)
        } finally {
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                currentFileName = null,
                speedBytesPerSec = 0L,
                etaSeconds = -1L
            )
        }
    }

    private fun filterMonitoredItems(items: List<LocalMediaItem>): List<LocalMediaItem> {
        val includeVideos = BackupPreferences.isIncludeVideos(context)
        val typeFiltered = if (includeVideos) items else items.filter { !it.isVideo }

        val cameraEnabled = BackupPreferences.isCameraFolderEnabled(context)
        val screenshotsEnabled = BackupPreferences.isScreenshotsFolderEnabled(context)
        val whatsAppEnabled = BackupPreferences.isWhatsAppFolderEnabled(context)
        val downloadsEnabled = BackupPreferences.isDownloadsFolderEnabled(context)
        val customFolders = BackupPreferences.getCustomFolders(context).map { it.second.lowercase() }

        return typeFiltered.filter { item ->
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
    }

    private fun reconcileLedgerWithMedia(items: List<LocalMediaItem>) {
        val activeIds = items.map { it.id }
        // Clean up deleted items from ledger
        if (activeIds.isNotEmpty()) {
            ledgerDao.deleteOrphans(activeIds)
        }

        for (item in items) {
            val existing = ledgerDao.getByMediaId(item.id)
            if (existing == null) {
                ledgerDao.insertOrReplace(
                    SyncLedgerEntity(
                        item.id,
                        item.generationModified,
                        item.sizeBytes,
                        null,
                        "PENDING",
                        0,
                        null,
                        null,
                        0L
                    )
                )
            } else if (existing.generation != item.generationModified) {
                // File content modified! Reset hash & status
                ledgerDao.insertOrReplace(
                    SyncLedgerEntity(
                        existing.mediaId,
                        item.generationModified,
                        item.sizeBytes,
                        null,
                        "PENDING",
                        0,
                        null,
                        null,
                        0L
                    )
                )
            }
        }
    }

    private suspend fun hashPendingItems(items: List<LocalMediaItem>, isCancelled: () -> Boolean) {
        val pendingHash = ledgerDao.allItems.filter { it.sha256.isNullOrEmpty() }
        val itemMap = items.associateBy { it.id }

        for (ledgerItem in pendingHash) {
            if (isCancelled()) return
            val mediaItem = itemMap[ledgerItem.mediaId] ?: continue

            ledgerDao.updateStatus(ledgerItem.mediaId, "HASHING", null)
            val hash = MediaHashCalculator.computeSha256(context, mediaItem.contentUri)
            if (hash != null) {
                ledgerDao.updateHash(ledgerItem.mediaId, hash, mediaItem.generationModified, "PENDING")
            } else {
                ledgerDao.markFailed(ledgerItem.mediaId, "Could not compute file hash", 1)
            }
        }
    }

    private suspend fun performBatchExistenceCheck(pairedNode: PairedNode) {
        val unverifiedItems = ledgerDao.pendingOrFailedItems.filter { !it.sha256.isNullOrEmpty() }
        if (unverifiedItems.isEmpty()) return

        // Batch in groups of 500
        val batches = unverifiedItems.chunked(500)
        for (batch in batches) {
            val hashes = batch.mapNotNull { it.sha256 }
            val checkResult = httpClient.checkUpload(
                endpointUrl = pairedNode.endpointUrl,
                authToken = pairedNode.authToken,
                hashes = hashes,
                expectedFingerprint = pairedNode.tlsFingerprint.ifEmpty { null }
            )

            if (checkResult.isSuccess) {
                val data = checkResult.getOrThrow()
                for (item in batch) {
                    val hash = item.sha256 ?: continue
                    if (data.existing.contains(hash)) {
                        ledgerDao.markDone(item.mediaId, System.currentTimeMillis())
                    } else if (data.partial.containsKey(hash)) {
                        val offset = data.partial[hash] ?: 0L
                        ledgerDao.updatePartOffset(item.mediaId, offset)
                    }
                }
            }
        }
    }

    fun retryAllFailed() {
        scope.launch {
            ledgerDao.resetAllFailed()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncRepository? = null

        fun getInstance(context: Context): SyncRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
