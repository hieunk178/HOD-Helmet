package com.hudhelmet.controller.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "HUD_STREAM_CHANNEL"
        private const val NOTIFICATION_ID = 2002
        private const val UDP_PORT = 5001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_CROP_X = "EXTRA_CROP_X"
        const val EXTRA_CROP_Y = "EXTRA_CROP_Y"
        const val EXTRA_CROP_W = "EXTRA_CROP_W"
        const val EXTRA_CROP_H = "EXTRA_CROP_H"
        const val EXTRA_OUT_W = "EXTRA_OUT_W"
        const val EXTRA_OUT_H = "EXTRA_OUT_H"
        const val EXTRA_DRAW_X = "EXTRA_DRAW_X"
        const val EXTRA_DRAW_Y = "EXTRA_DRAW_Y"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_ESP_IP = "EXTRA_ESP_IP"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        var isRunning: Boolean
            get() = _isRunningFlow.value
            private set(value) {
                _isRunningFlow.value = value
            }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cropX = intent.getIntExtra(EXTRA_CROP_X, 0)
        val cropY = intent.getIntExtra(EXTRA_CROP_Y, 0)
        val cropW = intent.getIntExtra(EXTRA_CROP_W, 0)
        val cropH = intent.getIntExtra(EXTRA_CROP_H, 0)
        val outW = intent.getIntExtra(EXTRA_OUT_W, 240)
        val outH = intent.getIntExtra(EXTRA_OUT_H, 240)
        val drawX = intent.getIntExtra(EXTRA_DRAW_X, 0)
        val drawY = intent.getIntExtra(EXTRA_DRAW_Y, 0)
        val fps = intent.getIntExtra(EXTRA_FPS, 18)
        val quality = intent.getIntExtra(EXTRA_QUALITY, 50)
        val espIp = intent.getStringExtra(EXTRA_ESP_IP) ?: "192.168.4.1"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, getNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, getNotification())
        }
        
        startStreaming(
            resultCode, resultData,
            cropX, cropY, cropW, cropH,
            outW, outH, drawX, drawY,
            fps, quality, espIp
        )

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopStreaming()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HUD Helmet Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HUD Screen Stream Active")
            .setContentText("Streaming your screen to HUD Helmet...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun startStreaming(
        resultCode: Int,
        resultData: Intent,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        outW: Int, outH: Int,
        drawX: Int, drawY: Int,
        fps: Int, quality: Int,
        espIp: String
    ) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            stopSelf()
            return
        }
        mediaProjection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val densityDpi = displayMetrics.densityDpi

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val display = mp.createVirtualDisplay(
            "HUDStreamVirtualDisplay",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        virtualDisplay = display

        scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                val espAddress = InetAddress.getByName(espIp)

                sendConfigPacket(socket, espAddress, outW, outH, drawX, drawY, fps, quality)

                val delayMs = 1000L / fps
                var frameId: Short = 0
                val streamId: Short = 1

                while (isActive) {
                    val startTime = System.currentTimeMillis()
                    val img = reader.acquireLatestImage()

                    if (img != null) {
                        try {
                            val plane = img.planes[0]
                            val buffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride

                            val rawWidth = img.width
                            val rawHeight = img.height

                            val bufferWidth = rawWidth + (rowStride - rawWidth * pixelStride) / pixelStride
                            val bmp = Bitmap.createBitmap(bufferWidth, rawHeight, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(buffer)

                            val cx = cropX.coerceIn(0, rawWidth - 1)
                            val cy = cropY.coerceIn(0, rawHeight - 1)
                            val cw = cropW.coerceIn(1, rawWidth - cx)
                            val ch = cropH.coerceIn(1, rawHeight - cy)

                            val cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
                            bmp.recycle()

                            val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
                            if (scaled != cropped) {
                                cropped.recycle()
                            }

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                            val jpegBytes = baos.toByteArray()
                            scaled.recycle()

                            sendFrameChunks(socket, espAddress, streamId, frameId, jpegBytes)
                            frameId = ((frameId + 1) % 65536).toShort()

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing frame: ${e.message}")
                        } finally {
                            img.close()
                        }
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    val sleepTime = delayMs - elapsed
                    if (sleepTime > 0) {
                        delay(sleepTime)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming thread error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    private fun sendConfigPacket(
        socket: DatagramSocket,
        address: InetAddress,
        outW: Int, outH: Int,
        drawX: Int, drawY: Int,
        fps: Int, quality: Int
    ) {
        val payload = ByteArray(10)
        payload[0] = (outW shr 8 and 0xFF).toByte()
        payload[1] = (outW and 0xFF).toByte()
        payload[2] = (outH shr 8 and 0xFF).toByte()
        payload[3] = (outH and 0xFF).toByte()
        payload[4] = (drawX shr 8 and 0xFF).toByte()
        payload[5] = (drawX and 0xFF).toByte()
        payload[6] = (drawY shr 8 and 0xFF).toByte()
        payload[7] = (drawY and 0xFF).toByte()
        payload[8] = fps.toByte()
        payload[9] = quality.toByte()

        val packetBytes = buildPacketHeader(
            type = 1.toByte(),
            streamId = 1.toShort(),
            frameId = 0.toShort(),
            chunkId = 0.toShort(),
            chunkCount = 1.toShort(),
            totalSize = 10,
            payload = payload
        )

        val datagram = DatagramPacket(packetBytes, packetBytes.size, address, UDP_PORT)
        socket.send(datagram)
        Log.i(TAG, "Sent CONFIG packet over UDP")
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

    private fun stopStreaming() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}")
        }
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)

        try {
            scope.cancel()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}")
        }
        Log.i(TAG, "Screen capture service stopped and resources released")
    }
}
