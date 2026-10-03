package com.fbreco.pclink

import org.junit.Assert.*
import org.junit.Test

class PcLinkProtocolTest {
    private val key = ByteArray(16) { it.toByte() }
    private val challenge = "0".repeat(64)
    private val proof = "0ddd31746e6498bcb259230029528c2fe895c8c1b343603737dabc8b2315f112"

    @Test fun connectionMatchesWindowsFixture() {
        assertEquals(proof, PcLinkProtocol.sign(key, "connect\n$challenge"))
        assertTrue(PcLinkProtocol.verifyConnection(key, challenge, proof))
    }
    @Test fun telemetryMatchesWindowsFixture() {
        val payload = """{"protocol":1,"deviceId":"11111111-1111-1111-1111-111111111111","sequence":1,"bikeConnected":true,"dataFresh":true,"isActive":false,"cadenceRpm":0,"powerWatts":0,"speedKmh":0}"""
        assertEquals("5d754cb400ffd57d898398e8a32e795328b90b8c2661fdad018d14640013d65c", PcLinkProtocol.signTelemetry(key, challenge, payload))
    }
    @Test fun rejectsWrongKeyNonceAndMalformedHeaders() {
        assertFalse(PcLinkProtocol.verifyConnection(ByteArray(16), challenge, proof))
        assertFalse(PcLinkProtocol.verifyConnection(key, "1".repeat(64), proof))
        assertFalse(PcLinkProtocol.verifyConnection(key, null, proof))
        assertFalse(PcLinkProtocol.verifyConnection(key, challenge, "invalid"))
        assertFalse(PcLinkProtocol.verifyConnection(key, "bad", proof))
    }
}
