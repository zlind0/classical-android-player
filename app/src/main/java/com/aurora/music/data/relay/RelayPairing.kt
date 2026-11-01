package com.aurora.music.data.relay

import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 局域网配对：只在手机与 NAS 同一 Wi-Fi 时可用。证书、私钥与媒体密钥都从这里一次性取回，
 * 不经过公网中继。
 */
object RelayPairing {

    data class Credentials(
        val relayAddr: String,
        val relayFingerprint: String,
        val keyId: Long,
        val mediaKeyB64: String,
        val certPem: String,
        val keyPem: String,
        val deviceId: String,
        val deviceName: String,
    )

    private data class RelayInfo(val addr: String = "", val fingerprint: String = "")

    private data class PairResponse(
        val version: Int = 0,
        val relay: RelayInfo = RelayInfo(),
        val keyId: Long = 0,
        val mediaKeyB64: String = "",
        val certPem: String = "",
        val keyPem: String = "",
        val deviceId: String = "",
        val deviceName: String = "",
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(uri: RelayPairUri, deviceName: String): Credentials = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "token" to uri.token,
                "deviceName" to deviceName,
                "platform" to "android",
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("http://${uri.host}:${uri.port}/relay/pair")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = text.ifBlank { "HTTP ${response.code}" }
                throw IllegalStateException(reason)
            }
            val parsed = runCatching { gson.fromJson(text, PairResponse::class.java) }.getOrNull()
                ?: throw IllegalStateException("配对响应无法解析")
            if (parsed.version != 2) throw IllegalStateException("配对协议版本不匹配")
            if (parsed.certPem.isBlank() || parsed.keyPem.isBlank()) {
                throw IllegalStateException("配对响应缺少证书")
            }
            if (parsed.relay.addr.isBlank() || parsed.relay.fingerprint.isBlank()) {
                throw IllegalStateException("配对响应缺少中继信息")
            }
            Credentials(
                relayAddr = parsed.relay.addr,
                relayFingerprint = parsed.relay.fingerprint,
                keyId = parsed.keyId,
                mediaKeyB64 = parsed.mediaKeyB64,
                certPem = parsed.certPem,
                keyPem = parsed.keyPem,
                deviceId = parsed.deviceId,
                deviceName = parsed.deviceName,
            )
        }
    }
}
