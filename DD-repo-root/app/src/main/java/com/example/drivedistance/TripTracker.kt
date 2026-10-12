package com.example.drivedistance

import android.location.Location
import org.osmdroid.util.GeoPoint

/**
 * Temporary in-memory store for the current trip.
 * Wiped when a new trip starts.
 */
object TripTracker {
    private val points = mutableListOf<Location>()   // intermediate GPS points (temp)
    @Volatile var distanceMeters = 0.0
    @Volatile var running = false
    @Volatile var startTime = 0L
    @Volatile var endTime = 0L
    @Volatile var startName = ""                      // reverse-geocoded Point A
    @Volatile var endName = ""                        // reverse-geocoded Point B
    var listener: (() -> Unit)? = null                // UI refresh callback

    @Synchronized fun reset() {
        points.clear()
        distanceMeters = 0.0
        startTime = System.currentTimeMillis()
        endTime = 0L
        startName = ""
        endName = ""
    }

    /** Returns true if the point was accepted. */
    @Synchronized fun addPoint(loc: Location): Boolean {
        val last = points.lastOrNull()
        if (last != null) {
            val d = last.distanceTo(loc)
            if (d < 5f) return false          // ignore GPS jitter when standing still
            distanceMeters += d
        }
        points.add(loc)
        return true
    }

    @Synchronized fun pointCount(): Int = points.size

    @Synchronized fun snapshot(): List<GeoPoint> =
        points.map { GeoPoint(it.latitude, it.longitude) }
}
