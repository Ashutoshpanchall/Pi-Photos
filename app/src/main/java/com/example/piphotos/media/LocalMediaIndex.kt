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
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.FavoritesRepository
import com.example.piphotos.db.MediaIndexEntity
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
    private val db = AppDatabase.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences("piphotos_index_meta", Context.MODE_PRIVATE)

    private val mutex = Mutex()

    @Volatile
    private var lastGeneration: Long = 0L

    @Volatile
    private var lastVersion: String = ""

    // Single source of truth for in-memory index, kept sorted by effectiveDateMs DESC
    private val items = ArrayList<LocalMediaItem>()
    private var revision: Long = 0L

    private val _mediaFlow = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    val mediaFlow: StateFlow<List<LocalMediaItem>> = _mediaFlow.asStateFlow()

    private val _indexStateFlow = MutableStateFlow<IndexState>(IndexState.Loading)
    val indexStateFlow: StateFlow<IndexState> = _indexStateFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(IndexStats())
    val statsFlow: StateFlow<IndexStats> = _statsFlow.asStateFlow()

    private var debounceJob: Job? = null
    private var isObserverRegistered = false

    init {
        lastGeneration = prefs.getLong("last_generation", 0L)
        lastVersion = prefs.getString("last_version", "") ?: ""

        // Fast cold-start recovery: load from Room cache immediately!
        scope.launch {
            mutex.withLock {
                if (items.isEmpty()) {
                    val cachedEntities = db.mediaIndexDao().all
                    if (cachedEntities.isNotEmpty()) {
                        val cachedItems = cachedEntities.map { it.toLocalMediaItem() }
                        items.ensureCapacity(cachedItems.size)
                        items.addAll(cachedItems)
                        revision++
                        val copied = ArrayList(items)
                        _mediaFlow.value = copied
                        _indexStateFlow.value = IndexState.Ready(copied, revision)
                    }
                }
            }
        }
    }

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

        // Persist to Room & SharedPreferences
        val entities = scannedItems.map { it.toEntity() }
        db.mediaIndexDao().clearAll()
        db.mediaIndexDao().insertAll(entities)
        prefs.edit()
            .putLong("last_generation", lastGeneration)
            .putString("last_version", lastVersion)
            .apply()

        revision++
        val copied = ArrayList(items)
        _mediaFlow.value = copied
        _indexStateFlow.value = IndexState.Ready(copied, revision)
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

            // 1. Detect and prune deletions & fetch modified/new items
            val activeIds = scanner.queryActiveIds()
            val modifiedItems = scanner.performDeltaScan(lastGeneration)

            // Sync database with delta changes
            if (activeIds.isNotEmpty()) {
                val removedIds = items.filter { !activeIds.contains(it.id) }.map { it.id }
                if (removedIds.isNotEmpty()) {
                    db.mediaIndexDao().deleteByIds(removedIds)
                }
            }
            if (modifiedItems.isNotEmpty()) {
                db.mediaIndexDao().insertAll(modifiedItems.map { it.toEntity() })
            }

            val hasChanged = IndexDeltaApplier.applyDelta(items, activeIds, modifiedItems)

            if (hasChanged || _indexStateFlow.value !is IndexState.Ready) {
                revision++
                val copied = ArrayList(items)
                _mediaFlow.value = copied
                _indexStateFlow.value = IndexState.Ready(copied, revision)
            }

            val newMaxGen = modifiedItems.maxOfOrNull { it.generationModified } ?: lastGeneration
            lastGeneration = maxOf(lastGeneration, newMaxGen)
            lastVersion = currentVersion

            prefs.edit()
                .putLong("last_generation", lastGeneration)
                .putString("last_version", lastVersion)
                .apply()

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

sealed class IndexState {
    object Loading : IndexState()
    data class Ready(val items: List<LocalMediaItem>, val revision: Long) : IndexState()
}

object IndexDeltaApplier {
    fun applyDelta(
        currentItems: MutableList<LocalMediaItem>,
        activeIds: Set<Long>,
        modifiedItems: List<LocalMediaItem>
    ): Boolean {
        val removedAny = currentItems.removeAll { it.id !in activeIds }
        val addedOrModified = modifiedItems.isNotEmpty()
        if (addedOrModified) {
            val modifiedIds = modifiedItems.map { it.id }.toSet()
            currentItems.removeAll { it.id in modifiedIds }
            currentItems.addAll(modifiedItems)
            currentItems.sortByDescending { it.effectiveDateMs }
        }
        return removedAny || addedOrModified
    }
}

fun MediaIndexEntity.toLocalMediaItem(): LocalMediaItem {
    return LocalMediaItem(
        id = id,
        isVideo = isVideo,
        mimeType = mimeType ?: if (isVideo) "video/mp4" else "image/jpeg",
        dateTakenMs = dateTakenMs,
        dateAddedSec = dateAddedSec,
        dateModifiedSec = dateModifiedSec,
        bucketId = bucketId,
        bucketName = bucketName ?: "",
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        generationModified = generationModified
    )
}

fun LocalMediaItem.toEntity(): MediaIndexEntity {
    return MediaIndexEntity(
        id,
        isVideo,
        mimeType,
        dateTakenMs,
        dateAddedSec,
        dateModifiedSec,
        bucketId,
        bucketName,
        sizeBytes,
        durationMs,
        generationModified
    )
}

