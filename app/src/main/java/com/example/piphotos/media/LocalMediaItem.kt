package com.example.piphotos.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class LocalMediaItem(
    val id: Long,
    val isVideo: Boolean,
    val mimeType: String,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val bucketId: String,
    val bucketName: String,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val generationModified: Long = 0L
) {
    val contentUri: Uri
        get() = if (isVideo) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

    val effectiveDateMs: Long
        get() = if (dateTakenMs > 0L) dateTakenMs else (dateAddedSec * 1000L)
}

