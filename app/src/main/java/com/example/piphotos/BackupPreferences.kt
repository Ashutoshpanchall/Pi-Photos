package com.example.piphotos

import android.content.Context
import android.content.SharedPreferences

object BackupPreferences {
    private const val PREFS_NAME = "piphotos_backup_settings"

    private const val KEY_WIFI_ONLY = "pref_wifi_only"
    private const val KEY_CHARGING_ONLY = "pref_charging_only"
    private const val KEY_INCLUDE_VIDEOS = "pref_include_videos"

    private const val KEY_FOLDER_CAMERA = "pref_folder_camera"
    private const val KEY_FOLDER_SCREENSHOTS = "pref_folder_screenshots"
    private const val KEY_FOLDER_WHATSAPP = "pref_folder_whatsapp"
    private const val KEY_FOLDER_DOWNLOADS = "pref_folder_downloads"
    private const val KEY_CUSTOM_FOLDERS = "pref_custom_folders_set"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isWifiOnly(context: Context): Boolean = getPrefs(context).getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun isChargingOnly(context: Context): Boolean = getPrefs(context).getBoolean(KEY_CHARGING_ONLY, false)
    fun setChargingOnly(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_CHARGING_ONLY, value).apply()

    fun isIncludeVideos(context: Context): Boolean = getPrefs(context).getBoolean(KEY_INCLUDE_VIDEOS, true)
    fun setIncludeVideos(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_INCLUDE_VIDEOS, value).apply()

    fun isCameraFolderEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLDER_CAMERA, true)
    fun setCameraFolderEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_FOLDER_CAMERA, value).apply()

    fun isScreenshotsFolderEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLDER_SCREENSHOTS, true)
    fun setScreenshotsFolderEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_FOLDER_SCREENSHOTS, value).apply()

    fun isWhatsAppFolderEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLDER_WHATSAPP, false)
    fun setWhatsAppFolderEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_FOLDER_WHATSAPP, value).apply()

    fun isDownloadsFolderEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FOLDER_DOWNLOADS, false)
    fun setDownloadsFolderEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_FOLDER_DOWNLOADS, value).apply()

    fun getCustomFolders(context: Context): List<Pair<String, String>> {
        val set = getPrefs(context).getStringSet(KEY_CUSTOM_FOLDERS, emptySet()) ?: emptySet()
        return set.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    fun addCustomFolder(context: Context, uriString: String, folderName: String) {
        val current = getPrefs(context).getStringSet(KEY_CUSTOM_FOLDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        // Remove duplicate uri if exists
        current.removeAll { it.startsWith("$uriString|") }
        current.add("$uriString|$folderName")
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_FOLDERS, current).apply()
    }

    fun removeCustomFolder(context: Context, uriString: String) {
        val current = getPrefs(context).getStringSet(KEY_CUSTOM_FOLDERS, emptySet())?.toMutableSet() ?: return
        current.removeAll { it.startsWith("$uriString|") }
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_FOLDERS, current).apply()
    }
}

