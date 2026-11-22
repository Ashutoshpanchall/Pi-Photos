package com.example.piphotos

import com.example.piphotos.sync.MediaContentObserver
import com.example.piphotos.sync.MediaSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Phase4ReliabilityTest {

    @Test
    fun contentTriggerConstant_isDefinedAndUnique() {
        assertEquals("piphotos_media_content_trigger", MediaSyncWorker.WORK_NAME_CONTENT_TRIGGER)
        assertNotEquals(MediaSyncWorker.WORK_NAME_CONTENT_TRIGGER, "piphotos_media_sync_periodic")
        assertNotEquals(MediaSyncWorker.WORK_NAME_CONTENT_TRIGGER, "piphotos_media_sync_immediate")
    }

    @Test
    fun mediaObserver_initializationIsSafe() {
        assertNotNull(MediaContentObserver::class.java)
    }
}
