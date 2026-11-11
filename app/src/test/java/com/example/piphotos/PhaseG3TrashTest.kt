package com.example.piphotos

import com.example.piphotos.media.LocalDeletePolicyHook
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.TrashUriHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG3TrashTest {

    private fun createMediaItem(id: Long, isVideo: Boolean): LocalMediaItem {
        return LocalMediaItem(
            id = id,
            isVideo = isVideo,
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            dateTakenMs = 1700000000000L,
            dateAddedSec = 1700000000L,
            dateModifiedSec = 1700000000L,
            bucketId = 1L,
            bucketName = "Camera"
        )
    }

    @Test
    fun trashUriSplit_imagesAndVideosSplitCorrectly() {
        val photoItem = createMediaItem(101L, isVideo = false)
        val videoItem = createMediaItem(202L, isVideo = true)

        val photoUri = TrashUriHelper.getBaseUriString(photoItem.isVideo)
        val videoUri = TrashUriHelper.getBaseUriString(videoItem.isVideo)

        assertTrue(photoUri.contains("images"))
        assertTrue(videoUri.contains("video"))
    }

    @Test
    fun computeDaysRemaining_handlesVariousTimestamps() {
        val nowSec = 1700000000L
        val thirtyDaysSec = 30L * 86400L

        // Just trashed: expires in 30 days
        val expires30Days = nowSec + thirtyDaysSec
        val days1 = TrashUriHelper.computeDaysRemaining(expires30Days, nowSec)
        assertEquals(30, days1)

        // Expires in 20 days
        val expires20Days = nowSec + (20L * 86400L)
        val days2 = TrashUriHelper.computeDaysRemaining(expires20Days, nowSec)
        assertEquals(20, days2)

        // Expired in past: 0 days remaining
        val expiredPast = nowSec - (5L * 86400L)
        val days3 = TrashUriHelper.computeDaysRemaining(expiredPast, nowSec)
        assertEquals(0, days3)

        // Fallback for missing/zero expiration
        assertEquals(30, TrashUriHelper.computeDaysRemaining(0L, nowSec))
    }

    @Test
    fun deletePolicyContract_documentsLocalOnlyConstraint() {
        // Must not throw or make any remote call
        LocalDeletePolicyHook.onLocalItemTrashed(listOf(999L))
        assertTrue(LocalDeletePolicyHook.POLICY_CONTRACT.isNotEmpty())
    }
}
