package com.hudhelmet.controller.network

import android.util.Log
import com.hudhelmet.controller.model.RouteData
import com.hudhelmet.controller.model.RoutePoint
import com.hudhelmet.controller.model.RouteStep
import com.hudhelmet.controller.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Client for BRouter routing API.
 * Uses the 'moped' profile — optimized for motorcycles/scooters.
 * Free, no API key required.
 */
object BRouterApi {

    private const val TAG = "BRouterApi"
    private const val BASE_URL = "https://brouter.de/brouter"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Calculate a route between two points using moped (motorcycle) profile.
     *
     * @param fromLat Start latitude
     * @param fromLon Start longitude
     * @param toLat Destination latitude
     * @param toLon Destination longitude
     * @return RouteData or null on failure
     */
    suspend fun calculateRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): RouteData? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?lonlats=$fromLon,$fromLat|$toLon,$toLat" +
                    "&profile=moped&alternativeidx=0&format=geojson"

            Log.d(TAG, "Requesting route: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "HUDHelmetApp/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "BRouter request failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                parseGeoJsonRoute(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating route", e)
            null
        }
    }

    /**
     * Parse GeoJSON FeatureCollection from BRouter into RouteData.
     */
    private fun parseGeoJsonRoute(geoJson: String): RouteData? {
        try {
            val root = JsonParser.parseString(geoJson).asJsonObject
            val features = root.getAsJsonArray("features")
            if (features == null || features.size() == 0) return null

            val feature = features[0].asJsonObject
            val properties = feature.getAsJsonObject("properties")
            val geometry = feature.getAsJsonObject("geometry")

            // Parse total distance and time from properties
            val totalDistance = properties.get("track-length")?.asString?.toDoubleOrNull() ?: 0.0
            val totalTime = properties.get("total-time")?.asString?.toIntOrNull() ?: 0

            // Parse coordinates from geometry (LineString)
            val coordinates = geometry.getAsJsonArray("coordinates")
            val points = mutableListOf<RoutePoint>()

            for (i in 0 until coordinates.size()) {
                val coord = coordinates[i].asJsonArray
                val lon = coord[0].asDouble
                val lat = coord[1].asDouble
                points.add(RoutePoint(lat, lon))
            }

            if (points.size < 2) return null

            // Parse messages (turn-by-turn) from properties
            val steps = parseStepsFromMessages(properties, points)

            return RouteData(
                points = points,
                steps = steps,
                totalDistance = totalDistance,
                totalTime = totalTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GeoJSON", e)
            return null
        }
    }

    /**
     * Parse turn-by-turn steps from BRouter messages and route geometry.
     *
     * BRouter messages contain waypoint tags. We derive turn instructions
     * by analyzing bearing changes between consecutive segments.
     */
    private fun parseStepsFromMessages(
        properties: com.google.gson.JsonObject,
        points: List<RoutePoint>
    ): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()

        val messages = properties.getAsJsonArray("messages")
        if (messages == null || messages.size() < 2) {
            // Fallback: generate steps from geometry bearing changes
            return generateStepsFromGeometry(points)
        }

        // First row is column headers, remaining are data rows
        val headers = messages[0].asJsonArray
        var lonIdx = -1; var latIdx = -1; var distIdx = -1; var wayTagsIdx = -1; var turnCostIdx = -1

        for (i in 0 until headers.size()) {
            when (headers[i].asString) {
                "Longitude" -> lonIdx = i
                "Latitude" -> latIdx = i
                "Distance" -> distIdx = i
                "WayTags" -> wayTagsIdx = i
                "TurnCost" -> turnCostIdx = i
            }
        }

        if (lonIdx < 0 || latIdx < 0) {
            return generateStepsFromGeometry(points)
        }

        for (i in 1 until messages.size()) {
            val row = messages[i].asJsonArray
            val lon = row[lonIdx].asString.toDouble() / 1_000_000.0
            val lat = row[latIdx].asString.toDouble() / 1_000_000.0
            val dist = if (distIdx >= 0) row[distIdx].asString.toDoubleOrNull() ?: 0.0 else 0.0
            val wayTags = if (wayTagsIdx >= 0) row[wayTagsIdx].asString else ""
            val turnCost = if (turnCostIdx >= 0) row[turnCostIdx].asString.toIntOrNull() ?: 0 else 0

            // Extract street name from way tags
            val streetName = extractStreetName(wayTags)

            // Find closest point index
            val pointIndex = findClosestPointIndex(points, lat, lon)

            // Calculate turn type based on bearing change at this point
            val turnType = calculateTurnTypeAtPoint(points, pointIndex, turnCost)

            // Generate instruction text
            val instruction = generateInstructionText(turnType, streetName)

            steps.add(
                RouteStep(
                    instruction = instruction,
                    streetName = streetName,
                    distance = dist,
                    turnType = turnType,
                    pointIndex = pointIndex,
                    lat = lat,
                    lon = lon
                )
            )
        }

        return steps
    }

    /**
     * Generate steps purely from geometry bearing changes.
     * Used as fallback when BRouter messages aren't available.
     */
    private fun generateStepsFromGeometry(points: List<RoutePoint>): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()
        if (points.size < 2) return steps

        // Add start step
        steps.add(
            RouteStep(
                instruction = "Bắt đầu hành trình",
                streetName = "",
                distance = GeoUtils.distanceMeters(points[0].lat, points[0].lon, points[1].lat, points[1].lon),
                turnType = 1,
                pointIndex = 0,
                lat = points[0].lat,
                lon = points[0].lon
            )
        )

        // Find significant bearing changes
        var prevBearing = GeoUtils.bearingDegrees(points[0].lat, points[0].lon, points[1].lat, points[1].lon)

        for (i in 1 until points.size - 1) {
            val newBearing = GeoUtils.bearingDegrees(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
            val bearingChange = normalizeBearing(newBearing - prevBearing)

            if (abs(bearingChange) > 25) {
                val turnType = bearingToTurnType(bearingChange)
                val dist = GeoUtils.distanceMeters(points[i].lat, points[i].lon,
                    points.getOrElse(i + 1) { points.last() }.lat,
                    points.getOrElse(i + 1) { points.last() }.lon)

                steps.add(
                    RouteStep(
                        instruction = generateInstructionText(turnType, ""),
                        streetName = "",
                        distance = dist,
                        turnType = turnType,
                        pointIndex = i,
                        lat = points[i].lat,
                        lon = points[i].lon
                    )
                )
            }
            prevBearing = newBearing
        }

        // Add arrival step
        steps.add(
            RouteStep(
                instruction = "Đã tới nơi",
                streetName = "",
                distance = 0.0,
                turnType = 1,
                pointIndex = points.size - 1,
                lat = points.last().lat,
                lon = points.last().lon
            )
        )

        return steps
    }

    private fun extractStreetName(wayTags: String): String {
        // wayTags format: "highway=secondary surface=asphalt oneway=yes"
        // Try to find name= tag (BRouter sometimes includes it)
        val nameMatch = Regex("""name=([^\s]+(?:\s+[^\s=]+)*)""").find(wayTags)
        if (nameMatch != null) return nameMatch.groupValues[1]

        // Fall back to highway type
        val hwMatch = Regex("""highway=(\w+)""").find(wayTags)
        return when (hwMatch?.groupValues?.get(1)) {
            "primary" -> "Đường chính"
            "secondary" -> "Đường liên khu"
            "tertiary" -> "Đường nhánh"
            "residential" -> "Đường nội bộ"
            "service" -> "Đường phụ"
            else -> ""
        }
    }

    private fun findClosestPointIndex(points: List<RoutePoint>, lat: Double, lon: Double): Int {
        var minDist = Double.MAX_VALUE
        var minIdx = 0
        for (i in points.indices) {
            val d = GeoUtils.distanceMeters(lat, lon, points[i].lat, points[i].lon)
            if (d < minDist) {
                minDist = d
                minIdx = i
            }
        }
        return minIdx
    }

    private fun calculateTurnTypeAtPoint(points: List<RoutePoint>, pointIndex: Int, turnCost: Int): Int {
        if (pointIndex <= 0 || pointIndex >= points.size - 1) return 1

        val prevBearing = GeoUtils.bearingDegrees(
            points[pointIndex - 1].lat, points[pointIndex - 1].lon,
            points[pointIndex].lat, points[pointIndex].lon
        )
        val nextBearing = GeoUtils.bearingDegrees(
            points[pointIndex].lat, points[pointIndex].lon,
            points[pointIndex + 1].lat, points[pointIndex + 1].lon
        )

        val bearingChange = normalizeBearing(nextBearing - prevBearing)

        // Use turnCost as a signal: higher cost = sharper turn
        return if (turnCost > 50) {
            bearingToTurnType(bearingChange)
        } else if (abs(bearingChange) > 25) {
            bearingToTurnType(bearingChange)
        } else {
            1 // STRAIGHT
        }
    }

    private fun bearingToTurnType(bearingChange: Double): Int {
        return when {
            bearingChange > 150 || bearingChange < -150 -> 4  // U_TURN
            bearingChange > 25 -> 3                           // RIGHT
            bearingChange < -25 -> 2                          // LEFT
            else -> 1                                         // STRAIGHT
        }
    }

    private fun normalizeBearing(bearing: Double): Double {
        var b = bearing % 360
        if (b > 180) b -= 360
        if (b < -180) b += 360
        return b
    }

    private fun generateInstructionText(turnType: Int, streetName: String): String {
        val turnText = when (turnType) {
            2 -> "Rẽ trái"
            3 -> "Rẽ phải"
            4 -> "Quay đầu"
            else -> "Đi thẳng"
        }
        return if (streetName.isNotEmpty()) "$turnText vào $streetName" else turnText
    }
}
