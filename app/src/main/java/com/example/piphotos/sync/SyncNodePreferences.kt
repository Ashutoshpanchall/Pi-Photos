package com.example.piphotos.sync

import android.content.Context
import android.content.SharedPreferences

data class PairedNode(
    val nodeName: String,
    val endpointUrl: String,
    val authToken: String,
    val pairedAtMs: Long,
    val tlsFingerprint: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val serverId: String = "",
    val lastSeenMs: Long = 0L
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
    private const val KEY_TLS_FINGERPRINT = "tls_fingerprint"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SERVER_ID = "server_id"
    private const val KEY_LAST_SEEN_AT = "last_seen_at_ms"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPairedNode(context: Context): PairedNode? {
        val prefs = getPrefs(context)
        val name = prefs.getString(KEY_NODE_NAME, null) ?: return null
        val url = prefs.getString(KEY_ENDPOINT_URL, null) ?: return null
        val token = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        val pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L)
        val tlsFp = prefs.getString(KEY_TLS_FINGERPRINT, "") ?: ""
        val userId = prefs.getString(KEY_USER_ID, "") ?: ""
        val deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        val serverId = prefs.getString(KEY_SERVER_ID, "") ?: ""
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_AT, pairedAt)
        return PairedNode(
            nodeName = name,
            endpointUrl = url,
            authToken = token,
            pairedAtMs = pairedAt,
            tlsFingerprint = tlsFp,
            userId = userId,
            deviceId = deviceId,
            serverId = serverId,
            lastSeenMs = lastSeen
        )
    }

    fun isPaired(context: Context): Boolean {
        return getPairedNode(context) != null
    }

    fun savePairedNode(
        context: Context,
        name: String,
        endpointUrl: String,
        authToken: String = "",
        tlsFingerprint: String = "",
        userId: String = "",
        deviceId: String = "",
        serverId: String = ""
    ) {
        val now = System.currentTimeMillis()
        getPrefs(context).edit()
            .putString(KEY_NODE_NAME, name)
            .putString(KEY_ENDPOINT_URL, endpointUrl)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putLong(KEY_PAIRED_AT, now)
            .putString(KEY_TLS_FINGERPRINT, tlsFingerprint)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_SERVER_ID, serverId)
            .putLong(KEY_LAST_SEEN_AT, now)
            .apply()
    }

    fun updateLastSeen(context: Context, timestamp: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_SEEN_AT, timestamp).apply()
    }

    fun getLastSeenTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SEEN_AT, 0L)
    }

    fun clearPairedNode(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun clear(context: Context) = clearPairedNode(context)

    fun isConfigured(context: Context): Boolean = isPaired(context)

    fun getServerUrl(context: Context): String? = getPairedNode(context)?.endpointUrl

    fun getNodeName(context: Context): String? = getPairedNode(context)?.nodeName

    fun getTlsFingerprint(context: Context): String? = getPairedNode(context)?.tlsFingerprint

    fun getAuthToken(context: Context): String? = getPairedNode(context)?.authToken

    fun getDeviceId(context: Context): String? = getPairedNode(context)?.deviceId

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
