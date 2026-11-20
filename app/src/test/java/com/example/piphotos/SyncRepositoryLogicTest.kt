package com.example.piphotos

import com.example.piphotos.sync.SyncLedgerState
import com.example.piphotos.sync.SyncRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryLogicTest {

    @Test
    fun percentComplete_computesAccuratePercentage() {
        val stateZero = SyncLedgerState(totalCount = 0, doneCount = 0)
        assertEquals(0, stateZero.percentComplete)

        val stateHalf = SyncLedgerState(totalCount = 10, doneCount = 5)
        assertEquals(50, stateHalf.percentComplete)

        val stateDone = SyncLedgerState(totalCount = 120, doneCount = 120)
        assertEquals(100, stateDone.percentComplete)
        assertTrue(stateDone.isAllBackedUp)

        val statePartial = SyncLedgerState(totalCount = 120, doneCount = 119)
        assertFalse(statePartial.isAllBackedUp)
    }

    @Test
    fun honestEtaCalculation_computesExpectedSeconds() {
        fun computeEta(speedBps: Long, remainingBytes: Long): Long {
            return if (speedBps > 1024) remainingBytes / speedBps else -1L
        }

        // 10 MB remaining at 2 MB/s = 5s
        val eta5s = computeEta(2 * 1024 * 1024L, 10 * 1024 * 1024L)
        assertEquals(5L, eta5s)

        // Speed <= 1024 bps returns -1 (estimating)
        val etaLow = computeEta(500L, 10 * 1024 * 1024L)
        assertEquals(-1L, etaLow)
    }

    @Test
    fun boundedRetryExponentialBackoff_scalesCorrectly() {
        fun backoffDelayMs(attempt: Int): Long {
            return (1L shl attempt) * 500L
        }

        assertEquals(1000L, backoffDelayMs(1))  // 2^1 * 500 = 1000ms
        assertEquals(2000L, backoffDelayMs(2))  // 2^2 * 500 = 2000ms
        assertEquals(4000L, backoffDelayMs(3))  // 2^3 * 500 = 4000ms
        assertEquals(8000L, backoffDelayMs(4))  // 2^4 * 500 = 8000ms
    }

    @Test
    fun deriveExtensionFromMimeType_derivesCorrectExtensions() {
        assertEquals("heic", SyncRepository.deriveExtensionFromMimeType("image/heic", isVideo = false))
        assertEquals("heic", SyncRepository.deriveExtensionFromMimeType("image/heif", isVideo = false))
        assertEquals("webp", SyncRepository.deriveExtensionFromMimeType("image/webp", isVideo = false))
        assertEquals("gif", SyncRepository.deriveExtensionFromMimeType("image/gif", isVideo = false))
        assertEquals("mov", SyncRepository.deriveExtensionFromMimeType("video/quicktime", isVideo = true))
        assertEquals("mp4", SyncRepository.deriveExtensionFromMimeType("video/mp4", isVideo = true))
        assertEquals("dng", SyncRepository.deriveExtensionFromMimeType("image/x-adobe-dng", isVideo = false))
        assertEquals("dng", SyncRepository.deriveExtensionFromMimeType("image/dng", isVideo = false))
        assertEquals("jpg", SyncRepository.deriveExtensionFromMimeType("image/jpeg", isVideo = false))
        assertEquals("png", SyncRepository.deriveExtensionFromMimeType("image/png", isVideo = false))
        assertEquals("3gp", SyncRepository.deriveExtensionFromMimeType("video/3gpp", isVideo = true))
        assertEquals("mp4", SyncRepository.deriveExtensionFromMimeType("video/unknown", isVideo = true))
        assertEquals("jpg", SyncRepository.deriveExtensionFromMimeType(null, isVideo = false))
        assertEquals("mp4", SyncRepository.deriveExtensionFromMimeType(null, isVideo = true))
    }

    @Test
    fun sanitizeFilename_cleansAndPreservesRealDisplayNames() {
        // Normal camera filename
        val normal = SyncRepository.sanitizeFilename("IMG_20240101_120000.jpg", "image/jpeg", isVideo = false, mediaId = 1)
        assertEquals("IMG_20240101_120000.jpg", normal)

        // Apple HEIC format
        val heic = SyncRepository.sanitizeFilename("IMG_4920.HEIC", "image/heic", isVideo = false, mediaId = 2)
        assertEquals("IMG_4920.heic", heic)

        // iPhone QuickTime video
        val mov = SyncRepository.sanitizeFilename("VID_9981.mov", "video/quicktime", isVideo = true, mediaId = 3)
        assertEquals("VID_9981.mov", mov)

        // Slashes and directory traversal characters
        val traversal = SyncRepository.sanitizeFilename("../../etc/passwd.jpg", "image/jpeg", isVideo = false, mediaId = 4)
        assertEquals("______etc_passwd.jpg", traversal)

        // Illegal characters (colons, quotes, question marks)
        val illegal = SyncRepository.sanitizeFilename("My:vacation*photo?\"test\".png", "image/png", isVideo = false, mediaId = 5)
        assertEquals("My_vacation_photo__test_.png", illegal)

        // Null or blank falls back to media_$id
        val nullName = SyncRepository.sanitizeFilename(null, "image/webp", isVideo = false, mediaId = 42)
        assertEquals("media_42.webp", nullName)

        val blankName = SyncRepository.sanitizeFilename("   ", "video/mp4", isVideo = true, mediaId = 99)
        assertEquals("media_99.mp4", blankName)
    }

    @Test
    fun throttledLedgerWrite_triggersOnlyWhenThresholdsMet() {
        var writesCount = 0
        var lastWriteMs = 1000L
        var bytesSinceLastWrite = 0L

        fun onBytesSent(chunkSize: Long, currentTimeMs: Long) {
            bytesSinceLastWrite += chunkSize
            if (currentTimeMs - lastWriteMs >= 1000L || bytesSinceLastWrite >= 4 * 1024 * 1024L) {
                writesCount++
                lastWriteMs = currentTimeMs
                bytesSinceLastWrite = 0L
            }
        }

        // 10 chunks of 64KB within 500ms -> 0 writes
        for (i in 1..10) {
            onBytesSent(64 * 1024L, 1100L)
        }
        assertEquals(0, writesCount)

        // Chunk reaches 4 MB -> 1 write triggered
        onBytesSent(4 * 1024 * 1024L, 1200L)
        assertEquals(1, writesCount)

        // Another 64KB chunk after 1200ms -> 1 write triggered by time threshold
        onBytesSent(64 * 1024L, 2500L)
        assertEquals(2, writesCount)
    }

    @Test
    fun offsetMismatchRetry_capsAtThreeRetries() {
        var offsetMismatchRetries = 0
        var failedWithError: String? = null

        fun handleMismatch(serverOffset: Long): Boolean {
            offsetMismatchRetries++
            if (offsetMismatchRetries > 3) {
                failedWithError = "Offset mismatch limit reached (server offset $serverOffset)"
                return false // break
            }
            return true // continue
        }

        assertTrue(handleMismatch(100L)) // 1st retry
        assertTrue(handleMismatch(200L)) // 2nd retry
        assertTrue(handleMismatch(300L)) // 3rd retry
        assertFalse(handleMismatch(400L)) // 4th mismatch triggers break & failure
        assertEquals("Offset mismatch limit reached (server offset 400)", failedWithError)
    }
}
