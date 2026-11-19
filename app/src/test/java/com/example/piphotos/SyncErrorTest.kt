package com.example.piphotos

import com.example.piphotos.net.HttpException
import com.example.piphotos.sync.SyncError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException

class SyncErrorTest {

    @Test
    fun connectException_mapsToUnreachableAndCanRetry() {
        val ex = ConnectException("Connection refused")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.Unreachable)
        assertTrue(err.canRetry)
    }

    @Test
    fun socketTimeoutException_mapsToTimeoutAndCanRetry() {
        val ex = SocketTimeoutException("Read timed out")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.Timeout)
        assertTrue(err.canRetry)
    }

    @Test
    fun http401_mapsToAuthAndCannotRetry() {
        val ex = HttpException(401, "Unauthorized")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.Auth)
        assertFalse(err.canRetry)
    }

    @Test
    fun http507Quota_mapsToQuotaExceededAndCannotRetry() {
        val ex = HttpException(507, "Upload would exceed your storage quota")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.QuotaExceeded)
        assertFalse(err.canRetry)
    }

    @Test
    fun http507Disk_mapsToDiskFullAndCannotRetry() {
        val ex = HttpException(507, "Disk reserve breached")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.DiskFull)
        assertFalse(err.canRetry)
    }

    @Test
    fun fileNotFoundException_mapsToFileUnreadableAndCannotRetry() {
        val ex = FileNotFoundException("No such file")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.FileUnreadable)
        assertFalse(err.canRetry)
    }

    @Test
    fun http500_mapsToServerErrorAndCanRetry() {
        val ex = HttpException(500, "Internal Server Error")
        val err = SyncError.fromThrowable(ex)
        assertTrue(err is SyncError.ServerError)
        assertTrue(err.canRetry)
        assertEquals(500, (err as SyncError.ServerError).code)
    }
}
