package com.hudhelmet.controller.viewmodel

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.hudhelmet.controller.model.*
import android.content.Intent
import com.hudhelmet.controller.network.BRouterApi
import com.hudhelmet.controller.network.NominatimApi
import com.hudhelmet.controller.network.UdpDiscoveryManager
import com.hudhelmet.controller.network.WebSocketManager
import com.hudhelmet.controller.service.NavigationEngine
import com.hudhelmet.controller.service.MapNavStreamService
import com.hudhelmet.controller.service.ScreenCaptureService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager.getInstance()
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

    // ================================================================
    // Map Navigation States
    // ================================================================

    private val _mapRoute = MutableStateFlow<RouteData?>(null)
    val mapRoute: StateFlow<RouteData?> = _mapRoute.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()

    private val _currentLocation = MutableStateFlow<RoutePoint?>(null)
    val currentLocation: StateFlow<RoutePoint?> = _currentLocation.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _currentBearing = MutableStateFlow(0f)
    val currentBearing: StateFlow<Float> = _currentBearing.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _originPoint = MutableStateFlow<RoutePoint?>(null)
    val originPoint: StateFlow<RoutePoint?> = _originPoint.asStateFlow()

    private val _destinationPoint = MutableStateFlow<RoutePoint?>(null)
    val destinationPoint: StateFlow<RoutePoint?> = _destinationPoint.asStateFlow()

    private val _destinationName = MutableStateFlow("")
    val destinationName: StateFlow<String> = _destinationName.asStateFlow()

    private val _isRouteLoading = MutableStateFlow(false)
    val isRouteLoading: StateFlow<Boolean> = _isRouteLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _navUpdateInfo = MutableStateFlow<NavigationEngine.NavigationUpdate?>(null)
    val navUpdateInfo: StateFlow<NavigationEngine.NavigationUpdate?> = _navUpdateInfo.asStateFlow()

    // GPS & Navigation internals
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var navigationEngine: NavigationEngine? = null
    private var navSendJob: Job? = null
    private var searchJob: Job? = null
    private var previousMode: Int = 1


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
    private var autoDiscoveryJob: Job? = null

    private var cachedTemp: Int? = null
    private var lastWeatherAttemptTime: Long = 0

    init {
        activeViewModel = this

        if (com.hudhelmet.controller.service.MapNavStreamService.isRunning) {
            _isNavigating.value = true
            _mapRoute.value = com.hudhelmet.controller.service.MapNavStreamService.activeRoute
        }
        
        // Listen to connection state updates from the shared WebSocketManager singleton
        viewModelScope.launch {
            webSocketManager.connectionState.collect { state ->
                _connectionState.value = state
                if (state == ConnectionState.CONNECTED) {
                    _statusMessage.value = "Connected to ESP32"
                    if (_autoSyncEnabled.value) {
                        startTimeSync()
                    }
                } else {
                    if (state == ConnectionState.DISCONNECTED) {
                        _statusMessage.value = "Disconnected"
                    } else if (state == ConnectionState.ERROR) {
                        _statusMessage.value = "Connection Error"
                    } else if (state == ConnectionState.CONNECTING) {
                        _statusMessage.value = "Connecting..."
                    }
                    timeSyncJob?.cancel()
                    if (state != ConnectionState.CONNECTING) {
                        // If background service is not running, run local auto-discovery as fallback
                        if (com.hudhelmet.controller.service.HudNotificationListenerService.instance == null) {
                            startAutoDiscovery()
                        }
                    }
                }
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

        // Stop any leftover services on app start to ensure clean state
        stopScreenCaptureService()
        val appCtx = getApplication<Application>()
        appCtx.stopService(Intent(appCtx, MapNavStreamService::class.java))
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

    fun connectToEsp() {
        val ip = _espIpAddress.value.trim()
        if (ip.isNotEmpty() && _connectionState.value == ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.CONNECTING
            webSocketManager.connect(ip)
            if (com.hudhelmet.controller.service.HudNotificationListenerService.instance == null) {
                startAutoDiscovery()
            }
        }
    }

    private fun startAutoDiscovery() {
        if (autoDiscoveryJob?.isActive == true) return
        autoDiscoveryJob = viewModelScope.launch {
            while (isActive && _connectionState.value != ConnectionState.CONNECTED) {
                Log.d("HudViewModel", "Attempting UDP discovery...")
                val discoveredIp = UdpDiscoveryManager.discoverEspIp(3000)
                if (discoveredIp != null) {
                    Log.i("HudViewModel", "Auto-discovered ESP32 IP: $discoveredIp")
                    if (discoveredIp != _espIpAddress.value) {
                        setEspIpAddress(discoveredIp)
                        break
                    }
                }
                delay(2000) // wait before retrying discovery
            }
        }
    }

    fun checkConnection() {
        val ip = _espIpAddress.value.trim()
        if (ip.isNotEmpty()) {
            _connectionState.value = ConnectionState.CONNECTING
            _statusMessage.value = "Connecting to ESP32..."
            webSocketManager.connect(ip)
        }
        if (com.hudhelmet.controller.service.HudNotificationListenerService.instance == null) {
            startAutoDiscovery()
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

    private fun stopScreenCaptureService() {
        val context = getApplication<Application>()
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun setHudMode(mode: Int) {
        _currentMode.value = mode
        sharedPrefs.edit().putInt("current_mode", mode).apply()
        
        // Stop screen streaming if leaving mode 3
        if (mode != 3) {
            stopScreenCaptureService()
        }
        
        // Stop map navigation if leaving mode 4
        if (mode != 4) {
            if (_isNavigating.value) {
                stopNavigation()
            }
        }
        
        // Map tab (4) on App is standby (1) on ESP32 until navigation starts
        val espMode = if (mode == 4) 1 else mode
        val modeData = ModeData(espMode)
        webSocketManager.sendMode(modeData) { success ->
            if (success) {
                _statusMessage.value = "Mode changed to $mode"
            } else {
                _statusMessage.value = "Failed to send mode"
            }
        }
    }

    fun sendModeToEsp(mode: Int) {
        val modeData = ModeData(mode)
        webSocketManager.sendMode(modeData) { success ->
            if (success) {
                Log.d("HudViewModel", "Explicitly set ESP32 mode to $mode")
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

    fun addSentNotification(title: String, message: String, success: Boolean) {
        val sentNotif = com.hudhelmet.controller.model.SentNotification(
            title = title,
            message = message,
            success = success
        )
        val current = _sentNotifications.value.toMutableList()
        current.add(0, sentNotif)
        if (current.size > 10) current.removeAt(current.size - 1)
        _sentNotifications.value = current
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



    // ================================================================
    // Map Navigation Functions
    // ================================================================

    /**
     * Search for places using Nominatim API with debounce.
     */
    fun searchPlaces(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _isSearching.value = true
            try {
                val loc = _currentLocation.value
                val results = NominatimApi.searchPlaces(
                    query = query,
                    lat = loc?.lat,
                    lon = loc?.lon
                )
                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("HudViewModel", "Search error", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    fun setOriginPoint(point: RoutePoint?) {
        _originPoint.value = point
        tryCalculateRoute()
    }

    fun setOriginToMyLocation() {
        _originPoint.value = _currentLocation.value
        tryCalculateRoute()
    }

    fun setDestination(point: RoutePoint, name: String) {
        _destinationPoint.value = point
        _destinationName.value = name
        tryCalculateRoute()
    }

    fun clearRoute() {
        _mapRoute.value = null
        _destinationPoint.value = null
        _destinationName.value = ""
        _originPoint.value = null
        navigationEngine = null
    }

    /**
     * Calculate route if both origin and destination are set.
     */
    private fun tryCalculateRoute() {
        val origin = _originPoint.value ?: _currentLocation.value ?: return
        val dest = _destinationPoint.value ?: return

        viewModelScope.launch {
            _isRouteLoading.value = true
            try {
                val route = BRouterApi.calculateRoute(
                    fromLat = origin.lat, fromLon = origin.lon,
                    toLat = dest.lat, toLon = dest.lon
                )
                _mapRoute.value = route
                if (route != null) {
                    _statusMessage.value = String.format(
                        "Tuyến đường: %.1f km · %d phút",
                        route.totalDistance / 1000.0,
                        route.totalTime / 60
                    )
                } else {
                    _statusMessage.value = "Không tìm được tuyến đường"
                }
            } catch (e: Exception) {
                Log.e("HudViewModel", "Route calculation error", e)
                _statusMessage.value = "Lỗi tính tuyến đường"
            } finally {
                _isRouteLoading.value = false
            }
        }
    }

    /**
     * Start active navigation: switch ESP32 to mode 2, start GPS tracking,
     * and begin sending nav data.
     */
    fun startNavigation() {
        val route = _mapRoute.value ?: return

        previousMode = _currentMode.value
        _isNavigating.value = true

        // Switch ESP32 to map navigation mode (Mode 4)
        sendModeToEsp(4)

        // Stop local location updates so only MapNavStreamService tracks location
        stopLocationUpdates()

        // Start UDP Minimap Streaming Service
        val context = getApplication<Application>()
        val intent = Intent(context, MapNavStreamService::class.java).apply {
            putExtra(MapNavStreamService.EXTRA_ESP_IP, _espIpAddress.value)
            putExtra(MapNavStreamService.EXTRA_FPS, 10) // 10 FPS for smoother rotation animation
            putExtra(MapNavStreamService.EXTRA_QUALITY, 40)
            putExtra(com.hudhelmet.controller.service.MapNavStreamService.EXTRA_ROUTE_JSON, com.google.gson.Gson().toJson(route))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        _statusMessage.value = "Bắt đầu dẫn đường"
    }

    /**
     * Stop active navigation and restore previous mode.
     */
    fun stopNavigation() {
        _isNavigating.value = false
        navigationEngine = null
        navSendJob?.cancel()
        stopLocationUpdates()
        clearNavData()

        // Stop UDP Minimap Streaming Service
        val context = getApplication<Application>()
        context.stopService(Intent(context, MapNavStreamService::class.java))

        // Inform ESP32 via WS to stop streaming and clear screen
        webSocketManager.sendStopNav()

        // Always restore ESP32 to Standby mode (Mode 1) when stopping navigation
        sendModeToEsp(1)

        _navUpdateInfo.value = null
        _statusMessage.value = "Đã dừng dẫn đường"
    }

    /**
     * Start receiving GPS location updates.
     */
    fun startLocationUpdates() {
        if (_isNavigating.value) return
        val context = getApplication<Application>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _statusMessage.value = "Chưa cấp quyền vị trí"
            return
        }

        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        ).setMinUpdateIntervalMillis(500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onLocationReceived(location)
            }
        }

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        // Also get last known location immediately
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                _currentLocation.value = RoutePoint(location.latitude, location.longitude)
            }
        }
    }

    /**
     * Stop GPS location updates.
     */
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
    }

    /**
     * Handle incoming GPS location.
     */
    private fun onLocationReceived(location: Location) {
        val point = RoutePoint(location.latitude, location.longitude)
        _currentLocation.value = point
        _currentSpeed.value = location.speed * 3.6f // m/s -> km/h
        if (location.hasBearing()) {
            _currentBearing.value = location.bearing
        }

        // If actively navigating, update navigation engine
        if (_isNavigating.value) {
            val engine = navigationEngine ?: return
            val update = engine.processLocationUpdate(
                lat = location.latitude,
                lon = location.longitude,
                speedMps = location.speed,
                bearing = location.bearing
            )

            _navUpdateInfo.value = update
            _currentStepIndex.value = update.currentStepIndex

            // Send nav data to ESP32 (throttled)
            sendNavDataThrottled(update.navData)

            // Handle off-route: recalculate
            if (update.isOffRoute) {
                Log.w("HudViewModel", "Off-route detected, recalculating...")
                _originPoint.value = point
                val dest = _destinationPoint.value
                if (dest != null) {
                    viewModelScope.launch {
                        val newRoute = BRouterApi.calculateRoute(
                            fromLat = point.lat, fromLon = point.lon,
                            toLat = dest.lat, toLon = dest.lon
                        )
                        if (newRoute != null) {
                            _mapRoute.value = newRoute
                            navigationEngine = NavigationEngine(newRoute)
                            _statusMessage.value = "Đã tính lại tuyến đường"
                        }
                    }
                }
            }

            // Handle arrival
            if (update.hasArrived) {
                _statusMessage.value = "Đã tới nơi!"
                stopNavigation()
                
                // Send success notification to ESP32 (will render on Standby screen)
                val arrivalNotif = NotificationData(
                    title = "Bản đồ",
                    message = "Đã đến nơi!",
                    icon = "check"
                )
                webSocketManager.sendNotification(arrivalNotif) { }
            }
        }
    }

    /**
     * Send nav data to ESP32, throttled to max once per second.
     */
    private var lastNavSendTime = 0L
    private fun sendNavDataThrottled(navData: NavData) {
        val now = System.currentTimeMillis()
        if (now - lastNavSendTime < 1000) return
        lastNavSendTime = now
        sendNavData(navData)
    }

    fun updateNavigationStateFromService(
        currentLocation: RoutePoint,
        currentSpeed: Float,
        currentBearing: Float,
        navUpdate: NavigationEngine.NavigationUpdate
    ) {
        _currentLocation.value = currentLocation
        _currentSpeed.value = currentSpeed
        _currentBearing.value = currentBearing
        _navUpdateInfo.value = navUpdate
    }

    fun updateRouteFromService(newRoute: RouteData) {
        _mapRoute.value = newRoute
    }

    fun handleArrival() {
        _statusMessage.value = "Đã tới nơi!"
        stopNavigation()
        val arrivalNotif = NotificationData(
            title = "Bản đồ",
            message = "Đã đến nơi!",
            icon = "check"
        )
        webSocketManager.sendNotification(arrivalNotif) { }
    }

    override fun onCleared() {
        super.onCleared()
        timeSyncJob?.cancel()
        navSendJob?.cancel()
        searchJob?.cancel()
        autoDiscoveryJob?.cancel()
        stopLocationUpdates()
        if (!com.hudhelmet.controller.service.MapNavStreamService.isRunning) {
            webSocketManager.disconnect()
        }
        if (activeViewModel == this) {
            activeViewModel = null
        }
    }

    companion object {
        @Volatile
        var activeViewModel: HudViewModel? = null
    }
}
