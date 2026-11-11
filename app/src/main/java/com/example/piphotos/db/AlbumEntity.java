package com.example.piphotos.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "album")
public class AlbumEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    @ColumnInfo(name = "cover_media_store_id")
    public Long coverMediaStoreId;

    public AlbumEntity(String name, long createdAtMs, Long coverMediaStoreId) {
        this.name = name;
        this.createdAtMs = createdAtMs;
        this.coverMediaStoreId = coverMediaStoreId;
    }
}

