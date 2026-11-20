package com.example.piphotos

import com.example.piphotos.net.AdminDeviceSummary
import com.example.piphotos.net.AdminOverview
import com.example.piphotos.net.AdminUser
import com.example.piphotos.net.CreateUserResult
import com.example.piphotos.net.InviteResult
import com.example.piphotos.net.OpenPairingResult
import com.example.piphotos.net.UserOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Phase U1 admin model data classes in PiHttpClient.kt.
 * These tests verify the data class structure, default values, and field semantics
 * without relying on Android APIs (org.json is an Android-only API).
 */
class AdminModelParsingTest {

    @Test
    fun `AdminUser stores all fields correctly`() {
        val user = AdminUser(
            id = "usr_abc123",
            name = "Alice Smith",
            displayName = "Alice Smith",
            slug = "alice-smith",
            role = "member",
            status = "active",
            quotaBytes = 107374182400L,
            usedBytes = 536870912L,
            fileCount = 100,
            devicesCount = 2,
            lastSeenAt = "2026-11-20T07:00:00+00:00"
        )
        assertEquals("usr_abc123", user.id)
        assertEquals("Alice Smith", user.displayName)
        assertEquals("alice-smith", user.slug)
        assertEquals("member", user.role)
        assertEquals("active", user.status)
        assertEquals(107374182400L, user.quotaBytes)
        assertEquals(536870912L, user.usedBytes)
        assertEquals(100, user.fileCount)
        assertEquals(2, user.devicesCount)
        assertEquals("2026-11-20T07:00:00+00:00", user.lastSeenAt)
    }

    @Test
    fun `AdminUser nullable lastSeenAt defaults to null`() {
        val user = AdminUser(
            id = "usr_1",
            name = "Bob",
            displayName = "Bob",
            slug = "bob",
            role = "owner",
            status = "active",
            quotaBytes = 0L,
            usedBytes = 0L,
            fileCount = 0,
            devicesCount = 0,
            lastSeenAt = null
        )
        assertNull(user.lastSeenAt)
    }

    @Test
    fun `AdminUser unlimited quota represented as zero`() {
        val user = AdminUser(
            id = "usr_owner",
            name = "Owner",
            displayName = "Owner",
            slug = "owner",
            role = "owner",
            status = "active",
            quotaBytes = 0L,  // 0 = unlimited
            usedBytes = 10_000_000L,
            fileCount = 500,
            devicesCount = 1,
            lastSeenAt = null
        )
        assertEquals(0L, user.quotaBytes)
        assertTrue(user.quotaBytes == 0L) // indicates unlimited
    }

    @Test
    fun `CreateUserResult holds user, invite code, expiry and qr payload`() {
        val user = AdminUser(
            id = "usr_new",
            name = "Dave",
            displayName = "Dave",
            slug = "dave",
            role = "member",
            status = "active",
            quotaBytes = 50_000L,
            usedBytes = 0L,
            fileCount = 0,
            devicesCount = 0,
            lastSeenAt = null
        )
        val result = CreateUserResult(
            user = user,
            inviteCode = "482913",
            expiresAt = "2026-11-21T07:00:00+00:00",
            qrPayload = "piphotos://pair?host=192.168.1.1&port=8443&tls_fp=AA:BB&code=482913"
        )
        assertEquals("usr_new", result.user.id)
        assertEquals("Dave", result.user.displayName)
        assertEquals("482913", result.inviteCode)
        assertEquals("2026-11-21T07:00:00+00:00", result.expiresAt)
        assertTrue(result.qrPayload.startsWith("piphotos://pair"))
        assertTrue(result.qrPayload.contains("code=482913"))
    }

    @Test
    fun `InviteResult stores invite code and qr payload`() {
        val result = InviteResult(
            inviteCode = "391827",
            expiresAt = "2026-11-21T08:00:00+00:00",
            qrPayload = "piphotos://pair?code=391827"
        )
        assertEquals("391827", result.inviteCode)
        assertEquals("piphotos://pair?code=391827", result.qrPayload)
    }

    @Test
    fun `AdminDeviceSummary stores all fields including nullable lastSeenAt`() {
        val device = AdminDeviceSummary(
            id = "dev_abc",
            name = "Pixel 9",
            model = "google pixel 9",
            slug = "pixel-9",
            createdAt = "2026-11-01T10:00:00+00:00",
            lastSeenAt = "2026-11-20T07:00:00+00:00"
        )
        assertEquals("dev_abc", device.id)
        assertEquals("Pixel 9", device.name)
        assertEquals("pixel-9", device.slug)
        assertEquals("2026-11-20T07:00:00+00:00", device.lastSeenAt)
    }

    @Test
    fun `AdminDeviceSummary nullable lastSeenAt can be null`() {
        val device = AdminDeviceSummary(
            id = "dev_1",
            name = "Phone",
            model = "Model",
            slug = "phone",
            createdAt = "2026-01-01T00:00:00+00:00",
            lastSeenAt = null
        )
        assertNull(device.lastSeenAt)
    }

    @Test
    fun `OpenPairingResult stores code and expiry`() {
        val result = OpenPairingResult(code = "183729", expiresInSeconds = 600)
        assertEquals("183729", result.code)
        assertEquals(600, result.expiresInSeconds)
    }

    @Test
    fun `OpenPairingResult code is 6 digits`() {
        val result = OpenPairingResult(code = "000001", expiresInSeconds = 600)
        assertEquals(6, result.code.length)
    }

    @Test
    fun `UserOverview stores quota and usage correctly`() {
        val overview = UserOverview(
            id = "usr_alice",
            name = "Alice",
            role = "member",
            quotaBytes = 100_000_000_000L,
            usedBytes = 5_000_000L,
            fileCount = 20
        )
        assertEquals("usr_alice", overview.id)
        assertEquals(100_000_000_000L, overview.quotaBytes)
        assertEquals(5_000_000L, overview.usedBytes)
        assertEquals(20, overview.fileCount)
    }

    @Test
    fun `AdminOverview aggregates disk and user list`() {
        val users = listOf(
            UserOverview("usr_owner", "Owner", "owner", 0L, 10_000_000L, 50),
            UserOverview("usr_alice", "Alice", "member", 100_000_000_000L, 5_000_000L, 20)
        )
        val overview = AdminOverview(
            diskTotal = 500_000_000_000L,
            diskFree = 300_000_000_000L,
            diskReserved = 50_000_000_000L,
            allocatedQuotaTotal = 200_000_000_000L,
            overAllocated = false,
            users = users
        )
        assertEquals(500_000_000_000L, overview.diskTotal)
        assertEquals(300_000_000_000L, overview.diskFree)
        assertEquals(50_000_000_000L, overview.diskReserved)
        assertEquals(200_000_000_000L, overview.allocatedQuotaTotal)
        assertFalse(overview.overAllocated)
        assertEquals(2, overview.users.size)
        assertEquals("owner", overview.users[0].role)
        assertEquals(0L, overview.users[0].quotaBytes) // unlimited owner
        assertEquals(100_000_000_000L, overview.users[1].quotaBytes)
    }

    @Test
    fun `AdminOverview overAllocated flag is set correctly`() {
        val overview = AdminOverview(
            diskTotal = 100L,
            diskFree = 10L,
            diskReserved = 10L,
            allocatedQuotaTotal = 200L,
            overAllocated = true,
            users = emptyList()
        )
        assertTrue(overview.overAllocated)
        assertEquals(0, overview.users.size)
    }

    @Test
    fun `AdminUser data class equality and copy work correctly`() {
        val original = AdminUser(
            id = "usr_x",
            name = "X",
            displayName = "X User",
            slug = "x-user",
            role = "member",
            status = "active",
            quotaBytes = 1_000_000L,
            usedBytes = 500_000L,
            fileCount = 10,
            devicesCount = 1,
            lastSeenAt = null
        )
        val copy = original.copy(status = "disabled")
        assertEquals("disabled", copy.status)
        assertEquals(original.id, copy.id)
        assertEquals(original.quotaBytes, copy.quotaBytes)
        assertFalse(original == copy)
    }
}
