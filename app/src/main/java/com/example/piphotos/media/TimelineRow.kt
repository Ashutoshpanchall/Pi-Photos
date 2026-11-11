package com.example.piphotos.media

import kotlin.math.absoluteValue

sealed class TimelineRow {
    abstract val stableId: Long

    data class HeaderRow(
        val key: String,
        val title: String,
        val subtitle: String? = null
    ) : TimelineRow() {
        override val stableId: Long = -(key.hashCode().toLong().absoluteValue + 1L)
    }

    data class MediaRow(
        val item: LocalMediaItem
    ) : TimelineRow() {
        override val stableId: Long = item.id
    }
}

enum class TimelineGroupMode(val spanCount: Int) {
    DAY(spanCount = 3),
    MONTH(spanCount = 4),
    YEAR(spanCount = 6)
}

enum class TimelineCategoryFilter(val label: String) {
    ALL("All"),
    CAMERA("Camera"),
    SCREENSHOTS("Screenshots"),
    VIDEOS("Videos"),
    FAVORITES("Favourites")
}

