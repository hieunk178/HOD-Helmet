package com.hudhelmet.controller.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.graphics.drawable.Icon
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Parcelable
import android.util.Log
import com.google.gson.Gson
import com.hudhelmet.controller.model.NavData
import com.hudhelmet.controller.model.NotificationData
import com.hudhelmet.controller.event.HudEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay


/**
 * Service that monitors ALL phone notifications and forwards them to the HUD helmet.
 *
 * - General notifications (Zalo, Messenger, SMS, ...) → WebSocket or HTTP POST
 * - Google Maps navigation notifications → WebSocket or HTTP POST
 *
 * Requires user to grant Notification Access permission in Android Settings.
 */
class HudNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: HudNotificationListenerService? = null
            private set
    }

    private val tag = "HudNotifService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val lastCallTimeMap = mutableMapOf<String, Long>()
    private val webSocketManager = com.hudhelmet.controller.network.WebSocketManager.getInstance()
    private var autoDiscoveryJob: kotlinx.coroutines.Job? = null
    private val pendingNotifications = java.util.concurrent.ConcurrentLinkedQueue<com.hudhelmet.controller.model.NotificationData>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(tag, "Service onCreate - starting background connection management")

        // Start as foreground service to prevent being killed when swiped away
        startForegroundService()

        // Periodically verify connection and run UDP discovery in background if offline
        scope.launch {
            webSocketManager.connectionState.collect { state ->
                Log.d(tag, "Background connection state: $state")
                if (state == com.hudhelmet.controller.model.ConnectionState.CONNECTED) {
                    // Drain pending notifications queue
                    while (pendingNotifications.isNotEmpty()) {
                        val notif = pendingNotifications.peek() ?: break
                        Log.i(tag, "Sending pending notification: ${notif.title}")
                        webSocketManager.sendNotification(notif) { success ->
                            if (success) {
                                pendingNotifications.poll()
                            }
                        }
                        delay(200) // Small delay between sends to avoid flooding
                    }
                } else if (state == com.hudhelmet.controller.model.ConnectionState.DISCONNECTED ||
                    state == com.hudhelmet.controller.model.ConnectionState.ERROR) {
                    startAutoDiscovery()
                }
            }
        }

        // Trigger initial connect
        val sharedPrefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("esp_ip_address", "192.168.4.1") ?: "192.168.4.1"
        webSocketManager.connect(ip)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDiscoveryJob?.cancel()
        instance = null
        Log.i(tag, "Service onDestroy - connection manager stopped")
    }

    private fun startForegroundService() {
        val channelId = "hud_service_channel"
        val channelName = "HUD Helmet Background Service"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, com.hudhelmet.controller.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("HUD Helmet Active")
            .setContentText("Duy trì kết nối mũ bảo hiểm trong nền")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    1002,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1002, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start foreground service: ${e.message}", e)
        }
    }

    private fun startAutoDiscovery() {
        if (autoDiscoveryJob?.isActive == true) return
        val sharedPrefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)

        autoDiscoveryJob = scope.launch {
            while (isActive && webSocketManager.connectionState.value != com.hudhelmet.controller.model.ConnectionState.CONNECTED) {
                Log.d(tag, "Background UDP auto-discovery run...")
                val discoveredIp = com.hudhelmet.controller.network.UdpDiscoveryManager.discoverEspIp(3000)
                if (discoveredIp != null) {
                    Log.i(tag, "Background discovered ESP32 IP: $discoveredIp")
                    sharedPrefs.edit().putString("esp_ip_address", discoveredIp).apply()
                    webSocketManager.connect(discoveredIp)
                    break
                }
                delay(5000) // 5s retry cooldown for background battery friendliness
            }
        }
    }

    // Packages to ignore (system/noise packages)
    private val ignoredPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.google.android.gms",
        "com.android.vending"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val sharedPrefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)
        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return

        // Skip our own app's notifications
        if (pkg == packageName) return

        // ── 1. Google Maps: handle as navigation data ───────────────────────────
        if (pkg == "com.google.android.apps.maps") {
            handleMapsNotification(sbn, sharedPrefs)
            return
        }

        // ── 2. General notification forwarding ──────────────────────────────────
        val forwardingEnabled = sharedPrefs.getBoolean("notif_forwarding_enabled", true)
        if (!forwardingEnabled) return

        val category = notification.category
        val isCall = category == Notification.CATEGORY_CALL || category == "call"

        // Skip ignored/system packages (except for calls)
        if (pkg in ignoredPackages && !isCall) return

        // Skip ongoing/persistent/silent notifications (except for calls)
        if (!isCall) {
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            if (notification.flags and Notification.FLAG_NO_CLEAR != 0) return
        }

        val extras = notification.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        // Resolve a human-readable app label for the icon/source
        var appLabel = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        // Skip if no meaningful content
        if (title.isBlank() && text.isBlank()) return

        if (isCall) {
            val textLower = text.lowercase()

            // Skip ongoing/active call updates (which update the timer and would block the HUD map)
            val isOngoing = textLower.contains("đang diễn ra") ||
                            textLower.contains("ongoing") ||
                            textLower.contains("đang trong cuộc gọi") ||
                            textLower.contains("active") ||
                            textLower.contains("mọi người trong") ||
                            """\d+:\d+""".toRegex().containsMatchIn(textLower)

            if (isOngoing) {
                Log.d(tag, "Skipping ongoing call update for $pkg")
                return
            }

            // Cooldown check to prevent multiple ring updates
            val now = System.currentTimeMillis()
            val lastSent = lastCallTimeMap[pkg] ?: 0L
            if (now - lastSent < 15000L) {
                Log.d(tag, "Throttling call notification from $pkg")
                return
            }
            lastCallTimeMap[pkg] = now

            // Override label/icon for system phone call to show the phone icon on ESP32
            val isChatVoip = pkg.contains("zalo") || pkg.contains("facebook") || pkg.contains("messenger") ||
                             pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("viber")
            if (!isChatVoip) {
                appLabel = "Phone" // Maps to LV_SYMBOL_CALL on ESP32
            }

            // Format call notification title in plain text to avoid emoji display errors on ESP32
            if (!title.startsWith("[Cuộc gọi]")) {
                title = "[Cuộc gọi] $title"
            }
            if (text.isBlank()) {
                text = "Cuộc gọi đến"
            }
        }

        val notifTitle = if (title.isNotBlank()) title else appLabel
        val notifMessage = if (text.isNotBlank()) text else ""

        Log.d(tag, "Phone notification from $appLabel: title='$notifTitle', msg='$notifMessage'")



        val notificationData = com.hudhelmet.controller.model.NotificationData(title = notifTitle, message = notifMessage, icon = appLabel)

        if (webSocketManager.connectionState.value == com.hudhelmet.controller.model.ConnectionState.CONNECTED) {
            webSocketManager.sendNotification(notificationData) { success ->
                if (success) {
                    Log.d(tag, "Successfully forwarded notification directly: $notifTitle")
                }
            }
        } else {
            // Buffer notification (max 10 to prevent unbounded growth)
            Log.i(tag, "WebSocket not connected. Buffering notification: $notifTitle")
            if (pendingNotifications.size < 10) {
                pendingNotifications.add(notificationData)
            }

            // Force connection attempt immediately
            val ip = sharedPrefs.getString("esp_ip_address", "192.168.4.1") ?: "192.168.4.1"
            webSocketManager.connect(ip)
        }

        // Notify ViewModel via event bus (decoupled, no memory leak)
        scope.launch {
            HudEventBus.emitNotificationSent(notifTitle, notifMessage, true)
        }
    }

    /**
     * Handles Google Maps navigation notifications — sends nav data to HUD.
     */
    private fun handleMapsNotification(sbn: StatusBarNotification, sharedPrefs: android.content.SharedPreferences) {
        val isNavEnabled = sharedPrefs.getBoolean("navigation_enabled", true)
        if (!isNavEnabled) return

        // Only forward nav data when HUD is in Directions Mode (mode 2)
        val mode = sharedPrefs.getInt("current_mode", 1)
        if (mode != 2) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        Log.d(tag, "Google Maps: title='$title', text='$text', subText='$subText'")

        val navData = parseNavigationNotification(title, text, subText) ?: return
        Log.i(tag, "Parsed nav: $navData")

        // Extract icon bitmap
        val iconObj = extras.getParcelable<Parcelable>(Notification.EXTRA_LARGE_ICON)
        var bitmap: Bitmap? = null
        if (iconObj is Icon) {
            val drawable = iconObj.loadDrawable(this)
            if (drawable is BitmapDrawable) {
                bitmap = drawable.bitmap
            } else if (drawable != null) {
                bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        } else if (iconObj is Bitmap) {
            bitmap = iconObj
        }

        // Send nav data via WebSocket directly and notify ViewModel via event bus
        webSocketManager.sendNav(navData) { }
        scope.launch {
            HudEventBus.emitNavData(navData, bitmap)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != "com.google.android.apps.maps") return
        Log.d(tag, "Google Maps notification removed")

        scope.launch {
            HudEventBus.emitNavClear()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Parses a Google Maps navigation notification into structured NavData.
     */
    private fun parseNavigationNotification(title: String?, text: String?, subText: String?): NavData? {
        if (title.isNullOrEmpty()) return null

        val textVal = text ?: ""
        val tripInfoVal = subText ?: ""
        var instruction = title ?: ""
        var street = ""

        val distanceRegex = """(\d+(?:[.,]\d+)?)\s*(m|km|ft|mi|met|dặm)""".toRegex(RegexOption.IGNORE_CASE)
        val titleMatch = distanceRegex.find(instruction)
        val textMatch = distanceRegex.find(textVal)

        if (titleMatch != null && textMatch == null) {
            // Distance is in title, text is likely the street or instruction
            street = textVal
        } else {
            // Distance is in text, title is the instruction
            val lowerInst = instruction.lowercase()
            when {
                lowerInst.contains(" vào ") -> street = instruction.substringAfterLast(" vào ", instruction).trim()
                lowerInst.contains(" trên ") -> street = instruction.substringAfterLast(" trên ", instruction).trim()
                lowerInst.contains(" onto ") -> street = instruction.substringAfterLast(" onto ", instruction).trim()
                lowerInst.contains(" on ") -> street = instruction.substringAfterLast(" on ", instruction).trim()
                else -> street = instruction
            }
            // If street name is too long or contains instructions, try to clean it
            if (street.length > 30) {
                street = street.take(30) + "..."
            }
        }

        var distance = 200
        val match = textMatch ?: titleMatch
        if (match != null) {
            val num = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 200.0
            distance = when (match.groupValues[2].lowercase()) {
                "km" -> (num * 1000).toInt()
                "ft" -> (num * 0.3048).toInt()
                "mi", "dặm" -> (num * 1609.34).toInt()
                else -> num.toInt()
            }
        }

        val combined = "$title $textVal".lowercase()
        val turnType = when {
            combined.contains("rẽ trái") || combined.contains("chếch sang trái") ||
            combined.contains("turn left") || combined.contains("keep left") ||
            combined.contains("bear left") || combined.contains("slight left") -> 2
            combined.contains("rẽ phải") || combined.contains("chếch sang phải") ||
            combined.contains("turn right") || combined.contains("keep right") ||
            combined.contains("bear right") || combined.contains("slight right") -> 3
            combined.contains("quay đầu") || combined.contains("u-turn") ||
            combined.contains("quay lại") -> 4
            else -> 1
        }

        var timeLeft = ""
        var distanceLeft = ""
        var eta = ""
        
        val parts = tripInfoVal.split(" · ")
        if (parts.size >= 3) {
            timeLeft = parts[0].trim()
            distanceLeft = parts[1].trim()
            eta = parts[2].trim()
            if (eta.startsWith("Dự kiến ", ignoreCase = true)) {
                eta = eta.substring(8).trim()
            }
        } else if (parts.size == 2) {
            timeLeft = parts[0].trim()
            distanceLeft = parts[1].trim()
        } else {
            timeLeft = tripInfoVal
        }

        return NavData(
            street = street,
            instruction = instruction ?: "",
            timeLeft = timeLeft,
            distanceLeft = distanceLeft,
            eta = eta,
            distance = distance,
            turnType = turnType,
            active = true
        )
    }

}
