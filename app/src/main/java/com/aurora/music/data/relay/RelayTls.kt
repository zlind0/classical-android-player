package com.aurora.music.data.relay

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 连接：自签证书 + 指纹固定。中继服务器证书的 SHA-256（base64）必须与设置一致。
 */
object RelayTls {

    fun open(settings: RelaySettings, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 30_000): SSLSocket {
        val context = buildContext(settings)
        val address = settings.addr.substringBeforeLast(':')
        val port = settings.addr.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalArgumentException("relay address must be host:port")
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(address, port), connectTimeoutMs)
        raw.soTimeout = readTimeoutMs
        val socket = context.socketFactory.createSocket(raw, address, port, true) as SSLSocket
        socket.useClientMode = true
        socket.soTimeout = readTimeoutMs
        socket.startHandshake()
        return socket
    }

    private fun buildContext(settings: RelaySettings): SSLContext {
        val certs = parseCerts(settings.certPem)
        val key = parsePrivateKey(settings.keyPem)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("pod-relay-client", key, charArrayOf(), certs.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())
        val trust = arrayOf<TrustManager>(PinnedTrustManager(settings.fingerprint))
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, trust, java.security.SecureRandom())
        }
    }

    private fun parseCerts(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val blocks = pemBlocks(pem)
        require(blocks.isNotEmpty()) { "client certificate is empty" }
        return blocks.map { block ->
            factory.generateCertificate(block.inputStream()) as X509Certificate
        }
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val blocks = pemBlocks(pem)
        val der = blocks.firstOrNull() ?: throw IllegalArgumentException("client key is empty")
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("EC", "RSA")) {
            runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }
                .getOrNull()
                ?.let { return it }
        }
        throw IllegalArgumentException("unsupported private key format (need PKCS#8 PEM)")
    }

    private fun pemBlocks(pem: String): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val regex = Regex(
            "-----BEGIN [^-]+-----([\\s\\S]*?)-----END [^-]+-----",
        )
        regex.findAll(pem).forEach { match ->
            val body = match.groupValues[1].replace("\\s".toRegex(), "")
            if (body.isNotEmpty()) {
                out += Base64.decode(body, Base64.DEFAULT)
            }
        }
        return out
    }

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        private fun fingerprint(cert: X509Certificate): String =
            "sha256/" + Base64.encodeToString(RelayCrypto.sha256(cert.encoded), Base64.NO_WRAP)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used here")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val got = fingerprint(cert)
            if (got != expected.trim()) {
                throw CertificateException("relay certificate pin mismatch: expected $expected, got $got")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
