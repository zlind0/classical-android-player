package com.aurora.music.data.relay

import android.util.Base64
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * 远程配对字符串（NAS 桌面端生成）：
 *
 * `podrelay://remote?v=1&d=<base64url(gzip(json))>`
 *
 * JSON 里的字段与局域网配对响应一致，额外带 `expiresAt`。整段自带客户端证书、
 * 私钥、中继地址/指纹和媒体密钥，所以手机不必与 NAS 处于同一局域网。
 */
object RelayRemotePairing {

    const val PREFIX = "podrelay://remote"

    data class Credentials(
        val relayAddr: String,
        val relayFingerprint: String,
        val keyId: Long,
        val mediaKeyB64: String,
        val certPem: String,
        val keyPem: String,
        val deviceId: String,
        val deviceName: String,
        val expiresAt: Long,
    )

    private data class RelayInfo(val addr: String = "", val fingerprint: String = "")

    private data class Bundle(
        val version: Int = 0,
        val relay: RelayInfo = RelayInfo(),
        val keyId: Long = 0,
        val mediaKeyB64: String = "",
        val certPem: String = "",
        val keyPem: String = "",
        val deviceId: String = "",
        val deviceName: String = "",
        val expiresAt: Long = 0,
    )

    private val gson = Gson()

    fun looksLike(raw: String?): Boolean =
        raw?.trim()?.startsWith(PREFIX, ignoreCase = true) == true

    fun decode(raw: String?, now: Long = System.currentTimeMillis()): Credentials {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX, ignoreCase = true)) {
            throw IllegalArgumentException("不是远程配对字符串")
        }
        val encoded = text.substringAfter("d=", "").trim()
        if (encoded.isBlank()) throw IllegalArgumentException("配对字符串缺少数据")
        val gz = runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrElse { throw IllegalArgumentException("配对字符串 Base64 无法解析") }
        val json = runCatching {
            GZIPInputStream(ByteArrayInputStream(gz)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        }.getOrElse { throw IllegalArgumentException("配对字符串 gzip 无法解析") }
        val bundle = runCatching { gson.fromJson(json, Bundle::class.java) }.getOrNull()
            ?: throw IllegalArgumentException("配对内容无法解析")
        if (bundle.version != 2) throw IllegalArgumentException("配对协议版本不匹配")
        if (bundle.relay.addr.isBlank() || bundle.relay.fingerprint.isBlank()) {
            throw IllegalArgumentException("配对内容缺少中继信息")
        }
        if (bundle.certPem.isBlank() || bundle.keyPem.isBlank()) {
            throw IllegalArgumentException("配对内容缺少证书")
        }
        if (bundle.mediaKeyB64.isBlank()) throw IllegalArgumentException("配对内容缺少媒体密钥")
        if (bundle.expiresAt <= 0L) throw IllegalArgumentException("配对内容缺少有效期")
        if (now > bundle.expiresAt) throw IllegalArgumentException("配对字符串已过期")
        val key = RelayCrypto.base64Key(bundle.mediaKeyB64)
            ?: throw IllegalArgumentException("媒体密钥格式不正确")
        val keyId = RelayCrypto.keyId(key)
        if (bundle.keyId != 0L && bundle.keyId != keyId) {
            throw IllegalArgumentException("配对内容 key_id 不匹配")
        }
        return Credentials(
            relayAddr = bundle.relay.addr,
            relayFingerprint = bundle.relay.fingerprint,
            keyId = keyId,
            mediaKeyB64 = bundle.mediaKeyB64,
            certPem = bundle.certPem,
            keyPem = bundle.keyPem,
            deviceId = bundle.deviceId,
            deviceName = bundle.deviceName,
            expiresAt = bundle.expiresAt,
        )
    }
}
