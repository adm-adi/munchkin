package com.munchkin.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security and compatibility tests for [JoinCodeParser].
 */
class JoinCodeParserTest {

    @Test
    fun `extractJoinCode normalizes plain 6 to 8 character alphanumeric codes`() {
        assertEquals("ABCD2345", JoinCodeParser.extractJoinCode("ABCD2345"))
        assertEquals("ABCD2345", JoinCodeParser.extractJoinCode("  abcd2345  "))
        assertEquals("A1B2C3", JoinCodeParser.extractJoinCode("a1b2c3"))
        assertEquals("Z9Y8X7W", JoinCodeParser.extractJoinCode("z9y8x7w"))
    }

    @Test
    fun `extractJoinCode parses universal hash and path join URLs`() {
        assertEquals(
            "ABCD2345",
            JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/#/join/ABCD2345")
        )
        assertEquals(
            "ABCD2345",
            JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/#/join/abcd2345")
        )
        assertEquals(
            "XYZ789",
            JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/join/xyz789?from=qr")
        )
        assertEquals(
            "ROOM42",
            JoinCodeParser.extractJoinCode("/join/room42")
        )
    }

    @Test
    fun `extractJoinCode parses query parameter join URLs`() {
        assertEquals(
            "ABCD2345",
            JoinCodeParser.extractJoinCode("?join=ABCD2345")
        )
        assertEquals(
            "ABCD2345",
            JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/?join=abcd2345&ref=share")
        )
    }

    @Test
    fun `extractJoinCode extracts joinCode from legacy QR JSON and ignores wsUrl`() {
        val maliciousQr = """{"joinCode":"ABCD2345","wsUrl":"ws://attacker.evil:8765"}"""
        assertEquals("ABCD2345", JoinCodeParser.extractJoinCode(maliciousQr))

        val lowercaseQr = """{"joinCode":" abcd2345 ","wsUrl":"wss://attacker.evil:9999"}"""
        assertEquals("ABCD2345", JoinCodeParser.extractJoinCode(lowercaseQr))
    }

    @Test
    fun `extractJoinCode rejects empty short long non-alphanumeric and malformed inputs`() {
        assertNull(JoinCodeParser.extractJoinCode(""))
        assertNull(JoinCodeParser.extractJoinCode("   \t\n "))
        assertNull(JoinCodeParser.extractJoinCode("ABC12"))       // 5 chars (< 6)
        assertNull(JoinCodeParser.extractJoinCode("ABCDEFGHI"))   // 9 chars (> 8)
        assertNull(JoinCodeParser.extractJoinCode("ABCD-234"))    // non-alphanumeric
        assertNull(JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/#/join/"))
        assertNull(JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/#/join/bad!code"))
        assertNull(JoinCodeParser.extractJoinCode("https://munchking-sirpepo.duckdns.org/?join=12345"))
        assertNull(JoinCodeParser.extractJoinCode("""{"wsUrl":"ws://attacker.evil:8765"}"""))
        assertNull(JoinCodeParser.extractJoinCode("""{"joinCode":12345678}"""))
        assertNull(JoinCodeParser.extractJoinCode("""{"joinCode":null}"""))
        assertNull(JoinCodeParser.extractJoinCode("{not valid json"))
    }
}
