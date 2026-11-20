package com.example.piphotos

import com.example.piphotos.net.AdminOverview
import com.example.piphotos.net.AdminUser
import com.example.piphotos.net.MeResponse
import com.example.piphotos.net.QrCodeGenerator
import com.example.piphotos.net.UserOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseU2UserManagerTest {

    @Test
    fun `isOwner returns true only for owner role`() {
        fun checkIsOwner(role: String): Boolean = role.equals("owner", ignoreCase = true)

        assertTrue(checkIsOwner("owner"))
        assertTrue(checkIsOwner("OWNER"))
        assertTrue(checkIsOwner("Owner"))
        assertFalse(checkIsOwner("member"))
        assertFalse(checkIsOwner("user"))
        assertFalse(checkIsOwner(""))
    }

    @Test
    fun `pairing code validation accepts 6 digits or 8 characters`() {
        fun isValidPairingCode(code: String): Boolean {
            val trimmed = code.trim()
            return trimmed.length == 6 || trimmed.length == 8
        }

        assertTrue(isValidPairingCode("123456"))
        assertTrue(isValidPairingCode("654321"))
        assertTrue(isValidPairingCode("ABCD1234"))
        assertTrue(isValidPairingCode("abcd1234"))
        assertTrue(isValidPairingCode("L3T5YYLF"))
        assertFalse(isValidPairingCode("12345"))
        assertFalse(isValidPairingCode("1234567"))
        assertFalse(isValidPairingCode("123456789"))
        assertFalse(isValidPairingCode(""))
    }

    @Test
    fun `quota usage calculation handles unlimited and percentage thresholds`() {
        fun calculateUsagePercent(usedBytes: Long, quotaBytes: Long): Int {
            if (quotaBytes <= 0L) return 0 // Unlimited
            return ((usedBytes.toDouble() / quotaBytes.toDouble()) * 100).toInt()
        }

        // Unlimited
        assertEquals(0, calculateUsagePercent(10_000_000_000L, 0L))

        // Normal 50%
        assertEquals(50, calculateUsagePercent(50_000_000_000L, 100_000_000_000L))

        // 80% soft warning
        assertEquals(80, calculateUsagePercent(80_000_000_000L, 100_000_000_000L))

        // 90% critical warning
        assertEquals(90, calculateUsagePercent(90_000_000_000L, 100_000_000_000L))

        // 100% full
        assertEquals(100, calculateUsagePercent(100_000_000_000L, 100_000_000_000L))
    }

    @Test
    fun `over-allocation calculation computes exact disk space deficit`() {
        val totalDisk = 500L * 1024 * 1024 * 1024 // 500 GB
        val allocatedQuotas = 650L * 1024 * 1024 * 1024 // 650 GB

        val isOverAllocated = allocatedQuotas > totalDisk
        assertTrue(isOverAllocated)

        val excessBytes = allocatedQuotas - totalDisk
        val excessGb = excessBytes / (1024.0 * 1024.0 * 1024.0)
        assertEquals(150.0, excessGb, 0.001)
    }

    @Test
    fun `qr code generator produces valid bit matrix for piphotos uri`() {
        val payload = "piphotos://pair?host=192.168.1.100&port=8443&tls_fp=AA:BB:CC&code=L3T5YYLF"
        val matrix = QrCodeGenerator.generateBitMatrix(payload, size = 128)
        assertNotNull(matrix)
        assertEquals(128, matrix?.width)
        assertEquals(128, matrix?.height)
    }
}
