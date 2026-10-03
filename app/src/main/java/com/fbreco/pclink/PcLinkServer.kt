package com.fbreco.pclink

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import com.fbreco.ble.BleConnectionState
import com.fbreco.ble.BleManager
import com.fbreco.ble.FtmsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PcLinkStatus(val message: String = "Wi-Fiを待っています", val endpoint: String = "", val clients: Int = 0)

@Singleton
class PcLinkServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
) {
    private val prefs = context.getSharedPreferences("pc_link", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    val pairingCode: String = prefs.getString("pairing_code", null) ?: ByteArray(16).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it.toInt() and 0xff) }.also {
        prefs.edit().putString("pairing_code", it).apply()
    }
    private val key = pairingCode.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(PcLinkStatus())
    val status = _status.asStateFlow()
    private data class Received(val data: FtmsParser.BikeData, val at: Long)
    @Volatile private var received: Received? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var registrationFailed = false
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private var server: NanoWSD? = null
    private var address: String? = null
    private var ticker: Job? = null
    private var connectionCollector: Job? = null
    @Volatile private var scope: CoroutineScope? = null
    private val serverGate = Any()

    fun onBikeData(data: FtmsParser.BikeData) { received = Received(data, SystemClock.elapsedRealtime()) }

    fun start(serviceScope: CoroutineScope) {
        if (ticker != null) return
        scope = serviceScope
        connectionCollector = serviceScope.launch {
            bleManager.connectionState.collect { if (it !is BleConnectionState.Connected) received = null }
        }
        ticker = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    synchronized(serverGate) {
                        if (scope == null) return@launch
                        val wifiAddress = findWifiAddress()
                        if (wifiAddress != address || registrationFailed) {
                            stopServer()
                            if (wifiAddress != null) startServer(wifiAddress)
                        }
                        if (server != null) {
                            sessions.forEach { it.enqueue() }
                            _status.value = PcLinkStatus("PC接続待ち", "${address}:${server!!.listeningPort}", sessions.size)
                        } else _status.value = PcLinkStatus("Wi-Fiを待っています")
                    }
                } catch (exception: Exception) {
                    Log.w("PcLink", "Unable to start local connection", exception)
                    synchronized(serverGate) { stopServer() }
                    _status.value = PcLinkStatus("接続を再試行しています")
                }
                delay(1000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findWifiAddress(): String? = connectivity.allNetworks.firstNotNullOfOrNull { network ->
        if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
        else connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress
    }

    private fun startServer(ip: String) {
        val candidate = object : NanoWSD(ip, 0) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val challenge = session.headers["x-fbreco-challenge"]
                val proof = session.headers["x-fbreco-proof"]
                if (session.uri != "/bike" || session.method != NanoHTTPD.Method.GET)
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found")
                if (!PcLinkProtocol.verifyConnection(key, challenge, proof))
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "text/plain", "Pairing required")
                if (sessions.size >= 4)
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Busy")
                return super.serve(session)
            }
            override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket =
                Session(handshake, handshake.headers.getValue("x-fbreco-challenge"))
        }
        candidate.start(5000, true)
        server = candidate
        address = ip
        registrationFailed = false
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (registration !== this) runCatching { nsd.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                if (registration === this) registrationFailed = true
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "FBReco-$deviceId"
            serviceType = "_fbreco._tcp."
            port = candidate.listeningPort
            setAttribute("id", deviceId)
            setAttribute("protocol", "1")
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopServer() {
        val previous = registration
        registration = null
        if (previous != null) runCatching { nsd.unregisterService(previous) }
        sessions.toList().forEach { it.shutdown() }
        sessions.clear()
        server?.stop()
        server = null
        address = null
        registrationFailed = false
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        connectionCollector?.cancel(); connectionCollector = null
        // Shutdown is serialized with the ticker to avoid leaving a server after service destruction.
        synchronized(serverGate) { scope = null; stopServer() }
        received = null
        _status.value = PcLinkStatus("PC連携を停止しました")
    }

    private inner class Session(handshake: NanoHTTPD.IHTTPSession, private val challenge: String) : NanoWSD.WebSocket(handshake) {
        private val sequence = AtomicLong()
        private val outgoing = Channel<String>(Channel.CONFLATED)
        private var writer: Job? = null
        override fun onOpen() {
            if (scope == null || sessions.size >= 4) { shutdown(); return }
            sessions.add(this)
            writer = scope?.launch(Dispatchers.IO) {
                try { for (message in outgoing) send(message) }
                catch (_: IOException) { shutdown() }
            }
            enqueue()
        }
        @Synchronized fun enqueue() {
            val snapshot = received
            val connected = bleManager.connectionState.value is BleConnectionState.Connected
            val fresh = connected && snapshot != null && snapshot.data.hasPedalData &&
                SystemClock.elapsedRealtime() - snapshot.at < 5000
            val data = snapshot?.data
            val payload = JSONObject().apply {
                put("protocol", 1); put("deviceId", deviceId); put("sequence", sequence.incrementAndGet())
                put("bikeConnected", connected); put("dataFresh", fresh); put("isActive", fresh && data?.isActive == true)
                put("cadenceRpm", data?.cadenceRpm ?: 0.0); put("powerWatts", data?.powerWatts ?: 0)
                put("speedKmh", data?.speedKmh ?: 0.0)
            }.toString()
            outgoing.trySend(JSONObject().apply {
                put("payload", payload); put("signature", PcLinkProtocol.signTelemetry(key, challenge, payload))
            }.toString())
        }
        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            if (message.textPayload != "ping") shutdown()
        }
        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit
        override fun onException(exception: IOException) { shutdown() }
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) { cleanup() }
        private fun cleanup() { sessions.remove(this); outgoing.close(); writer?.cancel() }
        fun shutdown() {
            cleanup()
            runCatching { close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "Disconnected", false) }
        }
    }
}
