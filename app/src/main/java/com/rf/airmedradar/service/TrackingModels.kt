package com.rf.airmedradar.service

import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.data.Aircraft

/** Nearest real, tracked aircraft relative to the active search target, with its closing trend. */
data class InterceptStatus(
    val aircraft: Aircraft,
    val distanceNm: Double,
    val isClosing: Boolean,
)

/** Live distance/ETA from the simulated calibration aircraft (MOCK911) to the active target. */
data class SimulationStatus(
    val distanceNm: Double,
    val etaSeconds: Long,
)

/** Full engine state published by [AirMedTrackingService] for UI consumption. */
data class TrackingSnapshot(
    val aircraft: List<Aircraft> = emptyList(),
    val isOffline: Boolean = false,
    val targetCoordinate: LatLng? = null,
    val interceptStatus: InterceptStatus? = null,
    val simulationStatus: SimulationStatus? = null,
    val hasLanded: Boolean = false,
)
