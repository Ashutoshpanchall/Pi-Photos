package com.example.piphotos

import com.example.piphotos.db.FavoriteDao
import com.example.piphotos.db.FavoriteEntity
import com.example.piphotos.db.FavoritesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG2FavoritesTest {

    private class TestFavoriteDao : FavoriteDao {
        private val records = mutableMapOf<Long, FavoriteEntity>()

        override fun getAllFavoriteIds(): List<Long> = records.keys.toList()

        override fun addFavorite(favorite: FavoriteEntity) {
            records[favorite.mediaStoreId] = favorite
        }

        override fun removeFavorite(mediaId: Long) {
            records.remove(mediaId)
        }

        override fun isFavorite(mediaId: Long): Boolean = records.containsKey(mediaId)

        override fun getFavoriteCount(): Int = records.size
    }

    @Test
    fun favoritesRepository_toggleAndQueryPersists() = runBlocking {
        val dao = TestFavoriteDao()
        val repo = FavoritesRepository(dao)

        assertFalse(repo.isFavorite(101L))
        assertEquals(0, repo.getAllFavoriteIds().size)

        // Toggle on
        val added = repo.toggleFavorite(101L)
        assertTrue(added)
        assertTrue(repo.isFavorite(101L))
        assertEquals(setOf(101L), repo.getAllFavoriteIds())

        // Toggle second
        repo.toggleFavorite(102L)
        assertTrue(repo.isFavorite(102L))
        assertEquals(2, repo.getAllFavoriteIds().size)

        // Toggle off first
        val removed = repo.toggleFavorite(101L)
        assertFalse(removed)
        assertFalse(repo.isFavorite(101L))
        assertTrue(repo.isFavorite(102L))
        assertEquals(1, repo.getAllFavoriteIds().size)
    }
}
