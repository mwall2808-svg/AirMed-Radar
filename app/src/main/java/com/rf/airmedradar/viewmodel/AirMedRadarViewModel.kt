package com.rf.airmedradar.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.data.AdsbRepository
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.GeocodingService
import com.rf.airmedradar.data.NetworkUtils
import com.rf.airmedradar.data.isRotorcraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Nearest tracked aircraft relative to the active search target, with its closing trend. */
data class InterceptStatus(
    val aircraft: Aircraft,
    val distanceNm: Double,
    val isClosing: Boolean,
)

/**
 * Owns the live telemetry feed for the map: polls adsb.lol on a fixed
 * interval, filters the result down to rotary-wing traffic, and exposes it
 * as a [StateFlow] the Compose layer can collect. Also owns marker-selection
 * state and the geocoded intercept target used for delta-vector tracking.
 */
class AirMedRadarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AdsbRepository()

    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft: StateFlow<List<Aircraft>> = _aircraft.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _selectedAircraft = MutableStateFlow<Aircraft?>(null)
    val selectedAircraft: StateFlow<Aircraft?> = _selectedAircraft.asStateFlow()

    private val _targetCoordinate = MutableStateFlow<LatLng?>(null)
    val targetCoordinate: StateFlow<LatLng?> = _targetCoordinate.asStateFlow()

    private val _interceptStatus = MutableStateFlow<InterceptStatus?>(null)
    val interceptStatus: StateFlow<InterceptStatus?> = _interceptStatus.asStateFlow()

    /** Last computed distance-to-target per aircraft, used to derive the closing trend. */
    private val previousTargetDistanceNm = mutableMapOf<String, Double>()

    init {
        startPollingLoop()
    }

    fun selectAircraft(aircraft: Aircraft?) {
        _selectedAircraft.value = aircraft
    }

    /** Geocodes [addressQuery] and, if resolved, sets it as the active intercept target. */
    fun searchTarget(addressQuery: String) {
        viewModelScope.launch {
            val resolved = GeocodingService.resolveAddress(getApplication(), addressQuery)
            if (resolved != null) {
                previousTargetDistanceNm.clear()
                _targetCoordinate.value = resolved
            }
        }
    }

    fun clearTarget() {
        _targetCoordinate.value = null
        _interceptStatus.value = null
        previousTargetDistanceNm.clear()
    }

    private fun startPollingLoop() {
        viewModelScope.launch {
            while (isActive) {
                refreshAircraft()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshAircraft() {
        if (!NetworkUtils.isOnline(getApplication())) {
            _isOffline.value = true
            return
        }
        runCatching {
            repository.fetchAircraftNear(
                lat = OPERATIONAL_CENTER_LAT,
                lon = OPERATIONAL_CENTER_LON,
                radiusNm = TRACKING_RADIUS_NM,
            )
        }.onSuccess { fetched ->
            val rotorcraft = fetched.filter { it.hasPosition && it.isRotorcraft() }
            _aircraft.value = rotorcraft
            _isOffline.value = false
            _selectedAircraft.value?.let { current ->
                _selectedAircraft.value = rotorcraft.find { it.icao == current.icao }
            }
            updateInterceptStatus(rotorcraft)
        }.onFailure {
            _isOffline.value = true
        }
    }

    /** Recomputes which aircraft is nearest the search target and whether it's closing in. */
    private fun updateInterceptStatus(rotorcraft: List<Aircraft>) {
        val target = _targetCoordinate.value
        if (target == null) {
            _interceptStatus.value = null
            return
        }
        _interceptStatus.value = rotorcraft.mapNotNull { ac ->
            val lat = ac.lat ?: return@mapNotNull null
            val lon = ac.lon ?: return@mapNotNull null
            val distanceNm = distanceNauticalMiles(lat, lon, target.latitude, target.longitude)
            val previous = previousTargetDistanceNm[ac.icao]
            previousTargetDistanceNm[ac.icao] = distanceNm
            InterceptStatus(ac, distanceNm, isClosing = previous != null && distanceNm < previous)
        }.minByOrNull { it.distanceNm }
    }

    private fun distanceNauticalMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0] / METERS_PER_NAUTICAL_MILE
    }

    companion object {
        private const val OPERATIONAL_CENTER_LAT = 39.0
        private const val OPERATIONAL_CENTER_LON = -84.9
        private const val TRACKING_RADIUS_NM = 75
        private const val POLL_INTERVAL_MS = 12_000L
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
    }
}
