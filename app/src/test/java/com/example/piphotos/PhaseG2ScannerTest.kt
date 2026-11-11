package com.example.piphotos

import android.provider.MediaStore
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.MediaStoreScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhaseG2ScannerTest {

    private val columns = MediaStoreScanner.PROJECTION

    @Test
    fun parseMediaCursor_parsesImagesAndVideosCorrectly() {
        val rows = listOf(
            arrayOf<Any?>(
                101L, // _ID
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, // MEDIA_TYPE
                "image/jpeg", // MIME_TYPE
                1700000000000L, // DATE_TAKEN
                1700000000L, // DATE_ADDED
                1700000000L, // DATE_MODIFIED
                1001L, // BUCKET_ID
                "Camera", // BUCKET_DISPLAY_NAME
                0L, // DURATION
                3500000L, // SIZE
                5L // GENERATION_MODIFIED
            ),
            arrayOf<Any?>(
                102L, // _ID
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, // MEDIA_TYPE
                "video/mp4", // MIME_TYPE
                1700005000000L, // DATE_TAKEN
                1700005000L, // DATE_ADDED
                1700005000L, // DATE_MODIFIED
                1002L, // BUCKET_ID
                "Screenshots", // BUCKET_DISPLAY_NAME
                45000L, // DURATION
                12500000L, // SIZE
                7L // GENERATION_MODIFIED
            )
        )

        val fakeCursor = FakeCursor(columns, rows)
        val items = MediaStoreScanner.parseMediaCursor(fakeCursor)

        assertEquals(2, items.size)

        val imageItem = items[0]
        assertEquals(101L, imageItem.id)
        assertFalse(imageItem.isVideo)
        assertEquals("image/jpeg", imageItem.mimeType)
        assertEquals(1700000000000L, imageItem.dateTakenMs)
        assertEquals("Camera", imageItem.bucketName)
        assertEquals(1001L, imageItem.bucketId)
        assertEquals(5L, imageItem.generationModified)

        val videoItem = items[1]
        assertEquals(102L, videoItem.id)
        assertTrue(videoItem.isVideo)
        assertEquals("video/mp4", videoItem.mimeType)
        assertEquals(45000L, videoItem.durationMs)
        assertEquals("Screenshots", videoItem.bucketName)
        assertEquals(7L, videoItem.generationModified)
    }

    @Test
    fun parseIdCursor_extractsActiveIdsForDeletionDetection() {
        val idColumns = MediaStoreScanner.ID_PROJECTION
        val rows = listOf(
            arrayOf<Any?>(101L),
            arrayOf<Any?>(102L),
            arrayOf<Any?>(103L)
        )

        val fakeCursor = FakeCursor(idColumns, rows)
        val ids = MediaStoreScanner.parseIdCursor(fakeCursor)

        assertEquals(3, ids.size)
        assertTrue(ids.contains(101L))
        assertTrue(ids.contains(102L))
        assertTrue(ids.contains(103L))
    }

    @Test
    fun deltaRefresh_prunesDeletedIdsAndAddsNewItems() {
        val existingMap = mutableMapOf<Long, LocalMediaItem>(
            101L to LocalMediaItem(
                id = 101L,
                isVideo = false,
                mimeType = "image/jpeg",
                dateTakenMs = 1000L,
                dateAddedSec = 1L,
                dateModifiedSec = 1L,
                bucketId = 10L,
                bucketName = "Camera",
                generationModified = 1L
            ),
            102L to LocalMediaItem(
                id = 102L,
                isVideo = false,
                mimeType = "image/jpeg",
                dateTakenMs = 2000L,
                dateAddedSec = 2L,
                dateModifiedSec = 2L,
                bucketId = 10L,
                bucketName = "Camera",
                generationModified = 1L
            )
        )

        // Simulate 101 deleted in MediaStore: active IDs now only contain 102 and a newly added 103
        val activeIds = setOf(102L, 103L)

        // Step 1: Detect deletions by set subtraction
        val deleted = existingMap.keys - activeIds
        assertEquals(setOf(101L), deleted)
        deleted.forEach { existingMap.remove(it) }

        assertEquals(1, existingMap.size)
        assertFalse(existingMap.containsKey(101L))
        assertTrue(existingMap.containsKey(102L))

        // Step 2: Ingest delta items (GENERATION_MODIFIED > 1L)
        val deltaItems = listOf(
            LocalMediaItem(
                id = 103L,
                isVideo = true,
                mimeType = "video/mp4",
                dateTakenMs = 3000L,
                dateAddedSec = 3L,
                dateModifiedSec = 3L,
                bucketId = 20L,
                bucketName = "Videos",
                generationModified = 2L
            )
        )
        for (item in deltaItems) {
            existingMap[item.id] = item
        }

        assertEquals(2, existingMap.size)
        assertTrue(existingMap.containsKey(102L))
        assertTrue(existingMap.containsKey(103L))
    }

    @Test
    fun folderGrouping_computesAccurateCounts() {
        val items = listOf(
            LocalMediaItem(1L, false, "image/jpeg", 1000L, 1L, 1L, 1L, "Camera", sizeBytes = 1000L),
            LocalMediaItem(2L, false, "image/jpeg", 2000L, 2L, 2L, 1L, "Camera", sizeBytes = 2000L),
            LocalMediaItem(3L, true, "video/mp4", 3000L, 3L, 3L, 2L, "Screenshots", sizeBytes = 5000L)
        )

        val grouped = items.groupBy { it.bucketId }
        assertEquals(2, grouped.size)

        val cameraGroup = grouped[1L]!!
        assertEquals(2, cameraGroup.size)
        assertEquals(3000L, cameraGroup.sumOf { it.sizeBytes })

        val screenshotsGroup = grouped[2L]!!
        assertEquals(1, screenshotsGroup.size)
        assertEquals(5000L, screenshotsGroup.sumOf { it.sizeBytes })
    }

    @Test
    fun benchmark_100k_rows_heap_and_time() {
        System.gc()
        val runtime = Runtime.getRuntime()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()

        val count = 100_000
        val rows = ArrayList<Array<Any?>>(count)
        val startTime = System.currentTimeMillis()

        for (i in 0 until count) {
            val id = i.toLong()
            val isVideo = (i % 10 == 0)
            rows.add(
                arrayOf(
                    id,
                    if (isVideo) MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                    if (isVideo) "video/mp4" else "image/jpeg",
                    1700000000000L + (i * 1000L),
                    1700000000L + i,
                    1700000000L + i,
                    (i % 10).toLong(),
                    if (i % 2 == 0) "Camera" else "Screenshots",
                    if (isVideo) 15000L else 0L,
                    2500000L,
                    id
                )
            )
        }

        val cursor = FakeCursor(columns, rows)
        val parseStart = System.currentTimeMillis()
        val items = MediaStoreScanner.parseMediaCursor(cursor)
        val parseElapsedMs = System.currentTimeMillis() - parseStart

        System.gc()
        val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
        val heapUsedMb = (memoryAfter - memoryBefore) / (1024.0 * 1024.0)

        println("================ 100k-ROW FAKE CURSOR BENCHMARK ================")
        println("Parsed items count : ${items.size}")
        println("Scan parse time     : ${parseElapsedMs} ms")
        println(String.format(Locale.US, "Heap allocated      : %.2f MB", heapUsedMb))
        println("================================================================")

        assertEquals(count, items.size)
        assertTrue("Scan parse time must be fast (< 2500ms)", parseElapsedMs < 2500L)
    }
}
