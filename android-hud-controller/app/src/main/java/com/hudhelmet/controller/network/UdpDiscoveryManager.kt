package com.hudhelmet.controller.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

object UdpDiscoveryManager {
    private const val TAG = "UdpDiscovery"
    private const val PORT = 8888

    /**
     * Listens for UDP broadcasts from the ESP32.
     * Suspends until an IP is found or timeout occurs.
     * 
     * @param timeoutMs Timeout in milliseconds
     * @return Discovered IP address string, or null if not found
     */
    suspend fun discoverEspIp(timeoutMs: Int = 5000): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(PORT)
            socket.soTimeout = timeoutMs
            socket.reuseAddress = true
            
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            Log.d(TAG, "Listening for UDP broadcast on port $PORT...")
            socket.receive(packet)
            
            val message = String(packet.data, 0, packet.length).trim()
            Log.d(TAG, "Received UDP broadcast: $message from ${packet.address.hostAddress}")
            
            if (message.startsWith("HUD_HELMET_IP:")) {
                return@withContext message.substringAfter("HUD_HELMET_IP:")
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "Discovery timeout")
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP discovery", e)
        } finally {
            socket?.close()
        }
        return@withContext null
    }
}
