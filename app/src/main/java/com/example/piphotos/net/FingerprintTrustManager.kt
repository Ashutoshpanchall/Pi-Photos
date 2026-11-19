package com.example.piphotos.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Custom X509TrustManager implementing Trust-On-First-Use (TOFU) certificate fingerprint pinning.
 *
 * Verifies that the SHA-256 digest of the server's DER-encoded X.509 leaf certificate matches
 * the expected fingerprint stored upon pairing or advertised via mDNS / QR code.
 *
 * Hostname verification is bypassed because self-signed storage node certificates are issued
 * for local network dynamic IP addresses and .local mDNS names.
 */
class FingerprintTrustManager(
    var expectedFingerprint: String? = null
) : X509TrustManager {

    var capturedFingerprint: String? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Client certs not required for device client
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server certificate chain is empty")
        }

        val leafCert = chain[0]
        val actualFp = computeFingerprint(leafCert)
        capturedFingerprint = actualFp

        val expected = expectedFingerprint
        if (!expected.isNullOrBlank()) {
            val normalizedExpected = normalizeFingerprint(expected)
            val normalizedActual = normalizeFingerprint(actualFp)
            if (normalizedExpected != normalizedActual) {
                throw CertificateException(
                    "Certificate fingerprint mismatch! Expected: $expected, Received: $actualFp"
                )
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun createSslSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(this), SecureRandom())
        return sslContext.socketFactory
    }

    companion object {
        val PermissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

        fun normalizeFingerprint(fp: String): String {
            return fp.replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .trim()
                .uppercase()
        }

        fun computeFingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            val sb = StringBuilder()
            for (i in hash.indices) {
                sb.append(String.format("%02X", hash[i]))
                if (i < hash.size - 1) {
                    sb.append(":")
                }
            }
            return sb.toString()
        }
    }
}
