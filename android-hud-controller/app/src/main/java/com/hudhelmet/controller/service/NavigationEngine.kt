package com.hudhelmet.controller.service

import com.hudhelmet.controller.model.NavData
import com.hudhelmet.controller.model.RouteData
import com.hudhelmet.controller.model.RoutePoint
import com.hudhelmet.controller.model.RouteStep
import com.hudhelmet.controller.utils.GeoUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-time turn-by-turn navigation engine.
 *
 * Responsibilities:
 * - Snap GPS position to the route polyline
 * - Determine current step (turn instruction)
 * - Calculate distance/time remaining
 * - Detect off-route conditions (> 50m from route)
 * - Detect arrival at destination (< 30m)
 * - Generate NavData for ESP32 HUD
 */
class NavigationEngine(private val route: RouteData) {

    companion object {
        /** Distance threshold to consider user off-route */
        const val OFF_ROUTE_THRESHOLD_METERS = 50.0
        /** Distance threshold to consider user arrived */
        const val ARRIVAL_THRESHOLD_METERS = 30.0
    }

    /** Current step index in route.steps */
    var currentStepIndex: Int = 0
        private set

    /** Current point index on the polyline */
    var currentPointIndex: Int = 0
        private set

    /** Whether user has arrived at destination */
    var hasArrived: Boolean = false
        private set

    /**
     * Result of processing a location update.
     */
    data class NavigationUpdate(
        val navData: NavData,
        val isOffRoute: Boolean,
        val hasArrived: Boolean,
        val distanceToNextTurn: Double,
        val distanceRemaining: Double,
        val timeRemainingSeconds: Int,
        val currentStreetName: String,
        val currentStepIndex: Int,
        val snappedLat: Double,
        val snappedLon: Double
    )

    /**
     * Process a GPS location update and return navigation state.
     *
     * @param lat Current GPS latitude
     * @param lon Current GPS longitude
     * @param speedMps Current speed in meters/second
     * @param bearing Current GPS bearing in degrees
     * @return NavigationUpdate with all relevant data
     */
    @Suppress("UNUSED_PARAMETER")
    fun processLocationUpdate(lat: Double, lon: Double, speedMps: Float, bearing: Float): NavigationUpdate {
        // 1. Find closest point on route (snap-to-route)
        val (snappedIdx, snappedDist) = findClosestPointOnRoute(lat, lon)
        val isOffRoute = snappedDist > OFF_ROUTE_THRESHOLD_METERS

        // Update current point index (only advance forward)
        if (snappedIdx >= currentPointIndex) {
            currentPointIndex = snappedIdx
        }

        // 2. Check arrival
        val destPoint = route.points.last()
        val distToDest = GeoUtils.distanceMeters(lat, lon, destPoint.lat, destPoint.lon)
        if (distToDest < ARRIVAL_THRESHOLD_METERS) {
            hasArrived = true
        }

        // 3. Determine current step
        updateCurrentStep()

        // 4. Calculate distance to next turn
        val distToNextTurn = calculateDistanceToNextTurn(lat, lon)

        // 5. Calculate remaining distance along route
        val distRemaining = calculateRemainingDistance()

        // 6. Estimate time remaining
        val avgSpeed = if (speedMps > 1.0f) speedMps.toDouble() else 8.33 // default ~30km/h
        val timeRemaining = (distRemaining / avgSpeed).roundToInt()

        // 7. Get current step info
        val currentStep = if (currentStepIndex < route.steps.size) {
            route.steps[currentStepIndex]
        } else {
            route.steps.lastOrNull()
        }

        val nextStep = if (currentStepIndex + 1 < route.steps.size) {
            route.steps[currentStepIndex + 1]
        } else null

        // 8. Build NavData for ESP32
        val streetName = currentStep?.streetName ?: ""
        val instruction = nextStep?.instruction ?: currentStep?.instruction ?: ""
        val turnType = nextStep?.turnType ?: currentStep?.turnType ?: 1

        // Format distance remaining
        val distLeftStr = if (distRemaining >= 1000) {
            String.format("%.1f km", distRemaining / 1000.0)
        } else {
            "${distRemaining.roundToInt()} m"
        }

        // Format time remaining
        val timeLeftStr = formatTimeRemaining(timeRemaining)

        // Format ETA
        val etaStr = formatEta(timeRemaining)

        val navData = NavData(
            street = streetName,
            instruction = instruction,
            timeLeft = timeLeftStr,
            distanceLeft = distLeftStr,
            eta = etaStr,
            distance = distToNextTurn.roundToInt(),
            turnType = turnType,
            active = !hasArrived
        )

        val snappedPoint = route.points.getOrElse(currentPointIndex) { route.points.first() }

        return NavigationUpdate(
            navData = navData,
            isOffRoute = isOffRoute,
            hasArrived = hasArrived,
            distanceToNextTurn = distToNextTurn,
            distanceRemaining = distRemaining,
            timeRemainingSeconds = timeRemaining,
            currentStreetName = streetName,
            currentStepIndex = currentStepIndex,
            snappedLat = snappedPoint.lat,
            snappedLon = snappedPoint.lon
        )
    }

    /**
     * Find the closest point on the route polyline to the given position.
     * Only searches forward from current position to prevent backwards snapping.
     */
    private fun findClosestPointOnRoute(lat: Double, lon: Double): Pair<Int, Double> {
        var minDist = Double.MAX_VALUE
        var minIdx = currentPointIndex

        // Search from a bit before current index to handle GPS jitter
        val searchStart = (currentPointIndex - 3).coerceAtLeast(0)
        val searchEnd = (currentPointIndex + 50).coerceAtMost(route.points.size - 1)

        for (i in searchStart..searchEnd) {
            val d = GeoUtils.distanceMeters(lat, lon, route.points[i].lat, route.points[i].lon)
            if (d < minDist) {
                minDist = d
                minIdx = i
            }
        }

        return Pair(minIdx, minDist)
    }

    /**
     * Update the current step based on the current point index.
     */
    private fun updateCurrentStep() {
        while (currentStepIndex < route.steps.size - 1) {
            val nextStep = route.steps[currentStepIndex + 1]
            if (currentPointIndex >= nextStep.pointIndex) {
                currentStepIndex++
            } else {
                break
            }
        }
    }

    /**
     * Calculate distance from current position to the next turn.
     */
    private fun calculateDistanceToNextTurn(lat: Double, lon: Double): Double {
        val nextStepIdx = (currentStepIndex + 1).coerceAtMost(route.steps.size - 1)
        val nextStep = route.steps[nextStepIdx]

        // Sum distance along route from current point to next step point
        var dist = 0.0
        val targetIdx = nextStep.pointIndex.coerceAtMost(route.points.size - 1)

        if (currentPointIndex < targetIdx) {
            // Distance from current position to next route point
            dist += GeoUtils.distanceMeters(
                lat, lon,
                route.points[currentPointIndex].lat, route.points[currentPointIndex].lon
            )
            // Sum along route
            for (i in currentPointIndex until targetIdx) {
                dist += GeoUtils.distanceMeters(
                    route.points[i].lat, route.points[i].lon,
                    route.points[i + 1].lat, route.points[i + 1].lon
                )
            }
        } else {
            dist = GeoUtils.distanceMeters(lat, lon, nextStep.lat, nextStep.lon)
        }

        return dist
    }

    /**
     * Calculate remaining distance along route from current position.
     */
    private fun calculateRemainingDistance(): Double {
        var dist = 0.0
        for (i in currentPointIndex until route.points.size - 1) {
            dist += GeoUtils.distanceMeters(
                route.points[i].lat, route.points[i].lon,
                route.points[i + 1].lat, route.points[i + 1].lon
            )
        }
        return dist
    }

    private fun formatTimeRemaining(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds} giây"
            seconds < 3600 -> "${seconds / 60} phút"
            else -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                "${hours} giờ ${mins} phút"
            }
        }
    }

    private fun formatEta(secondsRemaining: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.SECOND, secondsRemaining)
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
