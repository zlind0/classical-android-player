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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 到中继服务器的长连接：req 多路复用、断线重连、请求超时。
 * 所有写操作都走同一条 Channel，避免并发写把帧切碎。
 */
class RelayClient(private val config: RelayConfig) {

    data class Status(
        val connected: Boolean = false,
        val hostOnline: Boolean = false,
        val libVersion: Long = 0,
        val storeUsed: Long = 0,
        val storeCap: Long = 0,
        val sessionId: Long = 0,
        val keyId: Long = 0,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<RelayMsg>>()

    @Volatile private var outbox: Channel<ByteArray>? = null
    @Volatile private var connected = false
    @Volatile private var running = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        running = true
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        outbox?.close()
        outbox = null
        connected = false
        pending.values.forEach { it.cancel() }
        pending.clear()
        _status.value = _status.value.copy(connected = false)
    }

    fun isConnected(): Boolean = connected

    /** 配置改动后立即断开重连。 */
    fun reconnect() {
        outbox?.close()
        outbox = null
        connected = false
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (running) {
            try {
                session()
                backoff = 1_000L
            } catch (error: Throwable) {
                if (!running) break
                connected = false
                _status.value = _status.value.copy(
                    connected = false,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
            pending.values.forEach { it.cancel() }
            pending.clear()
            if (!running) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun session() = coroutineScope {
        val settings = config.current
        check(settings.isUsable()) { "relay settings incomplete" }

        val socket: SSLSocket = RelayTls.open(settings)
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
            is RelayMsg.ErrorMsg -> throw IllegalStateException("relay error ${hello.code}: ${hello.text}")
            else -> throw IllegalStateException("unexpected handshake reply")
        }

        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        outbox = channel
        connected = true

        val writer = launch(Dispatchers.IO) {
            for (bytes in channel) {
                output.write(bytes)
                output.flush()
            }
        }

        try {
            pump(input, decoder)
        } finally {
            connected = false
            channel.close()
            outbox = null
            runCatching { socket.close() }
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
            val message = runCatching { RelayMsg.decode(frame) }.getOrNull() ?: continue
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

    suspend fun live(seq: Int, ciphertext: ByteArray): RelayMsg.LiveResp? {
        val payload = RelayProto.Writer().u32(seq).bytes(ciphertext).build()
        return call(RelayProto.LIVE_REQ, payload, 25_000) as? RelayMsg.LiveResp
    }

    fun randomSeq(): Int = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
}
