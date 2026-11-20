package com.example.piphotos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.piphotos.net.PiHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

data class DiscoveredNode(
    val name: String,
    val hostAddress: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap(),
    val tlsFingerprint: String? = attributes["tls_fp"]
) {
    val formattedHost: String
        get() = if (hostAddress.contains(":") && !hostAddress.startsWith("[")) "[$hostAddress]" else hostAddress

    val useHttps: Boolean
        get() = attributes.containsKey("tls_fp") || port == 8443

    val endpointUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            return "$scheme://$formattedHost:$port"
        }
}

sealed class DiscoveryUiState {
    data class Scanning(val secondsRemaining: Int) : DiscoveryUiState()
    data class Found(val nodes: List<DiscoveredNode>) : DiscoveryUiState()
    object NothingFound : DiscoveryUiState()
}

/**
 * Robust discovery pipeline for PiPhotos storage nodes:
 * 1. NsdManager mDNS resolution with a sequential queue and IPv4 preference (API 34+ ServiceInfoCallback).
 * 2. Fallback probe of piphotos.local:8443.
 * 3. Fallback parallel probe of local /24 subnet on port 8443.
 * 4. Deterministic 10s countdown to Found or NothingFound.
 */
class PiDeviceDiscovery(
    private val context: Context,
    private val httpClient: PiHttpClient = PiHttpClient.getInstance(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null

    private val nsdManager = try {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: Exception) {
        null
    }

    private val wifiManager = try {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    } catch (e: Exception) {
        null
    }
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _nodes = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val nodes: StateFlow<List<DiscoveredNode>> = _nodes.asStateFlow()

    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Scanning(10))
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private val discoveredMap = mutableMapOf<String, DiscoveredNode>()
    private var isScanning = false

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            isScanning = false
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            isScanning = false
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            isScanning = true
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            isScanning = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo == null) return
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo == null) return
            synchronized(discoveredMap) {
                discoveredMap.remove(serviceInfo.serviceName)
                updateDiscoveredNodes()
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(serviceInfo)
            processNextResolve()
        }
    }

    private fun processNextResolve() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) return
            isResolving = true
            val next = resolveQueue.removeFirst()
            resolveSingleService(next)
        }
    }

    private fun resolveSingleService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: run {
            onResolveFinished()
            return
        }

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ ServiceInfoCallback API
            try {
                manager.registerServiceInfoCallback(
                    serviceInfo,
                    callbackExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            onResolveFinished()
                        }

                        override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                            handleResolvedService(resolvedInfo)
                            try {
                                manager.unregisterServiceInfoCallback(this)
                            } catch (e: Exception) {
                                // Ignore
                            }
                            onResolveFinished()
                        }

                        override fun onServiceLost() {
                            onResolveFinished()
                        }

                        override fun onServiceInfoCallbackUnregistered() {
                            // Unregistered
                        }
                    }
                )
            } catch (e: Exception) {
                onResolveFinished()
            }
        } else {
            // Pre-API 34 resolveService API
            try {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        onResolveFinished()
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        if (resolvedInfo != null) {
                            handleResolvedService(resolvedInfo)
                        }
                        onResolveFinished()
                    }
                })
            } catch (e: Exception) {
                onResolveFinished()
            }
        }
    }

    private fun onResolveFinished() {
        synchronized(resolveQueue) {
            isResolving = false
            processNextResolve()
        }
    }

    private fun handleResolvedService(resolvedInfo: NsdServiceInfo) {
        val host = resolvedInfo.host ?: return
        val hostAddress = host.hostAddress ?: return
        val port = resolvedInfo.port

        val attrs = mutableMapOf<String, String>()
        resolvedInfo.attributes.forEach { (k, v) ->
            attrs[k] = String(v, Charsets.UTF_8)
        }

        val node = DiscoveredNode(
            name = resolvedInfo.serviceName ?: "Home Vault",
            hostAddress = hostAddress,
            port = port,
            attributes = attrs,
            tlsFingerprint = attrs["tls_fp"]
        )

        synchronized(discoveredMap) {
            discoveredMap[node.name] = node
            updateDiscoveredNodes()
        }
    }

    private fun updateDiscoveredNodes() {
        val list = discoveredMap.values.toList()
        _nodes.value = list
        if (list.isNotEmpty()) {
            _uiState.value = DiscoveryUiState.Found(list)
        }
    }

    fun startDiscovery() {
        if (isScanning) return
        stopDiscovery()

        synchronized(discoveredMap) {
            discoveredMap.clear()
            _nodes.value = emptyList()
        }
        _uiState.value = DiscoveryUiState.Scanning(10)

        // 1. Acquire multicast lock
        try {
            multicastLock = wifiManager?.createMulticastLock("piphotos_mdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            // Ignore
        }

        // 2. Start NsdManager discovery
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isScanning = false
        }

        // 3. Launch timer + Fallback probing
        discoveryJob = scope.launch {
            // Fallback: piphotos.local probe
            launch { probePiphotosLocal() }

            // Fallback: Subnet /24 probe
            launch { probeLocalSubnet() }

            // 10s countdown
            for (sec in 10 downTo 1) {
                delay(1000)
                if (!isActive) break
                synchronized(discoveredMap) {
                    if (discoveredMap.isNotEmpty()) {
                        _uiState.value = DiscoveryUiState.Found(discoveredMap.values.toList())
                    } else {
                        _uiState.value = DiscoveryUiState.Scanning(sec - 1)
                    }
                }
            }

            synchronized(discoveredMap) {
                if (discoveredMap.isEmpty()) {
                    _uiState.value = DiscoveryUiState.NothingFound
                } else {
                    _uiState.value = DiscoveryUiState.Found(discoveredMap.values.toList())
                }
            }
        }
    }

    private suspend fun probePiphotosLocal() {
        try {
            val endpoint = "https://piphotos.local:8443"
            val res = httpClient.getInfo(endpoint)
            if (res.isSuccess) {
                val (info, fp) = res.getOrThrow()
                val node = DiscoveredNode(
                    name = info.name,
                    hostAddress = "piphotos.local",
                    port = 8443,
                    attributes = if (fp != null) mapOf("tls_fp" to fp) else emptyMap(),
                    tlsFingerprint = fp
                )
                synchronized(discoveredMap) {
                    discoveredMap[node.name] = node
                    updateDiscoveredNodes()
                }
            }
        } catch (e: Exception) {
            // piphotos.local probe failed
        }
    }

    private suspend fun probeLocalSubnet() {
        val localIpv4 = getLocalIpv4Address() ?: return
        val prefix = localIpv4.substringBeforeLast(".") + "."
        val semaphore = Semaphore(25)

        val jobs = (1..254).map { i ->
            scope.async {
                semaphore.withPermit {
                    val ip = "$prefix$i"
                    if (ip == localIpv4) return@withPermit
                    val portOpen = httpClient.probeTcp(ip, 8443, timeoutMs = 400)
                    if (portOpen) {
                        val res = httpClient.getInfo("https://$ip:8443")
                        if (res.isSuccess) {
                            val (info, fp) = res.getOrThrow()
                            val node = DiscoveredNode(
                                name = info.name,
                                hostAddress = ip,
                                port = 8443,
                                attributes = if (fp != null) mapOf("tls_fp" to fp) else emptyMap(),
                                tlsFingerprint = fp
                            )
                            synchronized(discoveredMap) {
                                discoveredMap[node.name] = node
                                updateDiscoveredNodes()
                            }
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    private fun getLocalIpv4Address(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNet = cm?.allNetworks?.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNet != null) {
                val linkProps = cm.getLinkProperties(wifiNet)
                val wifiAddr = linkProps?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                if (wifiAddr != null && (wifiAddr.startsWith("192.168.") || wifiAddr.startsWith("10.") || wifiAddr.startsWith("172."))) {
                    return wifiAddr
                }
            }
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null

        if (isScanning && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // Ignore
            } finally {
                isScanning = false
            }
        }

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_piphotos._tcp."
    }
}
