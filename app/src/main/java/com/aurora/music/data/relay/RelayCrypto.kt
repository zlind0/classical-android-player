package com.aurora.music.data.relay

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 应用层加解密。中继服务器没有这把钥匙，只保存密文。
 * 标签与 relay-server/protocol/src/crypto.rs 完全一致。
 */
object RelayCrypto {
    private const val LABEL_ID = "pod-relay/v1/id"
    private const val LABEL_MEDIA = "pod-relay/v1/media"
    private const val LABEL_META = "pod-relay/v1/meta"
    private const val LABEL_LIVE = "pod-relay/v1/live"
    private const val TAG_BYTES = 16

    fun base64Key(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.size == 32 }
    }

    fun encodeKey(key: ByteArray): String =
        android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun keyId(master: ByteArray): Long {
        val digest = hmac(master, LABEL_ID.toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return value
    }

    fun mediaKey(master: ByteArray): ByteArray = hmac(master, LABEL_MEDIA.toByteArray())
    fun metaKey(master: ByteArray): ByteArray = hmac(master, LABEL_META.toByteArray())
    fun liveKey(master: ByteArray): ByteArray = hmac(master, LABEL_LIVE.toByteArray())

    private fun nonce(index: Int, salt: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = (index ushr 24).toByte()
        nonce[1] = (index ushr 16).toByte()
        nonce[2] = (index ushr 8).toByte()
        nonce[3] = index.toByte()
        System.arraycopy(salt, 0, nonce, 4, 8)
        return nonce
    }

    private fun fragmentAad(fileId: ByteArray, index: Int): ByteArray {
        val aad = ByteArray(20)
        System.arraycopy(fileId, 0, aad, 0, 16)
        aad[16] = (index ushr 24).toByte()
        aad[17] = (index ushr 16).toByte()
        aad[18] = (index ushr 8).toByte()
        aad[19] = index.toByte()
        return aad
    }

    fun decryptFragment(
        key: ByteArray,
        fileId: ByteArray,
        index: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce),
        )
        cipher.updateAAD(fragmentAad(fileId, index))
        return cipher.doFinal(ciphertext)
    }

    private fun liveAad(seq: Int, dir: Int): ByteArray {
        val aad = ByteArray(5)
        aad[0] = (seq ushr 24).toByte()
        aad[1] = (seq ushr 16).toByte()
        aad[2] = (seq ushr 8).toByte()
        aad[3] = seq.toByte()
        aad[4] = dir.toByte()
        return aad
    }

    private fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** 直通请求加密：随机 12 字节 nonce 前置于密文。dir=1 请求，2 响应。 */
    fun liveSeal(key: ByteArray, seq: Int, dir: Int, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        java.security.SecureRandom().nextBytes(nonce)
        val ct = seal(key, nonce, liveAad(seq, dir), plain)
        return nonce + ct
    }

    fun liveOpen(key: ByteArray, seq: Int, dir: Int, data: ByteArray): ByteArray {
        require(data.size > 12 + TAG_BYTES) { "live payload too short" }
        val nonce = data.copyOfRange(0, 12)
        return open(key, nonce, liveAad(seq, dir), data.copyOfRange(12, data.size))
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** 快照头，来自 LIB_VERSION_OK。 */
    data class SnapshotHeader(
        val version: Long,
        val keyId: Long,
        val plainLen: Long,
        val gzLen: Long,
        val chunks: Int,
        val salt: ByteArray,
    )

    private fun snapshotAad(version: Long, chunkIndex: Int): ByteArray {
        val aad = ByteArray(20)
        val magic = "AURLIB1\u0000".toByteArray()
        System.arraycopy(magic, 0, aad, 0, 8)
        for (index in 0 until 8) {
            aad[8 + index] = ((version ushr (56 - index * 8)) and 0xFF).toByte()
        }
        aad[16] = (chunkIndex ushr 24).toByte()
        aad[17] = (chunkIndex ushr 16).toByte()
        aad[18] = (chunkIndex ushr 8).toByte()
        aad[19] = chunkIndex.toByte()
        return aad
    }

    fun decryptSnapshot(
        master: ByteArray,
        header: SnapshotHeader,
        chunks: List<ByteArray>,
    ): ByteArray {
        require(chunks.size == header.chunks) { "snapshot chunk count mismatch" }
        val key = metaKey(master)
        val gz = java.io.ByteArrayOutputStream()
        chunks.forEachIndexed { index, ct ->
            gz.write(
                open(
                    key,
                    nonce(index, header.salt),
                    snapshotAad(header.version, index),
                    ct,
                ),
            )
        }
        val gzBytes = gz.toByteArray()
        require(gzBytes.size.toLong() == header.gzLen) { "snapshot gz length mismatch" }
        val plain = GZIPInputStream(ByteArrayInputStream(gzBytes)).use { it.readBytes() }
        require(plain.size.toLong() == header.plainLen) { "snapshot plain length mismatch" }
        return plain
    }
}
