package com.fbreco.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbreco.ble.BleConnectionState
import com.fbreco.ble.BleManager
import com.fbreco.ble.FtmsParser
import com.fbreco.data.entity.DailyRecord
import com.fbreco.data.repository.DailyRecordRepository
import com.fbreco.service.BikeForegroundService
import com.fbreco.service.RideSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val dailyRecordRepository: DailyRecordRepository,
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState

    val todayRecord: StateFlow<DailyRecord?> = dailyRecordRepository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isActive: StateFlow<Boolean> = bleManager.bikeData
        .map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val serviceRunning: StateFlow<Boolean> = BikeForegroundService.isRunning
    val serviceSnapshot: StateFlow<RideSnapshot> = BikeForegroundService.currentSnapshot

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    fun startScan() {
        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        bleManager.startScan { device ->
            val name = try { device.name } catch (_: SecurityException) { null }
            val entry = DiscoveredDevice(
                name = name ?: "不明なデバイス",
                address = device.address,
            )
            val current = _discoveredDevices.value
            if (current.none { it.address == entry.address }) {
                _discoveredDevices.value = current + entry
            }
        }
        viewModelScope.launch {
            delay(10_500) // slightly after BleManager's 10s timeout
            _isScanning.value = false
        }
    }

    fun connectToDevice(address: String) {
        _isScanning.value = false
        bleManager.connect(address)
    }

    fun disconnectDevice() {
        bleManager.disconnect()
    }
}
