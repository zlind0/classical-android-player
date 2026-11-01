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
