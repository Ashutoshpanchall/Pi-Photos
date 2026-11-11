package com.example.piphotos.media

sealed class TimelineUiState {
    object Loading : TimelineUiState()
    object NoPermission : TimelineUiState()
    data class Empty(val filter: TimelineCategoryFilter) : TimelineUiState()
    data class Ready(
        val items: List<LocalMediaItem>,
        val filter: TimelineCategoryFilter,
        val revision: Long
    ) : TimelineUiState()
}

object TimelineStateMachine {

    fun computeState(
        hasPermission: Boolean,
        indexState: IndexState,
        filter: TimelineCategoryFilter,
        favoriteIds: Set<Long>
    ): TimelineUiState {
        if (!hasPermission) {
            return TimelineUiState.NoPermission
        }

        return when (indexState) {
            is IndexState.Loading -> TimelineUiState.Loading
            is IndexState.Ready -> {
                val filtered = TimelineGrouper.filterItems(indexState.items, filter, favoriteIds)
                if (filtered.isEmpty()) {
                    TimelineUiState.Empty(filter)
                } else {
                    TimelineUiState.Ready(filtered, filter, indexState.revision)
                }
            }
        }
    }

    fun getEmptyTitle(filter: TimelineCategoryFilter): String {
        return when (filter) {
            TimelineCategoryFilter.ALL -> "No Photos Found"
            TimelineCategoryFilter.CAMERA -> "No camera photos"
            TimelineCategoryFilter.SCREENSHOTS -> "No screenshots"
            TimelineCategoryFilter.VIDEOS -> "No videos"
            TimelineCategoryFilter.FAVORITES -> "No favourites yet"
        }
    }

    fun getEmptySubtitle(filter: TimelineCategoryFilter): String {
        return when (filter) {
            TimelineCategoryFilter.ALL -> "No pictures or videos were detected in your device storage."
            TimelineCategoryFilter.CAMERA -> "Photos taken with your camera will appear here."
            TimelineCategoryFilter.SCREENSHOTS -> "Screenshots you capture will appear here."
            TimelineCategoryFilter.VIDEOS -> "Recorded or downloaded videos will appear here."
            TimelineCategoryFilter.FAVORITES -> "Tap the star icon on photos to add them to your favourites."
        }
    }
}

