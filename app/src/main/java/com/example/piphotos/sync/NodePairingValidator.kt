package com.example.piphotos.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.piphotos.net.FingerprintTrustManager
import com.example.piphotos.net.HttpException
import com.example.piphotos.net.PairResult
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.net.ServerInfo
import com.example.piphotos.net.ServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException

enum class LiveCheckStep(val displayName: String) {
    RESOLVE_NAME("Resolve server address"),
    TCP_CONNECT("TCP connection check"),
    CHECK_SERVER_INFO("PiPhotos server handshake"),
    CHECK_CERTIFICATE("TLS certificate fingerprint"),
    SUBMIT_PAIRING_CODE("Validate pairing code"),
    CHECK_FREE_SPACE("Verify storage capacity")
}

sealed class LiveCheckStepState {
    object Pending : LiveCheckStepState()
    object Running : LiveCheckStepState()
    data class Passed(val message: String = "") : LiveCheckStepState()
    data class Failed(val error: String) : LiveCheckStepState()
}

data class QrPairPayload(
    val host: String,
    val port: Int,
    val tlsFingerprint: String?,
    val code: String?
)

sealed class ValidationException(message: String) : Exception(message) {
    class CertificateMismatch(val expected: String, val actual: String) :
        ValidationException("Certificate mismatch: expected $expected, got $actual")
    class InvalidServerInfo(val reason: String) :
        ValidationException("Invalid PiPhotos server info: $reason")
}

sealed class PairingValidationResult {
    data class Success(
        val pairedNode: PairedNode,
        val serverStatus: ServerStatus
    ) : PairingValidationResult()

    data class Failure(
        val failedStep: LiveCheckStep,
        val errorMessage: String
    ) : PairingValidationResult()
}

class NodePairingValidator(
    private val context: Context? = null,
    private val httpClient: PiHttpClient = PiHttpClient(context)
) {

    suspend fun executeValidation(
        rawHost: String,
        port: Int,
        pairingCode: String,
        providedTlsFp: String? = null,
        deviceName: String = Build.MODEL ?: "Android Device",
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        onStepUpdate: (LiveCheckStep, LiveCheckStepState) -> Unit
    ): PairingValidationResult = withContext(Dispatchers.IO) {
        val cleanHost = rawHost.trim().removePrefix("https://").removePrefix("http://")
        val host = if (cleanHost.contains(":")) cleanHost.substringBefore(":") else cleanHost

        val scheme = "https"
        val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val endpointUrl = "$scheme://$formattedHost:$port"

        // Step 1: Resolve name
        onStepUpdate(LiveCheckStep.RESOLVE_NAME, LiveCheckStepState.Running)
        val resolvedIp = try {
            val addr = InetAddress.getByName(host)
            addr.hostAddress ?: host
        } catch (e: Exception) {
            val err = mapErrorMessage(e, host, port)
            onStepUpdate(LiveCheckStep.RESOLVE_NAME, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.RESOLVE_NAME, err)
        }
        onStepUpdate(LiveCheckStep.RESOLVE_NAME, LiveCheckStepState.Passed("Resolved to $resolvedIp"))

        // Step 2: TCP Connect
        onStepUpdate(LiveCheckStep.TCP_CONNECT, LiveCheckStepState.Running)
        val tcpOk = httpClient.probeTcp(host, port, timeoutMs = 3000)
        if (!tcpOk) {
            val err = "Connection refused on port $port. Ensure the storage node is running."
            onStepUpdate(LiveCheckStep.TCP_CONNECT, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.TCP_CONNECT, err)
        }
        onStepUpdate(LiveCheckStep.TCP_CONNECT, LiveCheckStepState.Passed("TCP port $port open"))

        // Step 3 & 4: Server handshake & TLS Certificate
        onStepUpdate(LiveCheckStep.CHECK_SERVER_INFO, LiveCheckStepState.Running)
        val infoResult = httpClient.getInfo(endpointUrl, expectedFingerprint = providedTlsFp)
        if (infoResult.isFailure) {
            val ex = infoResult.exceptionOrNull()!!
            val err = mapErrorMessage(ex, host, port)
            onStepUpdate(LiveCheckStep.CHECK_SERVER_INFO, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.CHECK_SERVER_INFO, err)
        }
        val (info, serverCertFp) = infoResult.getOrThrow()
        if (info.protocolVersion != 1) {
            val err = "Endpoint answered, but is not a valid PiPhotos storage node (protocol v${info.protocolVersion} != v1)."
            onStepUpdate(LiveCheckStep.CHECK_SERVER_INFO, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.CHECK_SERVER_INFO, err)
        }
        onStepUpdate(LiveCheckStep.CHECK_SERVER_INFO, LiveCheckStepState.Passed("${info.name} (v${info.version})"))

        // Step 4: Certificate check
        onStepUpdate(LiveCheckStep.CHECK_CERTIFICATE, LiveCheckStepState.Running)
        val pinnedFp = providedTlsFp ?: serverCertFp
        if (pinnedFp.isNullOrBlank()) {
            val err = "Could not obtain TLS certificate fingerprint from storage node."
            onStepUpdate(LiveCheckStep.CHECK_CERTIFICATE, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.CHECK_CERTIFICATE, err)
        }
        onStepUpdate(LiveCheckStep.CHECK_CERTIFICATE, LiveCheckStepState.Passed("Fingerprint: ${pinnedFp.take(17)}..."))

        // Step 5: Pairing code
        onStepUpdate(LiveCheckStep.SUBMIT_PAIRING_CODE, LiveCheckStepState.Running)
        val pairResult = httpClient.pairDevice(
            endpointUrl = endpointUrl,
            code = pairingCode.trim(),
            deviceName = deviceName,
            deviceModel = deviceModel,
            expectedFingerprint = pinnedFp
        )
        if (pairResult.isFailure) {
            val ex = pairResult.exceptionOrNull()!!
            val err = mapErrorMessage(ex, host, port)
            onStepUpdate(LiveCheckStep.SUBMIT_PAIRING_CODE, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.SUBMIT_PAIRING_CODE, err)
        }
        val pairData = pairResult.getOrThrow()
        onStepUpdate(LiveCheckStep.SUBMIT_PAIRING_CODE, LiveCheckStepState.Passed("Device paired successfully"))

        // Step 6: Free space check
        onStepUpdate(LiveCheckStep.CHECK_FREE_SPACE, LiveCheckStepState.Running)
        val statusResult = httpClient.getStatus(
            endpointUrl = endpointUrl,
            authToken = pairData.token,
            expectedFingerprint = pinnedFp
        )
        if (statusResult.isFailure) {
            val ex = statusResult.exceptionOrNull()!!
            val err = mapErrorMessage(ex, host, port)
            onStepUpdate(LiveCheckStep.CHECK_FREE_SPACE, LiveCheckStepState.Failed(err))
            return@withContext PairingValidationResult.Failure(LiveCheckStep.CHECK_FREE_SPACE, err)
        }
        val status = statusResult.getOrThrow()
        val quotaGb = status.userQuota / (1024 * 1024 * 1024.0)
        onStepUpdate(LiveCheckStep.CHECK_FREE_SPACE, LiveCheckStepState.Passed("Quota: ${String.format("%.1f", quotaGb)} GB available"))

        val paired = PairedNode(
            nodeName = info.name,
            endpointUrl = endpointUrl,
            authToken = pairData.token,
            pairedAtMs = System.currentTimeMillis(),
            tlsFingerprint = pinnedFp,
            userId = pairData.userId,
            deviceId = pairData.deviceId,
            serverId = info.id,
            lastSeenMs = System.currentTimeMillis()
        )

        PairingValidationResult.Success(paired, status)
    }

    companion object {
        fun parseQrCode(content: String): QrPairPayload? {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                return try {
                    val host = Regex(""""host"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: return null
                    val port = Regex(""""port"\s*:\s*(\d+)""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 8443
                    val tlsFp = Regex(""""tls_fp"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                    val code = Regex(""""code"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
                    QrPairPayload(host, port, tlsFp, code)
                } catch (e: Exception) {
                    null
                }
            }

            if (trimmed.startsWith("piphotos://") || trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                return try {
                    val uri = java.net.URI(trimmed)
                    val query = uri.query ?: ""
                    val params = query.split("&").filter { it.contains("=") }.associate { param ->
                        val parts = param.split("=", limit = 2)
                        parts[0] to parts[1]
                    }
                    val host = params["host"] ?: uri.host ?: return null
                    val port = params["port"]?.toIntOrNull() ?: if (uri.port > 0) uri.port else 8443
                    val tlsFp = params["tls_fp"] ?: params["fp"]
                    val code = params["code"]
                    QrPairPayload(host, port, tlsFp, code)
                } catch (e: Exception) {
                    null
                }
            }

            return null
        }

        fun mapErrorMessage(error: Throwable, host: String, port: Int): String {
            return when (error) {
                is UnknownHostException -> "Unknown host or IP could not be resolved."
                is ConnectException -> "Connection refused on port $port. Ensure the storage node is running."
                is SocketTimeoutException -> "Connection timed out. Check IP address and Wi-Fi connection."
                is NoRouteToHostException -> "Storage node unreachable. Ensure your phone and the Pi are on the same local Wi-Fi."
                is ValidationException.CertificateMismatch ->
                    "Security warning: Server certificate fingerprint has changed from previous setup!"
                is CertificateException ->
                    if (error.message?.contains("mismatch", ignoreCase = true) == true) {
                        "Security warning: Server certificate fingerprint does not match expected node!"
                    } else {
                        "TLS certificate validation failed: ${error.message}"
                    }
                is HttpException -> {
                    when (error.code) {
                        401 -> "Incorrect pairing code. (Note: code invalidates after 5 failed attempts)."
                        409 -> "Pairing is closed on the server. Open pairing in the server console first."
                        429 -> "Too many pairing attempts. Please wait a minute before retrying."
                        507 -> "Storage node storage quota or disk space exceeded."
                        else -> error.message ?: "Server returned HTTP ${error.code}"
                    }
                }
                else -> error.localizedMessage ?: "Unexpected error during node validation."
            }
        }
    }
}
