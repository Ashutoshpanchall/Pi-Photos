package com.example.piphotos

import com.example.piphotos.net.FingerprintTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class FingerprintTrustManagerTest {

    private fun generateSelfSignedCert(): X509Certificate {
        // Genuine X.509 DER certificate
        val certB64 = "MIICojCCAYqgAwIBAgICA+gwDQYJKoZIhvcNAQELBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0" +
                "MB4XDTI2MTExOTEyMzgyMVoXDTI2MTEyOTEyMzgyMVowFDESMBAGA1UEAwwJbG9jYWxob3N0" +
                "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1duPAZ9qDqIKl3/tjBAoY7ATXjX6" +
                "c5UyoF/T2jHAJvXrTS+F/gl726En10WI47Hq5WkNFyjvPkzdWqV7U08mWhMNUZHNfd/hEORo" +
                "VHr1YQMsLcRXRA4lzhnNwhlgqOUqGVYV68VjhkpTlFQQD0d3/mg4NVBglhOZFzyvEB3YvExH" +
                "VGT/N5m64NJFaop3bw3fTK3d9u+JjGmFMknfLVK0T/mNjzESNP9nvcL5gJm3Sl50GPcnSlCX" +
                "agNeT98sOr6w+TFEknaxliqTobKQLidXCEFCFN5XvvVVc9evnPXg5j8QtQSwseTQVz4AwTD7" +
                "22Ch2qH8XBki/Trf2eBSqnpAdQIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQAzZBQzLnDfYjuw" +
                "vbvl/z27+JDFKbXhr+ExyKO2NWKyxFzt+vHTrJCxF2gGI+sdgBdaasJh5MbmjKfOazivIfC5" +
                "6jr/kgUGZ74LT/YcGLRdNz32X6bLVBlvNJ0xMkJ2ga1Fl9kg/nbtMMlvS+QwYkYbTvPZSBXE" +
                "U1KAv6mpFOiAZgYKqHlmAz/wFgW4jclGypWHmrEI8OEqxen4qh4SKEF9cz+ndSGnHxZDpuOi" +
                "zjHmgQygpmCfZ1Fm7ISVvCRbOIla6mrysnOX03GeJcp6ZCWd4nCBma9/7ZOBZzeo5vsitHRH" +
                "CRok85Wl+gBbV6kLhfGzMTxkbRietquOAXRglmVL"
        val certBytes = java.util.Base64.getDecoder().decode(certB64)
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    @Test
    fun computeSha256Fingerprint_formatsColonSeparatedUppercaseHex() {
        val cert = generateSelfSignedCert()
        val fp = FingerprintTrustManager.computeFingerprint(cert)
        assertTrue(fp.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")))
    }

    @Test
    fun checkServerTrusted_acceptsMatchingFingerprint() {
        val cert = generateSelfSignedCert()
        val fp = FingerprintTrustManager.computeFingerprint(cert)

        // Test with exact match
        val managerExact = FingerprintTrustManager(expectedFingerprint = fp)
        managerExact.checkServerTrusted(arrayOf(cert), "RSA")

        // Test with lowercase and no-colon formatting
        val rawHex = fp.replace(":", "").lowercase()
        val managerFlexible = FingerprintTrustManager(expectedFingerprint = rawHex)
        managerFlexible.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun checkServerTrusted_rejectsMismatchingFingerprint() {
        val cert = generateSelfSignedCert()
        val wrongFp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val manager = FingerprintTrustManager(expectedFingerprint = wrongFp)
        manager.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun trustOnFirstUse_capturesFingerprintWhenNotPreconfigured() {
        val cert = generateSelfSignedCert()
        val expectedFp = FingerprintTrustManager.computeFingerprint(cert)

        val tofuManager = FingerprintTrustManager(expectedFingerprint = null)
        tofuManager.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(expectedFp, tofuManager.capturedFingerprint)
    }
}
