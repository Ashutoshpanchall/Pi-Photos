package com.example.piphotos.media

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object FrostedGlassHelper {

    /**
     * Applies Apple-style frosted glass effect to a chrome overlay surface:
     * - On API 31+: Uses hardware RenderEffect blur (~24dp) with CLAMP tile mode.
     * - On API 30 and below: No blur is set; relies on a ~70% opaque scrim background.
     *
     * Note: Blur is applied once to static chrome containers and NEVER to the media or
     * recomputed during scroll frames, keeping scrolling at a smooth 60fps.
     */
    fun applyGlass(view: View, blurRadiusDp: Float = 24f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val density = view.resources.displayMetrics.density
            val blurPx = (blurRadiusDp * density).coerceAtLeast(1f)
            val effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            view.setRenderEffect(effect)
        }
    }

    /**
     * Clears any active render effect.
     */
    fun clearGlass(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
