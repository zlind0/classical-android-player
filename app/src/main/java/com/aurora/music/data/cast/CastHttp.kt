package com.aurora.music.data.cast

import com.google.gson.Gson
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 设备间投屏的短连接客户端。所有请求都很短（1.5s 超时），
 * 失败只返回 null/false，由调用方决定提示还是重试。
 */
object CastHttp {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
    private val form = "application/x-www-form-urlencoded".toMediaType()

    /** 发送投屏请求。data 用 URL 编码走表单，避免自写 HTTP 服务处理 UTF-8 body。 */
    fun offer(device: CastDevice, offer: CastOffer): Boolean = postForm(
        "${device.baseUrl}/cast/offer",
        "data=" + URLEncoder.encode(gson.toJson(offer), "UTF-8"),
    )

    fun stop(device: CastDevice): Boolean = postForm("${device.baseUrl}/cast/stop", "")

    fun command(device: CastDevice, cmd: String, extra: String = ""): Boolean =
        get("${device.baseUrl}/cast/command?cmd=$cmd$extra")

    fun get(url: String): Boolean {
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun fetch(url: String): String? {
        val req = Request.Builder().url(url).get()
            .header("X-Aurora-Device", "1")
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    fun postForm(url: String, body: String): Boolean {
        val req = Request.Builder().url(url).post(body.toRequestBody(form)).build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
