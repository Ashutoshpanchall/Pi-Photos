package com.example.piphotos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FavoriteDao {
    @Query("SELECT mediaId FROM favorites")
    List<Long> getAllFavoriteIds();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addFavorite(FavoriteRecord favorite);

    @Query("DELETE FROM favorites WHERE mediaId = :mediaId")
    void removeFavorite(long mediaId);

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mediaId = :mediaId)")
    boolean isFavorite(long mediaId);

    @Query("SELECT COUNT(*) FROM favorites")
    int getFavoriteCount();
}

