package com.example.piphotos.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object TrashUriHelper {

    fun getBaseUriString(isVideo: Boolean): String {
        return if (isVideo) "content://media/external/video/media" else "content://media/external/images/media"
    }

    fun buildUri(id: Long, isVideo: Boolean): Uri {
        val baseUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(baseUri, id)
    }

    fun splitUris(idTypePairs: List<Pair<Long, Boolean>>): List<Uri> {
        return idTypePairs.map { (id, isVideo) -> buildUri(id, isVideo) }
    }

    fun splitUrisForItems(items: List<LocalMediaItem>): List<Uri> {
        return items.map { item -> buildUri(item.id, item.isVideo) }
    }

    fun computeDaysRemaining(dateExpiresSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): Int {
        if (dateExpiresSec <= 0L) return 30
        val diffSec = dateExpiresSec - nowSec
        return maxOf(0, (diffSec / 86400L).toInt())
    }
}
