package com.example.piphotos.media

import kotlin.math.abs

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
}

