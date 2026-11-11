package com.example.piphotos

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object WindowInsetsHelper {

    /**
     * Physical status bar height fallback from Android system resources.
     * Guaranteed to return a non-zero pixel height even before WindowInsets dispatch.
     */
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        if (result == 0) {
            result = (32 * context.resources.displayMetrics.density).toInt()
        }
        return result
    }

    /**
     * Applies system window insets safely on the root view and distributes them to top and bottom bars.
     * 
     * Layer 1: Synchronously sets statusBarSpacer height (or pads topBar) with physical status bar height
     * so headers are NEVER at Y=0 or cut off on initial render.
     * 
     * Layer 2: Reactive WindowInsetsListener updates dynamically when notch/cutout/status bar insets change.
     * 
     * Layer 3: Controls status bar icon contrast (Dark icons in Light mode, White in AMOLED dark mode).
     */
    fun applyEdgeToEdge(
        activity: Activity? = null,
        rootView: View,
        statusBarSpacer: View? = null,
        topBar: View? = null,
        bottomBar: View? = null,
        scrollableView: View? = null,
        extraTopDp: Int = 12,
        extraBottomDp: Int = 12
    ) {
        val density = rootView.resources.displayMetrics.density
        val extraTopPx = (extraTopDp * density).toInt()
        val extraBottomPx = (extraBottomDp * density).toInt()

        // LAYER 1: Synchronous initial status bar height
        val initialStatusHeight = getStatusBarHeight(rootView.context)

        statusBarSpacer?.let { spacer ->
            val lp = spacer.layoutParams
            lp.height = initialStatusHeight
            spacer.layoutParams = lp
        } ?: run {
            topBar?.let {
                it.setPadding(
                    it.paddingLeft,
                    initialStatusHeight + extraTopPx,
                    it.paddingRight,
                    extraTopPx
                )
            }
        }

        // LAYER 2: Reactive WindowInsets listener for dynamic display cutouts and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val effectiveStatusBarTop = maxOf(statusBars, displayCutout, systemBars, initialStatusHeight)

            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val systemNavBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val effectiveNavBarBottom = maxOf(navBars, systemNavBars)

            statusBarSpacer?.let { spacer ->
                val lp = spacer.layoutParams
                if (lp.height != effectiveStatusBarTop) {
                    lp.height = effectiveStatusBarTop
                    spacer.layoutParams = lp
                }
            } ?: run {
                topBar?.let {
                    it.setPadding(
                        it.paddingLeft,
                        effectiveStatusBarTop + extraTopPx,
                        it.paddingRight,
                        extraTopPx
                    )
                }
            }

            bottomBar?.let {
                it.setPadding(
                    it.paddingLeft,
                    it.paddingTop,
                    it.paddingRight,
                    effectiveNavBarBottom + extraBottomPx
                )
            }

            scrollableView?.let {
                it.setPadding(
                    it.paddingLeft,
                    it.paddingTop,
                    it.paddingRight,
                    effectiveNavBarBottom + extraBottomPx
                )
            }

            insets
        }

        // Force layout pass to trigger insets immediately
        ViewCompat.requestApplyInsets(rootView)

        // LAYER 3: Dark/Light status bar icon contrast
        activity?.let { act ->
            val isDark = ThemeManager.isDarkMode(act)
            WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
}
