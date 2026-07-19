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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.MainActivity
import com.rf.airmedradar.R
import com.rf.airmedradar.data.AdsbRepository
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.DiscoveredHemsProvider
import com.rf.airmedradar.data.GeocodingService
import com.rf.airmedradar.data.LocationRepository
import com.rf.airmedradar.data.NetworkUtils
import com.rf.airmedradar.data.discoverHemsProviders
import com.rf.airmedradar.data.isRotorcraft
import com.rf.airmedradar.util.formatEtaSeconds
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Owns the telemetry engine independent of any Activity/ViewModel lifecycle: real ADS-B
 * polling, per-aircraft position-history tracking, LZ proximity alerting, and the persistent
 * tracking notification. Started in the foreground so the system doesn't throttle the polling
 * loop during long shifts, and bound by [com.rf.airmedradar.viewmodel.AirMedRadarViewModel] so
 * the UI can observe [snapshot] whenever the app is in the foreground.
 *
 * Every aircraft in [snapshot] reflects verified incoming adsb.lol telemetry only — there is
 * no simulated/synthetic traffic in this engine.
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
    private val locationRepository by lazy { LocationRepository(applicationContext) }

    private val _liveAircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    private val _isOffline = MutableStateFlow(false)
    private val _targetCoordinate = MutableStateFlow<LatLng?>(null)
    private val _interceptStatus = MutableStateFlow<InterceptStatus?>(null)
    private val _hasLanded = MutableStateFlow(false)
    private val _deviceLocation = MutableStateFlow<LatLng?>(null)
    private val _discoveredProviders = MutableStateFlow<List<DiscoveredHemsProvider>>(emptyList())

    private val telemetrySnapshot: StateFlow<TrackingSnapshot> = combine(
        _liveAircraft,
        _isOffline,
        _targetCoordinate,
        _interceptStatus,
        _hasLanded,
    ) { aircraft, isOffline, target, intercept, landed ->
        TrackingSnapshot(
            aircraft = aircraft,
            isOffline = isOffline,
            targetCoordinate = target,
            interceptStatus = intercept,
            hasLanded = landed,
        )
    }.stateIn(serviceScope, SharingStarted.Eagerly, TrackingSnapshot())

    /** Single source of truth for the UI — everything it needs to render in one flow. */
    val snapshot: StateFlow<TrackingSnapshot> = combine(
        telemetrySnapshot,
        _deviceLocation,
        _discoveredProviders,
    ) { base, location, providers ->
        base.copy(deviceLocation = location, discoveredProviders = providers)
    }.stateIn(serviceScope, SharingStarted.Eagerly, TrackingSnapshot())

    /**
     * Running position trail per aircraft (icao -> immutable point list), used to attach
     * [Aircraft.historyPoints] on each poll. Internal bookkeeping only — never published
     * directly; every list handed to the UI via [_liveAircraft] is a freshly built copy so
     * Compose/StateFlow always see a genuinely new instance and recompose the Polyline.
     */
    private val aircraftHistory = mutableMapOf<String, List<LatLng>>()

    /** Last computed distance-to-target per real aircraft, used to derive the closing trend. */
    private val previousTargetDistanceNm = mutableMapOf<String, Double>()

    /** Edge-trigger guard so the 5 NM heads-up alert fires once per inbound approach. */
    private var hasFiredInboundAlert = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(TRACKING_NOTIFICATION_ID, buildTrackingNotification())
        startPollingLoop()
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
        _hasLanded.value = false
        hasFiredInboundAlert = false
        previousTargetDistanceNm.clear()
        postNotificationSafely(TRACKING_NOTIFICATION_ID, buildTrackingNotification())
    }

    /** Non-null once the recurring poll ticker has been started for this session — see
     *  [startPollingLoop]. Its null-ness, not the location itself, is what distinguishes "a
     *  genuine fresh lock" from "routine GPS refinement while already tracking." */
    private var pollTicker: Job? = null

    /**
     * Bootstraps entirely off the device's own GPS fix rather than any fixed/dispatcher-picked
     * region: [LocationRepository.observeLocation] supplies a cached fix immediately (never a
     * bogus (0,0) placeholder) and live high-accuracy updates thereafter. The *first* fix this
     * Service ever sees is a genuine fresh lock — it clears any state left behind by a previous
     * run and starts the recurring poll ticker. Every fix after that just updates
     * [_deviceLocation] in place for the ticker to read on its next cycle: ordinary GPS
     * refinement while already tracking must not keep re-clearing the map every ~30s.
     */
    private fun startPollingLoop() {
        serviceScope.launch {
            locationRepository.observeLocation().collect { location ->
                _deviceLocation.value = LatLng(location.latitude, location.longitude)
                if (pollTicker == null) {
                    resetForFreshLock()
                    pollTicker = serviceScope.launch {
                        while (isActive) {
                            _deviceLocation.value?.let { center ->
                                refreshAircraft(center)
                                tick()
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears every piece of state that would otherwise leak from a previous session: stale
     * aircraft/flight-path lines, position trails, the active search target/intercept status,
     * and alert edge-triggers — run exactly once, the moment a fresh GPS lock is established.
     */
    private fun resetForFreshLock() {
        aircraftHistory.clear()
        previousTargetDistanceNm.clear()
        hasFiredInboundAlert = false
        _liveAircraft.value = emptyList()
        _targetCoordinate.value = null
        _interceptStatus.value = null
        _hasLanded.value = false
        _discoveredProviders.value = emptyList()
    }

    /**
     * Pulls the latest telemetry batch scoped around [center]. On any network failure,
     * last-known aircraft positions are simply left in place (`_liveAircraft` is untouched) and
     * the next scheduled tick retries cleanly — a dropped signal never crashes this service.
     */
    private suspend fun refreshAircraft(center: LatLng) {
        if (!NetworkUtils.isOnline(applicationContext)) {
            _isOffline.value = true
            Log.w(TAG, "Skipping poll: device reports no active network")
            return
        }
        try {
            val fetched = repository.fetchAircraftNear(
                lat = center.latitude,
                lon = center.longitude,
                radiusNm = TRACKING_RADIUS_NM,
            )
            val rotorcraft = fetched.filter { it.hasPosition && it.isRotorcraft() }
            val withHistory = attachHistory(rotorcraft)
            _liveAircraft.value = withHistory
            _isOffline.value = false
            updateInterceptStatus(withHistory)
            // Runs against the full bounding-box batch, not just `rotorcraft` above — ICAO
            // type-code discovery is an independent classification from the isRotorcraft()
            // category/callsign heuristic and shouldn't be narrowed by it.
            _discoveredProviders.value = discoverHemsProviders(fetched)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "adsb.lol unreachable (no DNS/connectivity) — retaining last known positions", e)
            _isOffline.value = true
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "adsb.lol request timed out — retaining last known positions", e)
            _isOffline.value = true
        } catch (e: IOException) {
            Log.w(TAG, "adsb.lol network I/O failure — retaining last known positions", e)
            _isOffline.value = true
        } catch (e: Exception) {
            // Last line of defense: an unexpected SDK/runtime exception must never take
            // down a foreground service running unattended for an entire shift.
            Log.e(TAG, "Unexpected error refreshing aircraft telemetry", e)
            _isOffline.value = true
        }
    }

    /**
     * Appends each aircraft's current position to its running trail and returns a freshly
     * built [Aircraft] list, each entry carrying its own new [Aircraft.historyPoints] list.
     * Deliberately never mutates a previously-published list in place: `toMutableList().apply
     * { add(...) }.toList()` always produces a distinct instance, which is what lets
     * StateFlow/Compose detect the change and redraw the Polyline — appending to the same
     * list reference in place would leave `_liveAircraft.value` structurally unchanged and
     * silently skip recomposition.
     */
    private fun attachHistory(rotorcraft: List<Aircraft>): List<Aircraft> {
        val activeIcaos = rotorcraft.map { it.icao }.toSet()
        aircraftHistory.keys.retainAll(activeIcaos)

        return rotorcraft.map { ac ->
            val lat = ac.lat
            val lon = ac.lon
            if (lat == null || lon == null) return@map ac

            val currentTrail = aircraftHistory[ac.icao] ?: emptyList()
            val updatedTrail = currentTrail.toMutableList().apply { add(LatLng(lat, lon)) }.toList()
            val trimmedTrail = if (updatedTrail.size > MAX_TRAIL_POINTS) {
                updatedTrail.takeLast(MAX_TRAIL_POINTS)
            } else {
                updatedTrail
            }
            aircraftHistory[ac.icao] = trimmedTrail
            ac.copy(historyPoints = trimmedTrail)
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

    /**
     * Recomputes the single nearest inbound aircraft relative to the target, evaluates
     * proximity alerts against it, and refreshes the tracking notification.
     */
    private fun tick() {
        val closest = computeClosestInbound()
        evaluateAlerts(closest)
        postNotificationSafely(TRACKING_NOTIFICATION_ID, buildTrackingNotification(closest))
    }

    private fun computeClosestInbound(): ClosestInbound? {
        if (_targetCoordinate.value == null) return null
        val status = _interceptStatus.value ?: return null
        return ClosestInbound(status.aircraft.displayName, status.distanceNm, estimateEtaSeconds(status))
    }

    private fun estimateEtaSeconds(status: InterceptStatus): Long {
        val speed = status.aircraft.safeGroundSpeedKts
        return if (speed > 1.0) (status.distanceNm / speed * 3_600).toLong() else UNKNOWN_ETA_SECONDS
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

    private data class ClosestInbound(val label: String, val distanceNm: Double, val etaSeconds: Long)

    companion object {
        const val EXTRA_FOCUS_LZ = "com.rf.airmedradar.extra.FOCUS_LZ"

        private const val TAG = "AirMedTrackingService"
        private const val TRACKING_CHANNEL_ID = "hems_tracking_service"
        private const val ALERT_CHANNEL_ID = "hems_inbound_alerts"
        private const val TRACKING_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val OPEN_APP_REQUEST_CODE = 2001

        private const val TRACKING_RADIUS_NM = 75
        private const val POLL_INTERVAL_MS = 12_000L
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val LANDING_THRESHOLD_NM = 0.3
        private const val INBOUND_ALERT_THRESHOLD_NM = 5.0
        private const val UNKNOWN_ETA_SECONDS = -1L

        /** Trail cap per aircraft (~10 min of history at the 12s poll interval). */
        private const val MAX_TRAIL_POINTS = 50
    }
}
