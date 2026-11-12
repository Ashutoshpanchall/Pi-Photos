package com.example.piphotos

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.example.piphotos.media.LocalDeletePolicyHook
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.TrashUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDeleteHandler(
    private val context: Context
) : DeleteHandler {

    override suspend fun trash(ids: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext DeleteResult.Success
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext DeleteResult.Error("Trash requires Android 11 or higher")
        }

        try {
            val index = LocalMediaIndex.getInstance(context)
            val uris = ids.map { id ->
                val isVideo = index.getItem(id)?.isVideo ?: false
                TrashUriHelper.buildUri(id, isVideo)
            }
            val pi = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            DeleteResult.RequiresConfirmation(pi.intentSender)
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: "Failed to trash items")
        }
    }

    override suspend fun restore(ids: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext DeleteResult.Success
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext DeleteResult.Error("Restore requires Android 11 or higher")
        }

        try {
            val binItems = listBin().associateBy { it.id }
            val uris = ids.map { id ->
                val isVideo = binItems[id]?.isVideo ?: false
                TrashUriHelper.buildUri(id, isVideo)
            }
            val pi = MediaStore.createTrashRequest(context.contentResolver, uris, false)
            DeleteResult.RequiresConfirmation(pi.intentSender)
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: "Failed to restore items")
        }
    }

    override suspend fun deletePermanently(ids: List<Long>): DeleteResult = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext DeleteResult.Success
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext DeleteResult.Error("Permanent delete requires Android 11 or higher")
        }

        try {
            val binItems = listBin().associateBy { it.id }
            val index = LocalMediaIndex.getInstance(context)
            val uris = ids.map { id ->
                val isVideo = binItems[id]?.isVideo ?: index.getItem(id)?.isVideo ?: false
                TrashUriHelper.buildUri(id, isVideo)
            }
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            DeleteResult.RequiresConfirmation(pi.intentSender)
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: "Failed to delete items permanently")
        }
    }

    override suspend fun listBin(): List<TrashedMediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext emptyList()
        }

        val results = mutableListOf<TrashedMediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
        }

        // 1. Query Images in Trash
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                bundle,
                null
            )?.use { cursor ->
                parseBinCursor(cursor, isVideo = false, results)
            }
        } catch (_: Exception) {}

        // 2. Query Videos in Trash
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                bundle,
                null
            )?.use { cursor ->
                parseBinCursor(cursor, isVideo = true, results)
            }
        } catch (_: Exception) {}

        results.sortByDescending { it.dateModifiedSec }
        results
    }

    override suspend fun getBinCount(): Int = withContext(Dispatchers.IO) {
        listBin().size
    }

    override suspend fun emptyBin(): DeleteResult = withContext(Dispatchers.IO) {
        val allTrashed = listBin()
        if (allTrashed.isEmpty()) return@withContext DeleteResult.Success
        deletePermanently(allTrashed.map { it.id })
    }

    companion object {
        fun parseBinCursor(
            cursor: Cursor,
            isVideo: Boolean,
            outList: MutableList<TrashedMediaItem>,
            nowSec: Long = System.currentTimeMillis() / 1000L
        ) {
            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val expCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                val modSec = if (modCol >= 0) cursor.getLong(modCol) else 0L
                val expSec = if (expCol >= 0) cursor.getLong(expCol) else 0L
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                val days = TrashUriHelper.computeDaysRemaining(expSec, nowSec)

                outList.add(
                    TrashedMediaItem(
                        id = id,
                        isVideo = isVideo,
                        mimeType = mime,
                        dateModifiedSec = modSec,
                        dateExpiresSec = expSec,
                        daysRemaining = days,
                        sizeBytes = size
                    )
                )
            }
        }
    }
}
