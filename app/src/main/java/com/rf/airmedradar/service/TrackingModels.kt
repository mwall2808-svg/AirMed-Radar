package com.rf.airmedradar.service

import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.data.Aircraft

/** Nearest real, tracked aircraft relative to the active search target, with its closing trend. */
data class InterceptStatus(
    val aircraft: Aircraft,
    val distanceNm: Double,
    val isClosing: Boolean,
)

/** Full engine state published by [AirMedTrackingService] for UI consumption. */
data class TrackingSnapshot(
    val aircraft: List<Aircraft> = emptyList(),
    val isOffline: Boolean = false,
    val targetCoordinate: LatLng? = null,
    val interceptStatus: InterceptStatus? = null,
    val hasLanded: Boolean = false,
    /** The device's own GPS fix — null until the first one arrives; see [AirMedTrackingService]. */
    val deviceLocation: LatLng? = null,
)
