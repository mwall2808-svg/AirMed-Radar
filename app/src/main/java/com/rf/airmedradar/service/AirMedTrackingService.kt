package com.rf.airmedradar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.MainActivity
import com.rf.airmedradar.R
import com.rf.airmedradar.data.AdsbRepository
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.GeocodingService
import com.rf.airmedradar.data.NetworkUtils
import com.rf.airmedradar.data.isRotorcraft
import com.rf.airmedradar.util.formatEtaSeconds
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the telemetry engine independent of any Activity/ViewModel lifecycle: ADS-B polling,
 * the MOCK911 calibration simulation, LZ proximity alerting, and the persistent tracking
 * notification. Started in the foreground so the system doesn't throttle the polling loop
 * during long shifts, and bound by [com.rf.airmedradar.viewmodel.AirMedRadarViewModel] so the
 * UI can observe [snapshot] whenever the app is in the foreground.
 */
class AirMedTrackingService : Service() {

    inner class LocalBinder : Binder() {
        val service: AirMedTrackingService get() = this@AirMedTrackingService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val repository = AdsbRepository()

    private val _liveAircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    private val _mockAircraft = MutableStateFlow(createMockAircraft())
    private val _isOffline = MutableStateFlow(false)
    private val _targetCoordinate = MutableStateFlow<LatLng?>(null)
    private val _interceptStatus = MutableStateFlow<InterceptStatus?>(null)
    private val _simulationStatus = MutableStateFlow<SimulationStatus?>(null)
    private val _hasLanded = MutableStateFlow(false)

    private val combinedAircraft = combine(_liveAircraft, _mockAircraft) { live, mock -> live + mock }
    private val engineGroupA = combine(combinedAircraft, _isOffline, _targetCoordinate) { a, o, t -> Triple(a, o, t) }
    private val engineGroupB = combine(_interceptStatus, _simulationStatus, _hasLanded) { i, s, l -> Triple(i, s, l) }

    /** Single source of truth for the UI — everything it needs to render in one flow. */
    val snapshot: StateFlow<TrackingSnapshot> = combine(engineGroupA, engineGroupB) { groupA, groupB ->
        val (aircraft, isOffline, target) = groupA
        val (intercept, simulation, landed) = groupB
        TrackingSnapshot(
            aircraft = aircraft,
            isOffline = isOffline,
            targetCoordinate = target,
            interceptStatus = intercept,
            simulationStatus = simulation,
            hasLanded = landed,
        )
    }.stateIn(serviceScope, SharingStarted.Eagerly, TrackingSnapshot())

    /** Last computed distance-to-target per real aircraft, used to derive the closing trend. */
    private val previousTargetDistanceNm = mutableMapOf<String, Double>()

    /** Edge-trigger guard so the 5 NM heads-up alert fires once per inbound approach. */
    private var hasFiredInboundAlert = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(TRACKING_NOTIFICATION_ID, buildTrackingNotification())
        startPollingLoop()
        startSimulationLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    /** Geocodes [addressQuery] (address or intersection) and, if resolved, sets the target LZ. */
    fun searchTarget(addressQuery: String) {
        serviceScope.launch {
            val resolved = GeocodingService.resolveAddress(applicationContext, addressQuery)
            if (resolved != null) {
                previousTargetDistanceNm.clear()
                hasFiredInboundAlert = false
                _hasLanded.value = false
                _targetCoordinate.value = resolved
            }
        }
    }

    fun clearTarget() {
        _targetCoordinate.value = null
        _interceptStatus.value = null
        _simulationStatus.value = null
        _hasLanded.value = false
        hasFiredInboundAlert = false
        previousTargetDistanceNm.clear()
        _mockAircraft.value = createMockAircraft()
        postNotificationSafely(TRACKING_NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun startPollingLoop() {
        serviceScope.launch {
            while (isActive) {
                refreshAircraft()
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startSimulationLoop() {
        serviceScope.launch {
            while (isActive) {
                advanceMockAircraft()
                tick()
                delay(SIMULATION_TICK_MS)
            }
        }
    }

    private suspend fun refreshAircraft() {
        if (!NetworkUtils.isOnline(applicationContext)) {
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
        if (distanceNm <= LANDING_THRESHOLD_NM) {
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

    /**
     * Recomputes the single nearest inbound aircraft (real or simulated) relative to the
     * target, evaluates proximity alerts against it, and refreshes the tracking notification.
     */
    private fun tick() {
        val closest = computeClosestInbound()
        evaluateAlerts(closest)
        postNotificationSafely(TRACKING_NOTIFICATION_ID, buildTrackingNotification(closest))
    }

    private fun computeClosestInbound(): ClosestInbound? {
        val target = _targetCoordinate.value ?: return null
        val candidates = buildList {
            _interceptStatus.value?.let { status ->
                add(ClosestInbound(status.aircraft.displayName, status.distanceNm, estimateEtaSeconds(status)))
            }
            _mockAircraft.value.let { mock ->
                val lat = mock.lat
                val lon = mock.lon
                if (lat != null && lon != null) {
                    val distanceNm = distanceNauticalMiles(lat, lon, target.latitude, target.longitude)
                    val eta = _simulationStatus.value?.etaSeconds ?: UNKNOWN_ETA_SECONDS
                    add(ClosestInbound(mock.displayName, distanceNm, eta))
                }
            }
        }
        return candidates.minByOrNull { it.distanceNm }
    }

    private fun estimateEtaSeconds(status: InterceptStatus): Long {
        val speed = status.aircraft.groundSpeedKts
        return if (speed != null && speed > 1.0) (status.distanceNm / speed * 3_600).toLong() else UNKNOWN_ETA_SECONDS
    }

    /** 5 NM inbound heads-up alert (edge-triggered) and sticky on-scene/landed detection. */
    private fun evaluateAlerts(closest: ClosestInbound?) {
        if (closest == null || _hasLanded.value) return
        if (closest.distanceNm <= LANDING_THRESHOLD_NM) {
            _hasLanded.value = true
            fireLandedNotification(closest)
            return
        }
        if (closest.distanceNm <= INBOUND_ALERT_THRESHOLD_NM) {
            if (!hasFiredInboundAlert) {
                hasFiredInboundAlert = true
                fireInboundAlertNotification(closest)
            }
        } else {
            hasFiredInboundAlert = false
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(TRACKING_CHANNEL_ID, "HEMS Tracking Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing regional telemetry polling status"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "HEMS Inbound Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical LZ proximity and on-scene landing alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                enableLights(true)
            },
        )
    }

    private fun buildTrackingNotification(closest: ClosestInbound? = computeClosestInbound()): Notification {
        val contentText = when {
            closest == null -> "Monitoring regional HEMS traffic"
            _hasLanded.value -> "${closest.label} — AIRCRAFT ON SCENE / LANDED"
            else -> "${closest.label} inbound — ${"%.1f".format(closest.distanceNm)} NM • " +
                "ETA ${formatEtaSeconds(closest.etaSeconds)}"
        }
        return NotificationCompat.Builder(this, TRACKING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("AirMed Radar Tracking")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(buildContentIntent())
            .build()
    }

    private fun fireInboundAlertNotification(closest: ClosestInbound) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("HEMS Inbound — 5 NM")
            .setContentText(
                "${closest.label} is within 5 NM of the designated LZ — ETA ${formatEtaSeconds(closest.etaSeconds)}",
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent())
            .build()
        postNotificationSafely(ALERT_NOTIFICATION_ID, notification)
    }

    private fun fireLandedNotification(closest: ClosestInbound) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("HEMS On Scene")
            .setContentText("${closest.label} — AIRCRAFT ON SCENE / LANDED")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent())
            .build()
        postNotificationSafely(ALERT_NOTIFICATION_ID, notification)
    }

    private fun postNotificationSafely(id: Int, notification: Notification) {
        // POST_NOTIFICATIONS may be denied on API 33+; notify() would otherwise throw.
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FOCUS_LZ, true)
        }
        return PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    private data class ClosestInbound(val label: String, val distanceNm: Double, val etaSeconds: Long)

    companion object {
        const val EXTRA_FOCUS_LZ = "com.rf.airmedradar.extra.FOCUS_LZ"

        private const val TRACKING_CHANNEL_ID = "hems_tracking_service"
        private const val ALERT_CHANNEL_ID = "hems_inbound_alerts"
        private const val TRACKING_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val OPEN_APP_REQUEST_CODE = 2001

        private const val OPERATIONAL_CENTER_LAT = 39.0
        private const val OPERATIONAL_CENTER_LON = -84.9
        private const val TRACKING_RADIUS_NM = 75
        private const val POLL_INTERVAL_MS = 12_000L
        private const val SIMULATION_TICK_MS = 3_000L
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MOCK_GROUND_SPEED_KTS = 140.0
        private const val MOCK_ALTITUDE_FEET = 1_500.0
        private const val LANDING_THRESHOLD_NM = 0.3
        private const val INBOUND_ALERT_THRESHOLD_NM = 5.0
        private const val UNKNOWN_ETA_SECONDS = -1L
    }
}
