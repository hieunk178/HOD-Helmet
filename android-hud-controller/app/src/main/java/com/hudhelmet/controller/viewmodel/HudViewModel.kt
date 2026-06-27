package com.hudhelmet.controller.viewmodel

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hudhelmet.controller.model.*
import com.hudhelmet.controller.network.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager()
    private val sharedPrefs = application.getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)

    // -- Connection State --
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // -- ESP32 IP Address --
    private val _espIpAddress = MutableStateFlow(
        sharedPrefs.getString("esp_ip_address", "192.168.4.1") ?: "192.168.4.1"
    )
    val espIpAddress: StateFlow<String> = _espIpAddress.asStateFlow()

    // -- Wi-Fi Management --
    private val _scannedNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val scannedNetworks: StateFlow<List<WifiNetwork>> = _scannedNetworks.asStateFlow()

    private val _savedNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val savedNetworks: StateFlow<List<WifiNetwork>> = _savedNetworks.asStateFlow()

    // -- Auto sync enabled --
    private val _autoSyncEnabled = MutableStateFlow(sharedPrefs.getBoolean("auto_sync_enabled", true))
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    // -- Sync interval (seconds) --
    private val _syncInterval = MutableStateFlow(sharedPrefs.getInt("sync_interval", 30))
    val syncInterval: StateFlow<Int> = _syncInterval.asStateFlow()

    // -- HUD Mode state --
    private val _currentMode = MutableStateFlow(sharedPrefs.getInt("current_mode", 1))
    val currentMode: StateFlow<Int> = _currentMode.asStateFlow()

    // -- Navigation Enabled state --
    private val _navigationEnabled = MutableStateFlow(sharedPrefs.getBoolean("navigation_enabled", true))
    val navigationEnabled: StateFlow<Boolean> = _navigationEnabled.asStateFlow()

    // -- Notification Forwarding Enabled state --
    private val _notifForwardingEnabled = MutableStateFlow(sharedPrefs.getBoolean("notif_forwarding_enabled", true))
    val notifForwardingEnabled: StateFlow<Boolean> = _notifForwardingEnabled.asStateFlow()

    // -- Navigation data state --
    private val _currentNavData = MutableStateFlow<NavData?>(null)
    val currentNavData: StateFlow<NavData?> = _currentNavData.asStateFlow()
    

    // -- Notification input --
    private val _notifTitle = MutableStateFlow("")
    val notifTitle: StateFlow<String> = _notifTitle.asStateFlow()

    private val _notifMessage = MutableStateFlow("")
    val notifMessage: StateFlow<String> = _notifMessage.asStateFlow()

    // -- Recent notifications --
    private val _sentNotifications = MutableStateFlow<List<SentNotification>>(emptyList())
    val sentNotifications: StateFlow<List<SentNotification>> = _sentNotifications.asStateFlow()

    // -- Status message --
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // -- Last sync time --
    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    // -- Background jobs --
    private var timeSyncJob: Job? = null

    private var cachedTemp: Int? = null
    private var lastWeatherAttemptTime: Long = 0

    init {
        activeViewModel = this
        webSocketManager.onConnectionStateChanged = { connected ->
            _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            if (connected) {
                _statusMessage.value = "Connected to ESP32"
                if (_autoSyncEnabled.value) {
                    startTimeSync()
                }
            } else {
                _statusMessage.value = "Disconnected"
                timeSyncJob?.cancel()
            }
        }

        webSocketManager.onWifiScanResult = { networks ->
            _scannedNetworks.value = networks
        }

        webSocketManager.onWifiListSaved = { networks ->
            _savedNetworks.value = networks
        }

        // Start periodic weather updates
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastWeatherAttemptTime > 15 * 60 * 1000L || 
                    (cachedTemp == null && now - lastWeatherAttemptTime > 5 * 60 * 1000L)) {
                    updateWeather()
                }
                delay(60 * 1000L) // Check every 60 seconds
            }
        }
    }

    // ================================================================
    // Connection Management
    // ================================================================

    fun setEspIpAddress(ip: String) {
        webSocketManager.disconnect()
        _espIpAddress.value = ip
        sharedPrefs.edit().putString("esp_ip_address", ip).apply()
        _connectionState.value = ConnectionState.DISCONNECTED
        checkConnection()
    }

    fun checkConnection() {
        val ip = _espIpAddress.value.trim()
        if (ip.isNotEmpty()) {
            _connectionState.value = ConnectionState.CONNECTING
            _statusMessage.value = "Connecting to ESP32..."
            webSocketManager.connect(ip)
        }
    }

    fun connectToApMode() {
        webSocketManager.disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Đang kết nối tới 192.168.4.1..."
        webSocketManager.connect("192.168.4.1")
    }

    // ================================================================
    // Wi-Fi Management
    // ================================================================

    fun scanWifi() {
        webSocketManager.scanWifi()
        _statusMessage.value = "Scanning Wi-Fi..."
    }

    fun getSavedWifi() {
        webSocketManager.getSavedWifi()
    }

    fun connectEspWifi(ssid: String, password: String) {
        webSocketManager.connectWifi(ssid, password)
        _statusMessage.value = "Sending connect command for $ssid..."
    }

    fun deleteSavedWifi(ssid: String) {
        webSocketManager.deleteWifi(ssid)
        _statusMessage.value = "Deleted saved network $ssid"
    }

    // ================================================================
    // Time Sync
    // ================================================================

    fun sendTimeNow() {
        val timeData = getCurrentTimeData()
        webSocketManager.sendTime(timeData) { success ->
            if (success) {
                _lastSyncTime.value = System.currentTimeMillis()
                _statusMessage.value = "Time synced: ${timeData.hour}:${String.format("%02d", timeData.minute)}"
            } else {
                _statusMessage.value = "Time sync failed"
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        _autoSyncEnabled.value = enabled
        sharedPrefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        if (enabled && _connectionState.value == ConnectionState.CONNECTED) {
            startTimeSync()
        } else {
            timeSyncJob?.cancel()
            timeSyncJob = null
        }
    }

    fun setSyncInterval(seconds: Int) {
        val coerced = seconds.coerceIn(5, 300)
        _syncInterval.value = coerced
        sharedPrefs.edit().putInt("sync_interval", coerced).apply()
        if (_autoSyncEnabled.value) {
            startTimeSync()
        }
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        timeSyncJob = viewModelScope.launch {
            while (isActive) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    sendTimeNow()
                }
                delay(_syncInterval.value * 1000L)
            }
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val context = getApplication<Application>()
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) pct else 100
        } catch (e: Exception) {
            100
        }
    }



    private fun updateWeather() {
        lastWeatherAttemptTime = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lat = 21.0285 // Fallback to Hanoi
                val lon = 105.8542
                
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m"
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                            val current = json.getAsJsonObject("current")
                            val temp = current.getAsJsonPrimitive("temperature_2m").asDouble
                            cachedTemp = kotlin.math.round(temp).toInt()
                            
                            withContext(Dispatchers.Main) {
                                if (_connectionState.value == ConnectionState.CONNECTED) {
                                    sendTimeNow()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getCurrentTimeData(): TimeData {
        val cal = Calendar.getInstance()
        return TimeData(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            second = cal.get(Calendar.SECOND),
            day = cal.get(Calendar.DAY_OF_MONTH),
            month = cal.get(Calendar.MONTH) + 1,  // Calendar months are 0-based
            year = cal.get(Calendar.YEAR),
            weekday = cal.get(Calendar.DAY_OF_WEEK) - 1,  // Convert to 0=Sun format
            battery = getBatteryPercentage(),
            temp = cachedTemp ?: -999
        )
    }

    // ================================================================
    // Notifications
    // ================================================================

    fun setNotifTitle(title: String) {
        _notifTitle.value = title
    }

    fun setNotifMessage(message: String) {
        _notifMessage.value = message
    }

    fun sendNotification() {
        val title = _notifTitle.value.trim()
        val message = _notifMessage.value.trim()

        if (title.isEmpty() || message.isEmpty()) {
            _statusMessage.value = "Please enter title and message"
            return
        }

        val notification = NotificationData(title = title, message = message)

        webSocketManager.sendNotification(notification) { success ->
            val sentNotif = SentNotification(
                title = title,
                message = message,
                success = success
            )

            // Add to history (max 10 items)
            val current = _sentNotifications.value.toMutableList()
            current.add(0, sentNotif)
            if (current.size > 10) current.removeAt(current.size - 1)
            _sentNotifications.value = current

            if (success) {
                _statusMessage.value = "Notification sent: $title"
                _notifTitle.value = ""
                _notifMessage.value = ""
            } else {
                _statusMessage.value = "Failed to send notification"
            }
        }
    }

    fun clearNotification() {
        webSocketManager.clearNotification { success ->
            if (success) {
                _statusMessage.value = "Notification cleared"
            } else {
                _statusMessage.value = "Failed to clear notification"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun setHudMode(mode: Int) {
        _currentMode.value = mode
        sharedPrefs.edit().putInt("current_mode", mode).apply()
        
        val modeData = ModeData(mode)
        webSocketManager.sendMode(modeData) { success ->
            if (success) {
                _statusMessage.value = "Mode changed to $mode"
            } else {
                _statusMessage.value = "Failed to send mode"
            }
        }
    }

    fun setNavigationEnabled(enabled: Boolean) {
        _navigationEnabled.value = enabled
        sharedPrefs.edit().putBoolean("navigation_enabled", enabled).apply()
    }

    fun setNotifForwardingEnabled(enabled: Boolean) {
        _notifForwardingEnabled.value = enabled
        sharedPrefs.edit().putBoolean("notif_forwarding_enabled", enabled).apply()
    }

    fun sendPhoneNotification(title: String, message: String, appLabel: String) {
        if (!_notifForwardingEnabled.value) return

        val notification = NotificationData(title = title, message = message, icon = appLabel)

        webSocketManager.sendNotification(notification) { success ->
            if (success) {
                android.util.Log.d("HudViewModel", "Phone notif forwarded: [$appLabel] $title")
                _statusMessage.value = "📲 $appLabel: $title"
                val sentNotif = com.hudhelmet.controller.model.SentNotification(
                    title = title,
                    message = message,
                    success = true
                )
                val current = _sentNotifications.value.toMutableList()
                current.add(0, sentNotif)
                if (current.size > 10) current.removeAt(current.size - 1)
                _sentNotifications.value = current
            }
        }
    }

    fun sendNavData(nav: NavData) {
        _currentNavData.value = nav
        webSocketManager.sendNav(nav) { success ->
            if (success) {
                _statusMessage.value = "Nav data sent"
            }
        }
    }

    fun clearNavData() {
        _currentNavData.value = null
        val emptyNav = NavData(street = "", instruction = "", distance = 0, turnType = 1, active = false)
        webSocketManager.sendNav(emptyNav) { }
    }

    fun sendNavIcon(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                val w = scaled.width
                val h = scaled.height
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                
                val outBytes = ByteArray(1 + w * h * 2)
                outBytes[0] = 0x01 // Command ID for Nav Icon
                
                var idx = 1
                for (pixel in pixels) {
                    val r = (Color.red(pixel) shr 3) and 0x1F
                    val g = (Color.green(pixel) shr 2) and 0x3F
                    val b = (Color.blue(pixel) shr 3) and 0x1F
                    
                    // Note: If image has transparency (alpha), we could blend with black background
                    val a = Color.alpha(pixel)
                    val rBlended = (r * a) / 255
                    val gBlended = (g * a) / 255
                    val bBlended = (b * a) / 255

                    // LVGL uses 16-bit color. For ESP32 it's typically Little Endian or Swapped.
                    // We'll send standard RGB565 (Little Endian bytes).
                    val rgb565 = (rBlended shl 11) or (gBlended shl 5) or bBlended
                    
                    outBytes[idx++] = (rgb565 and 0xFF).toByte()
                    outBytes[idx++] = ((rgb565 shr 8) and 0xFF).toByte()
                }
                
                webSocketManager.sendBinary(outBytes)
            } catch (e: Exception) {
                Log.e("HudViewModel", "Error processing nav icon", e)
            }
        }
    }



    override fun onCleared() {
        super.onCleared()
        timeSyncJob?.cancel()
        webSocketManager.disconnect()
        if (activeViewModel == this) {
            activeViewModel = null
        }
    }

    companion object {
        @Volatile
        var activeViewModel: HudViewModel? = null
    }
}
