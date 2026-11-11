package com.example.piphotos

data class FolderItem(
    val bucketId: String,
    val name: String,
    val path: String = "",
    val coverUri: String? = null,
    val coverDrawableRes: Int = 0,
    val itemCount: Int = 0,
    val sizeBytes: Long = 0L
)

