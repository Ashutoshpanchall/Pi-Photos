package com.example.piphotos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MediaIndexDao {
    @Query("SELECT * FROM media_index ORDER BY CASE WHEN date_taken_ms > 0 THEN date_taken_ms ELSE date_modified_sec * 1000 END DESC")
    List<MediaIndexEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<MediaIndexEntity> items);

    @Query("DELETE FROM media_index WHERE id IN (:ids)")
    void deleteByIds(List<Long> ids);

    @Query("DELETE FROM media_index")
    void clearAll();

    @Query("SELECT COUNT(*) FROM media_index")
    int getCount();
}

