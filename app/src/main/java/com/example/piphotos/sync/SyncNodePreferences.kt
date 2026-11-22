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
    private const val KEY_USER_ROLE = "user_role"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_QUOTA_BYTES = "quota_bytes"
    private const val KEY_USED_BYTES = "used_bytes"
    private const val KEY_DEVICE_NAME = "device_name"

    // Candidate server address fallback keys
    private const val KEY_LAST_IP = "last_ip"
    private const val KEY_MDNS_HOST = "mdns_host"
    private const val KEY_MANUAL_HOST = "manual_host"
    private const val KEY_PORT = "node_port"
    private const val KEY_ACTIVE_ADDRESS = "active_address"

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
        val uri = try { java.net.URI(endpointUrl) } catch (_: Exception) { null }
        val host = uri?.host ?: ""
        val port = if (uri != null && uri.port > 0) uri.port else 8443

        val editor = getPrefs(context).edit()
            .putString(KEY_NODE_NAME, name)
            .putString(KEY_ENDPOINT_URL, endpointUrl)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putLong(KEY_PAIRED_AT, now)
            .putString(KEY_TLS_FINGERPRINT, tlsFingerprint)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_SERVER_ID, serverId)
            .putLong(KEY_LAST_SEEN_AT, now)
            .putInt(KEY_PORT, port)
            .putString(KEY_ACTIVE_ADDRESS, endpointUrl)

        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            editor.putString(KEY_LAST_IP, host)
        } else if (host.endsWith(".local", ignoreCase = true)) {
            editor.putString(KEY_MDNS_HOST, host)
        } else if (host.isNotEmpty()) {
            editor.putString(KEY_MANUAL_HOST, host)
        }
        editor.apply()
    }

    fun saveAddressCandidates(
        context: Context,
        lastIp: String? = null,
        mdnsHost: String? = null,
        manualHost: String? = null,
        port: Int = 8443
    ) {
        val editor = getPrefs(context).edit()
        if (!lastIp.isNullOrBlank()) editor.putString(KEY_LAST_IP, lastIp.trim())
        if (!mdnsHost.isNullOrBlank()) editor.putString(KEY_MDNS_HOST, mdnsHost.trim())
        if (!manualHost.isNullOrBlank()) editor.putString(KEY_MANUAL_HOST, manualHost.trim())
        if (port > 0) editor.putInt(KEY_PORT, port)
        editor.apply()
    }

    fun updateLastIp(context: Context, newIp: String) {
        val cleanIp = newIp.trim()
        val port = getPort(context)
        getPrefs(context).edit()
            .putString(KEY_LAST_IP, cleanIp)
            .putString(KEY_ENDPOINT_URL, "https://$cleanIp:$port")
            .apply()
    }

    fun getLastIp(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_IP, null)
    }

    fun getPort(context: Context): Int {
        val port = getPrefs(context).getInt(KEY_PORT, 0)
        if (port > 0) return port
        val url = getPrefs(context).getString(KEY_ENDPOINT_URL, null) ?: return 8443
        return try {
            val uri = java.net.URI(url)
            if (uri.port > 0) uri.port else 8443
        } catch (_: Exception) {
            8443
        }
    }

    fun getCandidateAddresses(context: Context): List<String> {
        val prefs = getPrefs(context)
        val port = getPort(context)
        val lastIp = prefs.getString(KEY_LAST_IP, null)
        val mdnsHost = prefs.getString(KEY_MDNS_HOST, null)
        val manualHost = prefs.getString(KEY_MANUAL_HOST, null)
        val currentUrl = prefs.getString(KEY_ENDPOINT_URL, null)

        val rawCandidates = mutableListOf<String>()
        // 1. Last known IP
        if (!lastIp.isNullOrBlank()) {
            rawCandidates.add("https://${lastIp.trim()}:$port")
        }
        // 2. .local mDNS hostname
        if (!mdnsHost.isNullOrBlank()) {
            val cleanMdns = if (mdnsHost.endsWith(".local", ignoreCase = true)) mdnsHost else "$mdnsHost.local"
            rawCandidates.add("https://${cleanMdns.trim()}:$port")
        }
        // 3. Manual address
        if (!manualHost.isNullOrBlank()) {
            val formatted = if (manualHost.startsWith("http://") || manualHost.startsWith("https://")) {
                manualHost.trim()
            } else {
                "https://${manualHost.trim()}:$port"
            }
            rawCandidates.add(formatted)
        }
        // 4. Current endpoint URL fallback
        if (!currentUrl.isNullOrBlank() && !rawCandidates.contains(currentUrl)) {
            rawCandidates.add(currentUrl)
        }

        return rawCandidates.distinct()
    }

    fun setActiveAddress(context: Context, address: String) {
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_ADDRESS, address)
            .putString(KEY_ENDPOINT_URL, address)
            .apply()
    }

    fun getActiveAddress(context: Context): String? {
        return getPrefs(context).getString(KEY_ACTIVE_ADDRESS, null)
            ?: getPrefs(context).getString(KEY_ENDPOINT_URL, null)
    }

    fun updateLastSeen(context: Context, timestamp: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_SEEN_AT, timestamp).apply()
    }

    fun getLastSeenTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SEEN_AT, 0L)
    }

    fun saveUserProfile(
        context: Context,
        role: String,
        displayName: String,
        quotaBytes: Long = 0L,
        usedBytes: Long = 0L,
        deviceName: String = ""
    ) {
        getPrefs(context).edit()
            .putString(KEY_USER_ROLE, role)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_QUOTA_BYTES, quotaBytes)
            .putLong(KEY_USED_BYTES, usedBytes)
            .putString(KEY_DEVICE_NAME, deviceName)
            .apply()
    }

    fun getUserRole(context: Context): String {
        return getPrefs(context).getString(KEY_USER_ROLE, "owner") ?: "owner"
    }

    fun isOwner(context: Context): Boolean {
        return getUserRole(context).equals("owner", ignoreCase = true)
    }

    fun getDisplayName(context: Context): String {
        return getPrefs(context).getString(KEY_DISPLAY_NAME, "") ?: ""
    }

    fun getQuotaBytes(context: Context): Long {
        return getPrefs(context).getLong(KEY_QUOTA_BYTES, 0L)
    }

    fun getUsedBytes(context: Context): Long {
        return getPrefs(context).getLong(KEY_USED_BYTES, 0L)
    }

    fun updateUsedBytes(context: Context, usedBytes: Long) {
        getPrefs(context).edit().putLong(KEY_USED_BYTES, usedBytes.coerceAtLeast(0L)).apply()
    }

    fun incrementUsedBytes(context: Context, delta: Long) {
        val current = getUsedBytes(context)
        getPrefs(context).edit().putLong(KEY_USED_BYTES, (current + delta).coerceAtLeast(0L)).apply()
    }

    fun getServerName(context: Context): String {
        return getPrefs(context).getString(KEY_NODE_NAME, "PiPhotos Vault") ?: "PiPhotos Vault"
    }

    fun getDeviceName(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_NAME, "") ?: ""
    }

    fun clearPairedNode(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun clear(context: Context) = clearPairedNode(context)

    fun isConfigured(context: Context): Boolean = isPaired(context)

    fun getServerUrl(context: Context): String? = getPairedNode(context)?.endpointUrl

    fun getNodeName(context: Context): String? = getPairedNode(context)?.nodeName

    fun getTlsFingerprint(context: Context): String? = getPairedNode(context)?.tlsFingerprint

    fun getServerFingerprint(context: Context): String? = getTlsFingerprint(context)

    fun getServerId(context: Context): String? = getPairedNode(context)?.serverId

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
