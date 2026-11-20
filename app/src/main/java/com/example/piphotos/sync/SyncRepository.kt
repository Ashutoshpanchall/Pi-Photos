package com.example.piphotos.sync

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import com.example.piphotos.BackupPreferences
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.SyncLedgerEntity
import com.example.piphotos.db.SyncLedgerSummary
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.net.HttpException
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
    private val httpClient: PiHttpClient = PiHttpClient.getInstance(context)
) {
    private val db = AppDatabase.getInstance(context)
    private val ledgerDao = db.syncLedgerDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow(SyncLedgerState())
    val syncState: StateFlow<SyncLedgerState> = _syncState.asStateFlow()

    private val _backedUpMediaIds = MutableStateFlow<Set<Long>>(emptySet())
    val backedUpMediaIds: StateFlow<Set<Long>> = _backedUpMediaIds.asStateFlow()

    private val _quotaState = MutableStateFlow(
        QuotaState(
            status = QuotaStateManager.computeStatus(
                SyncNodePreferences.getUsedBytes(context),
                SyncNodePreferences.getQuotaBytes(context)
            ),
            usedBytes = SyncNodePreferences.getUsedBytes(context),
            quotaBytes = SyncNodePreferences.getQuotaBytes(context),
            freeBytes = if (SyncNodePreferences.getQuotaBytes(context) > 0) {
                (SyncNodePreferences.getQuotaBytes(context) - SyncNodePreferences.getUsedBytes(context)).coerceAtLeast(0L)
            } else 0L,
            lastRefreshedAt = SyncNodePreferences.getLastSeenTimestamp(context)
        )
    )
    val quotaState: StateFlow<QuotaState> = _quotaState.asStateFlow()

    private var fullBackoffIndex = 0
    private val fullBackoffIntervals = listOf(5 * 60 * 1000L, 15 * 60 * 1000L, 60 * 60 * 1000L)
    private var lastFullCheckMs = 0L

    fun canCheckServerWhileFull(): Boolean {
        if (!_quotaState.value.isFullOrExceeded) return true
        val interval = fullBackoffIntervals[fullBackoffIndex.coerceIn(0, fullBackoffIntervals.size - 1)]
        val now = System.currentTimeMillis()
        if (now - lastFullCheckMs >= interval) {
            lastFullCheckMs = now
            fullBackoffIndex = (fullBackoffIndex + 1).coerceAtMost(fullBackoffIntervals.size - 1)
            return true
        }
        return false
    }

    fun resetFullBackoff() {
        fullBackoffIndex = 0
        lastFullCheckMs = 0L
    }

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
        val doneIds = ledgerDao.doneMediaIds.toSet()
        _backedUpMediaIds.value = doneIds
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

        val initialQuota = refreshQuotaState()
        when (initialQuota.status) {
            is QuotaStatus.AccountDisabled -> {
                val reason = initialQuota.errorMessage ?: "Your account has been turned off by the vault owner."
                _syncState.value = _syncState.value.copy(isSyncing = false, lastError = reason)
                return@withContext SyncOutcome.Interrupted(SyncError.Auth(reason))
            }
            is QuotaStatus.DeviceRevoked -> {
                val reason = initialQuota.errorMessage ?: "This device has been removed from the server."
                _syncState.value = _syncState.value.copy(isSyncing = false, lastError = reason)
                return@withContext SyncOutcome.Interrupted(SyncError.Auth(reason))
            }
            is QuotaStatus.Full -> {
                val reason = initialQuota.errorMessage ?: "Storage limit reached. Backups are paused."
                ledgerDao.pauseAllActiveForQuota(reason)
                _syncState.value = _syncState.value.copy(isSyncing = false, lastError = reason)
                return@withContext SyncOutcome.Interrupted(SyncError.QuotaExceeded(reason))
            }
            else -> { /* proceed with sync */ }
        }

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

            // Fast O(1) media item lookup
            val mediaItemMap = filteredItems.associateBy { it.id }

            // Cache initial summary in memory to avoid querying Room during streaming
            val initialSummary = ledgerDao.summary
            var currentUploadedBytes = initialSummary.uploadedBytes
            val totalLibraryBytes = initialSummary.totalBytes

            // Get pending or failed items
            val pendingItems = ledgerDao.pendingOrFailedItems
            val totalItemsToSync = pendingItems.size

            if (pendingItems.isEmpty()) {
                _syncState.value = _syncState.value.copy(isSyncing = false)
                return@withContext SyncOutcome.Success(uploadedCount = 0, skippedCount = 0)
            }

            var uploadedCount = 0
            var skippedCount = 0
            var totalProcessed = 0
            var sessionBytesTransferred = 0L
            val sessionStartTimeMs = System.currentTimeMillis()

            // 3. Process in batches of 25: lazy hash -> batch check -> upload remaining
            val batches = pendingItems.chunked(25)

            for (batch in batches) {
                if (isCancelled()) {
                    _syncState.value = _syncState.value.copy(isSyncing = false)
                    return@withContext SyncOutcome.Cancelled
                }

                // 3a. Lazy hash unhashed items in this batch
                for (item in batch) {
                    if (isCancelled()) return@withContext SyncOutcome.Cancelled
                    val mediaItem = mediaItemMap[item.mediaId] ?: continue
                    if (item.sha256.isNullOrEmpty()) {
                        ledgerDao.updateStatus(item.mediaId, "HASHING", null)
                        val hash = MediaHashCalculator.computeSha256(context, mediaItem.contentUri)
                        if (hash != null) {
                            ledgerDao.updateHash(item.mediaId, hash, mediaItem.generationModified, "PENDING")
                            item.sha256 = hash
                        } else {
                            ledgerDao.markFailed(item.mediaId, "Could not compute file hash", 1)
                        }
                    }
                }

                // 3b. Batch check upload state against server (POST /api/v1/upload/check)
                val hashesToCheck = batch.mapNotNull { it.sha256 }
                if (hashesToCheck.isNotEmpty()) {
                    val checkResult = httpClient.checkUpload(
                        endpointUrl = pairedNode.endpointUrl,
                        authToken = pairedNode.authToken,
                        hashes = hashesToCheck,
                        expectedFingerprint = pairedNode.tlsFingerprint.ifEmpty { null }
                    )

                    if (checkResult.isSuccess) {
                        val checkData = checkResult.getOrThrow()
                        for (item in batch) {
                            val hash = item.sha256 ?: continue
                            if (checkData.existing.contains(hash)) {
                                ledgerDao.markDone(item.mediaId, System.currentTimeMillis())
                                skippedCount++
                                totalProcessed++
                                currentUploadedBytes += item.size
                                val mediaItem = mediaItemMap[item.mediaId]
                                val filename = sanitizeFilename(mediaItem?.displayName, mediaItem?.mimeType, mediaItem?.isVideo == true, item.mediaId)
                                onProgressUpdate(totalProcessed, totalItemsToSync, filename)
                            } else if (checkData.partial.containsKey(hash)) {
                                val offset = checkData.partial[hash] ?: 0L
                                ledgerDao.updatePartOffset(item.mediaId, offset)
                                item.partOffset = offset
                            }
                        }
                    } else {
                        val checkError = checkResult.exceptionOrNull()
                        Log.w("SyncRepository", "Batch checkUpload failed (proceeding to direct upload): ${checkError?.message}", checkError)
                    }
                }

                // 3c. Upload remaining uncompleted items in this batch
                for (batchItem in batch) {
                    if (isCancelled()) {
                        _syncState.value = _syncState.value.copy(isSyncing = false)
                        return@withContext SyncOutcome.Cancelled
                    }

                    val freshEntity = ledgerDao.getByMediaId(batchItem.mediaId) ?: continue
                    if (freshEntity.status == "DONE" || freshEntity.sha256.isNullOrEmpty()) {
                        continue
                    }

                    val mediaItem = mediaItemMap[freshEntity.mediaId] ?: continue
                    val hash = freshEntity.sha256!!

                    val filename = sanitizeFilename(mediaItem.displayName, mediaItem.mimeType, mediaItem.isVideo, mediaItem.id)
                    val folder = mediaItem.bucketName.ifEmpty { "Default" }
                    val dateTakenEpoch = if (mediaItem.dateTakenMs > 0) mediaItem.dateTakenMs / 1000L else mediaItem.dateModifiedSec

                    totalProcessed++
                    _syncState.value = _syncState.value.copy(
                        currentFileName = filename,
                        currentFileIndex = totalProcessed
                    )
                    onProgressUpdate(totalProcessed, totalItemsToSync, filename)

                    // Pre-flight quota check: if free quota < file size remaining, pause immediately
                    val activeQuota = _quotaState.value
                    val remainingFileBytes = (freshEntity.size - freshEntity.partOffset).coerceAtLeast(0L)
                    if (activeQuota.quotaBytes > 0L && activeQuota.freeBytes < remainingFileBytes) {
                        val pauseMsg = "Pre-flight: insufficient quota (${QuotaStateManager.formatBytes(activeQuota.freeBytes)} free for ${QuotaStateManager.formatBytes(remainingFileBytes)})"
                        Log.w("SyncRepository", pauseMsg)
                        ledgerDao.markPausedQuota(freshEntity.mediaId, pauseMsg)
                        ledgerDao.pauseAllActiveForQuota(pauseMsg)
                        _syncState.value = _syncState.value.copy(lastError = "Storage limit reached. Backups are paused.")
                        _quotaState.value = activeQuota.copy(
                            status = QuotaStatus.Full(
                                if (activeQuota.quotaBytes > 0) ((activeQuota.usedBytes.toDouble() / activeQuota.quotaBytes.toDouble()) * 100).toInt().coerceAtLeast(100) else 100,
                                isHardLimit = true
                            ),
                            freeBytes = 0L
                        )
                        StorageAlertNotificationHelper.notifyIfNeeded(context, _quotaState.value)
                        return@withContext SyncOutcome.Interrupted(SyncError.QuotaExceeded(pauseMsg))
                    }

                    ledgerDao.updateStatus(freshEntity.mediaId, "UPLOADING", null)

                    var fileOffset = freshEntity.partOffset
                    var uploadSuccess = false
                    var currentAttempt = freshEntity.attempts
                    var offsetMismatchRetries = 0
                    var lastWriteMs = System.currentTimeMillis()
                    var bytesSinceLastWrite = 0L
                    var lastPersistedOffset = fileOffset

                    while (!uploadSuccess && currentAttempt < 5) {
                        if (isCancelled()) return@withContext SyncOutcome.Cancelled

                        val uploadResult = httpClient.uploadStreaming(
                            endpointUrl = pairedNode.endpointUrl,
                            authToken = pairedNode.authToken,
                            sha256 = hash,
                            deviceId = pairedNode.deviceId,
                            folder = folder,
                            filename = filename,
                            takenEpochSec = dateTakenEpoch,
                            totalSize = freshEntity.size,
                            resumeOffset = fileOffset,
                            contentUri = mediaItem.contentUri,
                            mimeType = mediaItem.mimeType,
                            expectedFingerprint = pairedNode.tlsFingerprint.ifEmpty { null },
                            onBytesSent = { bytesSent ->
                                sessionBytesTransferred += bytesSent
                                fileOffset += bytesSent
                                bytesSinceLastWrite += bytesSent

                                val now = System.currentTimeMillis()
                                // Throttle Room writes to >=1s or >=4MB, no Room summary queries
                                if (now - lastWriteMs >= 1000L || bytesSinceLastWrite >= 4 * 1024 * 1024L) {
                                    ledgerDao.updatePartOffset(freshEntity.mediaId, fileOffset)
                                    lastWriteMs = now
                                    bytesSinceLastWrite = 0L
                                    lastPersistedOffset = fileOffset

                                    val elapsedSec = ((now - sessionStartTimeMs) / 1000.0).coerceAtLeast(0.5)
                                    val speedBps = (sessionBytesTransferred / elapsedSec).toLong()
                                    val remainingBytes = (totalLibraryBytes - (currentUploadedBytes + sessionBytesTransferred)).coerceAtLeast(0L)
                                    val etaSec = if (speedBps > 1024) remainingBytes / speedBps else -1L

                                    _syncState.value = _syncState.value.copy(
                                        speedBytesPerSec = speedBps,
                                        etaSeconds = etaSec
                                    )
                                }
                            }
                        )

                        // Flush unwritten offset if loop finished or failed
                        if (bytesSinceLastWrite > 0L && fileOffset > lastPersistedOffset) {
                            ledgerDao.updatePartOffset(freshEntity.mediaId, fileOffset)
                            bytesSinceLastWrite = 0L
                            lastPersistedOffset = fileOffset
                        }

                        if (uploadResult.isSuccess) {
                            uploadSuccess = true
                            uploadedCount++
                            val addedBytes = (freshEntity.size - freshEntity.partOffset).coerceAtLeast(0L)
                            currentUploadedBytes += addedBytes
                            SyncNodePreferences.incrementUsedBytes(context, addedBytes)
                            val currentQuota = _quotaState.value
                            val newUsed = currentQuota.usedBytes + addedBytes
                            val newFree = (currentQuota.freeBytes - addedBytes).coerceAtLeast(0L)
                            _quotaState.value = currentQuota.copy(
                                usedBytes = newUsed,
                                freeBytes = newFree,
                                status = QuotaStateManager.computeStatus(newUsed, currentQuota.quotaBytes)
                            )
                            ledgerDao.markDone(freshEntity.mediaId, System.currentTimeMillis())
                            refreshSummary()
                        } else {
                            val ex = uploadResult.exceptionOrNull()!!
                            if (ex is OffsetMismatchException) {
                                offsetMismatchRetries++
                                if (offsetMismatchRetries > 3) {
                                    val errorMsg = "Offset mismatch limit reached (server offset ${ex.serverOffset})"
                                    Log.e("SyncRepository", "Capping OffsetMismatchException retries at 3 for mediaId=${freshEntity.mediaId}: $errorMsg")
                                    ledgerDao.markFailed(freshEntity.mediaId, errorMsg, currentAttempt + 1)
                                    _syncState.value = _syncState.value.copy(lastError = errorMsg)
                                    break
                                }
                                fileOffset = ex.serverOffset
                                ledgerDao.updatePartOffset(freshEntity.mediaId, fileOffset)
                                lastPersistedOffset = fileOffset
                                continue
                            }

                            val syncErr = SyncError.fromThrowable(ex)
                            currentAttempt++

                            if (!syncErr.canRetry || currentAttempt >= 5) {
                                if (syncErr is SyncError.QuotaExceeded || syncErr is SyncError.DiskFull) {
                                    ledgerDao.markPausedQuota(freshEntity.mediaId, syncErr.message)
                                    ledgerDao.pauseAllActiveForQuota(syncErr.message)
                                    _syncState.value = _syncState.value.copy(lastError = syncErr.message)
                                    _quotaState.value = QuotaStateManager.markError(_quotaState.value, HttpException(507, syncErr.message))
                                    StorageAlertNotificationHelper.notifyIfNeeded(context, _quotaState.value)
                                    return@withContext SyncOutcome.Interrupted(syncErr)
                                } else if (syncErr is SyncError.Auth) {
                                    val statusCode = if (syncErr.message.contains("disabled", ignoreCase = true)) 403 else 401
                                    _quotaState.value = QuotaStateManager.markError(_quotaState.value, HttpException(statusCode, syncErr.message))
                                    ledgerDao.markFailed(freshEntity.mediaId, syncErr.message, currentAttempt)
                                    _syncState.value = _syncState.value.copy(lastError = syncErr.message)
                                    return@withContext SyncOutcome.Interrupted(syncErr)
                                }
                                ledgerDao.markFailed(freshEntity.mediaId, syncErr.message, currentAttempt)
                                _syncState.value = _syncState.value.copy(lastError = syncErr.message)
                                break
                            } else {
                                delay((1L shl currentAttempt) * 500L)
                            }
                        }
                    }
                }
            }

            SyncNodePreferences.setLastSyncTimestamp(context, System.currentTimeMillis())
            val serverUrl = SyncNodePreferences.getServerUrl(context)
            val authToken = SyncNodePreferences.getAuthToken(context)
            val fp = SyncNodePreferences.getTlsFingerprint(context)
            if (!serverUrl.isNullOrEmpty() && !authToken.isNullOrEmpty()) {
                val meResult = httpClient.getMe(serverUrl, authToken, fp)
                meResult.onSuccess { me ->
                    SyncNodePreferences.saveUserProfile(
                        context,
                        role = me.role,
                        displayName = me.displayName,
                        quotaBytes = me.quotaBytes,
                        usedBytes = me.usedBytes,
                        deviceName = me.deviceName
                    )
                }
            }
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

    fun retryAllFailed() {
        scope.launch {
            ledgerDao.resetAllFailed()
            ledgerDao.resumeAllPausedQuota()
            resetFullBackoff()
            refreshSummary()
            refreshQuotaState()
        }
    }

    suspend fun refreshQuotaState(force: Boolean = false): QuotaState = withContext(Dispatchers.IO) {
        val serverUrl = SyncNodePreferences.getServerUrl(context)
        val authToken = SyncNodePreferences.getAuthToken(context)
        val fp = SyncNodePreferences.getTlsFingerprint(context)
        if (serverUrl.isNullOrEmpty() || authToken.isNullOrEmpty()) {
            return@withContext _quotaState.value
        }

        if (!force && _quotaState.value.isFullOrExceeded && !canCheckServerWhileFull()) {
            return@withContext _quotaState.value
        }

        val previous = _quotaState.value
        val result = httpClient.getStatus(serverUrl, authToken, fp)
        val newState = result.fold(
            onSuccess = { statusRes ->
                val updated = QuotaStateManager.updateFromStatus(statusRes)
                SyncNodePreferences.saveUserProfile(
                    context,
                    role = SyncNodePreferences.getUserRole(context),
                    displayName = SyncNodePreferences.getDisplayName(context),
                    quotaBytes = statusRes.userQuota,
                    usedBytes = statusRes.userUsed,
                    deviceName = SyncNodePreferences.getDeviceName(context)
                )
                resetFullBackoff()
                updated
            },
            onFailure = { err ->
                QuotaStateManager.markError(previous, err)
            }
        )

        _quotaState.value = newState
        StorageAlertNotificationHelper.notifyIfNeeded(context, newState)

        // Auto-resume check: if storage is no longer full/blocked and paused items exist
        if (!newState.isBlocked && ledgerDao.getPausedQuotaCount() > 0) {
            val resumed = ledgerDao.resumeAllPausedQuota()
            if (resumed > 0) {
                refreshSummary()
                MediaSyncWorker.enqueueImmediateSync(context, force = true)
            }
        }

        newState
    }

    fun retryFailed(mediaId: Long) {
        scope.launch {
            ledgerDao.resetForRetry(mediaId)
            refreshSummary()
        }
    }

    suspend fun getQueueItems(limit: Int = 50): List<SyncLedgerEntity> = withContext(Dispatchers.IO) {
        ledgerDao.getQueueItems(limit)
    }

    suspend fun getRecentActivity(limit: Int = 50): List<SyncLedgerEntity> = withContext(Dispatchers.IO) {
        ledgerDao.getRecentActivity(limit)
    }

    suspend fun fetchDeviceFolders(): Result<List<com.example.piphotos.net.ServerFolderInfo>> = withContext(Dispatchers.IO) {
        val isConfigured = SyncNodePreferences.isConfigured(context)
        if (!isConfigured) {
            return@withContext Result.failure(IllegalStateException("No node paired"))
        }
        val serverUrl = SyncNodePreferences.getServerUrl(context)
            ?: return@withContext Result.failure(IllegalStateException("No server URL"))
        val authToken = SyncNodePreferences.getAuthToken(context)
            ?: return@withContext Result.failure(IllegalStateException("No auth token"))
        val deviceId = SyncNodePreferences.getDeviceId(context)
            ?: return@withContext Result.failure(IllegalStateException("No device ID"))
        val fingerprint = SyncNodePreferences.getTlsFingerprint(context)

        val res = httpClient.getDeviceFolders(serverUrl, authToken, deviceId, fingerprint)
        res.map { it.folders }
    }

    suspend fun fetchServerStatus(): Result<com.example.piphotos.net.ServerStatus> = withContext(Dispatchers.IO) {
        val isConfigured = SyncNodePreferences.isConfigured(context)
        if (!isConfigured) {
            return@withContext Result.failure(IllegalStateException("No node paired"))
        }
        val serverUrl = SyncNodePreferences.getServerUrl(context)
            ?: return@withContext Result.failure(IllegalStateException("No server URL"))
        val authToken = SyncNodePreferences.getAuthToken(context)
            ?: return@withContext Result.failure(IllegalStateException("No auth token"))
        val fingerprint = SyncNodePreferences.getTlsFingerprint(context)

        val statusRes = httpClient.getStatus(serverUrl, authToken, fingerprint)
        if (statusRes.isSuccess) {
            fetchUserProfile()
        }
        statusRes
    }

    suspend fun fetchUserProfile(): Result<com.example.piphotos.net.MeResponse> = withContext(Dispatchers.IO) {
        val isConfigured = SyncNodePreferences.isConfigured(context)
        if (!isConfigured) {
            return@withContext Result.failure(IllegalStateException("No node paired"))
        }
        val serverUrl = SyncNodePreferences.getServerUrl(context)
            ?: return@withContext Result.failure(IllegalStateException("No server URL"))
        val authToken = SyncNodePreferences.getAuthToken(context)
            ?: return@withContext Result.failure(IllegalStateException("No auth token"))
        val fingerprint = SyncNodePreferences.getTlsFingerprint(context)

        val res = httpClient.getMe(serverUrl, authToken, fingerprint)
        res.onSuccess { me ->
            SyncNodePreferences.saveUserProfile(
                context = context,
                role = me.role,
                displayName = me.displayName,
                quotaBytes = me.quotaBytes,
                usedBytes = me.usedBytes,
                deviceName = me.deviceName
            )
        }
        res
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncRepository? = null

        fun getInstance(context: Context): SyncRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun deriveExtensionFromMimeType(mimeType: String?, isVideo: Boolean): String {
            val normalized = (mimeType ?: "").lowercase().trim()
            return when {
                normalized == "image/jpeg" || normalized == "image/jpg" -> "jpg"
                normalized == "image/png" -> "png"
                normalized == "image/heic" || normalized == "image/heif" -> "heic"
                normalized == "image/webp" -> "webp"
                normalized == "image/gif" -> "gif"
                normalized == "image/x-adobe-dng" || normalized == "image/dng" -> "dng"
                normalized == "image/tiff" -> "tiff"
                normalized == "image/bmp" || normalized == "image/x-ms-bmp" -> "bmp"
                normalized == "image/avif" -> "avif"

                normalized == "video/mp4" -> "mp4"
                normalized == "video/quicktime" -> "mov"
                normalized == "video/webm" -> "webm"
                normalized == "video/x-matroska" -> "mkv"
                normalized == "video/3gpp" || normalized == "video/3gp" -> "3gp"
                normalized == "video/x-msvideo" || normalized == "video/avi" -> "avi"

                normalized.startsWith("video/") -> "mp4"
                isVideo -> "mp4"
                else -> "jpg"
            }
        }

        fun sanitizeFilename(rawName: String?, mimeType: String?, isVideo: Boolean, mediaId: Long): String {
            val ext = deriveExtensionFromMimeType(mimeType, isVideo)
            if (rawName.isNullOrBlank()) {
                return "media_$mediaId.$ext"
            }

            var cleaned = rawName
                .replace("..", "__")
                .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
                .trim('.', ' ', '\t', '\r', '\n')

            if (cleaned.isBlank()) {
                return "media_$mediaId.$ext"
            }

            val lastDot = cleaned.lastIndexOf('.')
            val baseName = if (lastDot > 0) cleaned.substring(0, lastDot).trim() else cleaned
            val finalBase = if (baseName.isNotBlank()) baseName else "media_$mediaId"
            return "$finalBase.$ext"
        }
    }
}
