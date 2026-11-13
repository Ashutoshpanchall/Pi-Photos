package com.example.piphotos.sync

import android.content.Context
import android.content.SharedPreferences

data class PairedNode(
    val nodeName: String,
    val endpointUrl: String,
    val authToken: String,
    val pairedAtMs: Long
)

/**
 * Persistent preferences storing configuration and credentials for the paired storage node.
 */
object SyncNodePreferences {

    private const val PREFS_NAME = "piphotos_node_sync"

    private const val KEY_NODE_NAME = "node_name"
    private const val KEY_ENDPOINT_URL = "endpoint_url"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_PAIRED_AT = "paired_at_ms"
    private const val KEY_LAST_SYNC_AT = "last_sync_at_ms"
    private const val KEY_SYNC_IN_PROGRESS = "sync_in_progress"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPairedNode(context: Context): PairedNode? {
        val prefs = getPrefs(context)
        val name = prefs.getString(KEY_NODE_NAME, null) ?: return null
        val url = prefs.getString(KEY_ENDPOINT_URL, null) ?: return null
        val token = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        val pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L)
        return PairedNode(name, url, token, pairedAt)
    }

    fun isPaired(context: Context): Boolean {
        return getPairedNode(context) != null
    }

    fun savePairedNode(context: Context, name: String, endpointUrl: String, authToken: String = "") {
        getPrefs(context).edit()
            .putString(KEY_NODE_NAME, name)
            .putString(KEY_ENDPOINT_URL, endpointUrl)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPairedNode(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun getLastSyncTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_AT, 0L)
    }

    fun setLastSyncTimestamp(context: Context, timestamp: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_SYNC_AT, timestamp).apply()
    }

    fun setSyncInProgress(context: Context, inProgress: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_IN_PROGRESS, inProgress).apply()
    }

    fun isSyncInProgress(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_IN_PROGRESS, false)
    }
}

