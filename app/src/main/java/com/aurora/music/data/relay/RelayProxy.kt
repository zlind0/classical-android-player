package com.aurora.music.data.relay

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 本机回环 HTTP 代理：把现有 v1 协议的请求翻译成中继帧协议。
 *
 * 只监听 127.0.0.1。播放器、资料库同步、封面缓存、电子书下载都把它当成另一台局域网设备。
 */
class RelayProxy(
    private val config: RelayConfig,
    private val client: RelayClient,
    private val store: RelayStore,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    private var acceptJob: Job? = null

    @Volatile
    var port: Int = 0
        private set

    fun start(scope: CoroutineScope, preferredPort: Int) {
        if (server != null) return
        val socket = openSocket(preferredPort) ?: openSocket(0) ?: return
        server = socket
        port = socket.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val incoming = runCatching { socket.accept() }.getOrNull() ?: break
                scope.launch(Dispatchers.IO) {
                    try {
                        handle(incoming)
                    } catch (_: Throwable) {
                    } finally {
                        runCatching { incoming.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
        port = 0
    }

    private fun openSocket(port: Int): ServerSocket? = runCatching {
        ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
    }.getOrNull()

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val input = BufferedInputStream(socket.inputStream)
        val output = socket.outputStream
        val request = parse(input) ?: run {
            sendText(output, 400, "Bad Request", "bad request")
            return
        }
        when (request.path) {
            "/library/version" -> serveVersion(output)
            "/library/all" -> serveLibrary(output)
            "/library/ebook/scan" -> serveLiveJson(output, request)
            "/library/ebook/feed/start",
            "/library/ebook/feed/turn",
            "/library/ebook/feed/chapter",
            "/library/ebook/feed/close",
            -> serveLiveJson(output, request)
            "/media/music" -> serveMedia(output, request, "music", request.query["id"].orEmpty())
            "/media/audiobook" -> serveMedia(output, request, "audiobook", request.query["path"].orEmpty())
            "/media/ebook" -> serveMedia(output, request, "ebook", request.query["path"].orEmpty())
            "/media/songart" -> serveMedia(output, request, "songart", request.query["id"].orEmpty())
            "/media/cover" -> serveMedia(
                output,
                request,
                "cover",
                "${request.query["kind"].orEmpty()}\u001f${request.query["key"].orEmpty()}",
            )
            "/relay/status" -> serveStatus(output)
            else -> sendText(output, 404, "Not Found", "not found")
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(buffer, read, length - read)
                if (count <= 0) break
                read += count
            }
            buffer.copyOf(read)
        } else {
            ByteArray(0)
        }
        return Request(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (value == '\n'.code) {
                val bytes = out.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            out.write(value)
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            out[key] = value
        }
        return out
    }

    private fun decode(text: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '+' -> {
                    bytes.write(' '.code)
                    index += 1
                }
                char == '%' && index + 2 < text.length -> {
                    val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                    if (value == null) {
                        bytes.write(char.code)
                        index += 1
                    } else {
                        bytes.write(value)
                        index += 3
                    }
                }
                else -> {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
        }
        return bytes.toString("UTF-8")
    }

    // ---------------------------------------------------------------- routes

    private suspend fun serveVersion(output: OutputStream) {
        // 链路刚断开时不要卡满 10 秒：代理是同步循环的主路径，快速失败才能立刻换端点。
        val version = runCatching { client.libVersion(3_000) }.getOrNull()
        if (version == null) {
            sendText(output, 503, "Service Unavailable", "relay offline")
            return
        }
        sendJson(output, 200, "{\"version\":${version.version}}")
    }

    private suspend fun serveLibrary(output: OutputStream) {
        val snapshot = runCatching { store.ensureSnapshot(client, versionTimeoutMs = 3_000) }.getOrNull()
        if (snapshot == null) {
            sendText(output, 503, "Service Unavailable", "relay offline")
            return
        }
        sendBytes(output, 200, "OK", "application/json; charset=utf-8", snapshot.json.toByteArray())
    }

    /** Forwards a JSON request/response endpoint through the encrypted live channel. */
    private suspend fun serveLiveJson(output: OutputStream, request: Request) {
        val master = config.current.masterKey
        if (master == null) {
            sendText(output, 503, "Service Unavailable", "media key missing")
            return
        }
        val payload = JsonObject().apply {
            addProperty("method", request.method)
            addProperty("path", request.path)
            addProperty(
                "body",
                Base64.encodeToString(request.body, Base64.NO_WRAP),
            )
        }
        val seq = client.randomSeq()
        val sealed = runCatching {
            RelayCrypto.liveSeal(RelayCrypto.liveKey(master), seq, 1, payload.toString().toByteArray())
        }.getOrNull()
        if (sealed == null) {
            sendText(output, 500, "Internal Server Error", "encrypt failed")
            return
        }
        val reply = runCatching { client.live(seq, sealed) }.getOrNull()
        if (reply == null || reply.status == 0) {
            sendText(output, 503, "Service Unavailable", "relay offline")
            return
        }
        if (reply.ct.isEmpty()) {
            sendText(output, reply.status, "Upstream", "")
            return
        }
        val plain = runCatching {
            RelayCrypto.liveOpen(RelayCrypto.liveKey(master), seq, 2, reply.ct)
        }.getOrNull()
        val body = plain?.let {
            runCatching {
                val json = JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject
                val encoded = json.get("body")?.asString.orEmpty()
                if (encoded.isEmpty()) ByteArray(0) else Base64.decode(encoded, Base64.DEFAULT)
            }.getOrNull()
        }
        if (body == null) {
            sendText(output, 502, "Bad Gateway", "invalid upstream reply")
            return
        }
        sendBytes(output, reply.status, "OK", "application/json; charset=utf-8", body)
    }

    private suspend fun serveMedia(
        output: OutputStream,
        request: Request,
        kind: String,
        key: String,
    ) {
        if (key.isBlank()) {
            sendText(output, 404, "Not Found", "missing key")
            return
        }
        val fileId = RelayFileId.of(kind, key)
        val stat = when (val reply = runCatching { client.stat(fileId) }.getOrNull()) {
            is RelayMsg.StatOk -> reply
            is RelayMsg.StatErr -> {
                sendText(output, if (reply.code == 5) 503 else 404, "Not Found", "unknown media")
                return
            }
            else -> {
                sendText(output, 503, "Service Unavailable", "relay offline")
                return
            }
        }
        val keyId = config.current.keyId
        if (stat.keyId != 0L && keyId != 0L && stat.keyId != keyId) {
            sendText(output, 500, "Internal Server Error", "media key mismatch")
            return
        }

        val total = stat.total
        if (total <= 0) {
            sendBytes(output, 200, "OK", stat.mime, ByteArray(0))
            return
        }
        val range = parseRange(request.headers["range"], total)
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1)
        val status = if (range != null) 206 else 200
        val length = end - start + 1
        val extra = buildList {
            if (range != null) add("Content-Range" to "bytes $start-$end/$total")
        }
        sendHeaders(output, status, if (range != null) "Partial Content" else "OK", stat.mime, length, extra)

        client.pin(fileId, 600)
        val first = (start / RelayProto.FRAGMENT_BYTES).toInt()
        client.wantAhead(fileId, first, 32)

        var index = first
        val last = (end / RelayProto.FRAGMENT_BYTES).toInt()
        while (index <= last) {
            val plain = fetchFragment(fileId, index)
            if (plain == null) {
                // 已经写出头，只能中断连接让播放器重试。
                return
            }
            val fragmentStart = index.toLong() * RelayProto.FRAGMENT_BYTES
            val from = (start - fragmentStart).coerceAtLeast(0L).toInt()
            val to = ((end - fragmentStart + 1).coerceAtMost(plain.size.toLong())).toInt()
            if (to > from) {
                output.write(plain, from, to - from)
                output.flush()
            }
            index += 1
        }
    }

    private suspend fun fetchFragment(fileId: ByteArray, index: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            when (val reply = runCatching { client.fragment(fileId, index) }.getOrNull()) {
                is RelayMsg.FragData -> {
                    return runCatching {
                        store.decryptFragment(fileId, index, reply.nonce, reply.ct)
                    }.getOrNull()
                }
                is RelayMsg.Pending -> {
                    if (!reply.hostOnline) {
                        delay(reply.retryMs.coerceAtLeast(300).toLong())
                        if (System.currentTimeMillis() > deadline - 17_000) return null
                    } else {
                        delay(reply.retryMs.coerceAtLeast(80).toLong())
                    }
                }
                else -> delay(200)
            }
        }
        return null
    }

    private fun serveStatus(output: OutputStream) {
        val status = client.status.value
        val json = JsonObject().apply {
            addProperty("connected", status.connected)
            addProperty("hostOnline", status.hostOnline)
            addProperty("libVersion", status.libVersion)
            addProperty("deviceId", store.deviceId().orEmpty())
            addProperty("deviceName", store.deviceName().orEmpty())
            addProperty("proxyPort", port)
        }
        sendJson(output, 200, json.toString())
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank() || total <= 0) return null
        val text = header.trim()
        if (!text.startsWith("bytes=")) return null
        val part = text.removePrefix("bytes=").substringBefore(',').trim()
        if (part.isEmpty()) return null
        val a = part.substringBefore('-').trim()
        val b = part.substringAfter('-').trim()
        val start: Long
        val end: Long
        if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (total - suffix).coerceAtLeast(0)
            end = total - 1
        } else {
            start = a.toLongOrNull() ?: return null
            end = if (b.isEmpty()) total - 1 else b.toLongOrNull() ?: return null
        }
        if (start > end || start >= total) return null
        return start to end.coerceAtMost(total - 1)
    }

    // --------------------------------------------------------------- helpers

    private fun sendJson(output: OutputStream, status: Int, body: String) {
        sendBytes(output, status, "OK", "application/json; charset=utf-8", body.toByteArray())
    }

    private fun sendText(output: OutputStream, status: Int, statusText: String, text: String) {
        sendBytes(output, status, statusText, "text/plain; charset=utf-8", text.toByteArray())
    }

    private fun sendBytes(
        output: OutputStream,
        status: Int,
        statusText: String,
        mime: String,
        body: ByteArray,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        sendHeaders(output, status, statusText, mime, body.size.toLong(), extra)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun sendHeaders(
        output: OutputStream,
        status: Int,
        statusText: String,
        mime: String,
        length: Long,
        extra: List<Pair<String, String>>,
    ) {
        val header = buildString(180) {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n")
            extra.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
    }
}

/** 客户端与 NAS 端共用的不可逆文件标识。 */
object RelayFileId {
    fun of(kind: String, key: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(kind.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(key.toByteArray(Charsets.UTF_8))
        return digest.digest().copyOf(16)
    }

    fun hex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }
}
