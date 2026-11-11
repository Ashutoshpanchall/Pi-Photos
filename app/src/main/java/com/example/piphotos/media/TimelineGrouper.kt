package com.example.piphotos.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimelineGrouper {

    fun filterItems(
        items: List<LocalMediaItem>,
        filter: TimelineCategoryFilter,
        favoriteIds: Set<Long> = emptySet()
    ): List<LocalMediaItem> {
        return when (filter) {
            TimelineCategoryFilter.ALL -> items
            TimelineCategoryFilter.CAMERA -> items.filter { it.bucketName.equals("Camera", ignoreCase = true) }
            TimelineCategoryFilter.SCREENSHOTS -> items.filter {
                it.bucketName.contains("Screenshot", ignoreCase = true) || it.mimeType.contains("screenshot", ignoreCase = true)
            }
            TimelineCategoryFilter.VIDEOS -> items.filter { it.isVideo }
            TimelineCategoryFilter.FAVORITES -> items.filter { favoriteIds.contains(it.id) }
        }
    }

    fun group(
        items: List<LocalMediaItem>,
        mode: TimelineGroupMode,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        nowInstant: Instant = Instant.now()
    ): List<TimelineRow> {
        if (items.isEmpty()) return emptyList()

        val today = LocalDate.ofInstant(nowInstant, zoneId)
        val yesterday = today.minusDays(1)

        val daySameYearFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
        val dayOtherYearFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
        val monthSameYearFormatter = DateTimeFormatter.ofPattern("MMMM", locale)
        val monthOtherYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val yearFormatter = DateTimeFormatter.ofPattern("yyyy", locale)

        val rows = ArrayList<TimelineRow>(items.size + items.size / 4)
        val sorted = items.sortedByDescending { it.effectiveDateMs }

        when (mode) {
            TimelineGroupMode.DAY -> {
                var currentKey: String? = null
                for (item in sorted) {
                    val itemDate = LocalDate.ofInstant(Instant.ofEpochMilli(item.effectiveDateMs), zoneId)
                    val key = itemDate.toString()
                    if (key != currentKey) {
                        currentKey = key
                        val title = when {
                            itemDate == today -> "Today"
                            itemDate == yesterday -> "Yesterday"
                            itemDate.year == today.year -> itemDate.format(daySameYearFormatter)
                            else -> itemDate.format(dayOtherYearFormatter)
                        }
                        rows.add(TimelineRow.HeaderRow(key = key, title = title))
                    }
                    rows.add(TimelineRow.MediaRow(item))
                }
            }
            TimelineGroupMode.MONTH -> {
                var currentKey: String? = null
                for (item in sorted) {
                    val itemDate = LocalDate.ofInstant(Instant.ofEpochMilli(item.effectiveDateMs), zoneId)
                    val key = "${itemDate.year}-${itemDate.monthValue}"
                    if (key != currentKey) {
                        currentKey = key
                        val title = if (itemDate.year == today.year) {
                            itemDate.format(monthSameYearFormatter)
                        } else {
                            itemDate.format(monthOtherYearFormatter)
                        }
                        rows.add(TimelineRow.HeaderRow(key = key, title = title))
                    }
                    rows.add(TimelineRow.MediaRow(item))
                }
            }
            TimelineGroupMode.YEAR -> {
                var currentKey: String? = null
                for (item in sorted) {
                    val itemDate = LocalDate.ofInstant(Instant.ofEpochMilli(item.effectiveDateMs), zoneId)
                    val key = "${itemDate.year}"
                    if (key != currentKey) {
                        currentKey = key
                        val title = itemDate.format(yearFormatter)
                        rows.add(TimelineRow.HeaderRow(key = key, title = title))
                    }
                    rows.add(TimelineRow.MediaRow(item))
                }
            }
        }
        return rows
    }
}

