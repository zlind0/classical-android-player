package com.aurora.music.data.relay

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 到中继服务器的长连接：req 多路复用、断线重连、PING 保活、请求超时。
 * 所有写操作都走同一条 Channel，避免并发写把帧切碎。
 */
/** 中继明确拒绝认证（设备被撤销/未授权），重试也不会成功。 */
class RelayAuthException(val code: Int, message: String) : IllegalStateException(message)

class RelayClient(private val config: RelayConfig) {

    private companion object {
        /** 客户端每 5 秒一个 PING：半开链路最多 10 秒就能发现并重连。 */
        const val HEARTBEAT_MS = 5_000L
        const val MIN_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        /** 首次 + 两次重试；仍然认证失败就停止，避免无限重连。 */
        const val MAX_AUTH_ATTEMPTS = 3
        val AUTH_ERROR_CODES = setOf(3, 12) // unauthorized / device_revoked
    }

    data class Status(
        val connected: Boolean = false,
        val hostOnline: Boolean = false,
        val libVersion: Long = 0,
        val storeUsed: Long = 0,
        val storeCap: Long = 0,
        val sessionId: Long = 0,
        val keyId: Long = 0,
        val lastError: String? = null,
        val authFailed: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<RelayMsg>>()

    @Volatile private var outbox: Channel<ByteArray>? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var connected = false
    @Volatile private var running = false
    /** 最近一次收到任何帧（含 PONG）的时间；心跳用它判定半开链路。 */
    @Volatile private var lastInboundAt = 0L
    private var job: Job? = null

    /** 用来打断退避等待：网络恢复时立刻重连，而不是等指数退避走完。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        running = true
        _status.value = _status.value.copy(authFailed = false, lastError = null)
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        outbox?.close()
        outbox = null
        connected = false
        pending.values.forEach { it.cancel() }
        pending.clear()
        _status.value = _status.value.copy(connected = false)
    }

    fun isConnected(): Boolean = connected

    fun reconnect() {
        outbox?.close()
        outbox = null
        connected = false
    }

    /**
     * 立即丢弃当前连接并重连（网络切换、隧道半开时调用）。
     * 关闭 socket 会让阻塞中的 [pump] 立刻返回，退避等待也会被 [wake] 打断。
     */
    fun reconnectNow() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        outbox?.close()
        outbox = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        wake.trySend(Unit)
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        var authAttempts = 0
        while (running) {
            try {
                session()
                authAttempts = 0
                backoff = 1_000L
            } catch (error: Throwable) {
                if (!running) break
                connected = false
                if (error is RelayAuthException) {
                    authAttempts++
                    if (authAttempts >= MAX_AUTH_ATTEMPTS) {
                        _status.value = _status.value.copy(
                            connected = false,
                            authFailed = true,
                            lastError = "认证失败：设备已被撤销或未授权，请删除配置后重新配对",
                        )
                        running = false
                        break
                    }
                    _status.value = _status.value.copy(
                        connected = false,
                        authFailed = false,
                        lastError = "认证失败，正在重试（$authAttempts/$MAX_AUTH_ATTEMPTS）…",
                    )
                } else {
                    _status.value = _status.value.copy(
                        connected = false,
                        lastError = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
            pending.values.forEach { it.cancel() }
            pending.clear()
            if (!running) break
            // 等待退避；期间如果调用过 reconnectNow()，立刻重连。
            val woke = wake.tryReceive().isSuccess ||
                withTimeoutOrNull(backoff) { wake.receive() } != null
            backoff = if (woke) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun session() = coroutineScope {
        val settings = config.current
        check(settings.isUsable()) { "relay settings incomplete" }

        val socket: SSLSocket = RelayTls.open(settings)
        this@RelayClient.socket = socket
        val input = socket.inputStream
        val output = socket.outputStream
        val decoder = RelayProto.Decoder()

        // 握手在写协程启动前同步完成。
        output.write(
            RelayProto.encode(
                RelayProto.HELLO,
                0,
                RelayProto.Writer()
                    .u8(RelayProto.ROLE_CLIENT)
                    .u16(RelayProto.PROTOCOL_VERSION)
                    .u64(settings.keyId)
                    .u32(0)
                    .bytes(ByteArray(0))
                    .build(),
            ),
        )
        output.flush()
        val helloFrame = readFrame(input, decoder, 10_000)
            ?: throw IllegalStateException("relay did not send HELLO_OK")
        when (val hello = RelayMsg.decode(helloFrame)) {
            is RelayMsg.HelloOk -> {
                _status.value = Status(
                    connected = true,
                    hostOnline = hello.hostOnline,
                    libVersion = hello.libVersion,
                    storeUsed = hello.storeUsed,
                    storeCap = hello.storeCap,
                    sessionId = hello.sessionId,
                    keyId = settings.keyId,
                )
            }
            is RelayMsg.ErrorMsg -> {
                if (hello.code in AUTH_ERROR_CODES) {
                    throw RelayAuthException(hello.code, hello.text)
                }
                throw IllegalStateException("relay error ${hello.code}: ${hello.text}")
            }
            else -> throw IllegalStateException("unexpected handshake reply")
        }

        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        outbox = channel
        connected = true
        lastInboundAt = System.currentTimeMillis()

        val writer = launch(Dispatchers.IO) {
            try {
                for (bytes in channel) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: Throwable) {
                // 写失败说明链路已死：关掉 socket，让 pump 立即退出并触发重连。
                runCatching { socket.close() }
                throw error
            }
        }

        // 中继服务器 90 秒收不到任何帧就断开客户端。每 5 秒一个 PING 保活；
        // 连续两个心跳周期收不到任何帧（包括 PONG）就主动重连，别让 live 请求干等。
        val heartbeat = launch(Dispatchers.IO) {
            while (isActive && connected) {
                delay(HEARTBEAT_MS)
                if (!connected) break
                if (System.currentTimeMillis() - lastInboundAt > HEARTBEAT_MS * 2) {
                    reconnectNow()
                    break
                }
                sendNoWait(
                    RelayProto.PING,
                    RelayProto.Writer().u64(System.nanoTime()).build(),
                )
            }
        }

        try {
            pump(input, decoder)
        } finally {
            heartbeat.cancel()
            connected = false
            channel.close()
            outbox = null
            runCatching { socket.close() }
            if (this@RelayClient.socket === socket) this@RelayClient.socket = null
            writer.cancel()
            _status.value = _status.value.copy(connected = false)
        }
    }

    private suspend fun pump(input: InputStream, decoder: RelayProto.Decoder) {
        val buffer = ByteArray(64 * 1024)
        while (running) {
            val frame = decoder.next()
            if (frame == null) {
                val count = input.read(buffer)
                if (count <= 0) throw SocketException("relay closed connection")
                decoder.push(buffer, count)
                continue
            }
            lastInboundAt = System.currentTimeMillis()
            val message = runCatching { RelayMsg.decode(frame) }.getOrNull() ?: continue
            if (message is RelayMsg.ErrorMsg && message.code in AUTH_ERROR_CODES) {
                // 已建立的会话被撤销时 relay 会先发 ERROR 再断开；必须把它当成
                // 认证失败，而不是普通断线，否则会无限重连。
                throw RelayAuthException(message.code, message.text)
            }
            if (frame.req != 0) {
                pending.remove(frame.req)?.complete(message)
            }
        }
    }

    private fun readFrame(
        input: InputStream,
        decoder: RelayProto.Decoder,
        timeoutMs: Long,
    ): RelayProto.Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < deadline) {
            decoder.next()?.let { return it }
            val count = runCatching { input.read(buffer) }.getOrElse { 0 }
            if (count > 0) decoder.push(buffer, count)
        }
        return null
    }

    private fun sendFrame(bytes: ByteArray) {
        val channel = outbox ?: throw IllegalStateException("relay is not connected")
        if (!channel.trySend(bytes).isSuccess) {
            throw IllegalStateException("relay write queue is closed")
        }
    }

    suspend fun call(typ: Int, payload: ByteArray, timeoutMs: Long = 20_000): RelayMsg {
        val req = seq.getAndIncrement()
        val deferred = CompletableDeferred<RelayMsg>()
        pending[req] = deferred
        return try {
            sendFrame(RelayProto.encode(typ, req, payload))
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(req)
        }
    }

    fun sendNoWait(typ: Int, payload: ByteArray) {
        runCatching { sendFrame(RelayProto.encode(typ, 0, payload)) }
    }

    // ---- 高层请求 ----

    suspend fun libVersion(timeoutMs: Long = 10_000): RelayMsg.LibVersionOk {
        val reply = call(RelayProto.LIB_VERSION_REQ, ByteArray(0), timeoutMs)
        val version = reply as? RelayMsg.LibVersionOk
            ?: throw IllegalStateException("unexpected version reply: $reply")
        _status.value = _status.value.copy(
            hostOnline = version.hostOnline,
            libVersion = version.version,
        )
        return version
    }

    suspend fun snapshotChunk(version: Long, chunkIndex: Int): RelayMsg.LibSnapshotChunk {
        val payload = RelayProto.Writer().i64(version).u32(chunkIndex).build()
        val reply = call(RelayProto.LIB_SNAPSHOT_REQ, payload, 30_000)
        return reply as? RelayMsg.LibSnapshotChunk
            ?: throw IllegalStateException("unexpected snapshot reply: $reply")
    }

    suspend fun stat(fileId: ByteArray): RelayMsg =
        call(RelayProto.STAT_REQ, fileId, 20_000)

    suspend fun fragment(fileId: ByteArray, index: Int): RelayMsg {
        val payload = RelayProto.Writer().raw(fileId).u32(index).build()
        return call(RelayProto.FRAG_REQ, payload, 25_000)
    }

    fun wantAhead(fileId: ByteArray, from: Int, count: Int) {
        sendNoWait(
            RelayProto.WANT_AHEAD,
            RelayProto.Writer().raw(fileId).u32(from).u16(count).build(),
        )
    }

    fun pin(fileId: ByteArray, ttlSec: Int) {
        sendNoWait(RelayProto.PIN, RelayProto.Writer().raw(fileId).u32(ttlSec).build())
    }

    suspend fun live(
        seq: Int,
        ciphertext: ByteArray,
        timeoutMs: Long = 90_000,
    ): RelayMsg.LiveResp? {
        val payload = RelayProto.Writer().u32(seq).bytes(ciphertext).build()
        // start 要在桌面端解析最多 3 本 EPUB（大书可能很慢），给足等待时间；
        // turn/batch 走预缓存，调用方会传更短的超时。
        return call(RelayProto.LIVE_REQ, payload, timeoutMs) as? RelayMsg.LiveResp
    }

    fun randomSeq(): Int = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
}
