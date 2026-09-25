package com.aurora.music.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// OpenAI-compatible chat client for the song-intro feature.
// Endpoint base like https://api.openai.com/v1 (also works with local Ollama-style gateways).
class LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming reads have no fixed deadline
        .build()
    private val gson = Gson()

    fun normalize(base: String) = base.trim().trimEnd('/')

    suspend fun models(endpoint: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${normalize(endpoint)}/models")
                .header("User-Agent", USER_AGENT)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: throw IllegalStateException("模型列表解析失败")
                val arr = root.getAsJsonArray("data") ?: throw IllegalStateException("模型列表解析失败")
                arr.mapNotNull { it.asJsonObject?.get("id")?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .distinct().sorted()
                    .also { if (it.isEmpty()) throw IllegalStateException("该端点没有返回可用模型") }
            }
        }
    }

    // Minimal non-stream ping to verify endpoint + key + model end to end.
    suspend fun ping(endpoint: String, apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to "只回复：连接正常")),
                "max_tokens" to 10,
                "temperature" to 0.0,
                "stream" to false,
            )
            val req = Request.Builder()
                .url("${normalize(endpoint)}/chat/completions")
                .header("User-Agent", USER_AGENT)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${body.take(200)}")
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: throw IllegalStateException("响应解析失败")
                root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")?.get("content")?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("响应解析失败")
            }
        }
    }

    // Streaming chat. onDelta returns false to abort early. Throws on HTTP/error status;
    // coroutine cancellation aborts the HTTP call.
    suspend fun chatStream(
        endpoint: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        onDelta: (String) -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            "max_tokens" to 2000,
            "temperature" to 0.7,
            "stream" to true,
        )
        val req = Request.Builder()
            .url("${normalize(endpoint)}/chat/completions")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/event-stream")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        val job = coroutineContext.job
        job.invokeOnCompletion { runCatching { call.cancel() } }
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw IllegalStateException("HTTP ${resp.code}：${body.take(200)}")
            }
            val source = resp.body?.source() ?: throw IllegalStateException("空响应")
            var done = false
            while (!done) {
                val line = try {
                    if (!source.isOpen) break
                    source.readUtf8Line() ?: break
                } catch (t: Throwable) {
                    if (job.isCancelled) throw CancellationException("cancelled", t)
                    throw IllegalStateException("读取中断：${t.message}")
                }
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") {
                    if (data == "[DONE]") done = true
                    continue
                }
                val delta = runCatching {
                    JsonParser.parseString(data).asJsonObject
                        .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("delta")?.get("content")?.asString
                }.getOrNull().orEmpty()
                if (delta.isNotEmpty() && !onDelta(delta)) done = true
            }
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Aurora/1.0"
    }
}
