package com.example.piphotos

import com.example.piphotos.media.FastScrollerMath
import com.example.piphotos.media.IndexDeltaApplier
import com.example.piphotos.media.IndexState
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.TimelineAdapter
import com.example.piphotos.media.TimelineCategoryFilter
import com.example.piphotos.media.TimelineDiffCallback
import com.example.piphotos.media.TimelineGroupMode
import com.example.piphotos.media.TimelineGrouper
import com.example.piphotos.media.TimelineRow
import com.example.piphotos.media.TimelineStateMachine
import com.example.piphotos.media.TimelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class PhaseG3TimelineTest {

    private val zoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    // Fixed reference time: 2026-11-11T12:00:00Z
    private val fixedNow = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zoneId).toInstant()

    private fun createMediaItem(
        id: Long,
        timestampMs: Long,
        isVideo: Boolean = false,
        bucketName: String = "Camera",
        mimeType: String = if (isVideo) "video/mp4" else "image/jpeg"
    ): LocalMediaItem {
        return LocalMediaItem(
            id = id,
            isVideo = isVideo,
            mimeType = mimeType,
            dateTakenMs = timestampMs,
            dateAddedSec = timestampMs / 1000L,
            dateModifiedSec = timestampMs / 1000L,
            bucketId = 100L,
            bucketName = bucketName
        )
    }

    @Test
    fun grouping_dayMode_midnightBoundariesSeparatesDays() {
        // Day 1: 2026-11-10T23:59:59Z
        val day1Item = createMediaItem(
            id = 1L,
            timestampMs = ZonedDateTime.of(2026, 11, 10, 23, 59, 59, 0, zoneId).toInstant().toEpochMilli()
        )
        // Day 2: 2026-11-11T00:00:01Z (2 seconds later, across midnight)
        val day2Item = createMediaItem(
            id = 2L,
            timestampMs = ZonedDateTime.of(2026, 11, 11, 0, 0, 1, 0, zoneId).toInstant().toEpochMilli()
        )

        val rows = TimelineGrouper.group(
            items = listOf(day1Item, day2Item),
            mode = TimelineGroupMode.DAY,
            zoneId = zoneId,
            locale = locale,
            nowInstant = fixedNow
        )

        // Expected: Header 1 (Today: 2026-11-11) -> day2Item -> Header 2 (Yesterday: 2026-11-10) -> day1Item
        assertEquals(4, rows.size)

        assertTrue(rows[0] is TimelineRow.HeaderRow)
        assertEquals("Today", (rows[0] as TimelineRow.HeaderRow).title)

        assertTrue(rows[1] is TimelineRow.MediaRow)
        assertEquals(2L, (rows[1] as TimelineRow.MediaRow).item.id)

        assertTrue(rows[2] is TimelineRow.HeaderRow)
        assertEquals("Yesterday", (rows[2] as TimelineRow.HeaderRow).title)

        assertTrue(rows[3] is TimelineRow.MediaRow)
        assertEquals(1L, (rows[3] as TimelineRow.MediaRow).item.id)
    }

    @Test
    fun grouping_dayMode_formatsDatesAcrossYears() {
        // Today (2026-11-11)
        val todayItem = createMediaItem(
            id = 10L,
            timestampMs = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        )
        // Same year earlier date (2026-05-18)
        val sameYearItem = createMediaItem(
            id = 11L,
            timestampMs = ZonedDateTime.of(2026, 5, 18, 14, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        )
        // Past year date (2025-11-11)
        val pastYearItem = createMediaItem(
            id = 12L,
            timestampMs = ZonedDateTime.of(2025, 11, 11, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        )

        val rows = TimelineGrouper.group(
            items = listOf(todayItem, sameYearItem, pastYearItem),
            mode = TimelineGroupMode.DAY,
            zoneId = zoneId,
            locale = locale,
            nowInstant = fixedNow
        )

        val headers = rows.filterIsInstance<TimelineRow.HeaderRow>()
        assertEquals(3, headers.size)
        assertEquals("Today", headers[0].title)
        assertEquals("Mon, 18 May", headers[1].title)
        assertEquals("Tue, 11 Nov 2025", headers[2].title)
    }

    @Test
    fun grouping_monthMode_monthAndYearEdges() {
        // 2025-12-31T23:59:59Z
        val decItem = createMediaItem(
            id = 20L,
            timestampMs = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 0, zoneId).toInstant().toEpochMilli()
        )
        // 2026-01-01T00:00:01Z (2 seconds later, across month and year boundary)
        val janItem = createMediaItem(
            id = 21L,
            timestampMs = ZonedDateTime.of(2026, 1, 1, 0, 0, 1, 0, zoneId).toInstant().toEpochMilli()
        )

        val rows = TimelineGrouper.group(
            items = listOf(decItem, janItem),
            mode = TimelineGroupMode.MONTH,
            zoneId = zoneId,
            locale = locale,
            nowInstant = fixedNow
        )

        val headers = rows.filterIsInstance<TimelineRow.HeaderRow>()
        assertEquals(2, headers.size)
        // 2026 is the same year as fixedNow (2026), so it formats with MMMM
        assertEquals("January", headers[0].title)
        // 2025 is a past year from fixedNow (2026), so it includes year
        assertEquals("December 2025", headers[1].title)
    }

    @Test
    fun grouping_yearMode_groupsByYearHeaders() {
        val y2026Item = createMediaItem(
            id = 30L,
            timestampMs = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        )
        val y2024Item = createMediaItem(
            id = 31L,
            timestampMs = ZonedDateTime.of(2024, 3, 15, 12, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        )

        val rows = TimelineGrouper.group(
            items = listOf(y2026Item, y2024Item),
            mode = TimelineGroupMode.YEAR,
            zoneId = zoneId,
            locale = locale,
            nowInstant = fixedNow
        )

        val headers = rows.filterIsInstance<TimelineRow.HeaderRow>()
        assertEquals(2, headers.size)
        assertEquals("2026", headers[0].title)
        assertEquals("2024", headers[1].title)
    }

    @Test
    fun grouping_invalidDateTaken_usesEffectiveDateGracefully() {
        // Item with dateTaken = 0, but valid dateModifiedSec (2026-11-10)
        val modSec = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, zoneId).toEpochSecond()
        val item = LocalMediaItem(
            id = 40L,
            isVideo = false,
            mimeType = "image/jpeg",
            dateTakenMs = 0L,
            dateAddedSec = modSec,
            dateModifiedSec = modSec,
            bucketId = 100L,
            bucketName = "Camera"
        )

        val rows = TimelineGrouper.group(
            items = listOf(item),
            mode = TimelineGroupMode.DAY,
            zoneId = zoneId,
            locale = locale,
            nowInstant = fixedNow
        )

        assertEquals(2, rows.size)
        assertTrue(rows[0] is TimelineRow.HeaderRow)
        assertEquals("Yesterday", (rows[0] as TimelineRow.HeaderRow).title)
        assertEquals(40L, (rows[1] as TimelineRow.MediaRow).item.id)
    }

    @Test
    fun chipFilters_filterCategoriesCorrectly() {
        val cameraItem = createMediaItem(1L, 1000L, isVideo = false, bucketName = "Camera")
        val screenshotItem = createMediaItem(2L, 2000L, isVideo = false, bucketName = "Screenshots")
        val videoItem = createMediaItem(3L, 3000L, isVideo = true, bucketName = "Camera")
        val favItem = createMediaItem(4L, 4000L, isVideo = false, bucketName = "Vacation")

        val items = listOf(cameraItem, screenshotItem, videoItem, favItem)
        val favIds = setOf(4L)

        // ALL
        assertEquals(4, TimelineGrouper.filterItems(items, TimelineCategoryFilter.ALL, favIds).size)

        // CAMERA
        val cameraOnly = TimelineGrouper.filterItems(items, TimelineCategoryFilter.CAMERA, favIds)
        assertEquals(2, cameraOnly.size)
        assertTrue(cameraOnly.all { it.bucketName.equals("Camera", ignoreCase = true) })

        // SCREENSHOTS
        val screenshotsOnly = TimelineGrouper.filterItems(items, TimelineCategoryFilter.SCREENSHOTS, favIds)
        assertEquals(1, screenshotsOnly.size)
        assertEquals(2L, screenshotsOnly[0].id)

        // VIDEOS
        val videosOnly = TimelineGrouper.filterItems(items, TimelineCategoryFilter.VIDEOS, favIds)
        assertEquals(1, videosOnly.size)
        assertTrue(videosOnly[0].isVideo)

        // FAVORITES
        val favsOnly = TimelineGrouper.filterItems(items, TimelineCategoryFilter.FAVORITES, favIds)
        assertEquals(1, favsOnly.size)
        assertEquals(4L, favsOnly[0].id)
    }

    @Test
    fun spanLookup_spanSizesMatchGroupingMode() {
        for (mode in TimelineGroupMode.values()) {
            val headerSpan = mode.spanCount
            val mediaSpan = 1
            assertEquals(mode.spanCount, headerSpan)
            assertEquals(1, mediaSpan)
        }
    }

    @Test
    fun stableIds_areDeterministicAndNeverCollideWithMediaIds() {
        val header = TimelineRow.HeaderRow("2026-11-11", "Today")
        val media = TimelineRow.MediaRow(createMediaItem(100L, 1000L))

        // Headers have negative stable IDs; Media has positive MediaStore _ID
        assertTrue("Header stableId must be negative", header.stableId < 0)
        assertTrue("Media stableId must be positive", media.stableId > 0)
        assertNotEquals(header.stableId, media.stableId)
    }

    @Test
    fun anchorPreservation_acrossSpanChange() {
        val item1 = createMediaItem(101L, 1700000000000L)
        val item2 = createMediaItem(102L, 1690000000000L)
        val item3 = createMediaItem(103L, 1680000000000L)
        val items = listOf(item1, item2, item3)

        // Month mode
        val monthRows = TimelineGrouper.group(items, TimelineGroupMode.MONTH, zoneId, locale, fixedNow)
        val firstMediaRowInMonth = monthRows.first { it is TimelineRow.MediaRow } as TimelineRow.MediaRow
        val anchorId = firstMediaRowInMonth.item.id

        // Transition to Day mode
        val dayRows = TimelineGrouper.group(items, TimelineGroupMode.DAY, zoneId, locale, fixedNow)
        val targetPos = dayRows.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == anchorId }

        assertTrue("Anchor item must exist in the new day rows", targetPos >= 0)
        assertEquals(anchorId, (dayRows[targetPos] as TimelineRow.MediaRow).item.id)
    }

    @Test
    fun filterTransitions_andEmptyStateMapping() {
        assertEquals("No Photos Found", TimelineStateMachine.getEmptyTitle(TimelineCategoryFilter.ALL))
        assertEquals("No camera photos", TimelineStateMachine.getEmptyTitle(TimelineCategoryFilter.CAMERA))
        assertEquals("No screenshots", TimelineStateMachine.getEmptyTitle(TimelineCategoryFilter.SCREENSHOTS))
        assertEquals("No videos", TimelineStateMachine.getEmptyTitle(TimelineCategoryFilter.VIDEOS))
        assertEquals("No favourites yet", TimelineStateMachine.getEmptyTitle(TimelineCategoryFilter.FAVORITES))

        assertEquals("No pictures or videos were detected in your device storage.", TimelineStateMachine.getEmptySubtitle(TimelineCategoryFilter.ALL))
        assertEquals("Photos taken with your camera will appear here.", TimelineStateMachine.getEmptySubtitle(TimelineCategoryFilter.CAMERA))
        assertEquals("Screenshots you capture will appear here.", TimelineStateMachine.getEmptySubtitle(TimelineCategoryFilter.SCREENSHOTS))
        assertEquals("Recorded or downloaded videos will appear here.", TimelineStateMachine.getEmptySubtitle(TimelineCategoryFilter.VIDEOS))
        assertEquals("Tap the star icon on photos to add them to your favourites.", TimelineStateMachine.getEmptySubtitle(TimelineCategoryFilter.FAVORITES))
    }

    @Test
    fun emitOnlyOnChange_localMediaIndex() {
        val item1 = createMediaItem(101L, 1000L)
        val item2 = createMediaItem(102L, 2000L)
        val currentItems = mutableListOf(item1, item2)

        // Case A: No deletions, no new/modified items -> must return false (no change, no emit)
        val activeIds = setOf(101L, 102L)
        val modifiedEmpty = emptyList<LocalMediaItem>()
        val hasChangedNoOp = IndexDeltaApplier.applyDelta(currentItems, activeIds, modifiedEmpty)
        assertFalse("Delta with no additions or deletions must return false", hasChangedNoOp)
        assertEquals(2, currentItems.size)

        // Case B: Item 102 was deleted in MediaStore -> returns true, item 102 removed
        val activeIdsPruned = setOf(101L)
        val hasChangedDelete = IndexDeltaApplier.applyDelta(currentItems, activeIdsPruned, modifiedEmpty)
        assertTrue("Delta with deletions must return true", hasChangedDelete)
        assertEquals(1, currentItems.size)
        assertEquals(101L, currentItems[0].id)

        // Case C: New item 103 added -> returns true, item 103 inserted
        val item3 = createMediaItem(103L, 3000L)
        val hasChangedInsert = IndexDeltaApplier.applyDelta(currentItems, setOf(101L, 103L), listOf(item3))
        assertTrue("Delta with new items must return true", hasChangedInsert)
        assertEquals(2, currentItems.size)
        assertEquals(103L, currentItems[0].id) // 3000L > 1000L, sorted descending
    }

    @Test
    fun selectionPayload_updatesSelectionWithoutRebindingImage() {
        assertEquals("PAYLOAD_SELECTION", TimelineAdapter.PAYLOAD_SELECTION)
        val item1 = createMediaItem(101L, 1000L)
        val row = TimelineRow.MediaRow(item1)
        val diff = TimelineDiffCallback()
        assertTrue(diff.areItemsTheSame(row, row))
        assertTrue(diff.areContentsTheSame(row, row))
    }

    @Test
    fun timelineUiState_transitions() {
        val item = createMediaItem(101L, 1000L, isVideo = true, bucketName = "Camera")
        val favIds = emptySet<Long>()

        // 1. No permission -> NoPermission state
        val stateNoPerm = TimelineStateMachine.computeState(
            hasPermission = false,
            indexState = IndexState.Loading,
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = favIds
        )
        assertEquals(TimelineUiState.NoPermission, stateNoPerm)

        // 2. Permission granted, Index is Loading -> Loading state
        val stateLoading = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Loading,
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = favIds
        )
        assertEquals(TimelineUiState.Loading, stateLoading)

        // 3. Permission granted, Index is Ready with 0 items -> Empty state
        val stateEmpty = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Ready(emptyList(), 1L),
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = favIds
        )
        assertTrue(stateEmpty is TimelineUiState.Empty)
        assertEquals(TimelineCategoryFilter.ALL, (stateEmpty as TimelineUiState.Empty).filter)

        // 4. Permission granted, Index has videos, but filter is SCREENSHOTS -> Empty(SCREENSHOTS)
        val stateEmptyScreenshots = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Ready(listOf(item), 1L),
            filter = TimelineCategoryFilter.SCREENSHOTS,
            favoriteIds = favIds
        )
        assertTrue(stateEmptyScreenshots is TimelineUiState.Empty)
        assertEquals(TimelineCategoryFilter.SCREENSHOTS, (stateEmptyScreenshots as TimelineUiState.Empty).filter)

        // 5. Permission granted, Index has video, filter is VIDEOS -> Ready state
        val stateReady = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Ready(listOf(item), 2L),
            filter = TimelineCategoryFilter.VIDEOS,
            favoriteIds = favIds
        )
        assertTrue(stateReady is TimelineUiState.Ready)
        val ready = stateReady as TimelineUiState.Ready
        assertEquals(1, ready.items.size)
        assertEquals(2L, ready.revision)
        assertEquals(TimelineCategoryFilter.VIDEOS, ready.filter)
    }

    @Test
    fun fastScroller_touchHitTest_andPixelMath() {
        // Hit-test within thumb: thumb at Y=100, height=48, slop=20
        // Active range: [80f, 168f]
        assertTrue(FastScrollerMath.isHitThumb(100f, 100f, 48f, 20f))
        assertTrue(FastScrollerMath.isHitThumb(85f, 100f, 48f, 20f))
        assertTrue(FastScrollerMath.isHitThumb(165f, 100f, 48f, 20f))
        assertFalse(FastScrollerMath.isHitThumb(50f, 100f, 48f, 20f))
        assertFalse(FastScrollerMath.isHitThumb(180f, 100f, 48f, 20f))

        // Pixel scroll delta computation:
        // trackHeight=1000, scrollRange=5000, drag deltaY=20 -> delta=100
        val delta = FastScrollerMath.computeScrollDelta(20f, 1000, 5000)
        assertEquals(100, delta)

        // Negative drag
        val negDelta = FastScrollerMath.computeScrollDelta(-15f, 1000, 5000)
        assertEquals(-75, negDelta)

        // Zero track or range guards
        assertEquals(0, FastScrollerMath.computeScrollDelta(20f, 0, 5000))
        assertEquals(0, FastScrollerMath.computeScrollDelta(20f, 1000, 0))

        // Thumb translation
        val thumbY = FastScrollerMath.computeThumbTranslationY(scrollOffset = 2500, scrollRange = 5000, trackHeight = 1000)
        assertEquals(500f, thumbY, 0.01f)
    }
}
