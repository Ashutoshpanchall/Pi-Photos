package com.example.piphotos

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

class LocalDeleteHandler(
    private val context: Context? = null,
    private val mediaSource: MediaSource? = null
) : DeleteHandler {

    constructor(mediaSource: MediaSource) : this(context = null, mediaSource = mediaSource)
    constructor(context: Context) : this(context = context, mediaSource = null)

    override suspend fun trash(ids: List<Long>): DeleteResult {
        if (ids.isEmpty()) return DeleteResult.Success

        val ctx = context
        if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = ids.map { id ->
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                }
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, true)
                return DeleteResult.RequiresConfirmation(pi.intentSender)
            } catch (e: Exception) {
                // Return success if handled or fallback
            }
        }

        mediaSource?.let { src ->
            for (id in ids) {
                val item = src.getItem(id.toString())
                item?.isTrash = true
            }
        }
        return DeleteResult.Success
    }

    override fun moveToTrash(id: String): Boolean {
        val item = mediaSource?.getItem(id) ?: return false
        item.isTrash = true
        return true
    }

    override fun restoreFromTrash(id: String): Boolean {
        val item = mediaSource?.getItem(id) ?: return false
        item.isTrash = false
        return true
    }

    override fun deletePermanently(id: String): Boolean {
        return true
    }

    override fun emptyTrash(): Int {
        val trashed = mediaSource?.getMediaForCategory("trash") ?: emptyList()
        trashed.forEach { it.isTrash = false }
        return trashed.size
    }
}
