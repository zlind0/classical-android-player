package com.aurora.music.data.cast.receiver.airplay

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.RSAPrivateCrtKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay / RAOP cryptography.
 *
 * RAOP encrypts the per-session AES key with a fixed 2048-bit RSA key pair that
 * every AirPort Express / Apple TV used to ship (`rsaaeskey` in the SDP). The
 * matching private key is public and embedded below, which is what all
 * open-source AirPlay receivers do.
 *
 * Screen mirroring then reuses the FairPlay-decrypted audio AES key to derive a
 * separate AES-CTR key/IV for the video stream (see [VideoCrypto]).
 */
object AirPlayCrypto {

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** PKCS#1 `RSAPrivateKey` DER, wrapped as PEM (AirPort Express key). */
    private const val AIRPORT_PEM = """
-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt
wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U
wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf
/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/
UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW
BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa
LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5
NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm
lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz
aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu
a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM
oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z
oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+
k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL
AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA
cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf
54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov
17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc
1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI
LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ
2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=
-----END RSA PRIVATE KEY-----
"""

    private val privateKey: PrivateKey by lazy { parsePkcs1PrivateKey(AIRPORT_PEM) }

    /** Test hook: derives the matching public key so the RSA path can be exercised. */
    internal fun airportPublicKey(): java.security.PublicKey {
        val crt = privateKey as java.security.interfaces.RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent))
    }

    /** `rsaaeskey` from the SDP ANNOUNCE; returns the 16-byte RAOP AES key. */
    fun decryptRaopAesKey(rsaaeskey: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(rsaaeskey).also {
            require(it.size == 16) { "RAOP AES key is ${it.size} bytes" }
        }
    }.getOrNull()

    /** AES-128-CBC, no padding: RAOP leaves the trailing <16 bytes in clear. */
    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = data.copyOfRange(offset, offset + length)
        val encrypted = length / 16 * 16
        if (encrypted <= 0) return out
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(data, offset, encrypted)
        System.arraycopy(decrypted, 0, out, 0, decrypted.size)
        return out
    }

    /**
     * AirPlay video key derivation, matching UxPlay's `mirror_buffer_init_aes`:
     * SHA-512("AirPlayStreamKey" + decimal streamConnectionID) then append the
     * 16-byte audio AES key; the first 16 digest bytes are the AES-128 key.
     */
    fun deriveVideoKey(streamConnectionId: Long, audioAesKey: ByteArray): ByteArray =
        sha512(
            "AirPlayStreamKey${java.lang.Long.toUnsignedString(streamConnectionId)}"
                .toByteArray(Charsets.US_ASCII) + audioAesKey,
        ).copyOf(16)

    /** AirPlay video IV derivation (same construction as [deriveVideoKey]). */
    fun deriveVideoIv(streamConnectionId: Long, audioAesKey: ByteArray): ByteArray =
        sha512(
            "AirPlayStreamIV${java.lang.Long.toUnsignedString(streamConnectionId)}"
                .toByteArray(Charsets.US_ASCII) + audioAesKey,
        ).copyOf(16)

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    fun decodeBase64(value: String): ByteArray? = runCatching {
        // Pure-Kotlin decoder: keeps SDP parsing testable on the JVM without
        // android.util.Base64 (AirPlay senders use standard base64).
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in value) {
            if (c.isWhitespace()) continue
            if (c == '=') break
            val v = BASE64_ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base64" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        out.toByteArray()
    }.getOrNull()

    // ------------------------------------------------------- DER / key parsing

    private fun parsePkcs1PrivateKey(pem: String): PrivateKey {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val der = decodeBase64(body) ?: error("invalid RSA PEM")
        val reader = DerReader(der)
        val sequence = reader.readSequence()
        val version = sequence.readInteger() // 0
        require(version.signum() == 0) { "unsupported RSA key version" }
        val n = sequence.readInteger()
        val e = sequence.readInteger()
        val d = sequence.readInteger()
        val p = sequence.readInteger()
        val q = sequence.readInteger()
        val dp = sequence.readInteger()
        val dq = sequence.readInteger()
        val qi = sequence.readInteger()
        val spec = RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private class DerReader(private val data: ByteArray, private var pos: Int = 0) {
        fun readSequence(): DerReader {
            val tag = data[pos++].toInt() and 0xFF
            require(tag == 0x30) { "expected SEQUENCE, got $tag" }
            val length = readLength()
            val child = DerReader(data.copyOfRange(pos, pos + length))
            pos += length
            return child
        }

        fun readInteger(): BigInteger {
            val tag = data[pos++].toInt() and 0xFF
            require(tag == 0x02) { "expected INTEGER, got $tag" }
            val length = readLength()
            val value = data.copyOfRange(pos, pos + length)
            pos += length
            return BigInteger(1, value)
        }

        private fun readLength(): Int {
            val first = data[pos++].toInt() and 0xFF
            if (first < 0x80) return first
            val bytes = first and 0x7F
            var length = 0
            repeat(bytes) { length = (length shl 8) or (data[pos++].toInt() and 0xFF) }
            return length
        }
    }
}

/**
 * AES-CTR decryption state for AirPlay mirroring packets (port of UxPlay
 * `mirror_buffer_decrypt`).
 *
 * The sender does not restart the counter for every packet. The keystream is
 * continuous for the whole stream; at a packet boundary any unused bytes of the
 * current 16-byte block are discarded (the next packet starts on a fresh block)
 * but the counter itself keeps advancing. Resetting the IV per packet — which
 * the old implementation did — only decrypted the first packet correctly.
 */
class AirPlayVideoDecryptor(key: ByteArray, iv: ByteArray) {

    private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = iv.copyOf(16)
    private var block: ByteArray? = null
    private var offset = 0

    @Synchronized
    fun decrypt(input: ByteArray, inputOffset: Int = 0, length: Int = input.size - inputOffset): ByteArray {
        // Consume the tail of the previous packet's 16-byte keystream block
        // first (UxPlay mirror_buffer_decrypt's nextDecryptCount path), then
        // continue with the following CTR blocks.
        val out = ByteArray(length)
        var written = 0
        while (written < length) {
            val b = ensureBlock()
            val n = minOf(16 - offset, length - written)
            for (i in 0 until n) {
                out[written + i] = (input[inputOffset + written + i].toInt() xor b[offset + i].toInt()).toByte()
            }
            written += n
            offset += n
            if (offset == 16) {
                block = null
                offset = 0
            }
        }
        return out
    }

    @Synchronized
    fun reset() {
        block = null
        offset = 0
        // Caller recreates the instance when a new stream starts; the IV is
        // final, so nothing else to reset here.
    }

    private fun ensureBlock(): ByteArray {
        block?.let { return it }
        val generated = cipher.doFinal(counter)
        incrementCounter(counter)
        block = generated
        offset = 0
        return generated
    }

    private fun incrementCounter(counter: ByteArray) {
        for (i in counter.indices.reversed()) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i].toInt() != 0) break
        }
    }
}
