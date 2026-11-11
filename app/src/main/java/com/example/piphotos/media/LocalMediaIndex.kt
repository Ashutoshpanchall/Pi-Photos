package com.example.piphotos.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.example.piphotos.FolderItem
import com.example.piphotos.MediaItem
import com.example.piphotos.db.FavoritesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalMediaIndex(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner = MediaStoreScanner(appContext)
    val favoritesRepo = FavoritesRepository(appContext)

    private val mutex = Mutex()

    @Volatile
    private var lastGeneration: Long = 0L

    @Volatile
    private var lastVersion: String = ""

    // Single source of truth for in-memory index, kept sorted by effectiveDateMs DESC
    private val items = ArrayList<LocalMediaItem>()

    private val _mediaFlow = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    val mediaFlow: StateFlow<List<LocalMediaItem>> = _mediaFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(IndexStats())
    val statsFlow: StateFlow<IndexStats> = _statsFlow.asStateFlow()

    private var debounceJob: Job? = null
    private var isObserverRegistered = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            debounceUpdate()
        }
    }

    fun startObserving() {
        if (isObserverRegistered) return
        try {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
            isObserverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Error registering ContentObserver: ${e.message}", e)
        }
    }

    fun stopObserving() {
        if (!isObserverRegistered) return
        try {
            appContext.contentResolver.unregisterContentObserver(contentObserver)
            isObserverRegistered = false
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering ContentObserver: ${e.message}", e)
        }
    }

    private fun debounceUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500L) // 500ms debounce
            refreshDelta()
        }
    }

    suspend fun rebuildIndex(): IndexStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            doRebuildLocked()
        }
    }

    private fun doRebuildLocked(): IndexStats {
        val (scannedItems, stats) = scanner.performFullScan()
        items.clear()
        items.ensureCapacity(scannedItems.size)
        items.addAll(scannedItems)
        items.sortByDescending { it.effectiveDateMs }

        lastGeneration = stats.generation
        lastVersion = stats.mediaStoreVersion

        _mediaFlow.value = ArrayList(items)
        _statsFlow.value = stats

        startObserving()
        return stats
    }

    suspend fun refreshDelta(): IndexStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentVersion = scanner.getCurrentMediaStoreVersion()
            if (lastVersion.isNotEmpty() && currentVersion != lastVersion) {
                return@withLock doRebuildLocked()
            }

            val startTime = SystemClock.elapsedRealtime()

            // 1. Detect and prune deletions by re-reading active IDs
            val activeIds = scanner.queryActiveIds()
            items.removeAll { it.id !in activeIds }

            // 2. Fetch modified or new items since lastGeneration
            val modifiedItems = scanner.performDeltaScan(lastGeneration)
            if (modifiedItems.isNotEmpty()) {
                val modifiedIds = modifiedItems.map { it.id }.toSet()
                items.removeAll { it.id in modifiedIds }
                items.addAll(modifiedItems)
                items.sortByDescending { it.effectiveDateMs }
            }

            val newMaxGen = modifiedItems.maxOfOrNull { it.generationModified } ?: lastGeneration
            lastGeneration = maxOf(lastGeneration, newMaxGen)
            lastVersion = currentVersion

            val elapsed = SystemClock.elapsedRealtime() - startTime

            val photosCount = items.count { !it.isVideo }
            val videosCount = items.count { it.isVideo }
            val foldersMap = items.groupingBy { it.bucketName }.eachCount()

            val updatedStats = IndexStats(
                totalItems = items.size,
                totalPhotos = photosCount,
                totalVideos = videosCount,
                totalFolders = foldersMap.size,
                buildTimeMs = elapsed,
                lastScanTimestampMs = System.currentTimeMillis(),
                mediaStoreVersion = lastVersion,
                generation = lastGeneration,
                folderBreakdown = foldersMap
            )

            _mediaFlow.value = ArrayList(items)
            _statsFlow.value = updatedStats
            updatedStats
        }
    }

    fun getAllItems(): List<LocalMediaItem> {
        return _mediaFlow.value
    }

    fun getItem(id: Long): LocalMediaItem? {
        val current = _mediaFlow.value
        return current.firstOrNull { it.id == id }
    }

    suspend fun getFavoriteItems(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val favIds = favoritesRepo.getAllFavoriteIds()
        _mediaFlow.value.filter { favIds.contains(it.id) }
    }

    fun getVideoItems(): List<LocalMediaItem> {
        return _mediaFlow.value.filter { it.isVideo }
    }

    fun getItemsForFolder(bucketId: Long): List<LocalMediaItem> {
        return _mediaFlow.value.filter { it.bucketId == bucketId }
    }

    fun getItemsForFolderName(bucketName: String): List<LocalMediaItem> {
        return _mediaFlow.value.filter { it.bucketName.equals(bucketName, ignoreCase = true) }
    }

    fun getFolderList(): List<FolderItem> {
        val grouped = _mediaFlow.value.groupBy { it.bucketId }
        val folders = ArrayList<FolderItem>(grouped.size)
        for ((bucketId, bucketItems) in grouped) {
            val first = bucketItems.first()
            val coverUri = first.contentUri.toString()
            folders.add(
                FolderItem(
                    bucketId = bucketId.toString(),
                    name = first.bucketName,
                    path = "",
                    coverUri = coverUri,
                    itemCount = bucketItems.size,
                    sizeBytes = bucketItems.sumOf { it.sizeBytes }
                )
            )
        }
        folders.sortByDescending { it.itemCount }
        return folders
    }

    fun toUiMediaItem(localItem: LocalMediaItem, isFav: Boolean = false): MediaItem {
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val dateStr = dateFormat.format(Date(localItem.effectiveDateMs))
        val durationFormatted = if (localItem.isVideo && localItem.durationMs > 0) {
            val totalSec = localItem.durationMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            String.format(Locale.US, "%d:%02d", min, sec)
        } else null

        return MediaItem(
            id = localItem.id.toString(),
            title = "${if (localItem.isVideo) "VID" else "IMG"}_${localItem.id}",
            date = dateStr,
            category = localItem.bucketName,
            contentUri = localItem.contentUri.toString(),
            isVideo = localItem.isVideo,
            duration = durationFormatted,
            dateTakenMs = localItem.effectiveDateMs,
            bucketId = localItem.bucketId.toString(),
            bucketName = localItem.bucketName,
            mimeType = localItem.mimeType,
            sizeBytes = localItem.sizeBytes,
            durationMs = localItem.durationMs,
            isFavorite = isFav
        )
    }

    companion object {
        const val TAG = "LocalMediaIndex"

        @Volatile
        private var INSTANCE: LocalMediaIndex? = null

        fun getInstance(context: Context): LocalMediaIndex {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalMediaIndex(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
