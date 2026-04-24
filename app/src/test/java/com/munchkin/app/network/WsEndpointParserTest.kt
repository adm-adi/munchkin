package com.munchkin.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsEndpointParserTest {
    @Test
    fun explicitPortAndPathArePreserved() {
        val endpoint = WsEndpointParser.parse("wss://example.com:8765/ws/game")!!

        assertEquals("wss", endpoint.scheme)
        assertEquals("example.com", endpoint.host)
        assertEquals(8765, endpoint.port)
        assertEquals("/ws/game", endpoint.path)
        assertEquals("wss://example.com:8765/ws/game", endpoint.urlString)
    }

    @Test
    fun bareSecureHostDefaultsToStandardPortAndGamePath() {
        val endpoint = WsEndpointParser.parse("wss://example.com")!!

        assertEquals("wss", endpoint.scheme)
        assertEquals(443, endpoint.port)
        assertEquals("/ws/game", endpoint.path)
        assertEquals("wss://example.com:443/ws/game", endpoint.urlString)
    }

    @Test
    fun httpUrlsAreAcceptedAsWebsocketInputs() {
        val endpoint = WsEndpointParser.parse("https://example.com/rooms/live?token=abc")!!

        assertEquals("wss", endpoint.scheme)
        assertEquals(443, endpoint.port)
        assertEquals("/rooms/live?token=abc", endpoint.path)
        assertEquals("wss://example.com:443/rooms/live?token=abc", endpoint.urlString)
    }

    @Test
    fun unsupportedUrlsReturnNull() {
        assertNull(WsEndpointParser.parse("ftp://example.com/ws/game"))
        assertNull(WsEndpointParser.parse("example.com:8765"))
        assertNull(WsEndpointParser.parse("not a url"))
    }
}
