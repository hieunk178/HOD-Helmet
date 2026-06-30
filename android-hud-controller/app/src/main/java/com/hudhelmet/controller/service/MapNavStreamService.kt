package com.hudhelmet.controller.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hudhelmet.controller.model.RoutePoint
import com.hudhelmet.controller.model.RouteData
import com.hudhelmet.controller.model.NavData
import com.hudhelmet.controller.model.NotificationData
import com.hudhelmet.controller.event.HudEventBus
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.cos

import com.google.android.gms.location.*
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

class MapNavStreamService : Service() {

    companion object {
        private const val TAG = "MapNavStreamService"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "HUD_MAP_NAV_STREAM_CHANNEL"
        private const val UDP_PORT = 5001

        const val EXTRA_ESP_IP = "com.hudhelmet.controller.EXTRA_ESP_IP"
        const val EXTRA_FPS = "com.hudhelmet.controller.EXTRA_FPS"
        const val EXTRA_QUALITY = "com.hudhelmet.controller.EXTRA_QUALITY"
        const val EXTRA_ROUTE_JSON = "com.hudhelmet.controller.EXTRA_ROUTE_JSON"

        var isRunning = false
            private set
            
        var activeRoute: RouteData? = null
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var navigationEngine: NavigationEngine? = null

    @Volatile private var currentLocation: RoutePoint? = null
    @Volatile private var currentSpeed: Float = 0f
    @Volatile private var currentBearing: Float = 0f
    @Volatile private var navUpdateInfo: NavigationEngine.NavigationUpdate? = null
    @Volatile private var route: RouteData? = null
    @Volatile private var espIpAddress: String = "192.168.4.1"

    private var lastNavSendTime = 0L

    private var serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val routePaint = Paint().apply {
        color = Color.rgb(66, 133, 244) // Google Maps Blue
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val userPaint = Paint().apply {
        color = Color.rgb(0, 229, 255) // Cyan
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val userBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val destPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val destBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val headerBackgroundPaint = Paint().apply {
        color = Color.argb(220, 18, 18, 18) // Dark grey semi-transparent
        style = Paint.Style.FILL
    }

    private val speedPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val speedUnitPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 10f
        isAntiAlias = true
    }

    private val streetNamePaint = Paint().apply {
        color = Color.WHITE
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val instructionPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 11f
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        color = Color.rgb(0, 230, 118) // Green turn arrow
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private fun startLocationUpdates() {
        val context = this

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted for service")
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

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            // Also get last known location immediately
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null) {
                    onLocationReceived(location)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates", e)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun onLocationReceived(location: Location) {
        val point = RoutePoint(location.latitude, location.longitude)
        currentLocation = point
        currentSpeed = location.speed * 3.6f // m/s -> km/h
        if (location.hasBearing()) {
            currentBearing = location.bearing
        }

        val engine = navigationEngine ?: return
        val update = engine.processLocationUpdate(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = location.speed,
            bearing = location.bearing
        )

        navUpdateInfo = update

        // Send nav data to ESP32 (throttled)
        sendNavDataThrottled(update.navData)

        // Notify ViewModel via event bus (decoupled)
        scope.launch {
            HudEventBus.emitNavigationStateUpdate(
                currentLocation = point,
                currentSpeed = location.speed * 3.6f,
                currentBearing = if (location.hasBearing()) location.bearing else currentBearing,
                navUpdate = update
            )
        }

        // Handle off-route: recalculate
        if (update.isOffRoute) {
            Log.w(TAG, "Off-route detected in background, recalculating...")
            val dest = route?.points?.lastOrNull()
            if (dest != null) {
                scope.launch {
                    try {
                        val newRoute = com.hudhelmet.controller.network.BRouterApi.calculateRoute(
                            fromLat = point.lat, fromLon = point.lon,
                            toLat = dest.lat, toLon = dest.lon
                        )
                        if (newRoute != null) {
                            route = newRoute
                            activeRoute = newRoute
                            navigationEngine = com.hudhelmet.controller.service.NavigationEngine(newRoute)
                            
                            // Re-send Config Packet because route has changed
                            var socket: DatagramSocket? = null
                            try {
                                socket = DatagramSocket()
                                val espAddress = InetAddress.getByName(espIpAddress)
                                sendConfigPacket(socket, espAddress)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send new CONFIG after reroute: ${e.message}")
                            } finally {
                                socket?.close()
                            }

                            // Notify ViewModel via event bus
                            HudEventBus.emitRouteUpdate(newRoute)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Rerouting error", e)
                    }
                }
            }
        }

        // Handle arrival
        if (update.hasArrived) {
            // Always send arrival commands to ESP32
            com.hudhelmet.controller.network.WebSocketManager.getInstance().sendNotification(
                NotificationData(title = "Bản đồ", message = "Đã đến nơi!", icon = "check")
            ) { }
            val emptyNav = NavData(street = "", instruction = "", distance = 0, turnType = 1, active = false)
            com.hudhelmet.controller.network.WebSocketManager.getInstance().sendNav(emptyNav) { }
            com.hudhelmet.controller.network.WebSocketManager.getInstance().sendStopNav()
            com.hudhelmet.controller.network.WebSocketManager.getInstance().sendMode(com.hudhelmet.controller.model.ModeData(1)) { }

            // Notify ViewModel via event bus
            scope.launch {
                HudEventBus.emitArrival()
            }
            stopSelf()
        }
    }

    private fun sendNavDataThrottled(navData: NavData) {
        val now = System.currentTimeMillis()
        if (now - lastNavSendTime < 1000) return
        lastNavSendTime = now
        com.hudhelmet.controller.network.WebSocketManager.getInstance().sendNav(navData) { }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            // Send stop commands to ESP32
            val ws = com.hudhelmet.controller.network.WebSocketManager.getInstance()
            val emptyNav = NavData(street = "", instruction = "", distance = 0, turnType = 1, active = false)
            ws.sendNav(emptyNav) { }
            ws.sendStopNav()
            ws.sendMode(com.hudhelmet.controller.model.ModeData(1)) { }

            // Notify ViewModel via event bus
            scope.launch {
                HudEventBus.emitArrival()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) {
            Log.w(TAG, "MapNavStreamService is already running")
            return START_NOT_STICKY
        }

        val espIp = intent?.getStringExtra(EXTRA_ESP_IP) ?: "192.168.4.1"
        val fps = intent?.getIntExtra(EXTRA_FPS, 5) ?: 5
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 40) ?: 40
        espIpAddress = espIp

        val routeJson = intent?.getStringExtra(EXTRA_ROUTE_JSON)
        if (routeJson != null) {
            try {
                val parsedRoute = com.google.gson.Gson().fromJson(routeJson, RouteData::class.java)
                route = parsedRoute
                activeRoute = parsedRoute
                navigationEngine = com.hudhelmet.controller.service.NavigationEngine(parsedRoute)
                startLocationUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse route JSON", e)
            }
        }

        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                getNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, getNotification())
        }

        startMapStreaming(espIp, fps, quality)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        activeRoute = null
        stopLocationUpdates()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HUD Helmet Map Nav Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val stopIntent = Intent(this, MapNavStreamService::class.java).apply {
            action = "STOP"
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HUD Map Dẫn Đường Đang Chạy")
            .setContentText("Đang truyền dữ liệu bản đồ tới mũ bảo hiểm...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", pendingStopIntent)
            .build()
    }

    private fun startMapStreaming(espIp: String, fps: Int, quality: Int) {
        val delayMs = (1000 / fps).toLong()

        scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                val espAddress = InetAddress.getByName(espIp)
                Log.d(TAG, "Starting map stream to $espIp:$UDP_PORT at $fps FPS")

                // 1. Send Config Packet first (tells ESP32 screen dimensions)
                sendConfigPacket(socket, espAddress)

                val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val baos = ByteArrayOutputStream(240 * 240)  // Pre-allocated, reused across frames

                var frameId: Short = 0
                val streamId: Short = 2

                var currentRenderBearing = -1f

                while (isActive && isRunning) {
                    val startTime = System.currentTimeMillis()

                    val curRoute = route
                    val curLoc = currentLocation
                    val targetBearing = currentBearing
                    val speed = currentSpeed
                    val navUpdate = navUpdateInfo

                    if (curRoute != null && curLoc != null) {
                        // Smoothly interpolate currentRenderBearing towards targetBearing (shortest angular path)
                        if (currentRenderBearing < 0) {
                            currentRenderBearing = targetBearing
                        } else {
                            var diff = targetBearing - currentRenderBearing
                            while (diff < -180f) diff += 360f
                            while (diff > 180f) diff -= 360f
                            // Interpolation factor (0.25f means 25% of difference is closed each frame)
                            currentRenderBearing += diff * 0.25f
                            while (currentRenderBearing < 0f) currentRenderBearing += 360f
                            while (currentRenderBearing >= 360f) currentRenderBearing -= 360f
                        }

                        // Render Minimap with smoothed bearing
                        renderMinimapFrame(canvas, curRoute.points, curLoc, currentRenderBearing, speed, navUpdate)

                        // Compress (reuse buffer)
                        baos.reset()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                        val jpegBytes = baos.toByteArray()

                        // Send
                        sendFrameChunks(socket, espAddress, streamId, frameId, jpegBytes)
                        frameId = ((frameId + 1) % 65536).toShort()
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    val sleepTime = delayMs - elapsed
                    if (sleepTime > 0) {
                        delay(sleepTime)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream thread error: ${e.message}", e)
            } finally {
                socket?.close()
                stopSelf()
            }
        }
    }

    private fun renderMinimapFrame(
        canvas: Canvas,
        points: List<RoutePoint>,
        currentLoc: RoutePoint,
        bearing: Float,
        speed: Float,
        navUpdate: NavigationEngine.NavigationUpdate?
    ) {
        // 1. Clear background
        canvas.drawColor(Color.rgb(18, 18, 18)) // Sleek dark grey background

        // 2. Draw Route (Translated and rotated)
        canvas.save()
        // Map zoom scale: 1 meter = 0.6 pixels (adjustable)
        val scale = 0.6f
        val centerX = 120f
        val centerY = 180f // User sits at bottom-center

        // Rotation & translation logic
        canvas.rotate(-bearing, centerX, centerY)
        canvas.translate(centerX, centerY)

        // Compute scaling coefficients
        val latScale = 111132.954
        val lonScale = 111132.954 * cos(currentLoc.lat * Math.PI / 180.0)

        val path = Path()
        var first = true

        // Draw points within 300 meters window to save CPU
        for (pt in points) {
            val dx = (pt.lon - currentLoc.lon) * lonScale
            val dy = (pt.lat - currentLoc.lat) * latScale

            val x = dx * scale
            val y = -dy * scale // Invert Y for screen space

            if (first) {
                path.moveTo(x.toFloat(), y.toFloat())
                first = false
            } else {
                path.lineTo(x.toFloat(), y.toFloat())
            }
        }

        canvas.drawPath(path, routePaint)

        // Draw destination red dot at the last point of the route
        val destPt = points.lastOrNull()
        if (destPt != null) {
            val dx = (destPt.lon - currentLoc.lon) * lonScale
            val dy = (destPt.lat - currentLoc.lat) * latScale
            val x = dx * scale
            val y = -dy * scale
            
            // Draw a red circle with a white outline
            canvas.drawCircle(x.toFloat(), y.toFloat(), 8f, destPaint)
            canvas.drawCircle(x.toFloat(), y.toFloat(), 8f, destBorderPaint)
        }

        canvas.restore()

        // 3. Draw User Triangle (Always pointing straight UP at 120, 180)
        val userPath = Path().apply {
            moveTo(120f, 168f)  // Tip pointing up
            lineTo(110f, 184f)  // Bottom left
            lineTo(120f, 178f)  // Indent
            lineTo(130f, 184f)  // Bottom right
            close()
        }
        canvas.drawPath(userPath, userPaint)
        canvas.drawPath(userPath, userBorderPaint)

        // 4. Draw Header Overlay
        canvas.drawRect(0f, 0f, 240f, 65f, headerBackgroundPaint)

        // Speed Display (top-left)
        canvas.drawText(String.format("%d", speed.toInt()), 15f, 32f, speedPaint)
        canvas.drawText("km/h", 15f, 48f, speedUnitPaint)

        // Instruction Text (top-middle)
        if (navUpdate != null) {
            val street = navUpdate.currentStreetName.ifEmpty { "Đường đang đi" }
            val instruction = navUpdate.navData.instruction.ifEmpty { "Đi thẳng" }
            val turnType = navUpdate.navData.turnType

            // Truncate strings to prevent canvas overflow
            val truncatedStreet = if (street.length > 18) street.take(16) + "..." else street
            val truncatedInstruction = if (instruction.length > 20) instruction.take(18) + "..." else instruction

            canvas.drawText(truncatedStreet, 70f, 24f, streetNamePaint)
            canvas.drawText(truncatedInstruction, 70f, 44f, instructionPaint)

            // Draw turn arrow vector (top-right)
            val arrowPath = Path()
            when (turnType) {
                2 -> { // Left
                    arrowPath.moveTo(215f, 24f)
                    arrowPath.lineTo(200f, 24f)
                    arrowPath.lineTo(200f, 38f)
                    arrowPath.moveTo(206f, 18f)
                    arrowPath.lineTo(200f, 24f)
                    arrowPath.lineTo(206f, 30f)
                }
                3 -> { // Right
                    arrowPath.moveTo(195f, 24f)
                    arrowPath.lineTo(210f, 24f)
                    arrowPath.lineTo(210f, 38f)
                    arrowPath.moveTo(204f, 18f)
                    arrowPath.lineTo(210f, 24f)
                    arrowPath.lineTo(204f, 30f)
                }
                4 -> { // U-Turn
                    arrowPath.moveTo(212f, 38f)
                    arrowPath.lineTo(212f, 24f)
                    arrowPath.quadTo(206f, 18f, 200f, 24f)
                    arrowPath.lineTo(200f, 38f)
                    arrowPath.moveTo(194f, 32f)
                    arrowPath.lineTo(200f, 38f)
                    arrowPath.lineTo(206f, 32f)
                }
                else -> { // Straight
                    arrowPath.moveTo(205f, 38f)
                    arrowPath.lineTo(205f, 18f)
                    arrowPath.lineTo(199f, 24f)
                    arrowPath.moveTo(205f, 18f)
                    arrowPath.lineTo(211f, 24f)
                }
            }
            canvas.drawPath(arrowPath, arrowPaint)
        } else {
            canvas.drawText("Đang kết nối...", 70f, 34f, streetNamePaint)
        }
    }

    private fun sendConfigPacket(socket: DatagramSocket, address: InetAddress) {
        val payload = ByteArray(10)
        // outW = 240
        payload[0] = 0.toByte()
        payload[1] = 240.toByte()
        // outH = 240
        payload[2] = 0.toByte()
        payload[3] = 240.toByte()
        // drawX = 0
        payload[4] = 0.toByte()
        payload[5] = 0.toByte()
        // drawY = 0
        payload[6] = 0.toByte()
        payload[7] = 0.toByte()
        // fps = 5, quality = 40
        payload[8] = 5.toByte()
        payload[9] = 40.toByte()

        val packetBytes = buildPacketHeader(
            type = 1.toByte(),
            streamId = 2.toShort(),
            frameId = 0,
            chunkId = 0,
            chunkCount = 1,
            totalSize = payload.size,
            payload = payload
        )

        val datagram = DatagramPacket(packetBytes, packetBytes.size, address, UDP_PORT)
        socket.send(datagram)
        Log.i(TAG, "Sent map stream CONFIG packet over UDP")
    }

    private fun sendFrameChunks(
        socket: DatagramSocket,
        address: InetAddress,
        streamId: Short,
        frameId: Short,
        jpegBytes: ByteArray
    ) {
        val totalSize = jpegBytes.size
        val maxChunkPayload = 1000
        val chunkCount = ((totalSize + maxChunkPayload - 1) / maxChunkPayload).toShort()

        for (i in 0 until chunkCount) {
            val offset = i * maxChunkPayload
            val size = Math.min(maxChunkPayload, totalSize - offset)
            val chunkPayload = jpegBytes.copyOfRange(offset, offset + size)

            val packetBytes = buildPacketHeader(
                type = 2.toByte(),
                streamId = streamId,
                frameId = frameId,
                chunkId = i.toShort(),
                chunkCount = chunkCount,
                totalSize = totalSize,
                payload = chunkPayload
            )

            val datagram = DatagramPacket(packetBytes, packetBytes.size, address, UDP_PORT)
            socket.send(datagram)
            
            // Add a tiny delay to prevent UDP buffer overflow on ESP32
            try {
                Thread.sleep(1)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
    }

    private fun buildPacketHeader(
        type: Byte,
        streamId: Short,
        frameId: Short,
        chunkId: Short,
        chunkCount: Short,
        totalSize: Int,
        payload: ByteArray
    ): ByteArray {
        val headerSize = 15
        val out = ByteArray(headerSize + payload.size)
        out[0] = 0x53
        out[1] = 0x54
        out[2] = type
        out[3] = (streamId.toInt() shr 8 and 0xFF).toByte()
        out[4] = (streamId.toInt() and 0xFF).toByte()
        out[5] = (frameId.toInt() shr 8 and 0xFF).toByte()
        out[6] = (frameId.toInt() and 0xFF).toByte()
        out[7] = (chunkId.toInt() shr 8 and 0xFF).toByte()
        out[8] = (chunkId.toInt() and 0xFF).toByte()
        out[9] = (chunkCount.toInt() shr 8 and 0xFF).toByte()
        out[10] = (chunkCount.toInt() and 0xFF).toByte()
        out[11] = (totalSize shr 24 and 0xFF).toByte()
        out[12] = (totalSize shr 16 and 0xFF).toByte()
        out[13] = (totalSize shr 8 and 0xFF).toByte()
        out[14] = (totalSize and 0xFF).toByte()

        System.arraycopy(payload, 0, out, headerSize, payload.size)
        return out
    }
}
