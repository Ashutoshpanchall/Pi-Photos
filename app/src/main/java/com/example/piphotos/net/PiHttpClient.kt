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
        val trustManager = FingerprintTrustManager(expectedFingerprint)
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(trustManager.createSslSocketFactory(), trustManager)
            .hostnameVerifier(FingerprintTrustManager.PermissiveHostnameVerifier)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        val wifiNetwork = getActiveWifiNetwork()
        if (wifiNetwork != null) {
            builder.socketFactory(wifiNetwork.socketFactory)
        }

        return Pair(builder.build(), trustManager)
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
                        val buffer = ByteArray(64 * 1024)
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

    companion object {
        @Volatile
        private var INSTANCE: PiHttpClient? = null

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
