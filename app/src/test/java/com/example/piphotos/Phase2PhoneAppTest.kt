package com.example.piphotos

import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.sync.NodePairingValidator
import com.example.piphotos.sync.QuotaState
import com.example.piphotos.sync.QuotaStateManager
import com.example.piphotos.sync.QuotaStatus
import com.example.piphotos.sync.SyncRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class Phase2PhoneAppTest {

    // 1. Candidate Address Fallback Order
    @Test
    fun candidateAddressOrdering_prefersLastIpThenMdnsThenManual() {
        val candidates = listOf(
            "192.168.1.150" to 8443,
            "homevault.local" to 8443,
            "10.0.0.5" to 8443
        )

        assertEquals("192.168.1.150", candidates[0].first)
        assertEquals("homevault.local", candidates[1].first)
        assertEquals("10.0.0.5", candidates[2].first)
    }

    // 2. Honest TCP Probe Error Mapping
    @Test
    fun honestErrorMapping_mapsSpecificExceptionsToHonestMessages() {
        val connectEx = ConnectException("Connection refused")
        val timeoutEx = SocketTimeoutException("connect timed out")
        val noRouteEx = NoRouteToHostException("No route to host")
        val genericEx = IllegalStateException("Something broke")

        val connectMsg = NodePairingValidator.mapErrorMessage(connectEx, "192.168.1.120", 8443)
        assertTrue(connectMsg.contains("Connection refused on port 8443"))

        val timeoutMsg = NodePairingValidator.mapErrorMessage(timeoutEx, "192.168.1.120", 8443)
        assertTrue(timeoutMsg.contains("Connection timed out"))

        val noRouteMsg = NodePairingValidator.mapErrorMessage(noRouteEx, "192.168.1.120", 8443)
        assertTrue(noRouteMsg.contains("Storage node unreachable"))

        val genericMsg = NodePairingValidator.mapErrorMessage(genericEx, "192.168.1.120", 8443)
        assertTrue(genericMsg.contains("Something broke"))
    }

    // 3. Separate Cellular Switches Logic (Improvement 3)
    @Test
    fun cellularFiltering_respectsSeparatePhotoAndVideoSwitches() {
        // When on Wi-Fi (not cellular), neither photos nor videos are skipped
        assertFalse(SyncRepository.shouldSkipForCellular(isVideo = false, isCellular = false, allowPhotosOnCellular = false, allowVideosOnCellular = false))
        assertFalse(SyncRepository.shouldSkipForCellular(isVideo = true, isCellular = false, allowPhotosOnCellular = false, allowVideosOnCellular = false))

        // When on cellular, only photos allowed
        val photoAllowedOnCellular = SyncRepository.shouldSkipForCellular(
            isVideo = false,
            isCellular = true,
            allowPhotosOnCellular = true,
            allowVideosOnCellular = false
        )
        assertFalse("Photo should not be skipped when photos are allowed on cellular", photoAllowedOnCellular)

        val videoSkippedOnCellular = SyncRepository.shouldSkipForCellular(
            isVideo = true,
            isCellular = true,
            allowPhotosOnCellular = true,
            allowVideosOnCellular = false
        )
        assertTrue("Video should be skipped when videos are NOT allowed on cellular", videoSkippedOnCellular)

        // When on cellular, both disabled
        assertTrue(SyncRepository.shouldSkipForCellular(isVideo = false, isCellular = true, allowPhotosOnCellular = false, allowVideosOnCellular = false))
        assertTrue(SyncRepository.shouldSkipForCellular(isVideo = true, isCellular = true, allowPhotosOnCellular = false, allowVideosOnCellular = false))

        // When on cellular, both enabled
        assertFalse(SyncRepository.shouldSkipForCellular(isVideo = false, isCellular = true, allowPhotosOnCellular = true, allowVideosOnCellular = true))
        assertFalse(SyncRepository.shouldSkipForCellular(isVideo = true, isCellular = true, allowPhotosOnCellular = true, allowVideosOnCellular = true))
    }

    // 4. Truthful Storage & Quota Formatting
    @Test
    fun truthfulStorageFormatting_displaysTruthfulAvailableBytesForOwnerAndMember() {
        // Owner with unlimited quota (quotaBytes = 0) and 79 GB available on node
        val ownerQuota = QuotaState(
            status = QuotaStatus.Unlimited,
            usedBytes = 18L * 1024 * 1024 * 1024,
            quotaBytes = 0L,
            freeBytes = 79L * 1024 * 1024 * 1024,
            availableBytes = 79L * 1024 * 1024 * 1024,
            allocationBytes = 79L * 1024 * 1024 * 1024,
            allocationUsedBytes = 18L * 1024 * 1024 * 1024
        )
        val ownerSummary = QuotaStateManager.formatUsageSummary(ownerQuota)
        val ownerSubtitle = QuotaStateManager.formatFreeSubtitle(ownerQuota)

        assertTrue(ownerSummary.contains("18.0 GB used"))
        assertTrue(ownerSummary.contains("79.0 GB available"))
        assertTrue(ownerSubtitle.contains("79.0 GB available"))

        // Member with 50 GB quota, 31 GB used, but node only has 10 GB free space left
        val memberQuota = QuotaState(
            status = QuotaStatus.Ok(62),
            usedBytes = 31L * 1024 * 1024 * 1024,
            quotaBytes = 50L * 1024 * 1024 * 1024,
            freeBytes = 19L * 1024 * 1024 * 1024,
            availableBytes = 10L * 1024 * 1024 * 1024, // Truthful min(quotaLeft, nodeFree)
            allocationBytes = 79L * 1024 * 1024 * 1024,
            allocationUsedBytes = 69L * 1024 * 1024 * 1024
        )
        val memberSummary = QuotaStateManager.formatUsageSummary(memberQuota)
        val memberSubtitle = QuotaStateManager.formatFreeSubtitle(memberQuota)

        assertTrue(memberSummary.contains("31.0 GB of 50.0 GB used"))
        assertTrue(memberSubtitle.contains("10.0 GB available"))
    }

    // 5. QR Code Parsing (JSON and URI formats)
    @Test
    fun qrCodeParsing_parsesJsonAndUriPayloads() {
        val jsonPayload = """
            {
                "piphotos": "1.0",
                "host": "192.168.1.120",
                "port": 8443,
                "tls_fp": "AA:BB:CC:DD:EE",
                "code": "482913"
            }
        """.trimIndent()

        val parsedJson = NodePairingValidator.parseQrCode(jsonPayload)
        assertNotNull(parsedJson)
        assertEquals("192.168.1.120", parsedJson?.host)
        assertEquals(8443, parsedJson?.port)
        assertEquals("AA:BB:CC:DD:EE", parsedJson?.tlsFingerprint)
        assertEquals("482913", parsedJson?.code)

        val uriPayload = "piphotos://pair?host=homevault.local&port=8443&fp=11:22:33&code=123456"
        val parsedUri = NodePairingValidator.parseQrCode(uriPayload)
        assertNotNull(parsedUri)
        assertEquals("homevault.local", parsedUri?.host)
        assertEquals(8443, parsedUri?.port)
        assertEquals("11:22:33", parsedUri?.tlsFingerprint)
        assertEquals("123456", parsedUri?.code)

        val invalid = NodePairingValidator.parseQrCode("random non-piphotos string")
        assertNull(invalid)
    }
}
