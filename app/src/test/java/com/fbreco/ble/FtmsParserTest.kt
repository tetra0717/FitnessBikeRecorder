package com.fbreco.ble

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FtmsParserTest {

    // ── helpers ──────────────────────────────────────────────────────

    /** Build an FTMS payload: little-endian uint16 flags + uint16 speed + extra bytes. */
    private fun buildPayload(flags: Int, speedHundredths: Int, vararg extraBytes: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(4 + extraBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(flags.toShort())
        buffer.putShort(speedHundredths.toShort())
        buffer.put(extraBytes)
        return buffer.array()
    }

    /** Convert a uint16 value to 2 little-endian bytes. */
    private fun u16le(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    /** Convert a sint16 value to 2 little-endian bytes. */
    private fun s16le(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        for (a in arrays) { a.copyInto(result, offset); offset += a.size }
        return result
    }

    // ── 1. Speed only (no optional fields) ──────────────────────────

    @Test
    fun `parse speed only - no optional fields`() {
        // flags=0x0000, speed=1550 (15.50 km/h)
        val data = buildPayload(0x0000, 1550)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(15.50, result!!.speedKmh, 0.001)
        assertEquals(0.0, result.cadenceRpm, 0.001)
        assertEquals(0, result.powerWatts)
        assertFalse(result.isActive)
    }

    // ── 2. Speed + Cadence (bit 2) ──────────────────────────────────

    @Test
    fun `parse speed and cadence`() {
        // flags=0x0004 (bit 2), speed=2000 (20.00 km/h), cadence raw=120 → 60.0 rpm
        val cadenceBytes = u16le(120)
        val data = buildPayload(0x0004, 2000, *cadenceBytes)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(20.00, result!!.speedKmh, 0.001)
        assertEquals(60.0, result.cadenceRpm, 0.001)
        assertEquals(0, result.powerWatts)
        assertTrue(result.isActive) // cadence > 0
    }

    // ── 3. Speed + Cadence + Power (bits 2, 6) ──────────────────────

    @Test
    fun `parse speed cadence and power`() {
        // flags = bit2 | bit6 = 0x0044
        val cadenceBytes = u16le(160) // 80 rpm
        val powerBytes = s16le(200)   // 200 W
        val data = buildPayload(0x0044, 2500, *cadenceBytes, *powerBytes)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(25.00, result!!.speedKmh, 0.001)
        assertEquals(80.0, result.cadenceRpm, 0.001)
        assertEquals(200, result.powerWatts)
        assertTrue(result.isActive)
    }

    // ── 4. Speed + Cadence + Resistance + Power (bits 2, 5, 6) ──────
    //    This matches main.py's bike format

    @Test
    fun `parse speed cadence resistance power - bike format`() {
        // flags = bit2 | bit5 | bit6 = 0x0064
        // speed=1550 (15.50 km/h), cadence raw=120 (60 rpm),
        // resistance=0 (skipped), power=100 W
        val cadenceBytes = u16le(120)
        val resistanceBytes = s16le(0)
        val powerBytes = s16le(100)
        val extra = concat(cadenceBytes, resistanceBytes, powerBytes)
        val data = buildPayload(0x0064, 1550, *extra)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(15.50, result!!.speedKmh, 0.001)
        assertEquals(60.0, result.cadenceRpm, 0.001)
        assertEquals(100, result.powerWatts)
        assertTrue(result.isActive)
    }

    // ── 5. All flags set (bits 1-6) ─────────────────────────────────

    @Test
    fun `parse all optional fields present - bits 1 through 6`() {
        // flags = bits 1-6 = 0x007E
        // speed = 3000 (30.00 km/h)
        // bit1: avg speed (uint16) = skip 2 bytes
        // bit2: cadence (uint16) raw=180 → 90 rpm
        // bit3: avg cadence (uint16) = skip 2 bytes
        // bit4: distance (uint24) = skip 3 bytes
        // bit5: resistance (sint16) = skip 2 bytes
        // bit6: power (sint16) = 150 W
        val avgSpeed = u16le(2800)
        val cadence = u16le(180)
        val avgCadence = u16le(170)
        val distance = byteArrayOf(0x10, 0x27, 0x00) // 3 bytes
        val resistance = s16le(5)
        val power = s16le(150)

        val extra = concat(avgSpeed, cadence, avgCadence, distance, resistance, power)
        val data = buildPayload(0x007E, 3000, *extra)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(30.00, result!!.speedKmh, 0.001)
        assertEquals(90.0, result.cadenceRpm, 0.001)
        assertEquals(150, result.powerWatts)
        assertTrue(result.isActive)
    }

    // ── 6. isActive detection ───────────────────────────────────────

    @Test
    fun `isActive false when cadence and power are zero`() {
        val data = buildPayload(0x0000, 1000) // speed only
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertFalse(result!!.isActive)
    }

    @Test
    fun `isActive true when cadence is positive`() {
        val cadenceBytes = u16le(20) // 10 rpm
        val data = buildPayload(0x0004, 0, *cadenceBytes)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertTrue(result!!.isActive)
    }

    @Test
    fun `isActive true when power is positive`() {
        // flags = bit6 only = 0x0040
        val powerBytes = s16le(50)
        val data = buildPayload(0x0040, 0, *powerBytes)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertTrue(result!!.isActive)
    }

    // ── 7. Zero speed ───────────────────────────────────────────────

    @Test
    fun `parse zero speed`() {
        val data = buildPayload(0x0000, 0)
        val result = FtmsParser.parse(data)

        assertNotNull(result)
        assertEquals(0.0, result!!.speedKmh, 0.001)
    }

    // ── 8. Corrupted data (too short) ───────────────────────────────

    @Test
    fun `parse returns null for data shorter than 4 bytes`() {
        val data = byteArrayOf(0x00, 0x00, 0x01) // only 3 bytes
        assertNull(FtmsParser.parse(data))
    }

    @Test
    fun `parse returns null when optional field data is truncated`() {
        // flags say cadence present (bit 2) but no cadence bytes follow
        val data = buildPayload(0x0004, 1000) // only flags + speed, no cadence bytes
        assertNull(FtmsParser.parse(data))
    }

    // ── 9. Empty byte array ─────────────────────────────────────────

    @Test
    fun `parse returns null for empty byte array`() {
        assertNull(FtmsParser.parse(byteArrayOf()))
    }

    // ── 10. calculateDistanceMeters ─────────────────────────────────

    @Test
    fun `calculateDistanceMeters at 10 kmh for 1 second`() {
        // 10 km/h = 10000 m / 3600 s ≈ 2.7778 m/s
        val distance = FtmsParser.calculateDistanceMeters(10.0, 1000L)
        assertEquals(2.7778, distance, 0.001)
    }

    @Test
    fun `calculateDistanceMeters at zero speed`() {
        val distance = FtmsParser.calculateDistanceMeters(0.0, 1000L)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `calculateDistanceMeters at zero interval`() {
        val distance = FtmsParser.calculateDistanceMeters(30.0, 0L)
        assertEquals(0.0, distance, 0.001)
    }
}
