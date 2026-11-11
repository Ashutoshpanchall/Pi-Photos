package com.example.piphotos

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * Ultra-responsive NestedScrollView that eliminates Android's default 100-180ms tap delay.
 *
 * Overrides `shouldDelayChildPressedState()` to return `false`, ensuring that all child
 * buttons, photo tiles, cards, and interactive rows receive touch down and click events
 * instantly without requiring the user to hold their finger down or risk missed taps.
 */
class ResponsiveScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    override fun shouldDelayChildPressedState(): Boolean = false
}

