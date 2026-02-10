package com.fbreco.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses FTMS Indoor Bike Data characteristic (0x2AD2) notifications.
 *
 * Byte format per FTMS spec:
 * - Bytes 0-1: Flags (uint16, little-endian)
 * - Bytes 2-3: Instantaneous Speed (uint16, 0.01 km/h resolution) — always present
 * - Conditional fields based on flags:
 *   - Bit 1: Average Speed (uint16) — skip
 *   - Bit 2: Instantaneous Cadence (uint16, 0.5 rpm resolution)
 *   - Bit 3: Average Cadence (uint16) — skip
 *   - Bit 4: Total Distance (uint24, 3 bytes) — skip
 *   - Bit 5: Resistance Level (sint16) — skip
 *   - Bit 6: Instantaneous Power (sint16, 1 Watt)
 */
object FtmsParser {

    data class BikeData(
        val speedKmh: Double,
        val cadenceRpm: Double,
        val powerWatts: Int,
        val isActive: Boolean
    )

    fun parse(data: ByteArray): BikeData? {
        if (data.size < 4) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Read flags (uint16)
        val flags = buffer.short.toInt() and 0xFFFF

        // 2. Speed (always present, uint16, 0.01 km/h)
        val speedRaw = buffer.short.toInt() and 0xFFFF
        val speedKmh = speedRaw * 0.01

        // 3. Average Speed (Bit 1) — skip if present
        if (flags and (1 shl 1) != 0) {
            if (buffer.remaining() < 2) return null
            buffer.short // skip
        }

        // 4. Instantaneous Cadence (Bit 2)
        var cadenceRpm = 0.0
        if (flags and (1 shl 2) != 0) {
            if (buffer.remaining() < 2) return null
            val cadenceRaw = buffer.short.toInt() and 0xFFFF
            cadenceRpm = cadenceRaw * 0.5
        }

        // 5. Average Cadence (Bit 3) — skip if present
        if (flags and (1 shl 3) != 0) {
            if (buffer.remaining() < 2) return null
            buffer.short // skip
        }

        // 6. Total Distance (Bit 4) — skip if present (3 bytes, uint24)
        if (flags and (1 shl 4) != 0) {
            if (buffer.remaining() < 3) return null
            buffer.get() // skip 3 bytes
            buffer.get()
            buffer.get()
        }

        // 7. Resistance Level (Bit 5) — skip if present
        if (flags and (1 shl 5) != 0) {
            if (buffer.remaining() < 2) return null
            buffer.short // skip
        }

        // 8. Instantaneous Power (Bit 6)
        var powerWatts = 0
        if (flags and (1 shl 6) != 0) {
            if (buffer.remaining() < 2) return null
            powerWatts = buffer.short.toInt() // sint16
        }

        val isActive = cadenceRpm > 0 || powerWatts > 0

        return BikeData(
            speedKmh = speedKmh,
            cadenceRpm = cadenceRpm,
            powerWatts = powerWatts,
            isActive = isActive
        )
    }

    fun calculateDistanceMeters(speedKmh: Double, intervalMs: Long): Double {
        val hours = intervalMs / 3600000.0
        return speedKmh * hours * 1000.0
    }
}
