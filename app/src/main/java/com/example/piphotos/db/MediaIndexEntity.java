package com.example.piphotos.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "media_index",
    indices = {
        @Index(value = {"date_taken_ms", "date_modified_sec"})
    }
)
public class MediaIndexEntity {
    @PrimaryKey
    public long id;

    @ColumnInfo(name = "is_video")
    public boolean isVideo;

    @ColumnInfo(name = "mime_type")
    public String mimeType;

    @ColumnInfo(name = "date_taken_ms")
    public long dateTakenMs;

    @ColumnInfo(name = "date_added_sec")
    public long dateAddedSec;

    @ColumnInfo(name = "date_modified_sec")
    public long dateModifiedSec;

    @ColumnInfo(name = "bucket_id")
    public long bucketId;

    @ColumnInfo(name = "bucket_name")
    public String bucketName;

    @ColumnInfo(name = "size_bytes")
    public long sizeBytes;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "generation_modified")
    public long generationModified;

    public MediaIndexEntity(
        long id,
        boolean isVideo,
        String mimeType,
        long dateTakenMs,
        long dateAddedSec,
        long dateModifiedSec,
        long bucketId,
        String bucketName,
        long sizeBytes,
        long durationMs,
        long generationModified
    ) {
        this.id = id;
        this.isVideo = isVideo;
        this.mimeType = mimeType;
        this.dateTakenMs = dateTakenMs;
        this.dateAddedSec = dateAddedSec;
        this.dateModifiedSec = dateModifiedSec;
        this.bucketId = bucketId;
        this.bucketName = bucketName;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.generationModified = generationModified;
    }
}
