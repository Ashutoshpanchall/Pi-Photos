package com.example.piphotos.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/**
 * High-performance, streaming SHA-256 content-hash calculator for content-addressable
 * deduplication on storage nodes.
 *
 * Streams bytes in 64KB chunks to keep memory allocations negligible even for multi-gigabyte 4K videos.
 */
object MediaHashCalculator {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    suspend fun computeSha256(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                computeSha256(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun computeSha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hashBytes = digest.digest()
        return bytesToHex(hashBytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_ARRAY[v ushr 4]
            hexChars[i * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    private val HEX_ARRAY = "0123456789abcdef".toCharArray()
}

