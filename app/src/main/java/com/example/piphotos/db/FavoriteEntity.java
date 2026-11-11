package com.example.piphotos.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "favorite")
public class FavoriteEntity {
    @PrimaryKey
    @ColumnInfo(name = "media_store_id")
    public long mediaStoreId;

    @ColumnInfo(name = "added_at_ms")
    public long addedAtMs;

    public FavoriteEntity(long mediaStoreId, long addedAtMs) {
        this.mediaStoreId = mediaStoreId;
        this.addedAtMs = addedAtMs;
    }
}

