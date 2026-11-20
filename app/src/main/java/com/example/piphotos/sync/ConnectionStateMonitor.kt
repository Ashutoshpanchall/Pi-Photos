package com.example.piphotos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.piphotos.net.HttpException
import com.example.piphotos.net.PiHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionStateMonitor(
    private val context: Context,
    private val httpClient: PiHttpClient = PiHttpClient.getInstance(context)
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var pollJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(scope: CoroutineScope) {
        stop()

        // 1. ConnectivityManager callback
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { checkNow() }
            }

            override fun onLost(network: Network) {
                scope.launch { checkNow() }
            }
        }
        try {
            cm?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            // Ignore callback registration failure
        }

        // 2. Initial check + 30-second polling while screen is active
        pollJob = scope.launch {
            while (isActive) {
                checkNow()
                delay(30_000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        networkCallback?.let { cb ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try {
                cm?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Ignore
            }
        }
        networkCallback = null
    }

    suspend fun checkNow() = withContext(Dispatchers.IO) {
        val paired = SyncNodePreferences.getPairedNode(context)
        if (paired == null) {
            _connectionState.value = ConnectionState.NotConfigured
            return@withContext
        }

        val statusResult = httpClient.getStatus(
            endpointUrl = paired.endpointUrl,
            authToken = paired.authToken,
            expectedFingerprint = paired.tlsFingerprint.ifEmpty { null }
        )

        if (statusResult.isSuccess) {
            SyncNodePreferences.updateLastSeen(context)
            _connectionState.value = ConnectionState.Connected
            return@withContext
        }

        val ex = statusResult.exceptionOrNull()
        if (ex is HttpException && (ex.code == 401 || ex.code == 403)) {
            _connectionState.value = ConnectionState.ReachableAuthNeeded
            return@withContext
        }

        // Check if endpoint is at least reachable via /info
        val infoResult = httpClient.getInfo(
            endpointUrl = paired.endpointUrl,
            expectedFingerprint = paired.tlsFingerprint.ifEmpty { null }
        )

        if (infoResult.isSuccess) {
            _connectionState.value = ConnectionState.ReachableAuthNeeded
        } else {
            val lastSeen = SyncNodePreferences.getLastSeenTimestamp(context)
            _connectionState.value = ConnectionState.Unreachable(lastSeen)
        }
    }

    companion object {
        fun evaluateState(
            isPaired: Boolean,
            endpointReachable: Boolean,
            authValid: Boolean,
            lastSeenMs: Long
        ): ConnectionState {
            if (!isPaired) return ConnectionState.NotConfigured
            if (endpointReachable && authValid) return ConnectionState.Connected
            if (endpointReachable && !authValid) return ConnectionState.ReachableAuthNeeded
            return ConnectionState.Unreachable(lastSeenMs)
        }
    }
}
