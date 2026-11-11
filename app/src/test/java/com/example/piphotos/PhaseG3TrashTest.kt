package com.example.piphotos

import android.provider.MediaStore
import com.example.piphotos.media.LocalDeletePolicyHook
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.TrashUriHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG3TrashTest {

    private fun createItem(id: Long, isVideo: Boolean): LocalMediaItem {
        return LocalMediaItem(
            id = id,
            isVideo = isVideo,
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            dateTakenMs = 1700000000000L + id,
            dateAddedSec = 1700000000L + id,
            dateModifiedSec = 1700000000L + id,
            bucketId = 101L,
            bucketName = "Camera"
        )
    }

    @Test
    fun trashUriSplit_imagesAndVideosSplitCorrectly() {
        val imageItem = createItem(101L, isVideo = false)
        val videoItem = createItem(102L, isVideo = true)

        assertFalse(imageItem.isVideo)
        assertTrue(videoItem.isVideo)

        assertEquals("content://media/external/images/media", TrashUriHelper.getBaseUriString(imageItem.isVideo))
        assertEquals("content://media/external/video/media", TrashUriHelper.getBaseUriString(videoItem.isVideo))
    }

    @Test
    fun daysRemainingCalculation_handlesEdges() {
        val nowSec = 1700000000L

        // 30 days in future: 30 * 86400 = 2,592,000 sec
        val exp30Days = nowSec + 2592000L
        assertEquals(30, TrashUriHelper.computeDaysRemaining(exp30Days, nowSec))

        // 5 days in future: 5 * 86400 = 432,000 sec
        val exp5Days = nowSec + 432000L
        assertEquals(5, TrashUriHelper.computeDaysRemaining(exp5Days, nowSec))

        // 12 hours in future: 0 days remaining (less than 1 full day)
        val exp12Hours = nowSec + 43200L
        assertEquals(0, TrashUriHelper.computeDaysRemaining(exp12Hours, nowSec))

        // Already expired in past: clamped to 0
        val expPast = nowSec - 1000L
        assertEquals(0, TrashUriHelper.computeDaysRemaining(expPast, nowSec))

        // No expiry timestamp (0): safe fallback to 30
        assertEquals(30, TrashUriHelper.computeDaysRemaining(0L, nowSec))
    }

    @Test
    fun binQueryParser_parsesCursorRowsCorrectly() {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val nowSec = 1700000000L
        val expSec = nowSec + (15 * 86400L) // 15 days left

        val rows = listOf(
            arrayOf<Any?>(501L, 1699900000L, expSec, 4500000L, "image/jpeg"),
            arrayOf<Any?>(502L, 1699910000L, expSec, 15000000L, "video/mp4")
        )

        val cursor = FakeCursor(columns, rows)
        val results = mutableListOf<TrashedMediaItem>()

        LocalDeleteHandler.parseBinCursor(cursor, isVideo = false, results, nowSec)

        assertEquals(2, results.size)
        val first = results[0]
        assertEquals(501L, first.id)
        assertFalse(first.isVideo)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(15, first.daysRemaining)
        assertEquals(4500000L, first.sizeBytes)
    }

    @Test
    fun backupSemanticsHook_contractAndSafety() {
        // Contract documentation must be strictly present
        assertTrue(LocalDeletePolicyHook.POLICY_CONTRACT.contains("local_deleted in Room"))
        assertTrue(LocalDeletePolicyHook.POLICY_CONTRACT.contains("does NOT issue a remote DELETE"))

        // Invoking hook must execute synchronously without network or exceptions
        LocalDeletePolicyHook.onLocalItemTrashed(listOf(101L, 102L))
    }

    @Test
    fun deleteResult_hierarchyStates() {
        val success: DeleteResult = DeleteResult.Success
        val error: DeleteResult = DeleteResult.Error("Disk error")

        assertTrue(success is DeleteResult.Success)
        assertTrue(error is DeleteResult.Error)
        assertEquals("Disk error", (error as DeleteResult.Error).message)
    }
}
