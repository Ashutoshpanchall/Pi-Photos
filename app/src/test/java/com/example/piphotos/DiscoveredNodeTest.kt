package com.example.piphotos

import com.example.piphotos.sync.DiscoveredNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredNodeTest {

    @Test
    fun formatsIPv6HostWithBrackets() {
        val node = DiscoveredNode(
            name = "IPv6 Node",
            hostAddress = "fe80::1ff:fe23:4567:890a",
            port = 8443,
            attributes = mapOf("tls_fp" to "AA:BB")
        )
        assertEquals("[fe80::1ff:fe23:4567:890a]", node.formattedHost)
        assertEquals("https://[fe80::1ff:fe23:4567:890a]:8443", node.endpointUrl)
    }

    @Test
    fun formatsIPv4HostNormally() {
        val node = DiscoveredNode(
            name = "IPv4 Node",
            hostAddress = "192.168.1.100",
            port = 8443,
            attributes = mapOf("tls_fp" to "AA:BB")
        )
        assertEquals("192.168.1.100", node.formattedHost)
        assertEquals("https://192.168.1.100:8443", node.endpointUrl)
    }

    @Test
    fun preservesHttpWhenPortIs8080AndNoTls() {
        val node = DiscoveredNode(
            name = "Legacy Node",
            hostAddress = "192.168.1.150",
            port = 8080
        )
        assertEquals("http://192.168.1.150:8080", node.endpointUrl)
    }
}
