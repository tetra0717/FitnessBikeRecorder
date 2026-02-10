package com.fbreco.ble

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    object Connecting : BleConnectionState()
    data class Connected(val deviceName: String?) : BleConnectionState()
    object Reconnecting : BleConnectionState()
}
