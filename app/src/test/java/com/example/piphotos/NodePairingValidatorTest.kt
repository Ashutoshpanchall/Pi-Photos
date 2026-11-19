package com.example.piphotos

import com.example.piphotos.sync.LiveCheckStep
import com.example.piphotos.sync.LiveCheckStepState
import com.example.piphotos.sync.NodePairingValidator
import com.example.piphotos.sync.ValidationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NodePairingValidatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mapErrorMessage_mapsCommonNetworkFailures() {
        assertEquals(
            "Connection refused on port 8443. Ensure the storage node is running.",
            NodePairingValidator.mapErrorMessage(java.net.ConnectException("Connection refused"), "192.168.1.50", 8443)
        )
        assertEquals(
            "Connection timed out. Check IP address and Wi-Fi connection.",
            NodePairingValidator.mapErrorMessage(java.net.SocketTimeoutException("Read timed out"), "192.168.1.50", 8443)
        )
        assertEquals(
            "Unknown host or IP could not be resolved.",
            NodePairingValidator.mapErrorMessage(java.net.UnknownHostException("piphotos.invalid"), "piphotos.invalid", 8443)
        )
        assertEquals(
            "Security warning: Server certificate fingerprint has changed from previous setup!",
            NodePairingValidator.mapErrorMessage(ValidationException.CertificateMismatch("FP1", "FP2"), "192.168.1.50", 8443)
        )
    }

    @Test
    fun parseQrCodePayload_extractsFieldsCorrectly() {
        val jsonPayload = """{"host":"192.168.1.120","port":8443,"tls_fp":"AA:BB:CC:DD","code":"654321"}"""
        val parsedJson = NodePairingValidator.parseQrCode(jsonPayload)
        assertEquals("192.168.1.120", parsedJson?.host)
        assertEquals(8443, parsedJson?.port)
        assertEquals("AA:BB:CC:DD", parsedJson?.tlsFingerprint)
        assertEquals("654321", parsedJson?.code)

        val uriPayload = "piphotos://pair?host=10.0.0.5&port=8443&tls_fp=11:22:33&code=123456"
        val parsedUri = NodePairingValidator.parseQrCode(uriPayload)
        assertEquals("10.0.0.5", parsedUri?.host)
        assertEquals(8443, parsedUri?.port)
        assertEquals("11:22:33", parsedUri?.tlsFingerprint)
        assertEquals("123456", parsedUri?.code)
    }
}
