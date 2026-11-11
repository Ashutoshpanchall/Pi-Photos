package com.example.piphotos

sealed class BackupState {
    object LocalOnly : BackupState()
    data class Disconnected(val nodeName: String) : BackupState()
    data class Syncing(val current: Int, val total: Int, val speedMBs: Double) : BackupState()
    data class Idle(val lastSyncTime: Long) : BackupState()

    companion object {
        var current: BackupState = LocalOnly
    }
}

