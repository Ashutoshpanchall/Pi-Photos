package com.example.piphotos

import android.os.SystemClock
import android.os.Trace
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fast scroll benchmark that exercises RecyclerView grid flings under Perfetto tracing.
 * Captures section traces for SquareFrameLayout.onMeasure, ThumbnailLoader.decode, and ThumbnailLoader.prefetch.
 */
@RunWith(AndroidJUnit4::class)
class FastScrollBenchmarkTest {

    @Test
    fun testFastScrollPerformance() {
        Trace.beginSection("FastScrollBenchmark.launchActivity")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Trace.endSection()

        try {
            // Allow initial index load and first layout pass
            SystemClock.sleep(1500)

            Trace.beginSection("FastScrollBenchmark.fastScrollFling")
            // Perform rapid consecutive flings to simulate aggressive user flings
            for (i in 0 until 5) {
                onView(withId(R.id.timelineView)).perform(swipeUp())
                SystemClock.sleep(150)
            }
            Trace.endSection()

            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        } finally {
            scenario.close()
        }
    }
}

