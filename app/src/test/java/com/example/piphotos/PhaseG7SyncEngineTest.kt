package com.example.piphotos

import com.example.piphotos.sync.DiscoveredNode
import com.example.piphotos.sync.MediaHashCalculator
import com.example.piphotos.sync.PairedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun folderFilter_matchesBucketsAccurately() {
        fun isItemIncluded(bucket: String, cameraOn: Boolean, screenshotsOn: Boolean): Boolean {
            val b = bucket.lowercase()
            return when {
                b == "camera" || b.contains("dcim") -> cameraOn
                b.contains("screenshot") -> screenshotsOn
                else -> false
            }
        }

        assertTrue(isItemIncluded("Camera", cameraOn = true, screenshotsOn = false))
        assertTrue(isItemIncluded("DCIM", cameraOn = true, screenshotsOn = false))
        assertFalse(isItemIncluded("Camera", cameraOn = false, screenshotsOn = true))
        assertTrue(isItemIncluded("Screenshots", cameraOn = false, screenshotsOn = true))
        assertFalse(isItemIncluded("Screenshots", cameraOn = true, screenshotsOn = false))
        assertFalse(isItemIncluded("Downloads", cameraOn = true, screenshotsOn = true))
    }

    @Test
    fun etaFormatting_scalesCorrectly() {
        fun formatEta(etaSec: Long): String {
            if (etaSec <= 0) return "Calculating..."
            val mins = etaSec / 60
            val secs = etaSec % 60
            return when {
                mins >= 60 -> "${mins / 60}h ${mins % 60}m remaining"
                mins > 0 -> "${mins}m remaining"
                else -> "${secs}s remaining"
            }
        }

        assertEquals("45s remaining", formatEta(45))
        assertEquals("3m remaining", formatEta(180))
        assertEquals("1h 15m remaining", formatEta(4500))
        assertEquals("Calculating...", formatEta(-1))
    }
}
