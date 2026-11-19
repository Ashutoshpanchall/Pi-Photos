package com.example.piphotos.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "sync_ledger",
    indices = {
        @Index(value = {"status"}),
        @Index(value = {"sha256"})
    }
)
public class SyncLedgerEntity {

    @PrimaryKey
    @ColumnInfo(name = "media_id")
    public long mediaId;

    @ColumnInfo(name = "generation")
    public long generation;

    @ColumnInfo(name = "size")
    public long size;

    @Nullable
    @ColumnInfo(name = "sha256")
    public String sha256;

    @NonNull
    @ColumnInfo(name = "status")
    public String status;

    @ColumnInfo(name = "attempts")
    public int attempts;

    @Nullable
    @ColumnInfo(name = "last_error")
    public String lastError;

    @Nullable
    @ColumnInfo(name = "uploaded_at")
    public Long uploadedAt;

    @ColumnInfo(name = "part_offset", defaultValue = "0")
    public long partOffset;

    public SyncLedgerEntity(
        long mediaId,
        long generation,
        long size,
        @Nullable String sha256,
        @NonNull String status,
        int attempts,
        @Nullable String lastError,
        @Nullable Long uploadedAt,
        long partOffset
    ) {
        this.mediaId = mediaId;
        this.generation = generation;
        this.size = size;
        this.sha256 = sha256;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.uploadedAt = uploadedAt;
        this.partOffset = partOffset;
    }
}
