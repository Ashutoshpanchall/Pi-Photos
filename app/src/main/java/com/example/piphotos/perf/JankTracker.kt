package com.example.piphotos.perf

import android.app.Activity

interface JankTracker {
    fun track(activity: Activity)
    fun setScrollState(state: String)

    companion object {
        fun getInstance(): JankTracker? {
            return try {
                val clazz = Class.forName("com.example.piphotos.perf.JankTrackerImpl")
                clazz.getField("INSTANCE").get(null) as? JankTracker
            } catch (e: Throwable) {
                null
            }
        }
    }
}
