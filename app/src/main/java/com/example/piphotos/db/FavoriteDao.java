package com.example.piphotos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FavoriteDao {
    @Query("SELECT media_store_id FROM favorite")
    List<Long> getAllFavoriteIds();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addFavorite(FavoriteEntity entity);

    @Query("DELETE FROM favorite WHERE media_store_id = :mediaStoreId")
    void removeFavorite(long mediaStoreId);

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE media_store_id = :mediaStoreId)")
    boolean isFavorite(long mediaStoreId);

    @Query("SELECT COUNT(*) FROM favorite")
    int getFavoriteCount();
}
