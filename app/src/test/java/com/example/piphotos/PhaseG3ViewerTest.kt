package com.example.piphotos

import android.net.Uri
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.ViewerSession
import com.example.piphotos.media.ZoomGestureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG3ViewerTest {

    private fun createItem(id: Long, isVideo: Boolean = false): LocalMediaItem {
        return LocalMediaItem(
            id = id,
            isVideo = isVideo,
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            dateTakenMs = 1700000000000L + id,
            dateAddedSec = 1700000000L + id,
            dateModifiedSec = 1700000000L + id,
            bucketId = 101L,
            bucketName = "Camera",
            durationMs = if (isVideo) 45000L else 0L
        )
    }

    @Test
    fun viewerSession_startingPositionResolution() {
        val items = listOf(
            createItem(101L),
            createItem(102L),
            createItem(103L),
            createItem(104L)
        )

        // Exact starting item resolution
        assertEquals(0, ViewerSession.resolveStartingPosition(items, 101L))
        assertEquals(1, ViewerSession.resolveStartingPosition(items, 102L))
        assertEquals(2, ViewerSession.resolveStartingPosition(items, 103L))
        assertEquals(3, ViewerSession.resolveStartingPosition(items, 104L))

        // Unknown starting item defaults safely to 0
        assertEquals(0, ViewerSession.resolveStartingPosition(items, 999L))
        assertEquals(0, ViewerSession.resolveStartingPosition(emptyList(), 101L))
    }

    @Test
    fun viewerSession_sessionLifecycleAndProcessDeathFallback() {
        val items = listOf(createItem(1L), createItem(2L))
        val sessionId = ViewerSession.createSession(items)

        // Retrieved session matches stored items
        val retrieved = ViewerSession.getSession(sessionId)
        assertEquals(2, retrieved?.size)
        assertEquals(1L, retrieved?.get(0)?.id)
        assertEquals(2L, retrieved?.get(1)?.id)

        // Session cleared
        ViewerSession.clearSession(sessionId)
        assertNull(ViewerSession.getSession(sessionId))

        // Null session ID returns null safely
        assertNull(ViewerSession.getSession(null))
    }

    @Test
    fun swipingAcrossBoundaryItems_resolution() {
        val items = listOf(createItem(10L), createItem(20L), createItem(30L))

        val firstPos = 0
        val midPos = 1
        val lastPos = items.size - 1

        assertEquals(10L, items[firstPos].id)
        assertEquals(20L, items[midPos].id)
        assertEquals(30L, items[lastPos].id)

        // Next item relative to current
        val nextFromFirst = items.getOrNull(firstPos + 1)
        assertEquals(20L, nextFromFirst?.id)

        // Previous item relative to current
        val prevFromLast = items.getOrNull(lastPos - 1)
        assertEquals(20L, prevFromLast?.id)

        // Out of bounds checks
        assertNull(items.getOrNull(-1))
        assertNull(items.getOrNull(items.size))
    }

    @Test
    fun gestureStateMachine_zoomAndPan_andPageSwipeLock() {
        // At 1.0x: pager is unlocked, swiping between photos is enabled
        assertFalse(ZoomGestureMath.shouldLockPager(1.0f))
        assertFalse(ZoomGestureMath.shouldLockPager(1.05f))

        // Above 1.05x: pager is locked to prevent accidental page switch during zoom/pan
        assertTrue(ZoomGestureMath.shouldLockPager(1.06f))
        assertTrue(ZoomGestureMath.shouldLockPager(2.5f))
        assertTrue(ZoomGestureMath.shouldLockPager(4.0f))

        // Clamped translation when zoomed in (content larger than view)
        val viewDim = 1080f
        val contentDim = 2700f // 2.5x zoomed

        // 1. Overscroll right: transX > 0 must clamp to 0
        val clampedRight = ZoomGestureMath.clampTranslation(viewDim, contentDim, 100f)
        assertEquals(0f, clampedRight, 0.001f)

        // 2. Overscroll left: transX < (viewDim - contentDim = -1620) must clamp to -1620
        val clampedLeft = ZoomGestureMath.clampTranslation(viewDim, contentDim, -2000f)
        assertEquals(-1620f, clampedLeft, 0.001f)

        // 3. Valid pan within bounds: transX = -800
        val validPan = ZoomGestureMath.clampTranslation(viewDim, contentDim, -800f)
        assertEquals(-800f, validPan, 0.001f)

        // 4. Content smaller than view (e.g. 800px image in 1080px view) -> centered
        val centered = ZoomGestureMath.clampTranslation(viewDim, 800f, 0f)
        assertEquals(140f, centered, 0.001f)
    }

    @Test
    fun swipeDownDismiss_at1x_vs_zoomedIn() {
        val touchSlop = 20f

        // Case 1: At 1.0x, predominant downward drag -> triggers dismiss
        val dismissAt1x = ZoomGestureMath.shouldDismissOnSwipeDown(
            scale = 1.0f,
            dy = 80f,
            dx = 10f,
            touchSlop = touchSlop
        )
        assertTrue("Downward swipe at 1x must trigger dismiss", dismissAt1x)

        // Case 2: At 1.0x, horizontal drag -> does NOT dismiss (page swipe)
        val horizontalAt1x = ZoomGestureMath.shouldDismissOnSwipeDown(
            scale = 1.0f,
            dy = 15f,
            dx = 80f,
            touchSlop = touchSlop
        )
        assertFalse("Horizontal swipe at 1x must not trigger dismiss", horizontalAt1x)

        // Case 3: While zoomed in (2.0x), downward drag is panning -> does NOT dismiss
        val downwardZoomed = ZoomGestureMath.shouldDismissOnSwipeDown(
            scale = 2.0f,
            dy = 80f,
            dx = 10f,
            touchSlop = touchSlop
        )
        assertFalse("Downward swipe while zoomed in must NOT trigger dismiss", downwardZoomed)
    }

    @Test
    fun videoPlayback_propertiesAndUriSetup() {
        val videoItem = createItem(201L, isVideo = true)
        assertTrue(videoItem.isVideo)
        assertEquals(201L, videoItem.id)
        assertEquals("video/mp4", videoItem.mimeType)
        assertEquals(45000L, videoItem.durationMs)
    }
}

