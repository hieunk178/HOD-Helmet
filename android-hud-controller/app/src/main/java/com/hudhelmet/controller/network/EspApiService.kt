package com.hudhelmet.controller.network

import com.google.gson.Gson
import com.hudhelmet.controller.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client service for communicating with ESP32 HUD device.
 *
 * All network operations are suspend functions that run on IO dispatcher.
 */
class EspApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Build the base URL from IP address
     */
    private fun baseUrl(ipAddress: String): String {
        val ip = ipAddress.trim()
        return if (ip.startsWith("http")) ip else "http://$ip"
    }

    /**
     * Send time data to ESP32
     *
     * @param ipAddress ESP32 IP address
     * @param timeData Time data to send
     * @return Result with success/failure
     */
    suspend fun sendTime(ipAddress: String, timeData: TimeData): Result<ApiResponse> {
        return postJson("${baseUrl(ipAddress)}/api/time", timeData)
    }

    /**
     * Send notification to ESP32
     *
     * @param ipAddress ESP32 IP address
     * @param notification Notification data
     * @return Result with success/failure
     */
    suspend fun sendNotification(ipAddress: String, notification: NotificationData): Result<ApiResponse> {
        val normalizedTitle = java.text.Normalizer.normalize(notification.title, java.text.Normalizer.Form.NFC)
        val normalizedMessage = java.text.Normalizer.normalize(notification.message, java.text.Normalizer.Form.NFC)
        val normalizedNotification = notification.copy(title = normalizedTitle, message = normalizedMessage)
        return postJson("${baseUrl(ipAddress)}/api/notification", normalizedNotification)
    }

    /**
     * Clear notification on ESP32
     *
     * @param ipAddress ESP32 IP address
     * @return Result with success/failure
     */
    suspend fun clearNotification(ipAddress: String): Result<ApiResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl(ipAddress)}/api/clear")
                    .post("".toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{\"status\":\"ok\"}"
                        val apiResponse = gson.fromJson(body, ApiResponse::class.java)
                        Result.success(apiResponse)
                    } else {
                        Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Get ESP32 status
     *
     * @param ipAddress ESP32 IP address
     * @return Result with ESP status or failure
     */
    suspend fun getStatus(ipAddress: String): Result<EspStatus> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl(ipAddress)}/api/status")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val status = gson.fromJson(body, EspStatus::class.java)
                        Result.success(status)
                    } else {
                        Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Send mode selection data to ESP32
     */
    suspend fun sendMode(ipAddress: String, modeData: ModeData): Result<ApiResponse> {
        return postJson("${baseUrl(ipAddress)}/api/mode", modeData)
    }

    /**
     * Send navigation data to ESP32
     */
    suspend fun sendNav(ipAddress: String, navData: NavData): Result<ApiResponse> {
        val normalizedStreet = java.text.Normalizer.normalize(navData.street, java.text.Normalizer.Form.NFC)
        val normalizedInstruction = java.text.Normalizer.normalize(navData.instruction, java.text.Normalizer.Form.NFC)
        val normalizedNavData = navData.copy(street = normalizedStreet, instruction = normalizedInstruction)
        return postJson("${baseUrl(ipAddress)}/api/nav", normalizedNavData)
    }

    /**
     * Check if ESP32 is reachable
     */
    suspend fun ping(ipAddress: String): Boolean {
        return try {
            val result = getStatus(ipAddress)
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generic POST with JSON body
     */
    private suspend fun <T> postJson(url: String, body: T): Result<ApiResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(body)
                val requestBody = json.toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: "{\"status\":\"ok\"}"
                        val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                        Result.success(apiResponse)
                    } else {
                        Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
