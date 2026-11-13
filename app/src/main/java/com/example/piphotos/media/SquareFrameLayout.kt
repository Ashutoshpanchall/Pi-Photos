package com.example.piphotos.media

import android.content.Context
import android.os.Trace
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A lightweight FrameLayout that enforces a strict 1:1 square aspect ratio
 * by using the measured width as its height measure spec in a single pass.
 *
 * Eliminates the expensive two-pass layout and measurement overhead of
 * ConstraintLayout with layout_constraintDimensionRatio="1:1" in RecyclerView grids,
 * fitting comfortably within the 8.33ms frame budget of 120Hz displays.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        Trace.beginSection("SquareFrameLayout.onMeasure")
        try {
            // Enforce square bounds based on measured width in a single pass
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        } finally {
            Trace.endSection()
        }
    }
}

