package com.aurora.music.data.relay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PodRelay v1 帧协议（与 relay-server/protocol 一一对应）。
 * 帧格式：[u32 len][u8 typ][u32 req][payload]，大端。
 */
object RelayProto {
    const val MAX_FRAME = 4 * 1024 * 1024

    const val ROLE_CLIENT = 2
    const val PROTOCOL_VERSION = 2

    const val HELLO = 0x01
    const val HELLO_OK = 0x02
    const val PING = 0x03
    const val PONG = 0x04
    const val ERROR = 0x7f

    const val LIB_VERSION_REQ = 0x10
    const val LIB_VERSION_OK = 0x11
    const val LIB_SNAPSHOT_REQ = 0x12
    const val LIB_SNAPSHOT_CHUNK = 0x13

    const val STAT_REQ = 0x20
    const val STAT_OK = 0x21
    const val FRAG_REQ = 0x22
    const val FRAG_DATA = 0x23
    const val PENDING = 0x24
    const val WANT_AHEAD = 0x25
    const val PIN = 0x26
    const val STAT_ERR_HOST = 0x38

    const val LIVE_REQ = 0x40
    const val LIVE_RESP = 0x41

    const val FRAGMENT_BYTES = 256 * 1024

    data class Frame(val typ: Int, val req: Int, val payload: ByteArray)

    fun encode(typ: Int, req: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(payload.size)
            put(typ.toByte())
            putInt(req)
            put(payload)
        }
        return out
    }

    class Decoder {
        private var buf = ByteArray(0)
        private var len = 0

        fun push(data: ByteArray, count: Int) {
            if (len + count > MAX_FRAME * 2) throw IllegalStateException("frame buffer overflow")
            if (len + count > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, len + count))
            }
            System.arraycopy(data, 0, buf, len, count)
            len += count
        }

        fun next(): Frame? {
            if (len < 9) return null
            val view = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
            val size = view.getInt(0)
            if (size < 0 || size > MAX_FRAME) throw IllegalStateException("frame too large: $size")
            if (len < 9 + size) return null
            val typ = buf[4].toInt() and 0xFF
            val req = view.getInt(5)
            val payload = buf.copyOfRange(9, 9 + size)
            val rest = len - (9 + size)
            System.arraycopy(buf, 9 + size, buf, 0, rest)
            len = rest
            return Frame(typ, req, payload)
        }
    }

    class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u32(v: Int) = apply {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun u64(v: Long) = apply {
            for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
        }

        fun i64(v: Long) = u64(v)
        fun bool(v: Boolean) = apply { out.write(if (v) 1 else 0) }
        fun raw(v: ByteArray) = apply { out.write(v) }
        fun bytes(v: ByteArray) = apply {
            u32(v.size)
            out.write(v)
        }

        fun str(v: String) = bytes(v.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = out.toByteArray()
    }

    class Reader(private val data: ByteArray) {
        private var pos = 0
        private fun need(n: Int) {
            if (pos + n > data.size) throw IllegalStateException("short payload")
        }

        fun u8(): Int {
            need(1)
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int {
            need(4)
            val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            pos += 4
            return v
        }

        fun u64(): Long {
            need(8)
            val v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN).long
            pos += 8
            return v
        }

        fun i64(): Long = u64()
        fun bool(): Boolean = u8() != 0
        fun array(n: Int): ByteArray {
            need(n)
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun bytes(): ByteArray = array(u32())
        fun str(): String = String(bytes(), Charsets.UTF_8)
    }
}

/** 客户端会解析的消息子集；未知类型返回 null。 */
sealed class RelayMsg {
    data class HelloOk(
        val sessionId: Long,
        val hostOnline: Boolean,
        val libVersion: Long,
        val storeUsed: Long,
        val storeCap: Long,
    ) : RelayMsg()

    data class LibVersionOk(
        val version: Long,
        val keyId: Long,
        val plainLen: Long,
        val gzLen: Long,
        val chunks: Int,
        val salt: ByteArray,
        val hostOnline: Boolean,
    ) : RelayMsg()

    data class LibSnapshotChunk(
        val version: Long,
        val chunkIndex: Int,
        val total: Long,
        val data: ByteArray,
    ) : RelayMsg()

    data class StatOk(
        val fileId: ByteArray,
        val total: Long,
        val frag: Int,
        val count: Int,
        val salt: ByteArray,
        val mime: String,
        val keyId: Long,
    ) : RelayMsg()

    data class StatErr(val fileId: ByteArray, val code: Int) : RelayMsg()
    data class FragData(val fileId: ByteArray, val index: Int, val nonce: ByteArray, val ct: ByteArray) : RelayMsg()
    data class Pending(val fileId: ByteArray, val index: Int, val retryMs: Int, val hostOnline: Boolean) : RelayMsg()
    data class LiveResp(val seq: Int, val status: Int, val ct: ByteArray) : RelayMsg()
    data class ErrorMsg(val code: Int, val text: String) : RelayMsg()
    data class Pong(val nonce: Long) : RelayMsg()

    companion object {
        fun decode(frame: RelayProto.Frame): RelayMsg? {
            val r = RelayProto.Reader(frame.payload)
            return when (frame.typ) {
                RelayProto.HELLO_OK -> {
                    r.u16()
                    val sessionId = r.u64()
                    r.i64() // relay_ms
                    val hostOnline = r.bool()
                    val libVersion = r.i64()
                    val storeUsed = r.u64()
                    val storeCap = r.u64()
                    HelloOk(sessionId, hostOnline, libVersion, storeUsed, storeCap)
                }

                RelayProto.LIB_VERSION_OK -> LibVersionOk(
                    version = r.i64(),
                    keyId = r.u64(),
                    plainLen = r.u64(),
                    gzLen = r.u64(),
                    chunks = r.u32(),
                    salt = r.array(8),
                    hostOnline = r.bool(),
                )

                RelayProto.LIB_SNAPSHOT_CHUNK -> LibSnapshotChunk(
                    version = r.i64(),
                    chunkIndex = r.u32(),
                    total = r.u64(),
                    data = r.bytes(),
                )

                RelayProto.STAT_OK -> StatOk(
                    fileId = r.array(16),
                    total = r.u64(),
                    frag = r.u32(),
                    count = r.u32(),
                    salt = r.array(8),
                    mime = r.str(),
                    keyId = r.u64(),
                )

                RelayProto.STAT_ERR_HOST -> StatErr(r.array(16), r.u16())
                RelayProto.FRAG_DATA -> FragData(r.array(16), r.u32(), r.array(12), r.bytes())
                RelayProto.PENDING -> Pending(r.array(16), r.u32(), r.u32(), r.bool())
                RelayProto.LIVE_RESP -> LiveResp(r.u32(), r.u16(), r.bytes())
                RelayProto.ERROR -> ErrorMsg(r.u16(), r.str())
                RelayProto.PONG -> Pong(r.u64())
                else -> null
            }
        }
    }
}
