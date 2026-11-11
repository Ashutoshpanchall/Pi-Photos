package com.example.piphotos

data class MediaItem(
    val id: String,
    var title: String,
    val date: String,
    var category: String,
    val drawableRes: Int = 0,
    val isVideo: Boolean = false,
    val duration: String? = null,
    val contentUri: String? = null,
    val dateTakenMs: Long = 0L,
    val bucketId: String = "",
    val bucketName: String = "",
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val cameraSpec: String = "",
    val fileSize: String = "",
    val dimensions: String = "",
    val storagePath: String = "",
    var isFavorite: Boolean = false,
    var isTrash: Boolean = false,
    var isVault: Boolean = false
)
