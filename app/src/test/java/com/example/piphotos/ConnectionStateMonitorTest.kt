package com.example.piphotos

import com.example.piphotos.sync.ConnectionState
import com.example.piphotos.sync.ConnectionStateMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMonitorTest {

    @Test
    fun evaluateState_returnsNotConfiguredWhenNodeEmpty() {
        val state = ConnectionStateMonitor.evaluateState(
            isPaired = false,
            endpointReachable = false,
            authValid = false,
            lastSeenMs = 0L
        )
        assertEquals(ConnectionState.NotConfigured, state)
    }

    @Test
    fun evaluateState_returnsConnectedWhenReachableAndAuthValid() {
        val state = ConnectionStateMonitor.evaluateState(
            isPaired = true,
            endpointReachable = true,
            authValid = true,
            lastSeenMs = System.currentTimeMillis()
        )
        assertEquals(ConnectionState.Connected, state)
    }

    @Test
    fun evaluateState_returnsReachableAuthNeededWhenEndpointAnswersButAuthFails() {
        val state = ConnectionStateMonitor.evaluateState(
            isPaired = true,
            endpointReachable = true,
            authValid = false,
            lastSeenMs = System.currentTimeMillis()
        )
        assertEquals(ConnectionState.ReachableAuthNeeded, state)
    }

    @Test
    fun evaluateState_returnsUnreachableWithLastSeenWhenOffline() {
        val lastSeen = System.currentTimeMillis() - 120_000L
        val state = ConnectionStateMonitor.evaluateState(
            isPaired = true,
            endpointReachable = false,
            authValid = false,
            lastSeenMs = lastSeen
        )
        assertTrue(state is ConnectionState.Unreachable)
        assertEquals(lastSeen, (state as ConnectionState.Unreachable).lastSeenMs)
    }
}
