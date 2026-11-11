package com.example.piphotos.db

import android.content.Context
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesRepository(
    private val dao: FavoriteDao,
    private val db: AppDatabase? = null
) {

    constructor(context: Context) : this(
        AppDatabase.getInstance(context).favoriteDao(),
        AppDatabase.getInstance(context)
    )

    private val _idsFlow = MutableStateFlow<Set<Long>>(emptySet())
    val idsFlow: StateFlow<Set<Long>> = _idsFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            refreshIds()
        }
        db?.invalidationTracker?.addObserver(object : InvalidationTracker.Observer(arrayOf("favorite")) {
            override fun onInvalidated(tables: Set<String>) {
                scope.launch {
                    refreshIds()
                }
            }
        })
    }

    private fun refreshIds() {
        try {
            _idsFlow.value = dao.allFavoriteIds.toSet()
        } catch (_: Exception) {}
    }

    fun observeIds(): Flow<Set<Long>> = idsFlow

    suspend fun getAllFavoriteIds(): Set<Long> = withContext(Dispatchers.IO) {
        val ids = dao.allFavoriteIds.toSet()
        _idsFlow.value = ids
        ids
    }

    suspend fun isFavorite(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        dao.isFavorite(mediaId)
    }

    suspend fun addFavorite(mediaId: Long) = withContext(Dispatchers.IO) {
        dao.addFavorite(FavoriteEntity(mediaId, System.currentTimeMillis()))
        _idsFlow.value = dao.allFavoriteIds.toSet()
    }

    suspend fun removeFavorite(mediaId: Long) = withContext(Dispatchers.IO) {
        dao.removeFavorite(mediaId)
        _idsFlow.value = dao.allFavoriteIds.toSet()
    }

    suspend fun toggle(id: Long): Boolean = toggleFavorite(id)

    suspend fun toggleFavorite(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        val currentlyFav = dao.isFavorite(mediaId)
        if (currentlyFav) {
            dao.removeFavorite(mediaId)
            _idsFlow.value = dao.allFavoriteIds.toSet()
            false
        } else {
            dao.addFavorite(FavoriteEntity(mediaId, System.currentTimeMillis()))
            _idsFlow.value = dao.allFavoriteIds.toSet()
            true
        }
    }

    suspend fun getFavoriteCount(): Int = withContext(Dispatchers.IO) {
        dao.favoriteCount
    }
}
