package com.rf.airmedradar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rf.airmedradar.data.AdsbRepository
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.NetworkUtils
import com.rf.airmedradar.data.isRotorcraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the live telemetry feed for the map: polls adsb.lol on a fixed
 * interval, filters the result down to rotary-wing traffic, and exposes it
 * as a [StateFlow] the Compose layer can collect.
 */
class AirMedRadarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AdsbRepository()

    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft: StateFlow<List<Aircraft>> = _aircraft.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    init {
        startPollingLoop()
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
            _aircraft.value = fetched.filter { it.hasPosition && it.isRotorcraft() }
            _isOffline.value = false
        }.onFailure {
            _isOffline.value = true
        }
    }

    companion object {
        private const val OPERATIONAL_CENTER_LAT = 39.0
        private const val OPERATIONAL_CENTER_LON = -84.9
        private const val TRACKING_RADIUS_NM = 75
        private const val POLL_INTERVAL_MS = 12_000L
    }
}
