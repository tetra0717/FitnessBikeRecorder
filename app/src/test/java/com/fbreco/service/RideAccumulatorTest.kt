package com.fbreco.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.fbreco.ble.FtmsParser
import java.time.LocalDate

class RideAccumulatorTest {

    private lateinit var accumulator: RideAccumulator

    @Before
    fun setup() {
        accumulator = RideAccumulator()
    }

    // ── 1. onBikeData accumulates time and distance when active ──────

    @Test
    fun `onBikeData accumulates time and distance when active`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 2000L)

        assertEquals(1L, accumulator.accumulatedTimeSeconds)
        val expectedDistance = FtmsParser.calculateDistanceMeters(20.0, 1000L)
        assertEquals(expectedDistance, accumulator.accumulatedDistanceMeters, 0.001)
    }

    // ── 2. onBikeData resets timestamp when inactive ─────────────────

    @Test
    fun `onBikeData resets timestamp when inactive`() {
        val active = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)
        val inactive = FtmsParser.BikeData(speedKmh = 0.0, cadenceRpm = 0.0, powerWatts = 0, isActive = false)

        accumulator.onBikeData(active, nowMs = 1000L)
        assertTrue(accumulator.lastActiveTimestamp > 0L)

        accumulator.onBikeData(inactive, nowMs = 2000L)
        assertEquals(0L, accumulator.lastActiveTimestamp)
    }

    // ── 3. onBikeData clamps interval to 5 seconds ──────────────────

    @Test
    fun `onBikeData clamps interval to 5 seconds`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 11_000L) // 10s gap, should clamp to 5s

        assertEquals(5L, accumulator.accumulatedTimeSeconds)
        val expectedDistance = FtmsParser.calculateDistanceMeters(20.0, 5_000L)
        assertEquals(expectedDistance, accumulator.accumulatedDistanceMeters, 0.001)
    }

    // ── 4. onBikeData first call doesn't accumulate ─────────────────

    @Test
    fun `onBikeData first call does not accumulate`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        val result = accumulator.onBikeData(data, nowMs = 1000L)

        assertTrue(result)
        assertEquals(0L, accumulator.accumulatedTimeSeconds)
        assertEquals(0.0, accumulator.accumulatedDistanceMeters, 0.001)
    }

    // ── 5. prepareFlush returns null when nothing accumulated ────────

    @Test
    fun `prepareFlush returns null when nothing accumulated`() {
        val result = accumulator.prepareFlush(LocalDate.now())
        assertNull(result)
    }

    // ── 6. prepareFlush returns accumulated data and resets ──────────

    @Test
    fun `prepareFlush returns accumulated data and resets`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)
        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 3000L) // 2s interval

        val today = LocalDate.now()
        val result = accumulator.prepareFlush(today)

        assertNotNull(result)
        assertEquals(2L, result!!.timeSeconds)
        val expectedDistance = FtmsParser.calculateDistanceMeters(20.0, 2000L)
        assertEquals(expectedDistance, result.distanceMeters, 0.001)
        assertFalse(result.midnightCrossed)
        assertEquals(today, result.date)

        assertEquals(0L, accumulator.accumulatedTimeSeconds)
        assertEquals(0.0, accumulator.accumulatedDistanceMeters, 0.001)
    }

    // ── 7. prepareFlush detects midnight boundary ───────────────────

    @Test
    fun `prepareFlush detects midnight boundary`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 2000L)

        // lastFlushDate defaults to today; flushing with tomorrow triggers midnight crossing
        val result = accumulator.prepareFlush(tomorrow)

        assertNotNull(result)
        assertTrue(result!!.midnightCrossed)
        assertEquals(today, result.date)
    }

    // ── 8. prepareFlush no midnight when same date ──────────────────

    @Test
    fun `prepareFlush no midnight when same date`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)
        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 2000L)

        val today = LocalDate.now()
        val result = accumulator.prepareFlush(today)

        assertNotNull(result)
        assertFalse(result!!.midnightCrossed)
        assertEquals(today, result.date)
    }

    // ── 9. Multiple accumulate-flush cycles ─────────────────────────

    @Test
    fun `multiple accumulate-flush cycles have independent values`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)
        val today = LocalDate.now()

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 3000L)
        val result1 = accumulator.prepareFlush(today)

        assertNotNull(result1)
        assertEquals(2L, result1!!.timeSeconds)
        val expectedDistance1 = FtmsParser.calculateDistanceMeters(20.0, 2000L)
        assertEquals(expectedDistance1, result1.distanceMeters, 0.001)

        // After flush, lastActiveTimestamp is still 3000 (not reset by prepareFlush)
        // So the next onBikeData at 5000 accumulates 2s, then at 8000 accumulates 3s = 5s total
        accumulator.onBikeData(data, nowMs = 5000L)
        accumulator.onBikeData(data, nowMs = 8000L)
        val result2 = accumulator.prepareFlush(today)

        assertNotNull(result2)
        assertEquals(5L, result2!!.timeSeconds)
        val expectedDistance2 = FtmsParser.calculateDistanceMeters(20.0, 2000L) +
            FtmsParser.calculateDistanceMeters(20.0, 3000L)
        assertEquals(expectedDistance2, result2.distanceMeters, 0.001)
    }

    // ── 10. Distance calculation accuracy ───────────────────────────

    @Test
    fun `distance calculation accuracy at 20 kmh for 1000ms`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 2000L)

        // 20 km/h for 1000ms = 20.0 * (1000/3600000.0) * 1000.0 = 5.556 meters
        val expected = 20.0 * (1000.0 / 3600000.0) * 1000.0
        assertEquals(expected, accumulator.accumulatedDistanceMeters, 0.001)
        assertEquals(5.556, accumulator.accumulatedDistanceMeters, 0.001)
    }

    // ── 11. onBikeData accumulates time correctly with sub-second intervals ──

    @Test
    fun `onBikeData accumulates time correctly with sub-second intervals`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 1500L)
        accumulator.onBikeData(data, nowMs = 2000L)
        accumulator.onBikeData(data, nowMs = 2500L)
        accumulator.onBikeData(data, nowMs = 3000L)

        assertEquals(2L, accumulator.accumulatedTimeSeconds)
    }

    // ── 12. onBikeData accumulates time correctly with 200ms intervals ──────

    @Test
    fun `onBikeData accumulates time correctly with 200ms intervals`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 1200L)
        accumulator.onBikeData(data, nowMs = 1400L)
        accumulator.onBikeData(data, nowMs = 1600L)
        accumulator.onBikeData(data, nowMs = 1800L)
        accumulator.onBikeData(data, nowMs = 2000L)
        accumulator.onBikeData(data, nowMs = 2200L)
        accumulator.onBikeData(data, nowMs = 2400L)
        accumulator.onBikeData(data, nowMs = 2600L)
        accumulator.onBikeData(data, nowMs = 2800L)
        accumulator.onBikeData(data, nowMs = 3000L)

        assertEquals(2L, accumulator.accumulatedTimeSeconds)
    }

    // ── 13. onBikeData remainder carries across calls ──────────────────────

    @Test
    fun `onBikeData remainder carries across calls`() {
        val data = FtmsParser.BikeData(speedKmh = 20.0, cadenceRpm = 60.0, powerWatts = 100, isActive = true)

        accumulator.onBikeData(data, nowMs = 1000L)
        accumulator.onBikeData(data, nowMs = 1700L)
        accumulator.onBikeData(data, nowMs = 2400L)

        assertEquals(1L, accumulator.accumulatedTimeSeconds)
    }
}
