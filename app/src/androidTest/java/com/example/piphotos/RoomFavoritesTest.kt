package com.example.piphotos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.FavoriteDao
import com.example.piphotos.db.FavoriteRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomFavoritesTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FavoriteDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.favoriteDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testFavoriteOperations() {
        assertEquals(0, dao.favoriteCount)
        assertFalse(dao.isFavorite(101L))

        dao.addFavorite(FavoriteRecord(101L, System.currentTimeMillis()))
        assertTrue(dao.isFavorite(101L))
        assertEquals(1, dao.favoriteCount)
        assertEquals(listOf(101L), dao.allFavoriteIds)

        dao.addFavorite(FavoriteRecord(102L, System.currentTimeMillis()))
        assertEquals(2, dao.favoriteCount)
        assertTrue(dao.allFavoriteIds.containsAll(listOf(101L, 102L)))

        dao.removeFavorite(101L)
        assertFalse(dao.isFavorite(101L))
        assertTrue(dao.isFavorite(102L))
        assertEquals(1, dao.favoriteCount)
    }
}

