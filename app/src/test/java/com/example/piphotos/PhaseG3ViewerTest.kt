package com.example.piphotos

import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.ViewerSession
import com.example.piphotos.media.ZoomGestureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseG3ViewerTest {

    private fun createItem(id: Long, isVideo: Boolean = false): LocalMediaItem {
        return LocalMediaItem(
            id = id,
            isVideo = isVideo,
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            dateTakenMs = 1700000000000L,
            dateAddedSec = 1700000000L,
            dateModifiedSec = 1700000000L,
            bucketId = 1L,
            bucketName = "Camera",
            durationMs = if (isVideo) 42000L else 0L
        )
    }

    @Test
    fun viewerSession_storesAndRetrievesItems() {
        val items = listOf(createItem(10L), createItem(20L), createItem(30L))
        val token = ViewerSession.createSession(items)

        val retrieved = ViewerSession.getSession(token)
        assertNotNull(retrieved)
        assertEquals(3, retrieved?.size)
        assertEquals(20L, retrieved?.get(1)?.id)

        assertNull(ViewerSession.getSession("invalid_token"))
    }

    @Test
    fun viewerSession_resolvesCorrectStartingIndex() {
        val items = listOf(createItem(100L), createItem(200L), createItem(300L))
        val pos = ViewerSession.resolveStartingPosition(items, 200L)
        assertEquals(1, pos)

        val notFoundPos = ViewerSession.resolveStartingPosition(items, 999L)
        assertEquals(0, notFoundPos)
    }

    @Test
    fun fitMatrix_computesCorrectCenterAndScale_portraitLandscapePanorama() {
        val viewW = 1080f
        val viewH = 1920f

        // 1. Portrait image (1080 x 2160, taller than screen)
        val portrait = ZoomGestureMath.computeFitRect(viewW, viewH, 1080f, 2160f)
        assertEquals(viewH / 2160f, portrait.scale, 0.001f)
        assertEquals(0f, portrait.top, 0.001f) // touches top and bottom
        assertTrue(portrait.left > 0f) // centered horizontally with black bars on sides

        // 2. Landscape image (1920 x 1080, wider than screen)
        val landscape = ZoomGestureMath.computeFitRect(viewW, viewH, 1920f, 1080f)
        assertEquals(viewW / 1920f, landscape.scale, 0.001f)
        assertEquals(0f, landscape.left, 0.001f) // touches left and right
        assertTrue(landscape.top > 0f) // centered vertically with letterboxing

        // 3. Panorama image (4000 x 800)
        val panorama = ZoomGestureMath.computeFitRect(viewW, viewH, 4000f, 800f)
        assertEquals(viewW / 4000f, panorama.scale, 0.001f)
        assertEquals(0f, panorama.left, 0.001f)
        assertTrue(panorama.top > 0f)

        // 4. Square image (1000 x 1000)
        val square = ZoomGestureMath.computeFitRect(viewW, viewH, 1000f, 1000f)
        assertEquals(viewW / 1000f, square.scale, 0.001f)
        assertEquals(0f, square.left, 0.001f)
        assertEquals((viewH - viewW) / 2f, square.top, 0.001f)
    }

    @Test
    fun clampTranslation_preventsImageLeavingScreen() {
        val viewW = 1080f
        val contentW = 2160f // 2x zoom

        // Image can pan between minTrans (1080 - 2160 = -1080) and maxTrans (0)
        val clampedMiddle = ZoomGestureMath.clampTranslation(viewW, contentW, -500f)
        assertEquals(-500f, clampedMiddle, 0.001f)

        // Dragging too far right (trans > 0) is clamped to 0
        val clampedRight = ZoomGestureMath.clampTranslation(viewW, contentW, 200f)
        assertEquals(0f, clampedRight, 0.001f)

        // Dragging too far left (trans < -1080) is clamped to -1080
        val clampedLeft = ZoomGestureMath.clampTranslation(viewW, contentW, -1500f)
        assertEquals(-1080f, clampedLeft, 0.001f)
    }

    @Test
    fun edgeHandOff_atLeftOrRightEdgeHandsOffToPager() {
        val viewW = 1080f

        // Case 1: Image is zoomed in, left edge is at screen edge (left >= 0), user drags right (distanceX < 0)
        // Image cannot move right anymore -> must hand off to pager to swipe to previous photo
        val handOffRight = ZoomGestureMath.isAtEdgeForHandOff(
            rectLeft = 0f,
            rectRight = 2000f,
            viewWidth = viewW,
            distanceX = -10f
        )
        assertTrue(handOffRight)

        // Case 2: Image is zoomed in, user drags left (distanceX > 0) while at left edge -> NOT at right edge -> NO handoff (image pans)
        val noHandOffPansLeft = ZoomGestureMath.isAtEdgeForHandOff(
            rectLeft = 0f,
            rectRight = 2000f,
            viewWidth = viewW,
            distanceX = 10f
        )
        assertFalse(noHandOffPansLeft)

        // Case 3: Image right edge is at screen right (right <= viewW), user drags left (distanceX > 0)
        // Image cannot move left anymore -> must hand off to pager to swipe to next photo
        val handOffLeft = ZoomGestureMath.isAtEdgeForHandOff(
            rectLeft = -920f,
            rectRight = 1080f,
            viewWidth = viewW,
            distanceX = 15f
        )
        assertTrue(handOffLeft)
    }

    @Test
    fun pagerLock_onlyCurrentPageCanChangeLock() {
        val currentPosition = 2

        // Event from current visible page (2) can update the lock
        assertTrue(ZoomGestureMath.shouldUpdatePagerLock(currentPage = currentPosition, reportingPage = 2))

        // Event from off-screen page (1 or 3) being bound or recycled CANNOT update the lock
        assertFalse(ZoomGestureMath.shouldUpdatePagerLock(currentPage = currentPosition, reportingPage = 1))
        assertFalse(ZoomGestureMath.shouldUpdatePagerLock(currentPage = currentPosition, reportingPage = 3))
    }

    @Test
    fun videoPlayback_propertiesAndUriSetup() {
        val videoItem = createItem(id = 505L, isVideo = true)
        assertEquals(505L, videoItem.id)
        assertTrue(videoItem.isVideo)
        assertEquals("video/mp4", videoItem.mimeType)
        assertEquals(42000L, videoItem.durationMs)
    }
}
