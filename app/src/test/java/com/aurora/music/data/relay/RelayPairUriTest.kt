package com.aurora.music.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPairUriTest {

    @Test
    fun parsesValidPairUri() {
        val uri = RelayPairUri.parse(
            "podrelay://pair?v=2&h=192.168.1.23&p=8080&t=abcDEF%2B123&n=Aurora%20NAS&id=dev-1",
        )!!
        assertEquals("192.168.1.23", uri.host)
        assertEquals(8080, uri.port)
        assertEquals("abcDEF+123", uri.token)
        assertEquals("Aurora NAS", uri.deviceName)
        assertEquals("dev-1", uri.deviceId)
    }

    @Test
    fun rejectsIncompleteOrWrongVersion() {
        assertNull(RelayPairUri.parse("podrelay://pair?v=1&h=1.2.3.4&p=8080&t=x"))
        assertNull(RelayPairUri.parse("podrelay://pair?v=2&p=8080&t=x"))
        assertNull(RelayPairUri.parse("podrelay://pair?v=2&h=1.2.3.4&p=0&t=x"))
        assertNull(RelayPairUri.parse("https://example.com/pair?v=2"))
        assertNull(RelayPairUri.parse(null))
    }
}
