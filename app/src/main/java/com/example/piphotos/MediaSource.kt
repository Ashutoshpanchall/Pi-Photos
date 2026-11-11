package com.example.piphotos

interface MediaSource {
    fun getMediaForCategory(category: String): List<MediaItem>
    fun getFolders(): List<FolderItem>
    fun getCustomAlbums(): List<String>
    fun getItem(id: String): MediaItem?
    fun addCustomAlbum(name: String)
    fun renameAlbum(oldName: String, newName: String)
    fun deleteAlbum(name: String, preserveMedia: Boolean = true)
}

