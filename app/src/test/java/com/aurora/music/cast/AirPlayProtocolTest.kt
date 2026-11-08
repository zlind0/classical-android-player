package com.aurora.music.cast

import com.aurora.music.data.cast.receiver.airplay.AirPlayCrypto
import com.aurora.music.data.cast.receiver.airplay.AirPlaySdp
import com.aurora.music.data.cast.receiver.airplay.AppleBinaryPlist
import com.aurora.music.data.cast.receiver.airplay.plistArray
import com.aurora.music.data.cast.receiver.airplay.plistDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayProtocolTest {

    @Test
    fun binaryPlistRoundTrip() {
        val source = plistDict(
            "name" to "nostalgiaPod",
            "features" to 0x1E527FFFF7L,
            "flag" to true,
            "volume" to 0.75,
            "blob" to byteArrayOf(1, 2, 3, 4),
            "streams" to plistArray(
                plistDict("type" to 96L, "dataPort" to 5001L, "controlPort" to 5002L),
                plistDict("type" to 110L, "dataPort" to 5003L),
            ),
        )
        val decoded = AppleBinaryPlist.decode(AppleBinaryPlist.encode(source)) as Map<*, *>
        assertEquals("nostalgiaPod", decoded["name"])
        assertEquals(0x1E527FFFF7L, decoded["features"])
        assertEquals(true, decoded["flag"])
        assertEquals(0.75, decoded["volume"] as Double, 0.0001)
        assertTrue((decoded["blob"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3, 4)))
        val streams = decoded["streams"] as List<*>
        assertEquals(2, streams.size)
        assertEquals(96L, (streams[0] as Map<*, *>)["type"])
        assertEquals(5003L, (streams[1] as Map<*, *>)["dataPort"])
    }

    @Test
    fun sdpAlacFormatParsed() {
        val sdp = """
            v=0
            o=iTunes 123 0 IN IP4 192.168.1.2
            s=iTunes
            c=IN IP4 192.168.1.2
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:AAAA
            a=aesiv:AAAAAAAAAAAAAAAAAAAAAA==
            a=ptime:0
        """.trimIndent()
        val format = AirPlaySdp.parse(sdp)
        // The fake RSA key cannot decrypt, but the ALAC config must still parse.
        assertNotNull(format)
        assertEquals(44100, format!!.sampleRate)
        assertEquals(2, format.channels)
        assertEquals(352, format.alacConfig?.frameLength)
    }

    @Test
    fun airportRsaKeyDecryptsRaopSessionKey() {
        val aesKey = ByteArray(16) { (0x30 + it).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, AirPlayCrypto.airportPublicKey())
        val encrypted = cipher.doFinal(aesKey)
        val decrypted = AirPlayCrypto.decryptRaopAesKey(encrypted)
        assertNotNull(decrypted)
        assertTrue(decrypted!!.contentEquals(aesKey))
    }

    @Test
    fun dmapMetadataParsed() {
        val dmap = dmapItem(
            "minm" to "Song".toByteArray(),
            "asar" to "Artist".toByteArray(),
            "asal" to "Album".toByteArray(),
            "astm" to byteArrayOf(0x00, 0x00, 0x30.toByte(), 0x39.toByte()),
        )
        val parsed = com.aurora.music.data.cast.receiver.airplay.AirPlayMetadata.parseDmap(dmap)
        assertNotNull(parsed)
        assertEquals("Song", parsed!!.title)
        assertEquals("Artist", parsed.artist)
        assertEquals("Album", parsed.album)
        assertEquals(12345L, parsed.durationMs)
    }

    private fun dmapItem(vararg tags: Pair<String, ByteArray>): ByteArray {
        val inner = java.io.ByteArrayOutputStream()
        for ((tag, value) in tags) {
            inner.write(tag.toByteArray(Charsets.US_ASCII))
            inner.write(byteArrayOf(
                ((value.size shr 24) and 0xFF).toByte(),
                ((value.size shr 16) and 0xFF).toByte(),
                ((value.size shr 8) and 0xFF).toByte(),
                (value.size and 0xFF).toByte(),
            ))
            inner.write(value)
        }
        val body = inner.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("mlit".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(
            ((body.size shr 24) and 0xFF).toByte(),
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        ))
        out.write(body)
        return out.toByteArray()
    }
}
