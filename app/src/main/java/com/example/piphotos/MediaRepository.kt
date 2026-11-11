package com.example.piphotos

object MediaRepository {

    private var source: MediaSource = DeviceMediaSource()
    private var deleteHandler: DeleteHandler = LocalDeleteHandler(source)
    var backupState: BackupState = BackupState.LocalOnly

    fun setSource(newSource: MediaSource, newDeleteHandler: DeleteHandler = LocalDeleteHandler(newSource)) {
        source = newSource
        deleteHandler = newDeleteHandler
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

    fun moveToTrash(id: String) {
        deleteHandler.moveToTrash(id)
    }

    fun restoreFromTrash(id: String) {
        deleteHandler.restoreFromTrash(id)
    }

    fun deletePermanently(id: String): Boolean = deleteHandler.deletePermanently(id)

    fun emptyTrash(): Int = deleteHandler.emptyTrash()
}
