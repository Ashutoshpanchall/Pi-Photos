package com.example.piphotos.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "favorites")
public class FavoriteRecord {
    @PrimaryKey
    public long mediaId;
    public long addedAt;

    public FavoriteRecord(long mediaId, long addedAt) {
        this.mediaId = mediaId;
        this.addedAt = addedAt;
    }
}

