package com.example.piphotos

/**
 * Production MediaSource backed by device MediaStore.
 * In Phase G1, acts as the local-only container for real device media.
 * Phase G2 populates this from MediaStore queries via ContentResolver.
 */
class DeviceMediaSource : MediaSource {
    private val localItems = mutableListOf<MediaItem>()
    private val customAlbums = mutableListOf<String>()

    override fun getMediaForCategory(category: String): List<MediaItem> {
        return when (category.lowercase()) {
            "recents" -> localItems.filter { !it.isTrash && !it.isVault }
            "favorites" -> localItems.filter { !it.isTrash && !it.isVault && it.isFavorite }
            "videos" -> localItems.filter { !it.isTrash && !it.isVault && it.isVideo }
            "trash" -> localItems.filter { it.isTrash }
            "vault" -> emptyList() // Hidden until Vault phase
            else -> localItems.filter { !it.isTrash && !it.isVault && it.category.equals(category, ignoreCase = true) }
        }
    }

    override fun getFolders(): List<FolderItem> {
        return localItems
            .groupBy { it.bucketId }
            .filter { it.key.isNotEmpty() }
            .map { (bucketId, items) ->
                val first = items.first()
                FolderItem(
                    bucketId = bucketId,
                    name = first.bucketName.ifEmpty { "Folder" },
                    coverUri = first.contentUri,
                    coverDrawableRes = first.drawableRes,
                    itemCount = items.size,
                    sizeBytes = items.sumOf { it.sizeBytes }
                )
            }
    }

    override fun getCustomAlbums(): List<String> = customAlbums.toList()

    override fun getItem(id: String): MediaItem? = localItems.find { it.id == id }

    override fun addCustomAlbum(name: String) {
        if (!customAlbums.contains(name)) customAlbums.add(name)
    }

    override fun renameAlbum(oldName: String, newName: String) {
        val index = customAlbums.indexOf(oldName)
        if (index != -1) {
            customAlbums[index] = newName
            localItems.filter { it.category == oldName }.forEach { it.category = newName }
        }
    }

    override fun deleteAlbum(name: String, preserveMedia: Boolean) {
        customAlbums.remove(name)
        if (preserveMedia) {
            localItems.filter { it.category == name }.forEach { it.category = "Recents" }
        } else {
            localItems.filter { it.category == name }.forEach { it.isTrash = true }
        }
    }

    fun setMedia(items: List<MediaItem>) {
        localItems.clear()
        localItems.addAll(items)
    }

    fun getAllItems(): List<MediaItem> = localItems.toList()
}

