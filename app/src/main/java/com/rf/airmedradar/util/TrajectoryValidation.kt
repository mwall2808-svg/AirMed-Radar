package com.rf.airmedradar.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Allowable deviation between an aircraft's live heading and the calculated bearing to the
 *  LZ before its track is rejected as an unrelated run — see [isOnTrajectoryToTarget]. */
const val TRAJECTORY_WINDOW_DEGREES = 30.0

/**
 * Initial great-circle bearing from [from] to [to], in compass degrees `[0, 360)`. Standard
 * forward-azimuth formula — accurate enough at HEMS operational ranges (tens of miles) that
 * the great-circle vs. rhumb-line distinction is immaterial.
 */
fun initialBearingDegrees(from: LatLng, to: LatLng): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)

    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360.0) % 360.0
}

/**
 * Smallest angular distance between two compass headings, correctly handling wraparound —
 * e.g. 350° vs 10° is a 20° difference, not 340°.
 */
fun angularDifferenceDegrees(a: Double, b: Double): Double {
    val diff = abs(a - b) % 360.0
    return if (diff > 180.0) 360.0 - diff else diff
}

/**
 * Trajectory validation gate: true if [aircraftTrackDegrees] (the aircraft's live ADS-B
 * heading) points toward [bearingToTargetDegrees] (the great-circle bearing from the
 * aircraft's current position to the LZ) within [windowDegrees] — i.e. this aircraft is
 * actually flying toward the LZ, not a same-fleet aircraft working an unrelated run elsewhere
 * that merely happens to share a registration prefix or operator.
 */
fun isOnTrajectoryToTarget(
    aircraftTrackDegrees: Double,
    bearingToTargetDegrees: Double,
    windowDegrees: Double = TRAJECTORY_WINDOW_DEGREES,
): Boolean = angularDifferenceDegrees(aircraftTrackDegrees, bearingToTargetDegrees) <= windowDegrees
