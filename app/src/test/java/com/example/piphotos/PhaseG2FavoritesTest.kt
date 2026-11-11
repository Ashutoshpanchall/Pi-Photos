package com.example.piphotos

import com.example.piphotos.db.FavoriteDao
import com.example.piphotos.db.FavoriteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG2FavoritesTest {

    private class FakeFavoriteDao : FavoriteDao {
        private val records = mutableMapOf<Long, FavoriteRecord>()

        override fun getAllFavoriteIds(): List<Long> = records.keys.toList()

        override fun addFavorite(favorite: FavoriteRecord) {
            records[favorite.mediaId] = favorite
        }

        override fun removeFavorite(mediaId: Long) {
            records.remove(mediaId)
        }

        override fun isFavorite(mediaId: Long): Boolean = records.containsKey(mediaId)

        override fun getFavoriteCount(): Int = records.size
    }

    @Test
    fun favoriteDao_addCheckAndRemoveOperatesCorrectly() {
        val dao = FakeFavoriteDao()
        assertEquals(0, dao.favoriteCount)
        assertFalse(dao.isFavorite(101L))

        // Add favorite
        dao.addFavorite(FavoriteRecord(101L, System.currentTimeMillis()))
        assertTrue(dao.isFavorite(101L))
        assertEquals(1, dao.favoriteCount)
        assertEquals(listOf(101L), dao.allFavoriteIds)

        // Add second favorite
        dao.addFavorite(FavoriteRecord(102L, System.currentTimeMillis()))
        assertEquals(2, dao.favoriteCount)
        assertTrue(dao.allFavoriteIds.containsAll(listOf(101L, 102L)))

        // Remove first favorite
        dao.removeFavorite(101L)
        assertFalse(dao.isFavorite(101L))
        assertTrue(dao.isFavorite(102L))
        assertEquals(1, dao.favoriteCount)
    }
}

