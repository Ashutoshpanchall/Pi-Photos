package com.example.piphotos.media

data class IndexStats(
    val totalItems: Int = 0,
    val totalPhotos: Int = 0,
    val totalVideos: Int = 0,
    val totalFolders: Int = 0,
    val buildTimeMs: Long = 0L,
    val lastScanTimestampMs: Long = 0L,
    val mediaStoreVersion: String = "",
    val generation: Long = 0L,
    val folderBreakdown: Map<String, Int> = emptyMap()
)

