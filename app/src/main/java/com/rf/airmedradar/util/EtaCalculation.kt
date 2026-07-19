package com.rf.airmedradar.util

import android.location.Location
import com.google.android.gms.maps.model.LatLng

/** 1 knot = 0.514444 m/s. */
private const val METERS_PER_SECOND_PER_KNOT = 0.514444

/** Straight-line distance between two coordinates, in meters. */
fun distanceMeters(from: LatLng, to: LatLng): Double {
    val results = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
    return results[0].toDouble()
}

/** Converts a transponder groundspeed reading (knots) to meters per second. */
fun knotsToMetersPerSecond(knots: Double): Double = knots * METERS_PER_SECOND_PER_KNOT

/**
 * Remaining time to close [distanceMeters] at [groundSpeedKnots], in whole seconds. Null if
 * groundspeed is at or below zero — a stationary, landed, or hovering aircraft has no
 * meaningful arrival countdown, and dividing by a zero/negative speed would produce an
 * infinite or nonsensical result rather than a real ETA.
 */
fun remainingSeconds(distanceMeters: Double, groundSpeedKnots: Double): Long? {
    val speedMetersPerSecond = knotsToMetersPerSecond(groundSpeedKnots)
    if (speedMetersPerSecond <= 0.0) return null
    return (distanceMeters / speedMetersPerSecond).toLong()
}
