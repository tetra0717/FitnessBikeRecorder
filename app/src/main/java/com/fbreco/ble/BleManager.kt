package com.fbreco.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleManager"

        private val FTMS_SERVICE_UUID: UUID =
            UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val INDOOR_BIKE_DATA_UUID: UUID =
            UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10

        private const val PREFS_NAME = "ble_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_ble_device_address"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _bikeData = MutableSharedFlow<FtmsParser.BikeData>(extraBufferCapacity = 64)
    val bikeData: SharedFlow<FtmsParser.BikeData> = _bikeData.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var userInitiatedDisconnect = false
    private var lastConnectedAddress: String? = null

    // ── Scan ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit = {}): Job {
        stopScan()
        _connectionState.value = BleConnectionState.Scanning

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(FTMS_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.startScan(listOf(scanFilter), scanSettings, callback)

        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            scanner?.stopScan(callback)
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        return scanJob!!
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    // ── Connect ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        userInitiatedDisconnect = false
        reconnectAttempts = 0
        cancelReconnect()
        stopScan()

        _connectionState.value = BleConnectionState.Connecting
        lastConnectedAddress = address
        saveLastDeviceAddress(address)

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            Log.e(TAG, "Device not found: $address")
            _connectionState.value = BleConnectionState.Disconnected
            return
        }

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ── Disconnect ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        cancelReconnect()
        stopScan()

        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    // ── SharedPreferences ─────────────────────────────────────────────────

    fun getLastDeviceAddress(): String? {
        return prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
    }

    private fun saveLastDeviceAddress(address: String) {
        prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, address).apply()
    }

    // ── GATT Callback ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    reconnectAttempts = 0
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server (status=$status)")
                    gatt.close()
                    bluetoothGatt = null

                    if (!userInitiatedDisconnect && lastConnectedAddress != null) {
                        startReconnect()
                    } else {
                        _connectionState.value = BleConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(FTMS_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "FTMS service not found")
                gatt.disconnect()
                return
            }

            val characteristic = service.getCharacteristic(INDOOR_BIKE_DATA_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Indoor Bike Data characteristic not found")
                gatt.disconnect()
                return
            }

            // Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true)

            // Write to CCCD descriptor to enable remote notifications
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                Log.e(TAG, "CCCD descriptor not found")
            }

            val deviceName = gatt.device?.name
            _connectionState.value = BleConnectionState.Connected(deviceName)
        }

        @Deprecated("Deprecated in API 33, but needed for backward compat")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == INDOOR_BIKE_DATA_UUID) {
                val data = characteristic.value ?: return
                val bikeData = FtmsParser.parse(data) ?: return
                _bikeData.tryEmit(bikeData)
            }
        }
    }

    // ── Reconnect with Exponential Backoff ────────────────────────────────

    private fun startReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            _connectionState.value = BleConnectionState.Disconnected
            return
        }

        _connectionState.value = BleConnectionState.Reconnecting

        reconnectJob = scope.launch {
            val delayMs = (INITIAL_BACKOFF_MS * (1L shl reconnectAttempts.coerceAtMost(14)))
                .coerceAtMost(MAX_BACKOFF_MS)
            reconnectAttempts++
            Log.d(TAG, "Reconnect attempt $reconnectAttempts in ${delayMs}ms")
            delay(delayMs)

            lastConnectedAddress?.let { address ->
                connectInternal(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            Log.e(TAG, "Device not found for reconnect: $address")
            _connectionState.value = BleConnectionState.Disconnected
            return
        }

        _connectionState.value = BleConnectionState.Reconnecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
}
