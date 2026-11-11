package com.example.piphotos

class LocalDeleteHandler(private val mediaSource: MediaSource) : DeleteHandler {
    override fun moveToTrash(id: String): Boolean {
        val item = mediaSource.getItem(id) ?: return false
        item.isTrash = true
        return true
    }

    override fun restoreFromTrash(id: String): Boolean {
        val item = mediaSource.getItem(id) ?: return false
        item.isTrash = false
        return true
    }

    override fun deletePermanently(id: String): Boolean {
        // Wired to MediaStore.createDeleteRequest in Phase G5
        return true
    }

    override fun emptyTrash(): Int {
        val trashed = mediaSource.getMediaForCategory("trash")
        trashed.forEach { it.isTrash = false }
        return trashed.size
    }
}

