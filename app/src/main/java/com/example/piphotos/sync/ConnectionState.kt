package com.example.piphotos.sync

sealed class ConnectionState {
    object NotConfigured : ConnectionState()
    object Connected : ConnectionState()
    object ReachableAuthNeeded : ConnectionState()
    data class Unreachable(val lastSeenMs: Long) : ConnectionState()

    val isOnline: Boolean get() = this is Connected
}
