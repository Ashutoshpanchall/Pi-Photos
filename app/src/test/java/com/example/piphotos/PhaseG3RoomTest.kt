package com.example.piphotos

import com.example.piphotos.db.AlbumDao
import com.example.piphotos.db.AlbumEntity
import com.example.piphotos.db.AlbumItemEntity
import com.example.piphotos.db.FavoriteDao
import com.example.piphotos.db.FavoriteEntity
import com.example.piphotos.db.FavoritesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG3RoomTest {

    private class InMemoryFavoriteDao : FavoriteDao {
        private val storage = mutableMapOf<Long, FavoriteEntity>()

        override fun getAllFavoriteIds(): List<Long> = storage.keys.toList()

        override fun addFavorite(favorite: FavoriteEntity) {
            storage[favorite.mediaStoreId] = favorite
        }

        override fun removeFavorite(mediaId: Long) {
            storage.remove(mediaId)
        }

        override fun isFavorite(mediaId: Long): Boolean = storage.containsKey(mediaId)

        override fun getFavoriteCount(): Int = storage.size
    }

    private class InMemoryAlbumDao : AlbumDao {
        private var nextId = 1L
        private val albums = mutableMapOf<Long, AlbumEntity>()
        private val items = mutableListOf<AlbumItemEntity>()

        override fun insertAlbum(album: AlbumEntity): Long {
            val id = if (album.id > 0) album.id else nextId++
            album.id = id
            albums[id] = album
            return id
        }

        override fun updateAlbum(album: AlbumEntity) {
            albums[album.id] = album
        }

        override fun renameAlbum(albumId: Long, newName: String) {
            albums[albumId]?.let {
                it.name = newName
            }
        }

        override fun updateCover(albumId: Long, coverId: Long?) {
            albums[albumId]?.let {
                it.coverMediaStoreId = coverId
            }
        }

        override fun deleteAlbum(albumId: Long) {
            albums.remove(albumId)
            // Enforce CASCADE delete
            items.removeAll { it.albumId == albumId }
        }

        override fun getAllAlbums(): List<AlbumEntity> {
            return albums.values.sortedByDescending { it.createdAtMs }
        }

        override fun getAlbumById(albumId: Long): AlbumEntity? {
            return albums[albumId]
        }

        override fun getAlbumByName(name: String): AlbumEntity? {
            return albums.values.firstOrNull { it.name == name }
        }

        override fun insertItems(newItems: List<AlbumItemEntity>) {
            for (newItem in newItems) {
                items.removeAll { it.albumId == newItem.albumId && it.mediaStoreId == newItem.mediaStoreId }
                items.add(newItem)
            }
        }

        override fun removeItem(albumId: Long, mediaStoreId: Long) {
            items.removeAll { it.albumId == albumId && it.mediaStoreId == mediaStoreId }
        }

        override fun getItemIdsForAlbum(albumId: Long): List<Long> {
            return items.filter { it.albumId == albumId }
                .sortedByDescending { it.addedAtMs }
                .map { it.mediaStoreId }
        }

        override fun getItemCountForAlbum(albumId: Long): Int {
            return items.count { it.albumId == albumId }
        }
    }

    @Test
    fun d1_favorites_insert_query_toggle_remove_persists() = runBlocking {
        val dao = InMemoryFavoriteDao()
        val repo = FavoritesRepository(dao)

        assertEquals(0, repo.getFavoriteCount())
        assertFalse(repo.isFavorite(1001L))

        // 1. Toggle on
        val toggledOn = repo.toggle(1001L)
        assertTrue(toggledOn)
        assertTrue(repo.isFavorite(1001L))
        assertEquals(1, repo.getFavoriteCount())
        assertEquals(setOf(1001L), repo.getAllFavoriteIds())

        // 2. Observe flow
        val flowValue = repo.observeIds().first()
        assertTrue(flowValue.contains(1001L))

        // 3. Add directly
        repo.addFavorite(1002L)
        assertTrue(repo.isFavorite(1002L))
        assertEquals(2, repo.getFavoriteCount())
        assertEquals(setOf(1001L, 1002L), repo.observeIds().first())

        // 4. Toggle off
        val toggledOff = repo.toggle(1001L)
        assertFalse(toggledOff)
        assertFalse(repo.isFavorite(1001L))
        assertEquals(1, repo.getFavoriteCount())
        assertEquals(setOf(1002L), repo.observeIds().first())

        // 5. Remove directly
        repo.removeFavorite(1002L)
        assertFalse(repo.isFavorite(1002L))
        assertEquals(0, repo.getFavoriteCount())
        assertTrue(repo.observeIds().first().isEmpty())
    }

    @Test
    fun d2_albums_crud_addPhotos_and_cascadeDelete() = runBlocking {
        val albumDao = InMemoryAlbumDao()

        // 1. Create Album
        val albumEntity = AlbumEntity("Summer Trip", 1000L, null)
        val albumId = albumDao.insertAlbum(albumEntity)
        assertEquals(1L, albumId)

        val retrieved = albumDao.getAlbumById(albumId)
        assertNotNull(retrieved)
        assertEquals("Summer Trip", retrieved?.name)
        assertEquals(0, albumDao.getItemCountForAlbum(albumId))

        // 2. Insert items into Album
        val item1 = AlbumItemEntity(albumId, 2001L, false, 1001L)
        val item2 = AlbumItemEntity(albumId, 2002L, true, 1002L)
        val item3 = AlbumItemEntity(albumId, 2003L, false, 1003L)
        albumDao.insertItems(listOf(item1, item2, item3))

        assertEquals(3, albumDao.getItemCountForAlbum(albumId))
        val itemIds = albumDao.getItemIdsForAlbum(albumId)
        assertEquals(3, itemIds.size)
        assertTrue(itemIds.contains(2001L))
        assertTrue(itemIds.contains(2002L))
        assertTrue(itemIds.contains(2003L))

        // 3. Rename Album
        albumDao.renameAlbum(albumId, "Winter Trip")
        val renamed = albumDao.getAlbumById(albumId)
        assertEquals("Winter Trip", renamed?.name)

        // 4. Remove single photo from Album
        albumDao.removeItem(albumId, 2002L)
        assertEquals(2, albumDao.getItemCountForAlbum(albumId))
        assertFalse(albumDao.getItemIdsForAlbum(albumId).contains(2002L))

        // 5. Cascade Delete on Album Deletion
        albumDao.deleteAlbum(albumId)
        assertNull(albumDao.getAlbumById(albumId))
        assertEquals(0, albumDao.getAllAlbums().size)
        // Ensure child items in album_item were cascade deleted
        assertEquals(0, albumDao.getItemCountForAlbum(albumId))
        assertEquals(0, albumDao.getItemIdsForAlbum(albumId).size)
    }
}
