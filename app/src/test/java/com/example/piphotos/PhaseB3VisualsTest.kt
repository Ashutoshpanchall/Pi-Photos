package com.example.piphotos

import com.example.piphotos.net.DeviceFoldersResponse
import com.example.piphotos.net.ServerFolderInfo
import com.example.piphotos.net.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PhaseB3VisualsTest {

    @Test
    fun serverStatus_holdsMetricsAccurately() {
        val total = 100_000_000_000L
        val free = 60_000_000_000L
        val reserved = 10_000_000_000L
        val used = total - free

        val quota = 50_000_000_000L
        val uUsed = 12_000_000_000L
        val uFree = quota - uUsed
        val uCount = 142

        val status = ServerStatus(
            diskTotal = total,
            diskFree = free,
            diskReserved = reserved,
            diskUsed = used,
            userQuota = quota,
            userUsed = uUsed,
            userFree = uFree,
            userFileCount = uCount
        )

        assertEquals(100_000_000_000L, status.diskTotal)
        assertEquals(60_000_000_000L, status.diskFree)
        assertEquals(10_000_000_000L, status.diskReserved)
        assertEquals(40_000_000_000L, status.diskUsed)
        assertEquals(50_000_000_000L, status.userQuota)
        assertEquals(12_000_000_000L, status.userUsed)
        assertEquals(38_000_000_000L, status.userFree)
        assertEquals(142, status.userFileCount)
    }

    @Test
    fun deviceFolders_constructsModelAccurately() {
        val folderList = listOf(
            ServerFolderInfo(
                name = "Camera",
                itemCount = 1240,
                totalBytes = 3_500_000_000L
            ),
            ServerFolderInfo(
                name = "Screenshots",
                itemCount = 85,
                totalBytes = 45_000_000L
            )
        )

        val resp = DeviceFoldersResponse(
            deviceId = "dev-456",
            deviceName = "Pixel Phone",
            folders = folderList
        )

        assertEquals("dev-456", resp.deviceId)
        assertEquals("Pixel Phone", resp.deviceName)
        assertEquals(2, resp.folders.size)
        assertEquals("Camera", resp.folders[0].name)
        assertEquals(1240, resp.folders[0].itemCount)
        assertEquals(3_500_000_000L, resp.folders[0].totalBytes)
        assertEquals("Screenshots", resp.folders[1].name)
        assertEquals(85, resp.folders[1].itemCount)
        assertEquals(45_000_000L, resp.folders[1].totalBytes)
    }

    @Test
    fun formatBytes_convertsUnitsAccurately() {
        assertEquals("0 B", BackupFragment.formatBytes(0))
        assertEquals("500.0 B", BackupFragment.formatBytes(500))
        assertEquals("1.0 KB", BackupFragment.formatBytes(1024))
        assertEquals("1.5 MB", BackupFragment.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", BackupFragment.formatBytes(2L * 1024 * 1024 * 1024))
    }
}
