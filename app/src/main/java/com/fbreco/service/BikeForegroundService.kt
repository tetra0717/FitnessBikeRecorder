package com.fbreco.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fbreco.ble.BleConnectionState
import com.fbreco.ble.BleManager
import com.fbreco.ble.FtmsParser
import com.fbreco.data.repository.DailyRecordRepository
import com.fbreco.data.repository.DestinationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class RideSnapshot(
    val totalTimeSeconds: Long,
    val totalDistanceMeters: Double,
    val accumulatorDistanceMeters: Double,
)

@AndroidEntryPoint
class BikeForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "BikeForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fbreco_bike_channel"
        private const val WRITE_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_UPDATE_MS = 1_000L

        val isRunning = MutableStateFlow(false)
        val currentSnapshot = MutableStateFlow(RideSnapshot(0L, 0.0, 0.0))
    }

    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var dailyRecordRepository: DailyRecordRepository
    @Inject lateinit var destinationRepository: DestinationRepository

    private val accumulator = RideAccumulator()

    private var flushJob: Job? = null
    private var collectJob: Job? = null
    private var notificationUpdateJob: Job? = null

    private var todayTotalTimeSeconds: Long = 0L
    private var todayTotalDistanceMeters: Double = 0.0

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        isRunning.value = true

        loadTodayStats()
        startBikeDataCollection()
        startPeriodicFlush()
        startNotificationUpdater()

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying — flushing remaining data")
        flushJob?.cancel()
        collectJob?.cancel()
        notificationUpdateJob?.cancel()

        // Synchronous-ish flush on destroy: launch and don't cancel
        // (lifecycleScope will be cancelled, so we use a direct coroutine)
        kotlinx.coroutines.runBlocking {
            flushAccumulatedData()
        }

        isRunning.value = false
        currentSnapshot.value = RideSnapshot(0L, 0.0, 0.0)
        super.onDestroy()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FBReco \u30d0\u30a4\u30af\u8a18\u9332",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "\u30d0\u30a4\u30af\u8d70\u884c\u8a18\u9332\u306e\u30b5\u30fc\u30d3\u30b9\u901a\u77e5"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("\u672a\u63a5\u7d9a")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FBReco - \u8a18\u9332\u4e2d")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val state = bleManager.connectionState.value
        val text = when (state) {
            is BleConnectionState.Connected -> {
                val totalTime = todayTotalTimeSeconds + accumulator.accumulatedTimeSeconds
                val totalDist = todayTotalDistanceMeters + accumulator.accumulatedDistanceMeters
                val timeStr = formatTime(totalTime)
                val distStr = String.format("%.2f", totalDist / 1000.0)
                "\u8d70\u884c\u6642\u9593: $timeStr | \u8ddd\u96e2: ${distStr} km"
            }
            is BleConnectionState.Scanning -> "\u30b9\u30ad\u30e3\u30f3\u4e2d..."
            is BleConnectionState.Connecting -> "\u63a5\u7d9a\u4e2d..."
            is BleConnectionState.Reconnecting -> "\u518d\u63a5\u7d9a\u4e2d..."
            is BleConnectionState.Disconnected -> "\u672a\u63a5\u7d9a"
        }

        val notification = buildNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatTime(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // ── BLE Data Collection ────────────────────────────────────────────────

    private fun startBikeDataCollection() {
        collectJob = lifecycleScope.launch {
            bleManager.bikeData.collect { data ->
                onBikeDataReceived(data)
            }
        }
    }

    private fun onBikeDataReceived(data: FtmsParser.BikeData) {
        accumulator.onBikeData(data, System.currentTimeMillis())
    }

    // ── Periodic Flush ─────────────────────────────────────────────────────

    private fun startPeriodicFlush() {
        flushJob = lifecycleScope.launch {
            while (isActive) {
                delay(WRITE_INTERVAL_MS)
                flushAccumulatedData()
            }
        }
    }

    private suspend fun flushAccumulatedData() {
        val result = accumulator.prepareFlush(LocalDate.now()) ?: return

        dailyRecordRepository.addRidingData(
            date = result.date,
            additionalTimeSeconds = result.timeSeconds,
            additionalDistanceMeters = result.distanceMeters
        )

        if (result.distanceMeters > 0) {
            destinationRepository.addDistance(result.distanceMeters)
        }

        if (result.midnightCrossed) {
            Log.d(TAG, "Midnight boundary detected")
            todayTotalTimeSeconds = 0L
            todayTotalDistanceMeters = 0.0
        } else {
            todayTotalTimeSeconds += result.timeSeconds
            todayTotalDistanceMeters += result.distanceMeters
            Log.d(TAG, "Flushed: +${result.timeSeconds}s, +${String.format("%.1f", result.distanceMeters)}m")
        }
        currentSnapshot.value = RideSnapshot(
            todayTotalTimeSeconds,
            todayTotalDistanceMeters,
            accumulator.accumulatedDistanceMeters
        )
    }

    // ── Notification Updater ───────────────────────────────────────────────

    private fun startNotificationUpdater() {
        notificationUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateNotification()
                val totalTime = todayTotalTimeSeconds + accumulator.accumulatedTimeSeconds
                val totalDist = todayTotalDistanceMeters + accumulator.accumulatedDistanceMeters
                currentSnapshot.value = RideSnapshot(
                    totalTime,
                    totalDist,
                    accumulator.accumulatedDistanceMeters
                )
                delay(NOTIFICATION_UPDATE_MS)
            }
        }
    }

    // ── Load Today's Stats ─────────────────────────────────────────────────

    private fun loadTodayStats() {
        lifecycleScope.launch {
            val today = dailyRecordRepository.getByDate(LocalDate.now())
            if (today != null) {
                todayTotalTimeSeconds = today.totalTimeSeconds
                todayTotalDistanceMeters = today.totalDistanceMeters
            }
            currentSnapshot.value = RideSnapshot(todayTotalTimeSeconds, todayTotalDistanceMeters, 0.0)
        }
    }
}
