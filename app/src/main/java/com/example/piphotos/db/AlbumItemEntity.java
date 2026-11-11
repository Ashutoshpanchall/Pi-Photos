package com.example.piphotos.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
    tableName = "album_item",
    primaryKeys = {"album_id", "media_store_id"},
    foreignKeys = {
        @ForeignKey(
            entity = AlbumEntity.class,
            parentColumns = "id",
            childColumns = "album_id",
            onDelete = ForeignKey.CASCADE
        )
    },
    indices = {@Index("album_id")}
)
public class AlbumItemEntity {
    @ColumnInfo(name = "album_id")
    public long albumId;

    @ColumnInfo(name = "media_store_id")
    public long mediaStoreId;

    @ColumnInfo(name = "is_video")
    public boolean isVideo;

    @ColumnInfo(name = "added_at_ms")
    public long addedAtMs;

    public AlbumItemEntity(long albumId, long mediaStoreId, boolean isVideo, long addedAtMs) {
        this.albumId = albumId;
        this.mediaStoreId = mediaStoreId;
        this.isVideo = isVideo;
        this.addedAtMs = addedAtMs;
    }
}

