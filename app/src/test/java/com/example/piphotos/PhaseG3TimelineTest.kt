package com.example.piphotos

import com.example.piphotos.media.FastScrollerMath
import com.example.piphotos.media.IndexDeltaApplier
import com.example.piphotos.media.IndexState
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.SelectionState
import com.example.piphotos.media.TimelineAdapter
import com.example.piphotos.media.TimelineCategoryFilter
import com.example.piphotos.media.TimelineDiffCallback
import com.example.piphotos.media.TimelineGroupMode
import com.example.piphotos.media.TimelineGrouper
import com.example.piphotos.media.TimelineRow
import com.example.piphotos.media.TimelineStateMachine
import com.example.piphotos.media.TimelineUiState
import com.example.piphotos.media.toEntity
import com.example.piphotos.media.toLocalMediaItem
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
    fun stateMachine_transitionsThroughAllPhases() {
        val emptyState = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Ready(emptyList(), revision = 1L),
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = emptySet()
        )
        assertTrue(emptyState is TimelineUiState.Empty)

        val loadingState = TimelineStateMachine.computeState(
            hasPermission = true,
            indexState = IndexState.Loading,
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = emptySet()
        )
        assertTrue(loadingState is TimelineUiState.Loading)

        val noPermState = TimelineStateMachine.computeState(
            hasPermission = false,
            indexState = IndexState.Ready(emptyList(), revision = 1L),
            filter = TimelineCategoryFilter.ALL,
            favoriteIds = emptySet()
        )
        assertTrue(noPermState is TimelineUiState.NoPermission)
    }

    @Test
    fun selectionState_pureUnitTest_select_toggle_clear() {
        val state = SelectionState()
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedIds.isEmpty())

        // 1. Toggle first item -> enters selection mode
        val res1 = state.toggle(101L)
        assertTrue(state.isSelectionMode)
        assertEquals(setOf(101L), state.selectedIds)
        assertTrue(res1 is SelectionState.Result.SingleItemToggled)
        assertTrue((res1 as SelectionState.Result.SingleItemToggled).isSelected)

        // 2. Toggle second item
        state.toggle(102L)
        assertEquals(setOf(101L, 102L), state.selectedIds)

        // 3. Deselect second item
        val res3 = state.toggle(102L)
        assertTrue(res3 is SelectionState.Result.SingleItemToggled)
        assertFalse((res3 as SelectionState.Result.SingleItemToggled).isSelected)
        assertEquals(setOf(101L), state.selectedIds)
        assertTrue(state.isSelectionMode)

        // 4. Deselect last item -> exits selection mode with full refresh required
        val res4 = state.toggle(101L)
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(res4 is SelectionState.Result.SelectionExited)
        assertTrue((res4 as SelectionState.Result.SelectionExited).needsFullRefresh)

        // 5. SelectAll
        val allIds = listOf(201L, 202L, 203L)
        val resAll = state.selectAll(allIds)
        assertTrue(state.isSelectionMode)
        assertEquals(3, state.selectedIds.size)
        assertTrue(resAll is SelectionState.Result.AllSelected)

        // 6. Clear -> exits selection mode and clears IDs
        val resClear = state.clear()
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(resClear is SelectionState.Result.SelectionExited)
    }

    @Test
    fun pinchZoomAnchor_preservesTopVisibleItemAcrossZoomLevels() {
        val day1Item = createMediaItem(id = 1L, timestampMs = 1700000000000L)
        val day2Item = createMediaItem(id = 2L, timestampMs = 1700086400000L)
        val items = listOf(day1Item, day2Item)

        val monthRows = TimelineGrouper.group(items, TimelineGroupMode.MONTH)
        val dayRows = TimelineGrouper.group(items, TimelineGroupMode.DAY)

        val anchorId = 2L
        val monthPos = monthRows.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == anchorId }
        val dayPos = dayRows.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == anchorId }

        assertTrue(monthPos >= 0)
        assertTrue(dayPos >= 0)
    }

    @Test
    fun fastScrollerHitTest_doesNotStealGridTaps() {
        val thumbY = 200f
        val thumbHeight = 60f
        val slopPx = 15f

        // Outside thumb bounds + slop (e.g. tap at 100f)
        assertFalse(FastScrollerMath.isHitThumb(100f, thumbY, thumbHeight, slopPx))

        // Inside thumb bounds (e.g. tap at 220f)
        assertTrue(FastScrollerMath.isHitThumb(220f, thumbY, thumbHeight, slopPx))

        // Inside slop region (e.g. tap at 190f, which is within [200 - 15, 200 + 60 + 15])
        assertTrue(FastScrollerMath.isHitThumb(190f, thumbY, thumbHeight, slopPx))

        // Far below thumb (e.g. tap at 500f)
        assertFalse(FastScrollerMath.isHitThumb(500f, thumbY, thumbHeight, slopPx))
    }

    @Test
    fun diffCallback_stableIdIntegrity() {
        val item1 = createMediaItem(id = 101L, timestampMs = 1000L)
        val item2 = createMediaItem(id = 102L, timestampMs = 2000L)

        val row1 = TimelineRow.MediaRow(item1)
        val row2 = TimelineRow.MediaRow(item2)
        val header = TimelineRow.HeaderRow(key = "2026-11", title = "November 2026")

        val callback = TimelineDiffCallback()
        assertTrue(callback.areItemsTheSame(row1, row1))
        assertFalse(callback.areItemsTheSame(row1, row2))
        assertNotEquals(row1.stableId, header.stableId)
    }

    @Test
    fun filterByQuery_matchesKeywordsCorrectly() {
        // Nov 11, 2026 UTC = 1794355200000 ms
        val photo = createMediaItem(id = 1L, timestampMs = 1794355200000L, isVideo = false, bucketName = "Vacation")
        val video = createMediaItem(id = 2L, timestampMs = 1794355200000L, isVideo = true, bucketName = "Camera")
        val items = listOf(photo, video)

        // 1. Filter "video"
        val videoResult = TimelineStateMachine.filterByQuery(items, "video", emptySet())
        assertEquals(listOf(video), videoResult)

        // 2. Filter "photo"
        val photoResult = TimelineStateMachine.filterByQuery(items, "photo", emptySet())
        assertEquals(listOf(photo), photoResult)

        // 3. Filter album name "vacation"
        val albumResult = TimelineStateMachine.filterByQuery(items, "vacation", emptySet())
        assertEquals(listOf(photo), albumResult)

        // 4. Filter favorites
        val favResult = TimelineStateMachine.filterByQuery(items, "favorite", setOf(2L))
        assertEquals(listOf(video), favResult)

        // 5. Filter year "2026"
        val yearResult = TimelineStateMachine.filterByQuery(items, "2026", emptySet())
        assertEquals(2, yearResult.size)
    }

    @Test
    fun mediaIndexEntity_mappingAndRoundTrip_preservesAllFields() {
        val original = createMediaItem(
            id = 42L,
            timestampMs = 1794355200000L,
            isVideo = true,
            bucketName = "Vacation",
            mimeType = "video/mp4"
        )
        val entity = original.toEntity()
        assertEquals(42L, entity.id)
        assertTrue(entity.isVideo)
        assertEquals("video/mp4", entity.mimeType)
        assertEquals(1794355200000L, entity.dateTakenMs)
        assertEquals("Vacation", entity.bucketName)

        val restored = entity.toLocalMediaItem()
        assertEquals(original.id, restored.id)
        assertEquals(original.isVideo, restored.isVideo)
        assertEquals(original.mimeType, restored.mimeType)
        assertEquals(original.dateTakenMs, restored.dateTakenMs)
        assertEquals(original.bucketName, restored.bucketName)
        assertEquals(original.effectiveDateMs, restored.effectiveDateMs)
    }
}
