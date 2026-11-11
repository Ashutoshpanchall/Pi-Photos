package com.example.piphotos

sealed class DeleteResult {
    object Success : DeleteResult()
    data class RequiresConfirmation(val intentSender: Any?) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

interface DeleteHandler {
    fun moveToTrash(id: String): Boolean
    fun restoreFromTrash(id: String): Boolean
    fun deletePermanently(id: String): Boolean
    fun emptyTrash(): Int
}

