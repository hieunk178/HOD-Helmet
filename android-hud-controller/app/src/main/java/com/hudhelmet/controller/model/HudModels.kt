package com.hudhelmet.controller.model

import com.google.gson.annotations.SerializedName

/**
 * Time data to send to ESP32
 */
data class TimeData(
    @SerializedName("hour") val hour: Int,
    @SerializedName("minute") val minute: Int,
    @SerializedName("second") val second: Int,
    @SerializedName("day") val day: Int,
    @SerializedName("month") val month: Int,
    @SerializedName("year") val year: Int,
    @SerializedName("weekday") val weekday: Int,  // 0=Sun, 1=Mon, ... 6=Sat
    @SerializedName("battery") val battery: Int,
    @SerializedName("temp") val temp: Int
)

/**
 * Notification data to send to ESP32
 */
data class NotificationData(
    @SerializedName("title") val title: String,
    @SerializedName("message") val message: String,
    @SerializedName("icon") val icon: String = "bell"
)

/**
 * ESP32 status response
 */
data class EspStatus(
    @SerializedName("wifi_connected") val wifiConnected: Boolean = false,
    @SerializedName("ip_address") val ipAddress: String = "",
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerializedName("display_on") val displayOn: Boolean = false,
    @SerializedName("has_notification") val hasNotification: Boolean = false
)

/**
 * API response from ESP32
 */
data class ApiResponse(
    @SerializedName("status") val status: String
)

/**
 * Connection state for the UI
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Wi-Fi network information from ESP32 scan
 */
data class WifiNetwork(
    val ssid: String,
    val rssi: Int = 0
)
/**
 * Sent notification record for history display
 */
data class SentNotification(
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
)

/**
 * Mode selection command data to send to ESP32
 */
data class ModeData(
    @SerializedName("mode") val mode: Int
)

/**
 * Navigation instructions and distance data to send to ESP32
 */
data class NavData(
    @SerializedName("street") val street: String,
    @SerializedName("instruction") val instruction: String,
    @SerializedName("time_left") val timeLeft: String = "",
    @SerializedName("distance_left") val distanceLeft: String = "",
    @SerializedName("eta") val eta: String = "",
    @SerializedName("distance") val distance: Int,
    @SerializedName("turn_type") val turnType: Int, // 1=STRAIGHT, 2=LEFT, 3=RIGHT, 4=U_TURN
    @SerializedName("active") val active: Boolean = true
)

