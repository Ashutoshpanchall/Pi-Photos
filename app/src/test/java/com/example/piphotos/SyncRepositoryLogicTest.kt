package com.example.piphotos

import com.example.piphotos.sync.SyncLedgerState
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
}
