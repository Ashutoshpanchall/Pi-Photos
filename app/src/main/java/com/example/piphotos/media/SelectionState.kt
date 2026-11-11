package com.example.piphotos.media

/**
 * Pure state machine for timeline selection mode.
 * Completely testable on pure JVM without Android framework dependencies.
 */
class SelectionState {

    var isSelectionMode: Boolean = false
        private set

    private val _selectedIds = mutableSetOf<Long>()
    val selectedIds: Set<Long> get() = _selectedIds.toSet()

    sealed class Result {
        data class SingleItemToggled(val id: Long, val isSelected: Boolean, val remainingCount: Int) : Result()
        data class SelectionExited(val needsFullRefresh: Boolean) : Result()
        data class AllSelected(val count: Int) : Result()
    }

    fun setSelectionMode(enabled: Boolean): Result {
        if (isSelectionMode == enabled) return Result.SelectionExited(false)
        isSelectionMode = enabled
        if (!enabled) {
            _selectedIds.clear()
            return Result.SelectionExited(true)
        }
        return Result.SelectionExited(true)
    }

    fun toggle(id: Long): Result {
        if (!isSelectionMode) {
            isSelectionMode = true
        }

        val wasSelected = _selectedIds.remove(id)
        if (!wasSelected) {
            _selectedIds.add(id)
            return Result.SingleItemToggled(id, isSelected = true, remainingCount = _selectedIds.size)
        }

        if (_selectedIds.isEmpty()) {
            isSelectionMode = false
            return Result.SelectionExited(needsFullRefresh = true)
        }

        return Result.SingleItemToggled(id, isSelected = false, remainingCount = _selectedIds.size)
    }

    fun selectAll(allIds: List<Long>): Result {
        if (allIds.isEmpty()) return Result.SelectionExited(false)
        _selectedIds.clear()
        _selectedIds.addAll(allIds)
        isSelectionMode = true
        return Result.AllSelected(_selectedIds.size)
    }

    fun clear(): Result {
        return setSelectionMode(false)
    }
}
