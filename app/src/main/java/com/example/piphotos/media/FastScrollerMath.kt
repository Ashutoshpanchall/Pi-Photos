package com.example.piphotos.media

import kotlin.math.roundToInt

object FastScrollerMath {

    fun isHitThumb(touchY: Float, thumbY: Float, thumbHeight: Float, slopPx: Float): Boolean {
        val top = thumbY - slopPx
        val bottom = thumbY + thumbHeight + slopPx
        return touchY in top..bottom
    }

    fun computeScrollDelta(deltaY: Float, trackHeight: Int, scrollRange: Int): Int {
        if (trackHeight <= 0 || scrollRange <= 0) return 0
        return ((deltaY / trackHeight.toFloat()) * scrollRange).roundToInt()
    }

    fun computeThumbTranslationY(scrollOffset: Int, scrollRange: Int, trackHeight: Int): Float {
        if (scrollRange <= 0 || trackHeight <= 0) return 0f
        val fraction = (scrollOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
        return fraction * trackHeight
    }
}

