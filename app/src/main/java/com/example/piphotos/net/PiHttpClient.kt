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
    val userQuota: Long,
    val userUsed: Long,
    val userFree: Long
)

class PiHttpClient(
    private val context: Context? = null
) {
    @Volatile
    private var activeWifiNetwork: Network? = null

    init {
        context?.let { ctx ->
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        activeWifiNetwork = network
                    }

                    override fun onLost(network: Network) {
                        if (activeWifiNetwork == network) {
                            activeWifiNetwork = null
                        }
                    }
                })
            } catch (e: Exception) {
                // Connectivity manager callback registration error
            }
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

        activeWifiNetwork?.let { wifi ->
            builder.socketFactory(wifi.socketFactory)
        }

        return Pair(builder.build(), trustManager)
    }

    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = activeWifiNetwork?.socketFactory?.createSocket() ?: Socket()
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
                val status = ServerStatus(
                    diskTotal = disk.getLong("total"),
                    diskFree = disk.getLong("free"),
                    userQuota = user.getLong("quota"),
                    userUsed = user.getLong("used"),
                    userFree = user.getLong("free")
                )
                Result.success(status)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class HttpException(val code: Int, message: String) : IOException(message)
