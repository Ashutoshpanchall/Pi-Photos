package com.example.piphotos.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SyncLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(SyncLedgerEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<SyncLedgerEntity> entities);

    @Query("SELECT * FROM sync_ledger WHERE media_id = :mediaId LIMIT 1")
    SyncLedgerEntity getByMediaId(long mediaId);

    @Query("SELECT * FROM sync_ledger WHERE sha256 = :sha256 LIMIT 1")
    SyncLedgerEntity getBySha256(String sha256);

    @Query("SELECT * FROM sync_ledger WHERE status IN ('PENDING', 'HASHING', 'UPLOADING') ORDER BY media_id ASC")
    List<SyncLedgerEntity> getActiveItems();

    @Query("SELECT * FROM sync_ledger WHERE status IN ('PENDING', 'FAILED') ORDER BY media_id ASC")
    List<SyncLedgerEntity> getPendingOrFailedItems();

    @Query("SELECT * FROM sync_ledger ORDER BY media_id ASC")
    List<SyncLedgerEntity> getAllItems();

    @Query("SELECT media_id FROM sync_ledger WHERE status = 'DONE'")
    List<Long> getDoneMediaIds();

    @Query("SELECT * FROM sync_ledger WHERE status IN ('UPLOADING', 'HASHING', 'PENDING', 'FAILED', 'PAUSED_QUOTA') ORDER BY CASE status WHEN 'UPLOADING' THEN 1 WHEN 'HASHING' THEN 2 WHEN 'PAUSED_QUOTA' THEN 3 WHEN 'FAILED' THEN 4 ELSE 5 END, media_id DESC LIMIT :limit")
    List<SyncLedgerEntity> getQueueItems(int limit);

    @Query("SELECT * FROM sync_ledger WHERE status IN ('DONE', 'FAILED') ORDER BY CASE WHEN uploaded_at > 0 THEN uploaded_at ELSE media_id END DESC LIMIT :limit")
    List<SyncLedgerEntity> getRecentActivity(int limit);

    @Query("UPDATE sync_ledger SET status = :status, last_error = :lastError WHERE media_id = :mediaId")
    void updateStatus(long mediaId, String status, String lastError);

    @Query("UPDATE sync_ledger SET sha256 = :sha256, generation = :generation, status = :status WHERE media_id = :mediaId")
    void updateHash(long mediaId, String sha256, long generation, String status);

    @Query("UPDATE sync_ledger SET part_offset = :offset WHERE media_id = :mediaId")
    void updatePartOffset(long mediaId, long offset);

    @Query("UPDATE sync_ledger SET status = 'DONE', uploaded_at = :uploadedAt, last_error = null WHERE media_id = :mediaId")
    void markDone(long mediaId, long uploadedAt);

    @Query("UPDATE sync_ledger SET status = 'FAILED', last_error = :error, attempts = :attempts WHERE media_id = :mediaId")
    void markFailed(long mediaId, String error, int attempts);

    @Query("UPDATE sync_ledger SET status = 'PENDING', attempts = 0, last_error = null WHERE media_id = :mediaId")
    void resetForRetry(long mediaId);

    @Query("UPDATE sync_ledger SET status = 'PENDING', attempts = 0, last_error = null WHERE status = 'FAILED'")
    void resetAllFailed();

    @Query("UPDATE sync_ledger SET status = 'PAUSED_QUOTA', last_error = :reason WHERE media_id = :mediaId")
    void markPausedQuota(long mediaId, String reason);

    @Query("UPDATE sync_ledger SET status = 'PAUSED_QUOTA', last_error = :reason WHERE status IN ('PENDING', 'HASHING', 'UPLOADING')")
    void pauseAllActiveForQuota(String reason);

    @Query("UPDATE sync_ledger SET status = 'PENDING', last_error = null WHERE status = 'PAUSED_QUOTA'")
    int resumeAllPausedQuota();

    @Query("SELECT COUNT(*) FROM sync_ledger WHERE status = 'PAUSED_QUOTA'")
    int getPausedQuotaCount();

    @Query("DELETE FROM sync_ledger WHERE media_id NOT IN (:activeIds)")
    void deleteOrphans(List<Long> activeIds);

    @Query("SELECT "
        + "COUNT(*) AS totalCount, "
        + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS doneCount, "
        + "SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pendingCount, "
        + "SUM(CASE WHEN status = 'HASHING' THEN 1 ELSE 0 END) AS hashingCount, "
        + "SUM(CASE WHEN status = 'UPLOADING' THEN 1 ELSE 0 END) AS uploadingCount, "
        + "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount, "
        + "COALESCE(SUM(size), 0) AS totalBytes, "
        + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN size ELSE part_offset END), 0) AS uploadedBytes "
        + "FROM sync_ledger")
    SyncLedgerSummary getSummary();
}
