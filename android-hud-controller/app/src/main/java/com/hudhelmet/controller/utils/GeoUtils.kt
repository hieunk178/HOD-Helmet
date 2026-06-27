package com.hudhelmet.controller.utils

import kotlin.math.*

object GeoUtils {
    // Earth radius in meters
    private const val R = 6371000.0

    // Calculate distance between two lat/lng points in meters
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // Calculate bearing from point 1 to point 2 in degrees
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        var brng = atan2(y, x)
        brng = Math.toDegrees(brng)
        return (brng + 360) % 360
    }

    // Convert lat/lng point to local x,y meters relative to an origin
    fun toLocalMeters(originLat: Double, originLon: Double, targetLat: Double, targetLon: Double): Pair<Double, Double> {
        val dLat = Math.toRadians(targetLat - originLat)
        val dLon = Math.toRadians(targetLon - originLon)
        
        // Approximate local flat earth at origin
        val yMeters = R * dLat
        val xMeters = R * cos(Math.toRadians(originLat)) * dLon
        return Pair(xMeters, yMeters)
    }
}
