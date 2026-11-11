package com.example.piphotos.media

import kotlin.math.abs

data class FitRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float
)

object ZoomGestureMath {

    fun clampTranslation(viewDim: Float, contentDim: Float, trans: Float): Float {
        return if (contentDim > viewDim) {
            val minTrans = viewDim - contentDim
            val maxTrans = 0f
            trans.coerceIn(minTrans, maxTrans)
        } else {
            (viewDim - contentDim) / 2f
        }
    }

    fun shouldLockPager(scale: Float): Boolean {
        return scale > 1.05f
    }

    fun shouldDismissOnSwipeDown(
        scale: Float,
        dy: Float,
        dx: Float,
        touchSlop: Float
    ): Boolean {
        if (scale > 1.05f) {
            // When zoomed in, panning takes priority; dismiss is locked
            return false
        }
        return dy > touchSlop && dy > abs(dx) * 1.3f
    }

    /**
     * Compute fit-center scale and bounds for any image aspect ratio (portrait, landscape, panorama).
     */
    fun computeFitRect(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): FitRect {
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
            return FitRect(0f, 0f, 0f, 0f, 1f)
        }
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f
        return FitRect(left, top, scaledWidth, scaledHeight, scale)
    }

    /**
     * Edge hand-off: checks whether panning in the direction of [distanceX] has reached the image edge
     * and should hand off touch events to the ViewPager.
     */
    fun isAtEdgeForHandOff(
        rectLeft: Float,
        rectRight: Float,
        viewWidth: Float,
        distanceX: Float,
        edgeEps: Float = 1.5f
    ): Boolean {
        val atLeftEdge = rectLeft >= -edgeEps
        val atRightEdge = rectRight <= viewWidth + edgeEps
        return (distanceX < 0f && atLeftEdge) || (distanceX > 0f && atRightEdge)
    }

    /**
     * Checks whether an incoming zoom-state event from [reportingPage] should update the pager lock
     * of [currentPage].
     */
    fun shouldUpdatePagerLock(currentPage: Int, reportingPage: Int): Boolean {
        return currentPage == reportingPage
    }
}
