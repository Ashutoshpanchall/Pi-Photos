package com.example.piphotos.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ServerInfo(
    val id: String,
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val pairingOpen: Boolean
)

data class PairResult(
    val token: String,
    val userId: String,
    val deviceId: String
)

data class ServerStatus(
    val diskTotal: Long,
    val diskFree: Long,
    val diskReserved: Long = 0L,
    val diskUsed: Long = 0L,
    val userQuota: Long,
    val userUsed: Long,
    val userFree: Long,
    val userFileCount: Int = 0
)

data class ServerFolderInfo(
    val name: String,
    val itemCount: Int,
    val totalBytes: Long
)

data class DeviceFoldersResponse(
    val deviceId: String,
    val deviceName: String,
    val folders: List<ServerFolderInfo>
)

class PiHttpClient(
    private val context: Context? = null
) {
    fun getActiveWifiNetwork(): Network? {
        val ctx = context ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun buildClient(
        expectedFingerprint: String? = null,
        connectTimeoutSec: Long = 5,
        readTimeoutSec: Long = 10
    ): Pair<OkHttpClient, FingerprintTrustManager> {
        val currentWifi = getActiveWifiNetwork()
        val netId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentWifi?.networkHandle ?: -1L
        } else {
            currentWifi?.hashCode()?.toLong() ?: -1L
        }

        // Only cache clients with an explicit expected fingerprint (pinned server).
        // TOFU discovery requests (expectedFingerprint == null) require fresh FingerprintTrustManager instances.
        if (expectedFingerprint != null) {
            val cacheKey = "${expectedFingerprint}_${connectTimeoutSec}_${readTimeoutSec}_$netId"
            val cached = clientCache[cacheKey]
            if (cached != null) {
                return cached
            }
        }

        val trustManager = FingerprintTrustManager(expectedFingerprint)
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(trustManager.createSslSocketFactory(), trustManager)
            .hostnameVerifier(FingerprintTrustManager.PermissiveHostnameVerifier)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        if (currentWifi != null) {
            builder.socketFactory(currentWifi.socketFactory)
        }

        val pair = Pair(builder.build(), trustManager)
        if (expectedFingerprint != null) {
            val cacheKey = "${expectedFingerprint}_${connectTimeoutSec}_${readTimeoutSec}_$netId"
            clientCache[cacheKey] = pair
        }
        return pair
    }

    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        try {
            val wifiNetwork = getActiveWifiNetwork()
            val socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
            socket.use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInfo(
        endpointUrl: String,
        expectedFingerprint: String? = null
    ): Result<Pair<ServerInfo, String?>> = withContext(Dispatchers.IO) {
        try {
            val (client, trustManager) = buildClient(expectedFingerprint, connectTimeoutSec = 4, readTimeoutSec = 5)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/info")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Server returned HTTP ${resp.code}: ${resp.message}")
                    )
                }
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val info = ServerInfo(
                    id = json.optString("id", ""),
                    name = json.optString("name", "Home Vault"),
                    version = json.optString("version", "1.0"),
                    protocolVersion = json.optInt("protocol_version", 1),
                    pairingOpen = json.optBoolean("pairing_open", false)
                )
                Result.success(Pair(info, trustManager.capturedFingerprint))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pairDevice(
        endpointUrl: String,
        code: String,
        deviceName: String,
        deviceModel: String,
        expectedFingerprint: String? = null
    ): Result<PairResult> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint, connectTimeoutSec = 5, readTimeoutSec = 10)
            val jsonBody = JSONObject().apply {
                put("code", code)
                put("device_name", deviceName)
                put("device_model", deviceModel)
            }.toString()

            val request = Request.Builder()
                .url("$endpointUrl/api/v1/pair")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = try {
                        val errJson = JSONObject(bodyStr)
                        val detail = errJson.optJSONObject("detail")
                        detail?.optString("message") ?: errJson.optString("message", "HTTP ${resp.code}")
                    } catch (e: Exception) {
                        "HTTP ${resp.code} ${resp.message}"
                    }
                    return@withContext Result.failure(
                        HttpException(resp.code, errMsg)
                    )
                }
                val json = JSONObject(bodyStr)
                val pairResult = PairResult(
                    token = json.getString("token"),
                    userId = json.getString("user_id"),
                    deviceId = json.getString("device_id")
                )
                Result.success(pairResult)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStatus(
        endpointUrl: String,
        authToken: String,
        expectedFingerprint: String? = null
    ): Result<ServerStatus> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint, connectTimeoutSec = 4, readTimeoutSec = 6)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/status")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        HttpException(resp.code, "Status check failed: HTTP ${resp.code}")
                    )
                }
                val json = JSONObject(bodyStr)
                val disk = json.getJSONObject("disk")
                val user = json.getJSONObject("user")

                val total = disk.optLong("total_bytes", disk.optLong("total", 0L))
                val free = disk.optLong("free_bytes", disk.optLong("free", 0L))
                val reserved = disk.optLong("reserved_bytes", disk.optLong("reserved", 0L))
                val used = disk.optLong("used_bytes", (total - free).coerceAtLeast(0L))

                val quota = user.optLong("quota_bytes", user.optLong("quota", 0L))
                val uUsed = user.optLong("used_bytes", user.optLong("used", 0L))
                val uFree = user.optLong("free_bytes", user.optLong("free", 0L))
                val uCount = user.optInt("file_count", 0)

                val status = ServerStatus(
                    diskTotal = total,
                    diskFree = free,
                    diskReserved = reserved,
                    diskUsed = used,
                    userQuota = quota,
                    userUsed = uUsed,
                    userFree = uFree,
                    userFileCount = uCount
                )
                Result.success(status)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceFolders(
        endpointUrl: String,
        authToken: String,
        deviceId: String,
        expectedFingerprint: String? = null
    ): Result<DeviceFoldersResponse> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint, connectTimeoutSec = 5, readTimeoutSec = 10)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/devices/$deviceId/folders")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        HttpException(resp.code, "Get folders failed: HTTP ${resp.code}")
                    )
                }
                val json = JSONObject(bodyStr)
                val devId = json.optString("device_id", deviceId)
                val devName = json.optString("device_name", "")
                val foldersArray = json.optJSONArray("folders") ?: JSONArray()
                val foldersList = mutableListOf<ServerFolderInfo>()
                for (i in 0 until foldersArray.length()) {
                    val obj = foldersArray.getJSONObject(i)
                    foldersList.add(
                        ServerFolderInfo(
                            name = obj.getString("name"),
                            itemCount = obj.getInt("item_count"),
                            totalBytes = obj.getLong("total_bytes")
                        )
                    )
                }
                Result.success(
                    DeviceFoldersResponse(
                        deviceId = devId,
                        deviceName = devName,
                        folders = foldersList
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkUpload(
        endpointUrl: String,
        authToken: String,
        hashes: List<String>,
        expectedFingerprint: String? = null
    ): Result<CheckUploadResult> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint, connectTimeoutSec = 5, readTimeoutSec = 10)
            val jsonArr = org.json.JSONArray(hashes)
            val reqBody = JSONObject().put("hashes", jsonArr).toString()

            val request = Request.Builder()
                .url("$endpointUrl/api/v1/upload/check")
                .header("Authorization", "Bearer $authToken")
                .post(reqBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        HttpException(resp.code, "Check failed HTTP ${resp.code}: $bodyStr")
                    )
                }
                val json = JSONObject(bodyStr)
                val existingArr = json.optJSONArray("existing") ?: org.json.JSONArray()
                val existingSet = mutableSetOf<String>()
                for (i in 0 until existingArr.length()) {
                    existingSet.add(existingArr.getString(i))
                }
                val partialObj = json.optJSONObject("partial") ?: JSONObject()
                val partialMap = mutableMapOf<String, Long>()
                val keys = partialObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    partialMap[k] = partialObj.getLong(k)
                }
                Result.success(CheckUploadResult(existingSet, partialMap))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadStreaming(
        endpointUrl: String,
        authToken: String,
        sha256: String,
        deviceId: String,
        folder: String,
        filename: String,
        takenEpochSec: Long?,
        totalSize: Long,
        resumeOffset: Long,
        contentUri: android.net.Uri,
        mimeType: String,
        expectedFingerprint: String? = null,
        onBytesSent: (Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IOException("No context available"))
        try {
            val (client, _) = buildClient(expectedFingerprint, connectTimeoutSec = 10, readTimeoutSec = 60)
            val lengthToSend = (totalSize - resumeOffset).coerceAtLeast(0L)

            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType(): okhttp3.MediaType? = mimeType.toMediaType()
                override fun contentLength(): Long = lengthToSend

                override fun writeTo(sink: okio.BufferedSink) {
                    ctx.contentResolver.openInputStream(contentUri)?.use { input ->
                        if (resumeOffset > 0) {
                            var skipped = 0L
                            while (skipped < resumeOffset) {
                                val s = input.skip(resumeOffset - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }
                        val buffer = ByteArray(256 * 1024)
                        var bytesRemaining = lengthToSend
                        while (bytesRemaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            sink.write(buffer, 0, read)
                            bytesRemaining -= read
                            onBytesSent(read.toLong())
                        }
                    } ?: throw IOException("Cannot read content URI $contentUri")
                }
            }

            val encodedFolder = java.net.URLEncoder.encode(folder, "UTF-8")
            val encodedName = java.net.URLEncoder.encode(filename, "UTF-8")
            var url = "$endpointUrl/api/v1/upload/$sha256?device=$deviceId&folder=$encodedFolder&name=$encodedName"
            if (takenEpochSec != null && takenEpochSec > 0) {
                url += "&taken=$takenEpochSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .header("Upload-Offset", resumeOffset.toString())
                .header("Content-Length", lengthToSend.toString())
                .put(requestBody)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    return@withContext Result.success(Unit)
                }
                if (resp.code == 409) {
                    try {
                        val json = JSONObject(bodyStr)
                        val detail = json.optJSONObject("detail")
                        val errType = detail?.optString("error") ?: json.optString("error")
                        if (errType == "offset_mismatch") {
                            val realOffset = detail?.optLong("offset", 0L) ?: json.optLong("offset", 0L)
                            return@withContext Result.failure(OffsetMismatchException(realOffset, "Offset mismatch"))
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                Result.failure(HttpException(resp.code, "Upload failed HTTP ${resp.code}: $bodyStr"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMe(
        endpointUrl: String,
        authToken: String,
        expectedFingerprint: String? = null
    ): Result<MeResponse> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/me")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to fetch user profile HTTP ${resp.code}: $bodyStr"))
                }
                val json = JSONObject(bodyStr)
                Result.success(
                    MeResponse(
                        userId = json.getString("user_id"),
                        displayName = json.optString("display_name", ""),
                        role = json.optString("role", "member"),
                        quotaBytes = json.optLong("quota_bytes", 0L),
                        usedBytes = json.optLong("used_bytes", 0L),
                        deviceId = json.optString("device_id", ""),
                        deviceName = json.optString("device_name", "")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listAdminUsers(
        endpointUrl: String,
        authToken: String,
        expectedFingerprint: String? = null
    ): Result<List<AdminUser>> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/users")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to list users HTTP ${resp.code}: $bodyStr"))
                }
                val arr = JSONArray(bodyStr)
                val list = (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).toAdminUser()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createAdminUser(
        endpointUrl: String,
        authToken: String,
        displayName: String,
        quotaBytes: Long? = null,
        unlimited: Boolean = false,
        expectedFingerprint: String? = null
    ): Result<CreateUserResult> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val payload = JSONObject().apply {
                put("display_name", displayName)
                if (quotaBytes != null) put("quota_bytes", quotaBytes)
                if (unlimited) put("unlimited", true)
            }
            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/users")
                .header("Authorization", "Bearer $authToken")
                .post(reqBody)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to create user HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(JSONObject(bodyStr).toCreateUserResult())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAdminUser(
        endpointUrl: String,
        authToken: String,
        userId: String,
        req: UpdateUserRequest,
        force: Boolean = false,
        expectedFingerprint: String? = null
    ): Result<AdminUser> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val payload = JSONObject().apply {
                if (req.displayName != null) put("display_name", req.displayName)
                if (req.quotaBytes != null) put("quota_bytes", req.quotaBytes)
                if (req.status != null) put("status", req.status)
                if (req.role != null) put("role", req.role)
            }
            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val url = "$endpointUrl/api/v1/admin/users/$userId" + if (force) "?force=true" else ""
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .patch(reqBody)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to update user HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(JSONObject(bodyStr).toAdminUser())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reissueInvite(
        endpointUrl: String,
        authToken: String,
        userId: String,
        expectedFingerprint: String? = null
    ): Result<InviteResult> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val emptyBody = "".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/users/$userId/invite")
                .header("Authorization", "Bearer $authToken")
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to reissue invite HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(JSONObject(bodyStr).toInviteResult())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAdminUser(
        endpointUrl: String,
        authToken: String,
        userId: String,
        deleteFiles: Boolean = false,
        expectedFingerprint: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val url = "$endpointUrl/api/v1/admin/users/$userId?delete_files=$deleteFiles"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .delete()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to delete user HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listUserDevices(
        endpointUrl: String,
        authToken: String,
        userId: String,
        expectedFingerprint: String? = null
    ): Result<List<AdminDeviceSummary>> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/users/$userId/devices")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to list devices HTTP ${resp.code}: $bodyStr"))
                }
                val arr = JSONArray(bodyStr)
                val list = (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).toAdminDeviceSummary()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeDevice(
        endpointUrl: String,
        authToken: String,
        deviceId: String,
        expectedFingerprint: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/devices/$deviceId")
                .header("Authorization", "Bearer $authToken")
                .delete()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to revoke device HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun openPairing(
        endpointUrl: String,
        authToken: String,
        expectedFingerprint: String? = null
    ): Result<OpenPairingResult> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val emptyBody = "".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/pairing/open")
                .header("Authorization", "Bearer $authToken")
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to open pairing HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(JSONObject(bodyStr).toOpenPairingResult())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAdminOverview(
        endpointUrl: String,
        authToken: String,
        expectedFingerprint: String? = null
    ): Result<AdminOverview> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val request = Request.Builder()
                .url("$endpointUrl/api/v1/admin/overview")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to fetch admin overview HTTP ${resp.code}: $bodyStr"))
                }
                Result.success(JSONObject(bodyStr).toAdminOverview())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFiles(
        endpointUrl: String,
        authToken: String,
        deviceId: String? = null,
        folder: String? = null,
        limit: Int = 100,
        offset: Int = 0,
        expectedFingerprint: String? = null
    ): Result<List<FileInfo>> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val base = endpointUrl.trimEnd('/')
            val params = mutableListOf<String>()
            if (!deviceId.isNullOrEmpty()) params.add("device_id=$deviceId")
            if (!folder.isNullOrEmpty()) params.add("folder=$folder")
            if (limit > 0) params.add("limit=$limit")
            if (offset > 0) params.add("offset=$offset")
            val url = if (params.isNotEmpty()) "$base/api/v1/files?" + params.joinToString("&") else "$base/api/v1/files"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(HttpException(resp.code, "Failed to list files HTTP ${resp.code}: $bodyStr"))
                }
                val arr = org.json.JSONArray(bodyStr)
                val files = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    FileInfo(
                        id = obj.getLong("id"),
                        userId = obj.optString("user_id", ""),
                        deviceId = obj.optString("device_id", ""),
                        folder = obj.optString("folder", ""),
                        relativePath = obj.optString("name", obj.optString("relative_path", "")),
                        sha256 = obj.optString("sha256", ""),
                        size = obj.optLong("size", 0L),
                        dateTaken = if (obj.isNull("date_taken")) null else obj.optLong("date_taken"),
                        uploadedAt = obj.optString("uploaded_at", "")
                    )
                }
                Result.success(files)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFileContent(
        endpointUrl: String,
        authToken: String,
        fileId: Long,
        expectedFingerprint: String? = null,
        outputStream: java.io.OutputStream
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val (client, _) = buildClient(expectedFingerprint)
            val base = endpointUrl.trimEnd('/')
            val url = "$base/api/v1/files/$fileId/content"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    return@withContext Result.failure(HttpException(resp.code, "Download failed HTTP ${resp.code}: $errBody"))
                }
                val body = resp.body ?: return@withContext Result.failure(IOException("Empty response body"))
                val bytesCopied = body.byteStream().use { input ->
                    input.copyTo(outputStream, bufferSize = 64 * 1024)
                }
                Result.success(bytesCopied)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PiHttpClient? = null

        // Cached OkHttpClient instances per fingerprint & timeout profile to reuse HTTP/2 socket pools
        private val clientCache = ConcurrentHashMap<String, Pair<OkHttpClient, FingerprintTrustManager>>()

        fun clearClientCache() {
            clientCache.clear()
        }

        fun getInstance(context: Context): PiHttpClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PiHttpClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

data class CheckUploadResult(
    val existing: Set<String>,
    val partial: Map<String, Long>
)

class OffsetMismatchException(val serverOffset: Long, message: String) : IOException(message)

class HttpException(val code: Int, message: String) : IOException(message)

// ---------------------------------------------------------------------------
// Phase U1 / U1.1 / U2: User profile & Admin models
// ---------------------------------------------------------------------------

/** Caller's own profile as returned by GET /api/v1/me. */
data class MeResponse(
    val userId: String,
    val displayName: String,
    val role: String,          // "owner" | "member"
    val quotaBytes: Long,      // 0 = unlimited
    val usedBytes: Long,
    val deviceId: String,
    val deviceName: String
)

/** User file metadata as returned by GET /api/v1/files. */
data class FileInfo(
    val id: Long,
    val userId: String,
    val deviceId: String,
    val folder: String,
    val relativePath: String,
    val sha256: String,
    val size: Long,
    val dateTaken: Long?,
    val uploadedAt: String
)

/** Summary of a user as returned by GET /api/v1/admin/users. */
data class AdminUser(
    val id: String,
    val name: String,
    val displayName: String,
    val slug: String,
    val role: String,          // "owner" | "member"
    val status: String,        // "active" | "disabled"
    val quotaBytes: Long,      // 0 = unlimited
    val usedBytes: Long,
    val fileCount: Int,
    val devicesCount: Int,
    val lastSeenAt: String?
)

/** Result of POST /api/v1/admin/users — includes one-time invite code. */
data class CreateUserResult(
    val user: AdminUser,
    val inviteCode: String,
    val expiresAt: String,
    val qrPayload: String
)

/** Request body for PATCH /api/v1/admin/users/{id}. All fields optional. */
data class UpdateUserRequest(
    val displayName: String? = null,
    val quotaBytes: Long? = null,
    val status: String? = null,   // "active" | "disabled"
    val role: String? = null      // "owner" | "member"
)

/** Result of POST /api/v1/admin/users/{id}/invite. */
data class InviteResult(
    val inviteCode: String,
    val expiresAt: String,
    val qrPayload: String
)

/** A device as returned by GET /api/v1/admin/users/{id}/devices. */
data class AdminDeviceSummary(
    val id: String,
    val name: String,
    val model: String,
    val slug: String,
    val createdAt: String,
    val lastSeenAt: String?
)

/** Result of POST /api/v1/admin/pairing/open. */
data class OpenPairingResult(
    val code: String,
    val expiresInSeconds: Int
)

/** Per-user entry inside AdminOverview. */
data class UserOverview(
    val id: String,
    val name: String,
    val role: String,
    val quotaBytes: Long,
    val usedBytes: Long,
    val fileCount: Int
)

/** Result of GET /api/v1/admin/overview. */
data class AdminOverview(
    val diskTotal: Long,
    val diskFree: Long,
    val diskReserved: Long,
    val allocatedQuotaTotal: Long,
    val overAllocated: Boolean,
    val users: List<UserOverview>
)

// ---------------------------------------------------------------------------
// JSON parsing helpers for admin models
// ---------------------------------------------------------------------------

internal fun JSONObject.toAdminUser(): AdminUser = AdminUser(
    id = getString("id"),
    name = optString("name", ""),
    displayName = optString("display_name", optString("name", "")),
    slug = optString("slug", ""),
    role = optString("role", "member"),
    status = optString("status", "active"),
    quotaBytes = optLong("quota_bytes", 0L),
    usedBytes = optLong("used_bytes", 0L),
    fileCount = optInt("file_count", 0),
    devicesCount = optInt("devices_count", 0),
    lastSeenAt = if (isNull("last_seen_at")) null else optString("last_seen_at").takeIf { it.isNotEmpty() && it != "null" }
)

internal fun JSONObject.toCreateUserResult(): CreateUserResult = CreateUserResult(
    user = getJSONObject("user").toAdminUser(),
    inviteCode = getString("invite_code"),
    expiresAt = getString("expires_at"),
    qrPayload = getString("qr_payload")
)

internal fun JSONObject.toInviteResult(): InviteResult = InviteResult(
    inviteCode = getString("invite_code"),
    expiresAt = getString("expires_at"),
    qrPayload = getString("qr_payload")
)

internal fun JSONObject.toAdminDeviceSummary(): AdminDeviceSummary = AdminDeviceSummary(
    id = getString("id"),
    name = optString("name", ""),
    model = optString("model", ""),
    slug = optString("slug", ""),
    createdAt = optString("created_at", ""),
    lastSeenAt = if (isNull("last_seen_at")) null else optString("last_seen_at").takeIf { it.isNotEmpty() && it != "null" }
)

internal fun JSONObject.toOpenPairingResult(): OpenPairingResult = OpenPairingResult(
    code = getString("code"),
    expiresInSeconds = optInt("expires_in_seconds", 600)
)

internal fun JSONObject.toAdminOverview(): AdminOverview {
    val disk = getJSONObject("disk")
    val usersArr = getJSONArray("users")
    val users = (0 until usersArr.length()).map { i ->
        usersArr.getJSONObject(i).let { u ->
            UserOverview(
                id = u.getString("id"),
                name = u.optString("name", ""),
                role = u.optString("role", "member"),
                quotaBytes = u.optLong("quota_bytes", 0L),
                usedBytes = u.optLong("used_bytes", 0L),
                fileCount = u.optInt("file_count", 0)
            )
        }
    }
    return AdminOverview(
        diskTotal = disk.getLong("total_bytes"),
        diskFree = disk.getLong("free_bytes"),
        diskReserved = disk.optLong("reserved_bytes", 0L),
        allocatedQuotaTotal = optLong("allocated_quota_total", 0L),
        overAllocated = optBoolean("over_allocated", false),
        users = users
    )
}
