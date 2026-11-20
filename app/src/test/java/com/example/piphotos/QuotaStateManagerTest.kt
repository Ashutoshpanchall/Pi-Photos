package com.example.piphotos

import com.example.piphotos.net.HttpException
import com.example.piphotos.net.MeResponse
import com.example.piphotos.net.ServerStatus
import com.example.piphotos.sync.QuotaState
import com.example.piphotos.sync.QuotaStateManager
import com.example.piphotos.sync.QuotaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QuotaStateManagerTest {

    @Test
    fun `computeStatus correctly classifies all quota thresholds`() {
        // Unlimited
        assertEquals(QuotaStatus.Unlimited, QuotaStateManager.computeStatus(10_000_000L, 0L))

        // Normal OK (< 80%)
        val okStatus = QuotaStateManager.computeStatus(50_000_000_000L, 100_000_000_000L)
        assertTrue(okStatus is QuotaStatus.Ok)
        assertEquals(50, (okStatus as QuotaStatus.Ok).usagePercent)

        // 80% boundary
        val warn80Status = QuotaStateManager.computeStatus(80_000_000_000L, 100_000_000_000L)
        assertTrue(warn80Status is QuotaStatus.Warn80)
        assertEquals(80, (warn80Status as QuotaStatus.Warn80).usagePercent)

        // 89% boundary
        val warn89Status = QuotaStateManager.computeStatus(89_000_000_000L, 100_000_000_000L)
        assertTrue(warn89Status is QuotaStatus.Warn80)
        assertEquals(89, (warn89Status as QuotaStatus.Warn80).usagePercent)

        // 90% boundary
        val warn90Status = QuotaStateManager.computeStatus(90_000_000_000L, 100_000_000_000L)
        assertTrue(warn90Status is QuotaStatus.Warn90)
        assertEquals(90, (warn90Status as QuotaStatus.Warn90).usagePercent)

        // 99% boundary
        val warn99Status = QuotaStateManager.computeStatus(99_000_000_000L, 100_000_000_000L)
        assertTrue(warn99Status is QuotaStatus.Warn90)
        assertEquals(99, (warn99Status as QuotaStatus.Warn90).usagePercent)

        // 100% boundary
        val fullStatus = QuotaStateManager.computeStatus(100_000_000_000L, 100_000_000_000L)
        assertTrue(fullStatus is QuotaStatus.Full)
        assertEquals(100, (fullStatus as QuotaStatus.Full).usagePercent)

        // Quota set below usage (e.g. 120 GB used of 100 GB quota)
        val overStatus = QuotaStateManager.computeStatus(120_000_000_000L, 100_000_000_000L)
        assertTrue(overStatus is QuotaStatus.Full)
        assertEquals(120, (overStatus as QuotaStatus.Full).usagePercent)
    }

    @Test
    fun `updateFromStatus transitions state accurately`() {
        val status = ServerStatus(
            diskTotal = 500L * 1024 * 1024 * 1024,
            diskFree = 300L * 1024 * 1024 * 1024,
            diskReserved = 50L * 1024 * 1024 * 1024,
            diskUsed = 200L * 1024 * 1024 * 1024,
            userQuota = 50L * 1024 * 1024 * 1024,
            userUsed = 45L * 1024 * 1024 * 1024, // 90%
            userFree = 5L * 1024 * 1024 * 1024,
            userFileCount = 1200
        )
        val state = QuotaStateManager.updateFromStatus(status, nowMs = 1000L)
        assertTrue(state.status is QuotaStatus.Warn90)
        assertEquals(status.userUsed, state.usedBytes)
        assertEquals(status.userQuota, state.quotaBytes)
        assertEquals(status.userFree, state.freeBytes)
        assertEquals(1000L, state.lastRefreshedAt)
        assertFalse(state.isStale)
    }

    @Test
    fun `updateFromMe transitions state accurately`() {
        val me = MeResponse(
            userId = "usr_2",
            displayName = "Alice",
            role = "member",
            quotaBytes = 10L * 1024 * 1024 * 1024,
            usedBytes = 2L * 1024 * 1024 * 1024, // 20%
            deviceId = "dev_1",
            deviceName = "Alice Phone"
        )
        val state = QuotaStateManager.updateFromMe(me, nowMs = 2000L)
        assertTrue(state.status is QuotaStatus.Ok)
        assertEquals(20, (state.status as QuotaStatus.Ok).usagePercent)
        assertEquals(me.usedBytes, state.usedBytes)
        assertEquals(8L * 1024 * 1024 * 1024, state.freeBytes)
        assertEquals(2000L, state.lastRefreshedAt)
    }

    @Test
    fun `markError maps 401, 403, and 507 to terminal states`() {
        val initial = QuotaState(
            status = QuotaStatus.Ok(50),
            usedBytes = 50L * 1024 * 1024 * 1024,
            quotaBytes = 100L * 1024 * 1024 * 1024,
            freeBytes = 50L * 1024 * 1024 * 1024
        )

        // 401 Revoked
        val revokedState = QuotaStateManager.markError(initial, HttpException(401, "Revoked"))
        assertTrue(revokedState.status is QuotaStatus.DeviceRevoked)
        assertTrue(revokedState.isBlocked)

        // 403 Disabled
        val disabledState = QuotaStateManager.markError(initial, HttpException(403, "Disabled"))
        assertTrue(disabledState.status is QuotaStatus.AccountDisabled)
        assertTrue(disabledState.isBlocked)

        // 507 Quota Exceeded
        val fullState = QuotaStateManager.markError(initial, HttpException(507, "Quota exceeded"))
        assertTrue(fullState.status is QuotaStatus.Full)
        assertTrue(fullState.isFullOrExceeded)
        assertEquals(0L, fullState.freeBytes)
    }

    @Test
    fun `markError on network exception keeps previous state and marks isStale true`() {
        val initial = QuotaState(
            status = QuotaStatus.Ok(40),
            usedBytes = 40L * 1024 * 1024 * 1024,
            quotaBytes = 100L * 1024 * 1024 * 1024,
            freeBytes = 60L * 1024 * 1024 * 1024,
            lastRefreshedAt = 5000L,
            isStale = false
        )
        val staleState = QuotaStateManager.markError(initial, IOException("Connect timed out"))
        assertTrue(staleState.status is QuotaStatus.Ok)
        assertTrue(staleState.isStale)
        assertEquals(40L * 1024 * 1024 * 1024, staleState.usedBytes)
        assertEquals(5000L, staleState.lastRefreshedAt)
    }

    @Test
    fun `formatting utilities produce clean human-readable text`() {
        // Bytes formatting
        assertEquals("0 B", QuotaStateManager.formatBytes(0L))
        assertEquals("500 B", QuotaStateManager.formatBytes(500L))
        assertEquals("1.5 KB", QuotaStateManager.formatBytes(1536L))
        assertEquals("10.0 MB", QuotaStateManager.formatBytes(10L * 1024 * 1024))
        assertEquals("12.4 GB", QuotaStateManager.formatBytes((12.4 * 1024 * 1024 * 1024).toLong()))

        // Usage summary
        val stateLimited = QuotaState(
            status = QuotaStatus.Warn90(90),
            usedBytes = 45L * 1024 * 1024 * 1024,
            quotaBytes = 50L * 1024 * 1024 * 1024,
            freeBytes = 5L * 1024 * 1024 * 1024
        )
        assertEquals("45.0 GB of 50.0 GB used", QuotaStateManager.formatUsageSummary(stateLimited))
        assertEquals("5.0 GB free", QuotaStateManager.formatFreeSubtitle(stateLimited))

        val stateUnlimited = QuotaState(
            status = QuotaStatus.Unlimited,
            usedBytes = 12L * 1024 * 1024 * 1024,
            quotaBytes = 0L,
            freeBytes = 100L * 1024 * 1024 * 1024
        )
        assertEquals("12.0 GB used", QuotaStateManager.formatUsageSummary(stateUnlimited))
        assertEquals("Unlimited storage", QuotaStateManager.formatFreeSubtitle(stateUnlimited))

        // Share message
        val shareMsg = QuotaStateManager.formatShareMessage(stateLimited, "Home Vault", "Alice")
        assertTrue(shareMsg.contains("Alice's PiPhotos backup"))
        assertTrue(shareMsg.contains("90%"))
        assertTrue(shareMsg.contains("Home Vault"))
        assertTrue(shareMsg.contains("increase my storage quota"))
    }
}
