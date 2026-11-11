package com.example.piphotos

import android.content.IntentSender

sealed class DeleteResult {
    object Success : DeleteResult()
    data class RequiresConfirmation(val intentSender: IntentSender) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

interface DeleteHandler {
    suspend fun trash(ids: List<Long>): DeleteResult
    fun moveToTrash(id: String): Boolean
    fun restoreFromTrash(id: String): Boolean
    fun deletePermanently(id: String): Boolean
    fun emptyTrash(): Int
}
