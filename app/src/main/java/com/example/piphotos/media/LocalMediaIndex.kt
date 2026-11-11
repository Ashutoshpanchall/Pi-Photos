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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LocalMediaIndex(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner = MediaStoreScanner(appContext)
    val favoritesRepo = FavoritesRepository(appContext)

    private val itemsMap = ConcurrentHashMap<Long, LocalMediaItem>()
    private var lastGeneration: Long = 0L
    private var lastVersion: String = ""

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
        val (items, stats) = scanner.performFullScan()
        itemsMap.clear()
        for (item in items) {
            itemsMap[item.id] = item
        }
        lastGeneration = stats.generation
        lastVersion = stats.mediaStoreVersion

        val sortedList = itemsMap.values.sortedByDescending { it.effectiveDateMs }
        _mediaFlow.value = sortedList
        _statsFlow.value = stats

        startObserving()
        stats
    }

    suspend fun refreshDelta(): IndexStats = withContext(Dispatchers.IO) {
        val currentVersion = scanner.getCurrentMediaStoreVersion()
        if (lastVersion.isNotEmpty() && currentVersion != lastVersion) {
            return@withContext rebuildIndex()
        }

        val startTime = SystemClock.elapsedRealtime()

        // 1. Detect and prune deletions by re-reading active IDs
        val activeIds = scanner.queryActiveIds()
        val currentKeys = itemsMap.keys.toSet()
        val deletedIds = currentKeys - activeIds
        for (delId in deletedIds) {
            itemsMap.remove(delId)
        }

        // 2. Fetch modified or new items since lastGeneration
        val modifiedItems = scanner.performDeltaScan(lastGeneration)
        for (item in modifiedItems) {
            itemsMap[item.id] = item
        }

        val newMaxGen = modifiedItems.maxOfOrNull { it.generationModified } ?: lastGeneration
        lastGeneration = maxOf(lastGeneration, newMaxGen)
        lastVersion = currentVersion

        val sortedList = itemsMap.values.sortedByDescending { it.effectiveDateMs }
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val photosCount = sortedList.count { !it.isVideo }
        val videosCount = sortedList.count { it.isVideo }
        val foldersMap = sortedList.groupingBy { it.bucketName }.eachCount()

        val updatedStats = IndexStats(
            totalItems = sortedList.size,
            totalPhotos = photosCount,
            totalVideos = videosCount,
            totalFolders = foldersMap.size,
            buildTimeMs = elapsed,
            lastScanTimestampMs = System.currentTimeMillis(),
            mediaStoreVersion = lastVersion,
            generation = lastGeneration,
            folderBreakdown = foldersMap
        )

        _mediaFlow.value = sortedList
        _statsFlow.value = updatedStats
        updatedStats
    }

    fun getAllItems(): List<LocalMediaItem> {
        return _mediaFlow.value
    }

    fun getItem(id: Long): LocalMediaItem? {
        return itemsMap[id]
    }

    suspend fun getFavoriteItems(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val favIds = favoritesRepo.getAllFavoriteIds()
        _mediaFlow.value.filter { favIds.contains(it.id) }
    }

    fun getVideoItems(): List<LocalMediaItem> {
        return _mediaFlow.value.filter { it.isVideo }
    }

    fun getItemsForFolder(bucketId: String): List<LocalMediaItem> {
        return _mediaFlow.value.filter { it.bucketId == bucketId }
    }

    fun getFolderList(): List<FolderItem> {
        val grouped = _mediaFlow.value.groupBy { it.bucketId }
        val folders = mutableListOf<FolderItem>()
        for ((bucketId, items) in grouped) {
            val first = items.first()
            val coverUri = first.contentUri.toString()
            folders.add(
                FolderItem(
                    bucketId = bucketId,
                    name = first.bucketName,
                    path = "",
                    coverUri = coverUri,
                    itemCount = items.size,
                    sizeBytes = items.sumOf { it.sizeBytes }
                )
            )
        }
        return folders.sortedByDescending { it.itemCount }
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
            bucketId = localItem.bucketId,
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
