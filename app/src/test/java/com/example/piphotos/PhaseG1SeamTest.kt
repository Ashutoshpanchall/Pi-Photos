package com.example.piphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhaseG1SeamTest {

    @Before
    fun setUp() {
        // Reset MediaRepository to clean DeviceMediaSource baseline
        MediaRepository.setSource(DeviceMediaSource())
        MediaRepository.backupState = BackupState.LocalOnly
        NodeRepository.clearUsers()
        NodeRepository.clearPendingInvites()
        NodeRepository.totalDiskGB = 0.0
        NodeRepository.systemReserveGB = 0.0
        NodeRepository.nodeName = ""
        NodeRepository.deviceModel = ""
        NodeRepository.remoteNodeDomain = ""
    }

    @Test
    fun defaultProductionMediaSource_startsEmpty_inLocalOnlyMode() {
        val recents = MediaRepository.getMediaForCategory("recents")
        val favorites = MediaRepository.getMediaForCategory("favorites")
        val customAlbums = MediaRepository.getCustomAlbums()

        // Phase G1 clean slate requirement: no fake mock items in production baseline
        assertTrue(recents.isEmpty())
        assertTrue(favorites.isEmpty())
        assertTrue(customAlbums.isEmpty())
        assertEquals(BackupState.LocalOnly, MediaRepository.backupState)
    }

    @Test
    fun deviceMediaSource_storesAndFiltersItems() {
        val deviceSource = DeviceMediaSource()
        val testPhotos = listOf(
            MediaItem(id = "p1", title = "Photo 1", date = "Today", category = "Recents", sizeBytes = 1024L, bucketId = "b1", bucketName = "Camera"),
            MediaItem(id = "p2", title = "Photo 2", date = "Today", category = "Recents", isFavorite = true, sizeBytes = 2048L, bucketId = "b1", bucketName = "Camera"),
            MediaItem(id = "v1", title = "Video 1", date = "Yesterday", category = "Recents", isVideo = true, duration = "1:00", sizeBytes = 10000L, bucketId = "b2", bucketName = "WhatsApp Video")
        )
        deviceSource.setMedia(testPhotos)
        MediaRepository.setSource(deviceSource)

        val recents = MediaRepository.getMediaForCategory("recents")
        assertEquals(3, recents.size)

        val favorites = MediaRepository.getMediaForCategory("favorites")
        assertEquals(1, favorites.size)
        assertEquals("p2", favorites[0].id)

        val videos = MediaRepository.getMediaForCategory("videos")
        assertEquals(1, videos.size)
        assertEquals("v1", videos[0].id)

        // Folders aggregation
        val folders = MediaRepository.getFolders()
        assertEquals(2, folders.size)
        val cameraFolder = folders.find { it.bucketId == "b1" }
        assertNotNull(cameraFolder)
        assertEquals("Camera", cameraFolder?.name)
        assertEquals(2, cameraFolder?.itemCount)
        assertEquals(3072L, cameraFolder?.sizeBytes)
    }

    @Test
    fun localDeleteHandler_seamMovesToTrash_andRestores() {
        val deviceSource = DeviceMediaSource()
        val item = MediaItem(id = "del1", title = "Trash Test", date = "Today", category = "Recents")
        deviceSource.setMedia(listOf(item))
        MediaRepository.setSource(deviceSource)

        assertEquals(1, MediaRepository.getMediaForCategory("recents").size)
        assertEquals(0, MediaRepository.getMediaForCategory("trash").size)

        // Move to trash
        MediaRepository.moveToTrash("del1")
        assertEquals(0, MediaRepository.getMediaForCategory("recents").size)
        assertEquals(1, MediaRepository.getMediaForCategory("trash").size)
        assertTrue(MediaRepository.getItem("del1")?.isTrash == true)

        // Restore
        MediaRepository.restoreFromTrash("del1")
        assertEquals(1, MediaRepository.getMediaForCategory("recents").size)
        assertEquals(0, MediaRepository.getMediaForCategory("trash").size)
        assertFalse(MediaRepository.getItem("del1")?.isTrash == true)
    }

    @Test
    fun productionNodeRepository_hasNoHardcodedUsersOrHardware_inLocalOnly() {
        // Assert NodeRepository starts unconfigured in main
        assertTrue(NodeRepository.getUsers().isEmpty())
        assertTrue(NodeRepository.getPendingInvites().isEmpty())
        assertEquals(0.0, NodeRepository.totalDiskGB, 0.01)
        assertEquals("", NodeRepository.nodeName)
        assertEquals("", NodeRepository.remoteNodeDomain)
    }

    @Test
    fun demoNodeData_andDemoMediaSource_existInDebugForTesting() {
        // Verify debug fixtures load cleanly without polluting main
        DemoNodeData.loadDemoBaseline()
        assertEquals(4, NodeRepository.getUsers().size)
        assertEquals(250.0, NodeRepository.totalDiskGB, 0.01)
        assertEquals("pizerow-vault", NodeRepository.nodeName)

        val demoSource = DemoMediaSource()
        val demoRecents = demoSource.getMediaForCategory("recents")
        assertTrue(demoRecents.isNotEmpty())
        assertEquals(4, demoSource.getCustomAlbums().size)
    }
}
