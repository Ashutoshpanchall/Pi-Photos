package com.example.piphotos

import android.content.IntentSender

sealed class DeleteResult {
    object Success : DeleteResult()
    data class RequiresConfirmation(val intentSender: IntentSender) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

data class TrashedMediaItem(
    val id: Long,
    val isVideo: Boolean,
    val mimeType: String,
    val dateModifiedSec: Long,
    val dateExpiresSec: Long,
    val daysRemaining: Int,
    val sizeBytes: Long
)

interface DeleteHandler {
    suspend fun trash(ids: List<Long>): DeleteResult
    suspend fun restore(ids: List<Long>): DeleteResult
    suspend fun deletePermanently(ids: List<Long>): DeleteResult
    suspend fun listBin(): List<TrashedMediaItem>
    suspend fun getBinCount(): Int
    suspend fun emptyBin(): DeleteResult
}
