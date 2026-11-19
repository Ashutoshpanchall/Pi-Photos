package com.example.piphotos.db;

public class SyncLedgerSummary {
    public int totalCount;
    public int doneCount;
    public int pendingCount;
    public int hashingCount;
    public int uploadingCount;
    public int failedCount;
    public long totalBytes;
    public long uploadedBytes;

    public SyncLedgerSummary(
        int totalCount,
        int doneCount,
        int pendingCount,
        int hashingCount,
        int uploadingCount,
        int failedCount,
        long totalBytes,
        long uploadedBytes
    ) {
        this.totalCount = totalCount;
        this.doneCount = doneCount;
        this.pendingCount = pendingCount;
        this.hashingCount = hashingCount;
        this.uploadingCount = uploadingCount;
        this.failedCount = failedCount;
        this.totalBytes = totalBytes;
        this.uploadedBytes = uploadedBytes;
    }
}
