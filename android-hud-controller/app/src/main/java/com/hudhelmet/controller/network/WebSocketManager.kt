package com.hudhelmet.controller.network

import android.util.Log
import com.google.gson.Gson
import com.hudhelmet.controller.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WebSocketManager private constructor() {
    companion object {
        private const val TAG = "WebSocketManager"

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastConnectedIp: String? = null
    private var userClosed = false
    private var isConnecting = false
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var isConnected = false
        private set

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onWifiScanResult: ((List<WifiNetwork>) -> Unit)? = null
    var onWifiListSaved: ((List<WifiNetwork>) -> Unit)? = null

    fun connect(ipAddress: String) {
        if (isConnected || isConnecting) {
            if (lastConnectedIp == ipAddress) {
                return
            } else {
                disconnect()
            }
        }

        userClosed = false
        lastConnectedIp = ipAddress
        isConnecting = true
        _connectionState.value = ConnectionState.CONNECTING
        reconnectJob?.cancel()

        val url = "ws://$ipAddress/ws"
        Log.i(TAG, "Connecting to WebSocket: $url")

        try {
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket Opened")
                    isConnected = true
                    isConnecting = false
                    _connectionState.value = ConnectionState.CONNECTED
                    onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WebSocket Msg: $text")
                    try {
                        val root = com.google.gson.JsonParser.parseString(text).asJsonObject
                        if (root.has("cmd")) {
                            val cmd = root.get("cmd").asString
                            if (cmd == "wifi_scan_result" || cmd == "wifi_list_saved") {
                                val networksArray = root.getAsJsonArray("networks")
                                val list = mutableListOf<WifiNetwork>()
                                for (i in 0 until networksArray.size()) {
                                    val item = networksArray[i].asJsonObject
                                    val ssid = item.get("ssid").asString
                                    val rssi = if (item.has("rssi")) item.get("rssi").asInt else 0
                                    list.add(WifiNetwork(ssid, rssi))
                                }
                                if (cmd == "wifi_scan_result") onWifiScanResult?.invoke(list)
                                else onWifiListSaved?.invoke(list)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing WS message", e)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket Closed: $reason")
                    isConnected = false
                    isConnecting = false
                    _connectionState.value = ConnectionState.DISCONNECTED
                    onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
                    attemptReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket Failure", t)
                    isConnected = false
                    isConnecting = false
                    _connectionState.value = ConnectionState.ERROR
                    onConnectionStateChanged?.invoke(ConnectionState.ERROR)
                    attemptReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating web socket connection", e)
            isConnecting = false
            isConnected = false
            _connectionState.value = ConnectionState.ERROR
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            attemptReconnect()
        }
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        isConnecting = false
        _connectionState.value = ConnectionState.DISCONNECTED
        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
    }

    private fun attemptReconnect() {
        if (userClosed || lastConnectedIp == null) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (!userClosed && !isConnected && !isConnecting) {
                Log.i(TAG, "Auto-reconnecting to $lastConnectedIp...")
                _connectionState.value = ConnectionState.CONNECTING
                onConnectionStateChanged?.invoke(ConnectionState.CONNECTING)
                connect(lastConnectedIp!!)
            }
        }
    }

    private fun sendJson(cmd: String, data: Any, callback: ((Boolean) -> Unit)? = null) {
        if (!isConnected || webSocket == null) {
            callback?.invoke(false)
            return
        }
        scope.launch {
            try {
                val jsonElement = gson.toJsonTree(data).asJsonObject
                jsonElement.addProperty("cmd", cmd)
                val text = jsonElement.toString()
                val success = webSocket?.send(text) ?: false
                if (success) Log.d(TAG, "Sent WS: $text")
                else Log.w(TAG, "Failed to send WS: $text")
                // Invoke callback on Main thread for safe UI updates
                if (callback != null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        callback.invoke(success)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending JSON", e)
                if (callback != null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        callback.invoke(false)
                    }
                }
            }
        }
    }

    fun sendBinary(bytes: ByteArray, callback: ((Boolean) -> Unit)? = null) {
        if (!isConnected || webSocket == null) {
            callback?.invoke(false)
            return
        }
        scope.launch {
            try {
                val success = webSocket?.send(bytes.toByteString()) ?: false
                if (success) Log.d(TAG, "Sent WS Binary: ${bytes.size} bytes")
                else Log.w(TAG, "Failed to send WS Binary")
                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "Exception sending binary WS", e)
                callback?.invoke(false)
            }
        }
    }

    fun sendTime(timeData: TimeData, callback: ((Boolean) -> Unit)? = null) {
        sendJson("time", timeData, callback)
    }

    fun sendNotification(notification: NotificationData, callback: (Boolean) -> Unit) {
        val normalizedTitle = java.text.Normalizer.normalize(notification.title, java.text.Normalizer.Form.NFC)
        val normalizedMessage = java.text.Normalizer.normalize(notification.message, java.text.Normalizer.Form.NFC)
        val normalizedNotification = notification.copy(title = normalizedTitle, message = normalizedMessage)
        sendJson("notification", normalizedNotification, callback)
    }

    fun clearNotification(callback: (Boolean) -> Unit) {
        sendJson("clear_notif", Any(), callback)
    }

    fun sendMode(modeData: ModeData, callback: (Boolean) -> Unit) {
        sendJson("mode", modeData, callback)
    }

    fun sendStopNav(callback: ((Boolean) -> Unit)? = null) {
        sendJson("stop_nav", Any(), callback)
    }

    fun sendNav(navData: NavData, callback: (Boolean) -> Unit) {
        val normalizedStreet = java.text.Normalizer.normalize(navData.street, java.text.Normalizer.Form.NFC)
        val normalizedInstruction = java.text.Normalizer.normalize(navData.instruction, java.text.Normalizer.Form.NFC)
        val normalizedNavData = navData.copy(street = normalizedStreet, instruction = normalizedInstruction)
        sendJson("nav", normalizedNavData, callback)
    }

    // Wi-Fi Management commands
    fun scanWifi() {
        if (!isConnected || webSocket == null) return
        val json = com.google.gson.JsonObject()
        json.addProperty("cmd", "wifi_scan")
        webSocket?.send(json.toString())
    }

    fun getSavedWifi() {
        if (!isConnected || webSocket == null) return
        val json = com.google.gson.JsonObject()
        json.addProperty("cmd", "wifi_list_saved")
        webSocket?.send(json.toString())
    }

    fun connectWifi(ssid: String, password: String) {
        if (!isConnected || webSocket == null) return
        val json = com.google.gson.JsonObject()
        json.addProperty("cmd", "wifi_connect")
        json.addProperty("ssid", ssid)
        json.addProperty("password", password)
        webSocket?.send(json.toString())
    }

    fun deleteWifi(ssid: String) {
        if (!isConnected || webSocket == null) return
        val json = com.google.gson.JsonObject()
        json.addProperty("cmd", "wifi_delete")
        json.addProperty("ssid", ssid)
        webSocket?.send(json.toString())
    }

    fun sendBrightness(brightness: Int, callback: ((Boolean) -> Unit)? = null) {
        if (!isConnected || webSocket == null) {
            callback?.invoke(false)
            return
        }
        scope.launch {
            try {
                val json = com.google.gson.JsonObject()
                json.addProperty("cmd", "brightness")
                json.addProperty("brightness", brightness.coerceIn(0, 100))
                val success = webSocket?.send(json.toString()) ?: false
                if (success) Log.d(TAG, "Sent brightness: $brightness%")
                if (callback != null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        callback.invoke(success)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending brightness", e)
                if (callback != null) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        callback.invoke(false)
                    }
                }
            }
        }
    }
}
