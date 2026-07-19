package com.rf.airmedradar.service

import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.DiscoveredHemsProvider

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
    /** HEMS operators identified live from the current poll's telemetry; see
     *  [com.rf.airmedradar.data.discoverHemsProviders]. Empty, not stale, the moment none of a
     *  provider's aircraft remain in range — this is a snapshot of "right now," not a registry. */
    val discoveredProviders: List<DiscoveredHemsProvider> = emptyList(),
    /** The tail numbers the dispatcher confirmed from the pre-flight selection popup — see
     *  [AirMedTrackingService.setActiveWatchList]. Empty means no provider has been dispatched. */
    val activeWatchList: List<String> = emptyList(),
    /** Derived: `activeWatchList.isNotEmpty()`. Whether the poll loop is currently tail-locked
     *  to a specific provider's fleet instead of showing every rotorcraft in range. */
    val isSearching: Boolean = false,
    /** Whether at least one tail-locked aircraft passed the trajectory validation gate this
     *  tick — see [AirMedTrackingService]'s trigonometric bearing-vs-heading check. False
     *  whenever [isSearching] is false, since there's nothing to lock onto. */
    val isTargetLocked: Boolean = false,
)
