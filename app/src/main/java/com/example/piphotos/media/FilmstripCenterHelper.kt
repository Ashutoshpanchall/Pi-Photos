package com.example.piphotos.media

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

object FilmstripCenterHelper {

    /**
     * Computes the horizontal start and end padding required so that both the first item (index 0)
     * and the last item (index N-1) can be centered in the RecyclerView.
     */
    fun computeStartEndPadding(containerWidth: Int, itemWidth: Int): Int {
        if (containerWidth <= 0 || itemWidth <= 0) return 0
        return maxOf(0, (containerWidth - itemWidth) / 2)
    }

    /**
     * Pure math: Finds the index in [itemCenters] closest to [targetCenter].
     */
    fun findClosestCenterIndex(itemCenters: List<Int>, targetCenter: Int): Int {
        if (itemCenters.isEmpty()) return -1
        var closestIdx = 0
        var minDiff = Int.MAX_VALUE
        for (i in itemCenters.indices) {
            val diff = abs(itemCenters[i] - targetCenter)
            if (diff < minDiff) {
                minDiff = diff
                closestIdx = i
            }
        }
        return closestIdx
    }

    /**
     * Determines which adapter position is currently nearest the horizontal center of the [RecyclerView].
     */
    fun findCenterItemPosition(recyclerView: RecyclerView): Int {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return RecyclerView.NO_POSITION
        val rvCenter = recyclerView.width / 2
        if (rvCenter <= 0) return RecyclerView.NO_POSITION

        var closestChild: View? = null
        var minDistance = Int.MAX_VALUE

        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val childCenter = (child.left + child.right) / 2
            val distance = abs(childCenter - rvCenter)
            if (distance < minDistance) {
                minDistance = distance
                closestChild = child
            }
        }

        return closestChild?.let { recyclerView.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
    }

    /**
     * Smoothly scrolls the [RecyclerView] to position [targetPosition] so that its center aligns
     * with the horizontal center of the [RecyclerView].
     */
    fun scrollToCenter(recyclerView: RecyclerView, targetPosition: Int, itemWidthPx: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (targetPosition < 0 || targetPosition >= (recyclerView.adapter?.itemCount ?: 0)) return

        val view = layoutManager.findViewByPosition(targetPosition)
        val rvCenter = recyclerView.width / 2

        if (view != null && rvCenter > 0) {
            val viewCenter = (view.left + view.right) / 2
            val deltaX = viewCenter - rvCenter
            if (deltaX != 0) {
                recyclerView.smoothScrollBy(deltaX, 0)
            }
        } else {
            // If target item is off-screen, calculate offset using start padding
            val padding = computeStartEndPadding(recyclerView.width, itemWidthPx)
            layoutManager.scrollToPositionWithOffset(targetPosition, padding)
        }
    }
}
