package com.example.piphotos

import com.example.piphotos.media.FilmstripCenterHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FilmstripMathTest {

    @Test
    fun computeStartEndPadding_centersFirstAndLastThumbnails() {
        // Typical phone width 1080px, thumbnail width 180px
        val padding = FilmstripCenterHelper.computeStartEndPadding(containerWidth = 1080, itemWidth = 180)
        assertEquals((1080 - 180) / 2, padding) // 450px

        // First item placed at x = 450: its center is 450 + 90 = 540 = 1080 / 2 (dead center)
        assertEquals(540, padding + 180 / 2)

        // Edge case: item wider than container
        val zeroPadding = FilmstripCenterHelper.computeStartEndPadding(containerWidth = 100, itemWidth = 200)
        assertEquals(0, zeroPadding)

        // Edge case: invalid dimensions
        assertEquals(0, FilmstripCenterHelper.computeStartEndPadding(containerWidth = 0, itemWidth = 100))
    }

    @Test
    fun findClosestCenterIndex_identifiesNearestThumbnail() {
        // Thumbnail centers along the horizontal axis
        val centers = listOf(100, 250, 400, 550, 700)
        val viewportCenter = 540

        // 550 is closest to 540 (diff 10) compared to 400 (diff 140)
        val closest = FilmstripCenterHelper.findClosestCenterIndex(centers, viewportCenter)
        assertEquals(3, closest)

        // Target exactly at index 1
        assertEquals(1, FilmstripCenterHelper.findClosestCenterIndex(centers, 250))

        // Target before first item
        assertEquals(0, FilmstripCenterHelper.findClosestCenterIndex(centers, 50))

        // Target after last item
        assertEquals(4, FilmstripCenterHelper.findClosestCenterIndex(centers, 900))

        // Empty list returns -1
        assertEquals(-1, FilmstripCenterHelper.findClosestCenterIndex(emptyList(), viewportCenter))
    }

    @Test
    fun mediaDateFormatting_producesExpectedCinematicHeader() {
        val format = SimpleDateFormat("d MMM yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // 2026-03-03 03:14:00 UTC -> 1772507640000 ms
        val timestamp = 1772507640000L
        val dateObj = Date(timestamp)

        assertEquals("3 Mar 2026", format.format(dateObj))
        assertEquals("3:14 AM", timeFormat.format(dateObj))
        assertEquals("3:14 am", timeFormat.format(dateObj).lowercase(Locale.US))
    }
}

