package com.rf.airmedradar.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Destination point reached by travelling [distanceMeters] from [start] along [bearingDegrees]
 * — the spherical-Earth inverse of [initialBearingDegrees]. Used by the Phase 9.11 launch
 * simulator to project a synthetic aircraft's position along a simulated course; not needed
 * anywhere in the real telemetry pipeline, which only ever receives positions, never projects
 * them.
 */
fun destinationPoint(start: LatLng, bearingDegrees: Double, distanceMeters: Double): LatLng {
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(start.latitude)
    val lon1 = Math.toRadians(start.longitude)

    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )

    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
