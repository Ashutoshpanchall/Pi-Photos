package com.example.piphotos

import com.example.piphotos.sync.DiscoveredNode
import com.example.piphotos.sync.MediaHashCalculator
import com.example.piphotos.sync.PairedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class PhaseG7SyncEngineTest {

    @Test
    fun sha256_computesEmptyStreamCorrectly() {
        val stream = ByteArrayInputStream(ByteArray(0))
        val hash = MediaHashCalculator.computeSha256(stream)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun sha256_computesSampleContentCorrectly() {
        val content = "hello world".toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(content)
        val hash = MediaHashCalculator.computeSha256(stream)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun discoveredNode_derivesEndpointUrlCorrectly() {
        val node = DiscoveredNode(
            name = "Test Vault",
            hostAddress = "192.168.1.150",
            port = 8080
        )
        assertEquals("http://192.168.1.150:8080", node.endpointUrl)
    }

    @Test
    fun pairedNode_retainsMetadata() {
        val paired = PairedNode(
            nodeName = "Family Vault",
            endpointUrl = "http://192.168.1.200:8080",
            authToken = "test-token-xyz",
            pairedAtMs = 1700000000000L
        )
        assertEquals("Family Vault", paired.nodeName)
        assertEquals("http://192.168.1.200:8080", paired.endpointUrl)
        assertEquals("test-token-xyz", paired.authToken)
        assertEquals(1700000000000L, paired.pairedAtMs)
    }
}

