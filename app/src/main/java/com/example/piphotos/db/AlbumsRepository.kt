package com.example.piphotos.db

import android.content.Context
import androidx.room.InvalidationTracker
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumWithCount(
    val id: Long,
    val name: String,
    val createdAtMs: Long,
    val coverMediaStoreId: Long?,
    val itemCount: Int
)

typealias RoomAlbum = AlbumWithCount

class AlbumsRepository(
    private val context: Context,
    private val albumDao: AlbumDao,
    private val db: AppDatabase
) {

    constructor(context: Context) : this(
        context.applicationContext,
        AppDatabase.getInstance(context).albumDao(),
        AppDatabase.getInstance(context)
    )

    suspend fun createAlbum(name: String, initialItemIds: List<Long> = emptyList()): Long = withContext(Dispatchers.IO) {
        val coverId = initialItemIds.firstOrNull()
        val albumId = albumDao.insertAlbum(AlbumEntity(name, System.currentTimeMillis(), coverId))
        if (initialItemIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val index = LocalMediaIndex.getInstance(context)
            val entities = initialItemIds.map { id ->
                val isVid = index.getItem(id)?.isVideo ?: false
                AlbumItemEntity(albumId, id, isVid, now)
            }
            albumDao.insertItems(entities)
        }
        albumId
    }

    suspend fun setAlbumCover(albumId: Long, coverMediaStoreId: Long) = withContext(Dispatchers.IO) {
        albumDao.updateCover(albumId, coverMediaStoreId)
    }

    suspend fun renameAlbum(id: Long, newName: String) = withContext(Dispatchers.IO) {
        albumDao.renameAlbum(id, newName)
    }

    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        albumDao.deleteAlbum(id)
    }

    suspend fun addPhotos(albumId: Long, items: List<LocalMediaItem>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = items.map { item ->
            AlbumItemEntity(albumId, item.id, item.isVideo, now)
        }
        albumDao.insertItems(entities)
        val album = albumDao.getAlbumById(albumId)
        if (album != null && album.coverMediaStoreId == null && items.isNotEmpty()) {
            albumDao.updateCover(albumId, items.first().id)
        }
    }

    suspend fun addPhotosToAlbum(albumId: Long, items: List<LocalMediaItem>) = addPhotos(albumId, items)

    suspend fun removePhotos(albumId: Long, itemIds: List<Long>) = withContext(Dispatchers.IO) {
        for (id in itemIds) {
            albumDao.removeItem(albumId, id)
        }
    }

    suspend fun removePhotoFromAlbum(albumId: Long, mediaStoreId: Long) = removePhotos(albumId, listOf(mediaStoreId))

    suspend fun getAllAlbums(): List<AlbumWithCount> = withContext(Dispatchers.IO) {
        val entities = albumDao.allAlbums
        entities.map { entity ->
            val count = albumDao.getItemCountForAlbum(entity.id)
            val coverId = entity.coverMediaStoreId ?: albumDao.getItemIdsForAlbum(entity.id).firstOrNull()
            AlbumWithCount(
                id = entity.id,
                name = entity.name ?: "Album",
                createdAtMs = entity.createdAtMs,
                coverMediaStoreId = coverId,
                itemCount = count
            )
        }
    }

    suspend fun getAlbumById(albumId: Long): AlbumWithCount? = withContext(Dispatchers.IO) {
        val entity = albumDao.getAlbumById(albumId) ?: return@withContext null
        val count = albumDao.getItemCountForAlbum(albumId)
        val coverId = entity.coverMediaStoreId ?: albumDao.getItemIdsForAlbum(albumId).firstOrNull()
        AlbumWithCount(
            id = entity.id,
            name = entity.name ?: "Album",
            createdAtMs = entity.createdAtMs,
            coverMediaStoreId = coverId,
            itemCount = count
        )
    }

    suspend fun getItemsForAlbumList(albumId: Long): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        val ids = albumDao.getItemIdsForAlbum(albumId)
        val index = LocalMediaIndex.getInstance(context)
        val allItems = index.getAllItems()
        val itemMap = allItems.associateBy { it.id }
        ids.mapNotNull { itemMap[it] }
    }

    fun observeAlbums(): Flow<List<AlbumWithCount>> = callbackFlow {
        val sendCurrent: suspend () -> Unit = {
            val albums = getAllAlbums()
            trySend(albums)
        }
        val observer = object : InvalidationTracker.Observer(arrayOf("album", "album_item")) {
            override fun onInvalidated(tables: Set<String>) {
                launch(Dispatchers.IO) {
                    sendCurrent()
                }
            }
        }
        db.invalidationTracker.addObserver(observer)
        launch(Dispatchers.IO) {
            sendCurrent()
        }
        awaitClose {
            db.invalidationTracker.removeObserver(observer)
        }
    }

    fun getItemsForAlbum(albumId: Long): Flow<List<LocalMediaItem>> = callbackFlow {
        val index = LocalMediaIndex.getInstance(context)
        val sendCurrent: suspend () -> Unit = {
            val items = getItemsForAlbumList(albumId)
            trySend(items)
        }
        val observer = object : InvalidationTracker.Observer(arrayOf("album_item")) {
            override fun onInvalidated(tables: Set<String>) {
                launch(Dispatchers.IO) {
                    sendCurrent()
                }
            }
        }
        db.invalidationTracker.addObserver(observer)

        val mediaJob = launch(Dispatchers.IO) {
            index.mediaFlow.collect {
                sendCurrent()
            }
        }

        launch(Dispatchers.IO) {
            sendCurrent()
        }

        awaitClose {
            mediaJob.cancel()
            db.invalidationTracker.removeObserver(observer)
        }
    }
}
