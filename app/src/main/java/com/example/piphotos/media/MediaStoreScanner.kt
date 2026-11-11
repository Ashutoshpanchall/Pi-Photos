package com.example.piphotos.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log

class MediaStoreScanner(private val context: Context) {

    private val contentResolver: ContentResolver get() = context.contentResolver

    companion object {
        private const val TAG = "MediaStoreScanner"

        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.GENERATION_MODIFIED
        )

        val ID_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID
        )

        val BASE_URI: Uri get() = MediaStore.Files.getContentUri("external")

        const val SELECTION_MEDIA = "( ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? )"

        val SELECTION_MEDIA_ARGS = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        const val SORT_ORDER = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        fun parseMediaCursor(cursor: Cursor): List<LocalMediaItem> {
            val items = ArrayList<LocalMediaItem>(cursor.count)

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mimeTypeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateTakenCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val bucketIdCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val genModifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mediaType = if (mediaTypeCol >= 0) cursor.getInt(mediaTypeCol) else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mimeType = if (mimeTypeCol >= 0) cursor.getString(mimeTypeCol) ?: (if (isVideo) "video/*" else "image/*") else ""
                val dateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                val dateModified = if (dateModifiedCol >= 0) cursor.getLong(dateModifiedCol) else 0L
                val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) ?: "unknown" else "unknown"
                val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "Default" else "Default"
                val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val generation = if (genModifiedCol >= 0) cursor.getLong(genModifiedCol) else 0L

                items.add(
                    LocalMediaItem(
                        id = id,
                        isVideo = isVideo,
                        mimeType = mimeType,
                        dateTakenMs = dateTaken,
                        dateAddedSec = dateAdded,
                        dateModifiedSec = dateModified,
                        bucketId = bucketId,
                        bucketName = bucketName,
                        durationMs = duration,
                        sizeBytes = size,
                        generationModified = generation
                    )
                )
            }
            return items
        }

        fun parseIdCursor(cursor: Cursor): Set<Long> {
            val ids = HashSet<Long>(cursor.count)
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idCol))
            }
            return ids
        }
    }

    fun getCurrentMediaStoreVersion(): String {
        return try {
            MediaStore.getVersion(context) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun performFullScan(): Pair<List<LocalMediaItem>, IndexStats> {
        val startTime = SystemClock.elapsedRealtime()
        val version = getCurrentMediaStoreVersion()

        val items = try {
            contentResolver.query(
                BASE_URI,
                PROJECTION,
                SELECTION_MEDIA,
                SELECTION_MEDIA_ARGS,
                SORT_ORDER
            )?.use { cursor ->
                parseMediaCursor(cursor)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan MediaStore: ${e.message}", e)
            emptyList()
        }

        val elapsed = SystemClock.elapsedRealtime() - startTime
        val maxGen = items.maxOfOrNull { it.generationModified } ?: 0L

        val photosCount = items.count { !it.isVideo }
        val videosCount = items.count { it.isVideo }
        val foldersMap = items.groupingBy { it.bucketName }.eachCount()

        val stats = IndexStats(
            totalItems = items.size,
            totalPhotos = photosCount,
            totalVideos = videosCount,
            totalFolders = foldersMap.size,
            buildTimeMs = elapsed,
            lastScanTimestampMs = System.currentTimeMillis(),
            mediaStoreVersion = version,
            generation = maxGen,
            folderBreakdown = foldersMap
        )

        return Pair(items, stats)
    }

    fun performDeltaScan(lastGeneration: Long): List<LocalMediaItem> {
        val selection = "( ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? ) AND $SELECTION_MEDIA"
        val selectionArgs = arrayOf(
            lastGeneration.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        return try {
            contentResolver.query(
                BASE_URI,
                PROJECTION,
                selection,
                selectionArgs,
                SORT_ORDER
            )?.use { cursor ->
                parseMediaCursor(cursor)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed delta query: ${e.message}", e)
            emptyList()
        }
    }

    fun queryActiveIds(): Set<Long> {
        return try {
            contentResolver.query(
                BASE_URI,
                ID_PROJECTION,
                SELECTION_MEDIA,
                SELECTION_MEDIA_ARGS,
                null
            )?.use { cursor ->
                parseIdCursor(cursor)
            } ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed querying active IDs: ${e.message}", e)
            emptySet()
        }
    }
}
