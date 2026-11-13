package com.example.piphotos.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class DiscoveredNode(
    val name: String,
    val hostAddress: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap()
) {
    val endpointUrl: String
        get() = "http://$hostAddress:$port"
}

/**
 * Native Network Service Discovery (mDNS) manager that scans local Wi-Fi networks
 * for Pi Photos storage nodes advertising the `_piphotos._tcp` service.
 */
class PiDeviceDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _nodes = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val nodes: StateFlow<List<DiscoveredNode>> = _nodes.asStateFlow()

    private val discoveredMap = mutableMapOf<String, DiscoveredNode>()
    private var isScanning = false

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
            resolveService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo == null) return
            synchronized(discoveredMap) {
                discoveredMap.remove(serviceInfo.serviceName)
                _nodes.value = discoveredMap.values.toList()
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.let { manager ->
            try {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        // Resolve failed, ignore transient error
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        if (resolvedInfo == null) return
                        val host = resolvedInfo.host ?: return
                        val hostAddress = host.hostAddress ?: return
                        val port = resolvedInfo.port

                        val attrs = mutableMapOf<String, String>()
                        resolvedInfo.attributes.forEach { (k, v) ->
                            attrs[k] = String(v, Charsets.UTF_8)
                        }

                        val node = DiscoveredNode(
                            name = resolvedInfo.serviceName ?: "Vault Node",
                            hostAddress = hostAddress,
                            port = port,
                            attributes = attrs
                        )

                        synchronized(discoveredMap) {
                            discoveredMap[node.name] = node
                            _nodes.value = discoveredMap.values.toList()
                        }
                    }
                })
            } catch (e: Exception) {
                // Resolution queue limit or transient error
            }
        }
    }

    fun startDiscovery() {
        if (isScanning || nsdManager == null) return
        synchronized(discoveredMap) {
            discoveredMap.clear()
            _nodes.value = emptyList()
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isScanning = false
        }
    }

    fun stopDiscovery() {
        if (!isScanning || nsdManager == null) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            // Already stopped
        } finally {
            isScanning = false
        }
    }

    companion object {
        const val SERVICE_TYPE = "_piphotos._tcp."
    }
}

