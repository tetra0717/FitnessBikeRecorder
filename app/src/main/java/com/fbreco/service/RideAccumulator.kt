package com.fbreco.service

import com.fbreco.ble.FtmsParser
import java.time.LocalDate

/**
 * Extracted accumulation logic — testable without Android deps.
 */
class RideAccumulator {
    var accumulatedTimeSeconds: Long = 0L
        private set
    var accumulatedDistanceMeters: Double = 0.0
        private set
    var lastFlushDate: LocalDate = LocalDate.now()
        private set
    var lastActiveTimestamp: Long = 0L
        private set
    private var accumulatedTimeMillisRemainder: Long = 0L

    /**
     * Process incoming bike data. Returns true if data was accumulated (isActive).
     */
    fun onBikeData(data: FtmsParser.BikeData, nowMs: Long): Boolean {
        if (data.isActive) {
            if (lastActiveTimestamp > 0L) {
                val intervalMs = nowMs - lastActiveTimestamp
                val clampedIntervalMs = intervalMs.coerceAtMost(5_000L)
                accumulatedDistanceMeters += FtmsParser.calculateDistanceMeters(data.speedKmh, clampedIntervalMs)
                val totalMs = accumulatedTimeMillisRemainder + clampedIntervalMs
                accumulatedTimeSeconds += totalMs / 1000L
                accumulatedTimeMillisRemainder = totalMs % 1000L
            }
            lastActiveTimestamp = nowMs
            return true
        } else {
            lastActiveTimestamp = 0L
            return false
        }
    }

    /**
     * Prepare flush data. Returns (date, time, distance) and whether a midnight boundary was crossed.
     * Resets accumulators after returning.
     */
    data class FlushResult(
        val date: LocalDate,
        val timeSeconds: Long,
        val distanceMeters: Double,
        val midnightCrossed: Boolean,
    )

    fun prepareFlush(now: LocalDate): FlushResult? {
        if (accumulatedTimeSeconds == 0L && accumulatedDistanceMeters == 0.0) return null

        val midnightCrossed = now != lastFlushDate
        val flushDate = if (midnightCrossed) lastFlushDate else now
        val result = FlushResult(
            date = flushDate,
            timeSeconds = accumulatedTimeSeconds,
            distanceMeters = accumulatedDistanceMeters,
            midnightCrossed = midnightCrossed,
        )

        accumulatedTimeSeconds = 0L
        accumulatedDistanceMeters = 0.0
        accumulatedTimeMillisRemainder = 0L
        if (midnightCrossed) {
            lastFlushDate = now
        }
        return result
    }

    fun reset() {
        accumulatedTimeSeconds = 0L
        accumulatedDistanceMeters = 0.0
        accumulatedTimeMillisRemainder = 0L
        lastActiveTimestamp = 0L
    }
}
