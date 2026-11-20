package com.example.piphotos.perf

import android.app.Activity
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

object JankTrackerImpl : JankTracker {
    private var currentMetricsState: PerformanceMetricsState? = null

    override fun track(activity: Activity) {
        val window = activity.window ?: return
        try {
            val metricsStateHolder = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
            currentMetricsState = metricsStateHolder.state

            val jankStats = JankStats.createAndTrack(window) { frameData ->
                // 120Hz frame budget is ~8.33ms (8_333_333 ns)
                val durationMs = frameData.frameDurationUiNanos / 1_000_000.0
                if (durationMs > 8.33) {
                    val statesStr = frameData.states.joinToString { "${it.key}=${it.value}" }
                    Log.w("JankStats", "JANK FRAME: ${"%.2f".format(durationMs)} ms (>8.3ms at 120Hz) | isJank=${frameData.isJank} | states=[$statesStr]")
                }
            }
            jankStats.isTrackingEnabled = true

            if (activity is LifecycleOwner) {
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onPause(owner: LifecycleOwner) {
                        jankStats.isTrackingEnabled = false
                    }
                    override fun onResume(owner: LifecycleOwner) {
                        jankStats.isTrackingEnabled = true
                    }
                    override fun onDestroy(owner: LifecycleOwner) {
                        jankStats.isTrackingEnabled = false
                        if (currentMetricsState == metricsStateHolder.state) {
                            currentMetricsState = null
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            Log.e("JankTracker", "Failed to initialize JankStats", e)
        }
    }

    override fun setScrollState(state: String) {
        try {
            currentMetricsState?.putState("ScrollState", state)
        } catch (e: Throwable) {
            // Ignore
        }
    }
}

