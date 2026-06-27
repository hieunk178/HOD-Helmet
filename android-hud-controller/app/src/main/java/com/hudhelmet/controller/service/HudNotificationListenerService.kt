package com.hudhelmet.controller.service

import android.app.Notification
import android.content.Context
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
import com.hudhelmet.controller.viewmodel.HudViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * Service that monitors ALL phone notifications and forwards them to the HUD helmet.
 *
 * - General notifications (Zalo, Messenger, SMS, ...) → WebSocket or HTTP POST
 * - Google Maps navigation notifications → WebSocket or HTTP POST
 *
 * Requires user to grant Notification Access permission in Android Settings.
 */
class HudNotificationListenerService : NotificationListenerService() {

    private val tag = "HudNotifService"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson = Gson()
    // Packages to ignore (system/noise packages)
    private val ignoredPackages = setOf(
        packageName,                                // Our own app
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

        // ── 1. Google Maps: handle as navigation data ───────────────────────────
        if (pkg == "com.google.android.apps.maps") {
            handleMapsNotification(sbn, sharedPrefs)
            return
        }

        // ── 2. General notification forwarding ──────────────────────────────────
        val forwardingEnabled = sharedPrefs.getBoolean("notif_forwarding_enabled", true)
        if (!forwardingEnabled) return

        // Skip ignored/system packages
        if (pkg in ignoredPackages) return

        // Skip ongoing/persistent/silent notifications (e.g. media players, progress bars)
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_NO_CLEAR != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // Skip if no meaningful content
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Resolve a human-readable app label for the icon/source
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        val notifTitle = if (!title.isNullOrBlank()) title else appLabel
        val notifMessage = if (!text.isNullOrBlank()) text else ""

        Log.d(tag, "Phone notification from $appLabel: title='$notifTitle', msg='$notifMessage'")

        val ipAddress = sharedPrefs.getString("esp_ip_address", "192.168.4.1") ?: "192.168.4.1"

        // Prefer ViewModel if app is active (so UI also shows the event)
        val activeVm = HudViewModel.activeViewModel
        if (activeVm != null) {
            activeVm.sendPhoneNotification(notifTitle, notifMessage, appLabel)
        } else {
            Log.w(tag, "ViewModel inactive, skipping notification forward (HTTP POST removed)")
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

        val activeVm = HudViewModel.activeViewModel
        if (activeVm != null) {
            activeVm.sendNavData(navData)
            
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
            if (bitmap != null) {
                activeVm.sendNavIcon(bitmap)
            }
        } else {
            Log.w(tag, "ViewModel inactive, skipping nav data forward (HTTP POST removed)")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != "com.google.android.apps.maps") return
        Log.d(tag, "Google Maps notification removed")

        val activeVm = HudViewModel.activeViewModel
        if (activeVm != null) {
            activeVm.clearNavData()
        } else {
            Log.w(tag, "ViewModel inactive, skipping nav clear (HTTP POST removed)")
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
