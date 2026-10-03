package com.fbreco.ble

import org.junit.Assert.*
import org.junit.Test

class PcLinkBikeDataTest {
    @Test fun distinguishesMissingPedalDataFromZeroPedalling() {
        val missing = FtmsParser.parse(byteArrayOf(0, 0, 0, 0))!!
        assertFalse(missing.hasPedalData)
        val stopped = FtmsParser.parse(byteArrayOf(4, 0, 0, 0, 0, 0))!!
        assertTrue(stopped.hasPedalData)
        assertFalse(stopped.isActive)
    }
    @Test fun continuationPacketDoesNotMistakeCadenceForSpeed() {
        val active = FtmsParser.parse(byteArrayOf(5, 0, 120, 0))!!
        assertEquals(60.0, active.cadenceRpm, 0.01)
        assertEquals(0.0, active.speedKmh, 0.01)
        assertTrue(active.isActive)
        assertNull(FtmsParser.parse(byteArrayOf(5, 0, 120)))
    }
}
