package com.aurora.music.data.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtoTest {

    @Test
    fun frameRoundtripSplit() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = RelayProto.encode(RelayProto.FRAG_REQ, 7, payload)
        val decoder = RelayProto.Decoder()
        decoder.push(bytes, 4)
        assertEquals(null, decoder.next())
        decoder.push(bytes.copyOfRange(4, bytes.size), bytes.size - 4)
        val frame = decoder.next()!!
        assertEquals(RelayProto.FRAG_REQ, frame.typ)
        assertEquals(7, frame.req)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun u16WritesTwoBytes() {
        val bytes = RelayProto.Writer().u16(2).build()
        assertArrayEquals(byteArrayOf(0, 2), bytes)
    }

    @Test
    fun helloUsesRustFieldLayout() {
        // relay-server/protocol/src/msg.rs 的 Hello 布局：u8 role + u16 ver + u64 keyId + u32 caps + bytes auth。
        val payload = RelayProto.Writer()
            .u8(RelayProto.ROLE_CLIENT)
            .u16(RelayProto.PROTOCOL_VERSION)
            .u64(7L)
            .u32(0)
            .bytes(ByteArray(0))
            .build()
        assertEquals(1 + 2 + 8 + 4 + 4, payload.size)

        val reader = RelayProto.Reader(payload)
        assertEquals(RelayProto.ROLE_CLIENT, reader.u8())
        assertEquals(RelayProto.PROTOCOL_VERSION, reader.u16())
        assertEquals(7L, reader.u64())
        assertEquals(0, reader.u32())
        assertEquals(0, reader.bytes().size)
    }

    @Test
    fun libVersionReplyDecodes() {
        val payload = RelayProto.Writer()
            .i64(42L)
            .u64(9L)
            .u64(100L)
            .u64(20L)
            .u32(3)
            .raw(ByteArray(8) { 5 })
            .bool(true)
            .build()
        val msg = RelayMsg.decode(RelayProto.Frame(RelayProto.LIB_VERSION_OK, 1, payload))
        assertTrue(msg is RelayMsg.LibVersionOk)
        val version = msg as RelayMsg.LibVersionOk
        assertEquals(42L, version.version)
        assertEquals(3, version.chunks)
        assertEquals(100L, version.plainLen)
    }

    @Test
    fun fileIdsMatchRustVectors() {
        // 与 relay-server/protocol/src/file_id.rs 使用同一算法（固定向量）。
        assertEquals(
            "2f5dd2f29af866a1a933a0aa7a953682",
            RelayFileId.hex(RelayFileId.of("music", "7")),
        )
        assertEquals(
            "0e3b98372a8716ad93b2132712ed61c9",
            RelayFileId.hex(RelayFileId.of("cover", "ebook\u001f/books/a.epub")),
        )
        assertNotEquals(
            RelayFileId.hex(RelayFileId.of("music", "7")),
            RelayFileId.hex(RelayFileId.of("songart", "7")),
        )
    }

    @Test
    fun queryEncodingMatchesServer() {
        // 客户端用 percent-encoding 计算 file_id 的 key，必须与服务端完全一致。
        assertEquals("%2Fa+b%2F%E4%B8%89%E4%BD%93.epub", encode("/a b/三体.epub"))
    }

    private fun encode(value: String): String {
        val out = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val isUnreserved =
                unsigned in 'A'.code..'Z'.code ||
                    unsigned in 'a'.code..'z'.code ||
                    unsigned in '0'.code..'9'.code ||
                    unsigned == '-'.code || unsigned == '_'.code ||
                    unsigned == '.'.code || unsigned == '~'.code
            when {
                isUnreserved -> out.append(unsigned.toChar())
                unsigned == ' '.code -> out.append('+')
                else -> out.append('%').append("%02X".format(unsigned))
            }
        }
        return out.toString()
    }
}
