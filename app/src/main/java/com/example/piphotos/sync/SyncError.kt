package com.example.piphotos.sync

import com.example.piphotos.net.HttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException

sealed class SyncError(val message: String, val canRetry: Boolean) {
    data class Unreachable(val msg: String) : SyncError(msg, canRetry = true)
    data class Timeout(val msg: String) : SyncError(msg, canRetry = true)
    data class Auth(val msg: String) : SyncError(msg, canRetry = false)
    data class DiskFull(val msg: String) : SyncError(msg, canRetry = false)
    data class QuotaExceeded(val msg: String) : SyncError(msg, canRetry = false)
    data class FileUnreadable(val msg: String) : SyncError(msg, canRetry = false)
    data class ServerError(val code: Int, val msg: String) : SyncError(msg, canRetry = (code in 500..599 && code != 507))

    companion object {
        fun fromThrowable(t: Throwable): SyncError {
            return when (t) {
                is ConnectException, is NoRouteToHostException, is UnknownHostException ->
                    Unreachable("Storage node unreachable. Ensure phone and node are on the same Wi-Fi.")
                is SocketTimeoutException ->
                    Timeout("Transfer timed out. Connection was too slow or dropped.")
                is CertificateException ->
                    Auth("Certificate validation error: ${t.message}")
                is FileNotFoundException, is SecurityException ->
                    FileUnreadable("Could not read local media file: ${t.localizedMessage}")
                is HttpException -> {
                    when (t.code) {
                        401 -> Auth("This device has been removed from the server.")
                        403 -> Auth("Your account has been turned off by the vault owner.")
                        507 -> {
                            if (t.message?.contains("quota", ignoreCase = true) == true) {
                                QuotaExceeded("User storage quota exceeded on storage node.")
                            } else {
                                DiskFull("Storage node disk is full.")
                            }
                        }
                        in 500..599 -> ServerError(t.code, "Node internal error (HTTP ${t.code}): ${t.message}")
                        else -> ServerError(t.code, "Node rejected upload (HTTP ${t.code}): ${t.message}")
                    }
                }
                is IOException -> {
                    val msg = t.message ?: ""
                    if (msg.contains("quota", ignoreCase = true)) {
                        QuotaExceeded("Storage quota exceeded.")
                    } else if (msg.contains("disk", ignoreCase = true) || msg.contains("full", ignoreCase = true)) {
                        DiskFull("Storage node disk is full.")
                    } else {
                        Unreachable("I/O error during transfer: $msg")
                    }
                }
                else -> ServerError(-1, t.localizedMessage ?: "Unexpected error during media transfer.")
            }
        }
    }
}
