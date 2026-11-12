package com.example.piphotos.media

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class TimelineUiState {
    object Loading : TimelineUiState()
    object NoPermission : TimelineUiState()
    data class Empty(
        val filter: TimelineCategoryFilter,
        val searchQuery: String = ""
    ) : TimelineUiState()
    data class Ready(
        val items: List<LocalMediaItem>,
        val filter: TimelineCategoryFilter,
        val revision: Long,
        val searchQuery: String = ""
    ) : TimelineUiState()
}

object TimelineStateMachine {

    fun computeState(
        hasPermission: Boolean,
        indexState: IndexState,
        filter: TimelineCategoryFilter,
        favoriteIds: Set<Long>,
        searchQuery: String = ""
    ): TimelineUiState {
        if (!hasPermission) {
            return TimelineUiState.NoPermission
        }

        return when (indexState) {
            is IndexState.Loading -> TimelineUiState.Loading
            is IndexState.Ready -> {
                val categoryFiltered = TimelineGrouper.filterItems(indexState.items, filter, favoriteIds)
                val finalItems = if (searchQuery.isBlank()) {
                    categoryFiltered
                } else {
                    filterByQuery(categoryFiltered, searchQuery, favoriteIds)
                }
                if (finalItems.isEmpty()) {
                    TimelineUiState.Empty(filter, searchQuery)
                } else {
                    TimelineUiState.Ready(finalItems, filter, indexState.revision, searchQuery)
                }
            }
        }
    }

    fun filterByQuery(
        items: List<LocalMediaItem>,
        query: String,
        favoriteIds: Set<Long>
    ): List<LocalMediaItem> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return items

        val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
        val shortMonthFormat = SimpleDateFormat("MMM", Locale.getDefault())
        val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())

        return items.filter { item ->
            // Media type keyword
            if (q == "video" || q == "videos") return@filter item.isVideo
            if (q == "photo" || q == "photos" || q == "image" || q == "images") return@filter !item.isVideo
            if (q == "favorite" || q == "favorites" || q == "fav") return@filter favoriteIds.contains(item.id)

            // Bucket / album name match
            if (item.bucketName.lowercase(Locale.getDefault()).contains(q)) return@filter true

            // Mime type match
            if (item.mimeType.lowercase(Locale.getDefault()).contains(q)) return@filter true

            // Date match (month name, short month, year)
            val date = Date(item.effectiveDateMs)
            if (yearFormat.format(date).contains(q)) return@filter true
            if (monthFormat.format(date).lowercase(Locale.getDefault()).contains(q)) return@filter true
            if (shortMonthFormat.format(date).lowercase(Locale.getDefault()).contains(q)) return@filter true

            false
        }
    }

    fun getEmptyTitle(filter: TimelineCategoryFilter, searchQuery: String = ""): String {
        if (searchQuery.isNotBlank()) {
            return "No Results Found"
        }
        return when (filter) {
            TimelineCategoryFilter.ALL -> "No Photos Found"
            TimelineCategoryFilter.CAMERA -> "No camera photos"
            TimelineCategoryFilter.SCREENSHOTS -> "No screenshots"
            TimelineCategoryFilter.VIDEOS -> "No videos"
            TimelineCategoryFilter.FAVORITES -> "No favourites yet"
        }
    }

    fun getEmptySubtitle(filter: TimelineCategoryFilter, searchQuery: String = ""): String {
        if (searchQuery.isNotBlank()) {
            return "No photos or videos matched \"$searchQuery\"."
        }
        return when (filter) {
            TimelineCategoryFilter.ALL -> "No pictures or videos were detected in your device storage."
            TimelineCategoryFilter.CAMERA -> "Photos taken with your camera will appear here."
            TimelineCategoryFilter.SCREENSHOTS -> "Screenshots you capture will appear here."
            TimelineCategoryFilter.VIDEOS -> "Recorded or downloaded videos will appear here."
            TimelineCategoryFilter.FAVORITES -> "Tap the star icon on photos to add them to your favourites."
        }
    }
}
