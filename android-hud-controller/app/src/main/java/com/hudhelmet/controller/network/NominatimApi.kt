package com.hudhelmet.controller.network

import android.util.Log
import com.hudhelmet.controller.model.PlaceSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client for OpenStreetMap Nominatim geocoding API.
 * Free, no API key required. Rate limited to 1 request/second.
 */
object NominatimApi {

    private const val TAG = "NominatimApi"
    private const val BASE_URL = "https://nominatim.openstreetmap.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Search for places matching the query.
     *
     * @param query Search text
     * @param lat Optional latitude to bias results toward
     * @param lon Optional longitude to bias results toward
     * @param limit Max number of results (default 5)
     * @return List of matching places
     */
    suspend fun searchPlaces(
        query: String,
        lat: Double? = null,
        lon: Double? = null,
        limit: Int = 5
    ): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlBuilder = StringBuilder("$BASE_URL/search?q=$encodedQuery&format=json&limit=$limit&countrycodes=vn&accept-language=vi")

            if (lat != null && lon != null) {
                urlBuilder.append("&viewbox=${lon - 0.5},${lat + 0.5},${lon + 0.5},${lat - 0.5}")
                urlBuilder.append("&bounded=0")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", "HUDHelmetApp/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Nominatim search failed: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = JsonParser.parseString(body).asJsonArray

                jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.asJsonObject
                        PlaceSearchResult(
                            displayName = obj.get("display_name").asString,
                            lat = obj.get("lat").asString.toDouble(),
                            lon = obj.get("lon").asString.toDouble(),
                            type = obj.get("type")?.asString ?: ""
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching places", e)
            emptyList()
        }
    }

    /**
     * Reverse geocode: get address from lat/lon coordinates.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/reverse?lat=$lat&lon=$lon&format=json&accept-language=vi&zoom=18"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "HUDHelmetApp/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(body).asJsonObject
                json.get("display_name")?.asString
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reverse geocoding", e)
            null
        }
    }
}
