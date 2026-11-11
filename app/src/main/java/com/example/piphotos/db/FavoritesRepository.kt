package com.example.piphotos.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FavoritesRepository(private val context: Context) {

    private val dao by lazy {
        AppDatabase.getInstance(context).favoriteDao()
    }

    suspend fun getAllFavoriteIds(): Set<Long> = withContext(Dispatchers.IO) {
        dao.allFavoriteIds.toSet()
    }

    suspend fun isFavorite(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        dao.isFavorite(mediaId)
    }

    suspend fun addFavorite(mediaId: Long) = withContext(Dispatchers.IO) {
        dao.addFavorite(FavoriteRecord(mediaId, System.currentTimeMillis()))
    }

    suspend fun removeFavorite(mediaId: Long) = withContext(Dispatchers.IO) {
        dao.removeFavorite(mediaId)
    }

    suspend fun toggleFavorite(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        val currentlyFav = dao.isFavorite(mediaId)
        if (currentlyFav) {
            dao.removeFavorite(mediaId)
            false
        } else {
            dao.addFavorite(FavoriteRecord(mediaId, System.currentTimeMillis()))
            true
        }
    }

    suspend fun getFavoriteCount(): Int = withContext(Dispatchers.IO) {
        dao.favoriteCount
    }
}

