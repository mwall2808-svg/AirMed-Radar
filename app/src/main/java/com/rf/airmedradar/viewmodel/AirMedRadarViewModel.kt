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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/**
 * Owns the live telemetry feed for the map: polls adsb.lol on a fixed
 * interval, filters the result down to rotary-wing traffic, and exposes it
 * as a [StateFlow] the Compose layer can collect. Also owns marker-selection
 * state, the geocoded intercept target, and a simulated aircraft (MOCK911)
 * used to calibrate intercept tracking ahead of the real background engine.
 */
class AirMedRadarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AdsbRepository()

    private val _liveAircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    private val _mockAircraft = MutableStateFlow(createMockAircraft())

    val aircraft: StateFlow<List<Aircraft>> =
        combine(_liveAircraft, _mockAircraft) { live, mock -> live + mock }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _selectedAircraft = MutableStateFlow<Aircraft?>(null)
    val selectedAircraft: StateFlow<Aircraft?> = _selectedAircraft.asStateFlow()

    private val _targetCoordinate = MutableStateFlow<LatLng?>(null)
    val targetCoordinate: StateFlow<LatLng?> = _targetCoordinate.asStateFlow()

    private val _interceptStatus = MutableStateFlow<InterceptStatus?>(null)
    val interceptStatus: StateFlow<InterceptStatus?> = _interceptStatus.asStateFlow()

    private val _simulationStatus = MutableStateFlow<SimulationStatus?>(null)
    val simulationStatus: StateFlow<SimulationStatus?> = _simulationStatus.asStateFlow()

    /** Last computed distance-to-target per real aircraft, used to derive the closing trend. */
    private val previousTargetDistanceNm = mutableMapOf<String, Double>()

    init {
        startPollingLoop()
        startSimulationLoop()
    }

    fun selectAircraft(aircraft: Aircraft?) {
        _selectedAircraft.value = aircraft
    }

    /** Geocodes [addressQuery] (address or intersection) and, if resolved, sets the target LZ. */
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
        _simulationStatus.value = null
        previousTargetDistanceNm.clear()
        _mockAircraft.value = createMockAircraft()
    }

    private fun startPollingLoop() {
        viewModelScope.launch {
            while (isActive) {
                refreshAircraft()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startSimulationLoop() {
        viewModelScope.launch {
            while (isActive) {
                advanceMockAircraft()
                delay(SIMULATION_TICK_MS)
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
            _liveAircraft.value = rotorcraft
            _isOffline.value = false
            _selectedAircraft.value?.let { current ->
                if (!current.isSimulated) {
                    _selectedAircraft.value = rotorcraft.find { it.icao == current.icao }
                }
            }
            updateInterceptStatus(rotorcraft)
        }.onFailure {
            _isOffline.value = true
        }
    }

    /** Recomputes which real aircraft is nearest the target and whether it's closing in. */
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

    /** Advances MOCK911 one tick along a great-circle course toward the active target, if any. */
    private fun advanceMockAircraft() {
        val target = _targetCoordinate.value
        val current = _mockAircraft.value
        val lat = current.lat ?: OPERATIONAL_CENTER_LAT
        val lon = current.lon ?: OPERATIONAL_CENTER_LON

        if (target == null) {
            _simulationStatus.value = null
            return
        }

        val distanceNm = distanceNauticalMiles(lat, lon, target.latitude, target.longitude)
        if (distanceNm <= ARRIVAL_THRESHOLD_NM) {
            _mockAircraft.value = current.copy(
                lat = target.latitude,
                lon = target.longitude,
                groundSpeedKts = 0.0,
            )
            _simulationStatus.value = SimulationStatus(distanceNm = 0.0, etaSeconds = 0L)
            return
        }

        val bearingDeg = initialBearingDegrees(lat, lon, target.latitude, target.longitude)
        val tickHours = SIMULATION_TICK_MS / 3_600_000.0
        val travelNm = MOCK_GROUND_SPEED_KTS * tickHours
        val (newLat, newLon) = destinationPoint(lat, lon, bearingDeg, travelNm)

        _mockAircraft.value = current.copy(
            lat = newLat,
            lon = newLon,
            track = bearingDeg,
            groundSpeedKts = MOCK_GROUND_SPEED_KTS,
            altGeom = MOCK_ALTITUDE_FEET,
        )

        val remainingNm = (distanceNm - travelNm).coerceAtLeast(0.0)
        val etaSeconds = (remainingNm / MOCK_GROUND_SPEED_KTS * 3_600).toLong()
        _simulationStatus.value = SimulationStatus(distanceNm = distanceNm, etaSeconds = etaSeconds)
    }

    private fun distanceNauticalMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0] / METERS_PER_NAUTICAL_MILE
    }

    private fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun destinationPoint(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceNm: Double,
    ): Pair<Double, Double> {
        val angularDistance = (distanceNm * METERS_PER_NAUTICAL_MILE) / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val phi2 = asin(sin(phi1) * cos(angularDistance) + cos(phi1) * sin(angularDistance) * cos(bearingRad))
        val lambda2 = lambda1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(phi1),
            cos(angularDistance) - sin(phi1) * sin(phi2),
        )
        return Math.toDegrees(phi2) to Math.toDegrees(lambda2)
    }

    private fun createMockAircraft() = Aircraft(
        icao = "MOCK911",
        callsign = "MOCK911",
        registration = "N911HN",
        category = "A7",
        lat = OPERATIONAL_CENTER_LAT,
        lon = OPERATIONAL_CENTER_LON,
        altGeom = MOCK_ALTITUDE_FEET,
        groundSpeedKts = 0.0,
        track = 0.0,
        isSimulated = true,
    )

    companion object {
        private const val OPERATIONAL_CENTER_LAT = 39.0
        private const val OPERATIONAL_CENTER_LON = -84.9
        private const val TRACKING_RADIUS_NM = 75
        private const val POLL_INTERVAL_MS = 12_000L
        private const val SIMULATION_TICK_MS = 2_000L
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MOCK_GROUND_SPEED_KTS = 140.0
        private const val MOCK_ALTITUDE_FEET = 1_500.0
        private const val ARRIVAL_THRESHOLD_NM = 0.25
    }
}
