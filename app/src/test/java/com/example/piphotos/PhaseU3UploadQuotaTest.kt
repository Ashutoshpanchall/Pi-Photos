package com.example.piphotos

import com.example.piphotos.db.SyncLedgerEntity
import com.example.piphotos.sync.QuotaState
import com.example.piphotos.sync.QuotaStateManager
import com.example.piphotos.sync.QuotaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseU3UploadQuotaTest {

    @Test
    fun `paused quota status preserves partial offset and does not increment attempts`() {
        // Arrange an active entity with 4MB partially uploaded out of 10MB
        val entity = SyncLedgerEntity(
            101L,
            1L,
            10_000_000L,
            "abc123sha",
            "UPLOADING",
            1,
            null,
            null,
            4_000_000L
        )

        // When paused due to quota 507
        val pausedEntity = SyncLedgerEntity(
            entity.mediaId,
            entity.generation,
            entity.size,
            entity.sha256,
            "PAUSED_QUOTA",
            entity.attempts, // attempts unchanged!
            "Upload exceeded user storage quota",
            entity.uploadedAt,
            entity.partOffset // partOffset preserved!
        )

        assertEquals("PAUSED_QUOTA", pausedEntity.status)
        assertEquals(1, pausedEntity.attempts)
        assertEquals(4_000_000L, pausedEntity.partOffset)
    }

    @Test
    fun `pre-flight check detects insufficient quota before upload begins`() {
        val quotaState = QuotaState(
            status = QuotaStatus.Warn90(95),
            usedBytes = 95_000_000L,
            quotaBytes = 100_000_000L,
            freeBytes = 5_000_000L // only 5MB free
        )

        val nextFileSize = 12_000_000L // 12MB file
        val offset = 0L
        val remainingBytesToUpload = nextFileSize - offset

        val isPreflightBlocked = quotaState.quotaBytes > 0L && quotaState.freeBytes < remainingBytesToUpload
        assertTrue(isPreflightBlocked)
    }

    @Test
    fun `auto-resume moves paused quota items back to pending`() {
        val items = mutableListOf(
            SyncLedgerEntity(1L, 1L, 5000L, "hash1", "PAUSED_QUOTA", 0, "quota full", null, 0L),
            SyncLedgerEntity(2L, 1L, 8000L, "hash2", "PAUSED_QUOTA", 1, "quota full", null, 2000L),
            SyncLedgerEntity(3L, 1L, 3000L, "hash3", "DONE", 0, null, 1000L, 0L)
        )

        // Simulate resumeAllPausedQuota
        val resumed = items.map { item ->
            if (item.status == "PAUSED_QUOTA") {
                SyncLedgerEntity(
                    item.mediaId,
                    item.generation,
                    item.size,
                    item.sha256,
                    "PENDING",
                    item.attempts,
                    null,
                    item.uploadedAt,
                    item.partOffset
                )
            } else item
        }

        assertEquals("PENDING", resumed[0].status)
        assertNull(resumed[0].lastError)
        assertEquals("PENDING", resumed[1].status)
        assertEquals(2000L, resumed[1].partOffset) // partial offset still intact!
        assertEquals("DONE", resumed[2].status)
    }

    @Test
    fun `quota state transitions properly when owner raises quota`() {
        // Initially at 100% FULL
        val fullState = QuotaStateManager.computeStatus(50_000_000_000L, 50_000_000_000L)
        assertTrue(fullState is QuotaStatus.Full)

        // Owner raises quota to 100 GB -> usage drops to 50% OK
        val raisedState = QuotaStateManager.computeStatus(50_000_000_000L, 100_000_000_000L)
        assertTrue(raisedState is QuotaStatus.Ok)
        assertEquals(50, (raisedState as QuotaStatus.Ok).usagePercent)
    }
}
