package com.example.piphotos

import android.content.Context
import com.example.piphotos.media.LocalMediaIndex

object MediaRepository {

    private var source: MediaSource = DeviceMediaSource()
    var backupState: BackupState = BackupState.LocalOnly

    fun setSource(newSource: MediaSource) {
        source = newSource
    }

    fun getSource(): MediaSource = source

    fun getMediaForCategory(category: String): List<MediaItem> = source.getMediaForCategory(category)

    fun getFolders(): List<FolderItem> = source.getFolders()

    fun getCustomAlbums(): List<String> = source.getCustomAlbums()

    fun addCustomAlbum(name: String) = source.addCustomAlbum(name)

    fun renameAlbum(oldName: String, newName: String) = source.renameAlbum(oldName, newName)

    fun deleteAlbum(name: String, preserveMedia: Boolean = true) = source.deleteAlbum(name, preserveMedia)

    fun getItem(id: String): MediaItem? = source.getItem(id)

    fun toggleFavorite(id: String): Boolean {
        val item = source.getItem(id) ?: return false
        item.isFavorite = !item.isFavorite
        return item.isFavorite
    }

    suspend fun toggleFavorite(context: Context, id: Long): Boolean {
        val favRepo = LocalMediaIndex.getInstance(context).favoritesRepo
        val newState = favRepo.toggleFavorite(id)
        val item = source.getItem(id.toString())
        item?.isFavorite = newState
        return newState
    }

    suspend fun isFavorite(context: Context, id: Long): Boolean {
        val favRepo = LocalMediaIndex.getInstance(context).favoritesRepo
        return favRepo.isFavorite(id)
    }

    fun moveToTrash(id: String) {
        val item = source.getItem(id) ?: return
        item.isTrash = true
    }

    fun restoreFromTrash(id: String) {
        val item = source.getItem(id) ?: return
        item.isTrash = false
    }

    fun deletePermanently(id: String): Boolean {
        val item = source.getItem(id) ?: return false
        return true
    }

    fun emptyTrash(): Int {
        val trashed = source.getMediaForCategory("trash")
        trashed.forEach { it.isTrash = false }
        return trashed.size
    }
}
