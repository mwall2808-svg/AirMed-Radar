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
import com.rf.airmedradar.util.HEMS_LOG_TAG
import com.rf.airmedradar.util.TRAJECTORY_WINDOW_DEGREES
import com.rf.airmedradar.util.angularDifferenceDegrees
import com.rf.airmedradar.util.distanceMeters
import com.rf.airmedradar.util.formatEtaSeconds
import com.rf.airmedradar.util.initialBearingDegrees
import com.rf.airmedradar.util.isOnTrajectoryToTarget
import com.rf.airmedradar.util.knotsToMetersPerSecond
import com.rf.airmedradar.util.remainingSeconds
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the telemetry engine independent of any Activity lifecycle, but not independent of
 * whether there's a mission worth tracking — see [isMissionActive]. Real ADS-B polling,
 * per-aircraft position-history tracking, LZ proximity alerting, and the persistent tracking
 * notification only actually run while a target is set or already locked; promoted to the
 * foreground for exactly that window so the system doesn't throttle the polling loop during a
 * long shift, and demoted back out — zero polling, zero GPS, zero notification — the instant
 * neither holds. Bound by [com.rf.airmedradar.viewmodel.AirMedRadarViewModel] so the UI can
 * observe [snapshot] whenever the app is in the foreground.
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
    private val _activeWatchList = MutableStateFlow<List<String>>(emptyList())
    private val _isTargetLocked = MutableStateFlow(false)
    private val _etaSeconds = MutableStateFlow<Long?>(null)

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

    /**
     * The tail-lock/trajectory trio bundled into one flow first — the typed [combine] overload
     * tops out at 5 flows, and [snapshot] below already needs 4 others alongside this.
     */
    private data class TargetLockState(
        val activeWatchList: List<String>,
        val isTargetLocked: Boolean,
        val etaSeconds: Long?,
    )

    private val targetLockState: StateFlow<TargetLockState> = combine(
        _activeWatchList,
        _isTargetLocked,
        _etaSeconds,
    ) { watchList, targetLocked, eta -> TargetLockState(watchList, targetLocked, eta) }
        .stateIn(serviceScope, SharingStarted.Eagerly, TargetLockState(emptyList(), false, null))

    /** Single source of truth for the UI — everything it needs to render in one flow. */
    val snapshot: StateFlow<TrackingSnapshot> = combine(
        telemetrySnapshot,
        _deviceLocation,
        _discoveredProviders,
        targetLockState,
    ) { base, location, providers, lockState ->
        base.copy(
            deviceLocation = location,
            discoveredProviders = providers,
            activeWatchList = lockState.activeWatchList,
            isSearching = lockState.activeWatchList.isNotEmpty(),
            isTargetLocked = lockState.isTargetLocked,
            etaSeconds = lockState.etaSeconds,
        )
    }.stateIn(serviceScope, SharingStarted.Eagerly, TrackingSnapshot())

    /**
     * The "Happy Medium" telemetry pipeline's master switch: true the moment there's anything
     * worth spending battery on — a dispatched LZ, or an already-locked target (kept true through
     * a locked target's brief unlock blips so the poll loop doesn't thrash off and back on every
     * time a trajectory check misses one tick). False the instant neither holds, which is what
     * [startMissionLifecycleManager] treats as "go all the way back to zero background cost."
     */
    private val isMissionActive: StateFlow<Boolean> = combine(
        _targetCoordinate,
        _isTargetLocked,
    ) { target, locked -> target != null || locked }
        .stateIn(serviceScope, SharingStarted.Eagerly, false)

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
        resetForFreshLock()
        startMissionLifecycleManager()
    }

    /** Idle services get killed the moment nothing is bound and nothing is tracked — see
     *  [isMissionActive] — so there's deliberately no reason to ask the OS to keep an empty,
     *  idle instance around. START_STICKY would fight that by having the OS relaunch this
     *  service after it's reclaimed even with no mission to resume. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /**
     * The task (this app's Recents entry) was swiped away. A locked/dispatched mission is
     * exactly the case a foreground service exists to survive — see [isMissionActive] — so it
     * deliberately keeps running there. With nothing active, though, there's nothing left to
     * monitor in the background; this is a service-side backstop (independent of
     * [com.rf.airmedradar.viewmodel.AirMedRadarViewModel]'s own Activity-lifecycle unbind)
     * against an idle instance lingering.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isMissionActive.value) {
            Log.d(HEMS_LOG_TAG, "[MISSION] onTaskRemoved with no active mission — stopping service")
            // Redundant with demoteFromForeground() in the normal case (it already ran the
            // moment isMissionActive went false) — cheap, explicit insurance against this task
            // removal somehow being the first place that's noticed idle, rather than relying on
            // that prior transition having fired correctly.
            NotificationManagerCompat.from(this).cancelAll()
            stopSelf()
        }
    }

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

    /**
     * Clearing the target always drops [isMissionActive] to false (this plus
     * [clearActiveWatchList] zero out both signals that keep it true) — so this deliberately
     * does *not* refresh the tracking notification itself the way it used to when the service
     * was always in the foreground. [startMissionLifecycleManager] reacts to that transition
     * and calls `demoteFromForeground()`, which cancels it; re-posting it here would race that
     * cancellation and could leave it stuck on screen for an idle session.
     */
    fun clearTarget() {
        _targetCoordinate.value = null
        _interceptStatus.value = null
        _hasLanded.value = false
        hasFiredInboundAlert = false
        previousTargetDistanceNm.clear()
        clearActiveWatchList()
    }

    /**
     * Dispatcher confirmed a provider from the pre-flight selection popup: the very next poll
     * tail-locks onto exactly these registrations instead of the default rotorcraft-category
     * filter — see [refreshAircraft]. Uppercased since ADS-B registrations are broadcast
     * upper-case and the Room-stored tail numbers already are too, but this is the one place
     * that actually matches them against live telemetry, so it shouldn't rely on every caller
     * having normalized case correctly upstream.
     */
    fun setActiveWatchList(tailNumbers: List<String>) {
        _activeWatchList.value = tailNumbers.map { it.uppercase() }
        _isTargetLocked.value = false
        _etaSeconds.value = null
    }

    /** Reverts to showing every rotorcraft in range instead of a tail-locked fleet. */
    fun clearActiveWatchList() {
        _activeWatchList.value = emptyList()
        _isTargetLocked.value = false
        _etaSeconds.value = null
    }

    /**
     * The "Happy Medium" telemetry pipeline's engine: reacts to [isMissionActive] flipping and
     * does nothing else the rest of the time. [collectLatest] is exactly the right primitive
     * here — a new value automatically cancels whatever the *previous* value's branch was doing
     * (structured concurrency via the nested [coroutineScope]), so there's no manual job
     * bookkeeping for "cancel the old poll loop before starting a new one."
     *
     * ACTIVE: promotes to a foreground service (persistent notification, survives the app being
     * backgrounded) and runs GPS observation + the 3-second ADS-B poll ticker as sibling
     * children of one [coroutineScope] — cancelling that scope (the instant [isMissionActive]
     * goes false) tears down both at once, including unregistering the location callback (see
     * [LocationRepository.observeLocation]'s `awaitClose`).
     *
     * IDLE: demotes out of the foreground state and dismisses every notification. No coroutines
     * are launched in this branch — nothing is left running that costs battery: no polling, no
     * GPS, not even a wakeup timer.
     */
    private fun startMissionLifecycleManager() {
        serviceScope.launch {
            isMissionActive.collectLatest { active ->
                if (active) {
                    Log.d(HEMS_LOG_TAG, "[MISSION] active -> promoting to foreground, starting 3s poll + GPS")
                    promoteToForeground()
                    coroutineScope {
                        launch { observeDeviceLocation() }
                        launch { runPollingLoop() }
                    }
                } else {
                    Log.d(HEMS_LOG_TAG, "[MISSION] idle -> demoting from foreground, all polling/GPS stopped")
                    demoteFromForeground()
                }
            }
        }
    }

    /** Emits into [_deviceLocation] for as long as this coroutine is alive — cancelled the
     *  instant the mission goes idle, which is what actually unregisters the location callback
     *  (see [LocationRepository.observeLocation]). */
    private suspend fun observeDeviceLocation() {
        locationRepository.observeLocation().collect { location ->
            _deviceLocation.value = LatLng(location.latitude, location.longitude)
        }
    }

    /** The 3-second ADS-B poll ticker — real network truth every [POLL_INTERVAL_MS], with
     *  [com.rf.airmedradar.MainActivity]'s marker interpolation gliding the icon smoothly
     *  between ticks rather than the map hopping once per fetch. */
    private suspend fun runPollingLoop() {
        while (currentCoroutineContext().isActive) {
            _deviceLocation.value?.let { center ->
                refreshAircraft(center)
                tick()
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun promoteToForeground() {
        startForeground(TRACKING_NOTIFICATION_ID, buildTrackingNotification())
    }

    /**
     * Drops out of the foreground state and clears every notification this app owns via
     * `cancelAll()` — not two individual `cancel(id)` calls — so an idle app leaves nothing
     * behind in the shade, guaranteed, even if a future notification type is added here and
     * someone forgets to list its ID alongside these two. Also calls [stopSelf] explicitly:
     * harmless no-op if [AirMedRadarViewModel][com.rf.airmedradar.viewmodel.AirMedRadarViewModel]
     * still has this instance bound (Android defers actual teardown until the last unbind
     * either way), but it correctly relinquishes the "started" state [promoteToForeground]'s
     * `startForeground()` call put this service into — a started service is responsible for
     * stopping itself once its work is done, and idle is exactly that moment.
     */
    private fun demoteFromForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancelAll()
        stopSelf()
    }

    /**
     * Clears every piece of state that would otherwise leak from a previous run: stale
     * aircraft/flight-path lines, position trails, the active search target/intercept status,
     * and alert edge-triggers. Called exactly once, synchronously from [onCreate] — deliberately
     * *not* gated on the first GPS fix the way it used to be: since [observeDeviceLocation] now
     * only ever runs once a mission is already active (see [startMissionLifecycleManager]), a
     * "first fix" trigger would fire *after* the mission's own target had just been set,
     * immediately wiping it back out and flipping [isMissionActive] straight back to false. None
     * of what this actually clears needs a location fix in the first place — it's all in-memory
     * bookkeeping that's already at these same defaults on a freshly constructed instance — so
     * there's no reason to wait for one.
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
        clearActiveWatchList()
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
            _isOffline.value = false
            processBatch(fetched)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "adsb.lol unreachable (no DNS/connectivity) — retaining last known positions", e)
            _isOffline.value = true
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "adsb.lol request timed out — retaining last known positions", e)
            _isOffline.value = true
        } catch (e: ClientRequestException) {
            _isOffline.value = true
            if (e.response.status == HttpStatusCode.TooManyRequests) {
                // adsb.lol is a free, unauthenticated, rate-limited public API — polling it
                // every 3s (see POLL_INTERVAL_MS) can outrun its limit under sustained use. An
                // extra cooldown here, on top of the normal 3s cadence, means this backs off
                // instead of hammering an endpoint that's already telling us to slow down —
                // both the considerate thing to do to a free service and the actual behavior
                // that gets telemetry flowing again soonest instead of retrying into more 429s.
                Log.w(TAG, "adsb.lol rate-limited this device (429) — backing off ${RATE_LIMIT_BACKOFF_MS}ms extra")
                delay(RATE_LIMIT_BACKOFF_MS)
            } else {
                Log.w(TAG, "adsb.lol rejected the request (${e.response.status}) — retaining last known positions", e)
            }
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
     * The real tail-lock filter / trajectory gate / history / intercept pipeline, run against
     * [fetched] — the current real ADS-B batch.
     */
    private fun processBatch(fetched: List<Aircraft>) {
        val watchList = _activeWatchList.value
        val candidates = if (watchList.isNotEmpty()) {
            // Tail-lock filter: once a provider has been dispatched, completely ignore
            // every other aircraft in the sky — including other rotorcraft — and isolate
            // strictly by registration membership in the dispatcher's confirmed fleet.
            fetched.filter { it.hasPosition && it.registration?.uppercase() in watchList }
        } else {
            fetched.filter { it.hasPosition && it.isRotorcraft() }
        }
        // Trajectory lock is evaluated but no longer decides what renders — a tail-locked
        // aircraft that's parked on the pad or outbound on an unrelated heading must still show
        // up on the map. Only the ETA card / vector line care whether it's actually inbound.
        val locked = if (watchList.isNotEmpty()) {
            evaluateTrajectoryLock(candidates)
        } else {
            _isTargetLocked.value = false
            emptyList()
        }

        val withHistory = attachHistory(candidates)
        _liveAircraft.value = withHistory
        updateInterceptStatus(withHistory)
        _etaSeconds.value = if (watchList.isNotEmpty()) computeLockedTargetEta(locked) else null
        // Runs against the full fetched batch, not just `candidates`/`locked` above — ICAO
        // type-code discovery is an independent classification from the tail-lock/
        // isRotorcraft() filters and shouldn't be narrowed by either.
        _discoveredProviders.value = discoverHemsProviders(fetched)
    }

    /**
     * Trigonometric vector validation: for each tail-locked [candidates] aircraft, calculates
     * the great-circle bearing from its current position to the LZ and compares it against the
     * aircraft's own live ADS-B heading ([Aircraft.track]). Only aircraft actually flying toward
     * this LZ (within [com.rf.airmedradar.util.TRAJECTORY_WINDOW_DEGREES]) are considered
     * "locked" — an identical-fleet aircraft handling an unrelated run elsewhere, or one still
     * parked/outbound, doesn't count. [_isTargetLocked] reflects whether at least one candidate
     * passed this tick; aircraft missing position/heading data can't be evaluated and are
     * rejected rather than assumed valid. The returned subset drives the ETA card only — it no
     * longer decides what renders on the map; every tail-locked candidate still reaches
     * [_liveAircraft] regardless of this result (see [processBatch]).
     */
    private fun evaluateTrajectoryLock(candidates: List<Aircraft>): List<Aircraft> {
        val target = _targetCoordinate.value
        if (target == null) {
            val previousLock = _isTargetLocked.value
            _isTargetLocked.value = false
            if (previousLock) {
                Log.d(HEMS_LOG_TAG, "[TARGET_LOCK] isTargetLocked: true -> false (no active LZ target)")
            }
            return emptyList()
        }
        val validated = candidates.filter { ac ->
            val lat = ac.lat ?: return@filter false
            val lon = ac.lon ?: return@filter false
            val track = ac.track ?: return@filter false
            val bearingToTarget = initialBearingDegrees(LatLng(lat, lon), target)
            val deltaTheta = angularDifferenceDegrees(track, bearingToTarget)
            val passed = isOnTrajectoryToTarget(aircraftTrackDegrees = track, bearingToTargetDegrees = bearingToTarget)
            Log.d(
                HEMS_LOG_TAG,
                "[TRAJECTORY_GATE] icao=${ac.icao} track=%.2f° bearingToLZ=%.2f° deltaTheta=%.2f° threshold=%.1f° result=%s"
                    .format(track, bearingToTarget, deltaTheta, TRAJECTORY_WINDOW_DEGREES, if (passed) "PASS" else "FAIL"),
            )
            passed
        }
        val previousLock = _isTargetLocked.value
        _isTargetLocked.value = validated.isNotEmpty()
        Log.d(
            HEMS_LOG_TAG,
            "[TARGET_LOCK] isTargetLocked: $previousLock -> ${_isTargetLocked.value} " +
                "(validated=${validated.size}/${candidates.size} candidates)",
        )
        return validated
    }

    /**
     * Metric arrival countdown for the locked target: straight-line distance (meters) from the
     * locked aircraft's current position to the LZ, divided by its groundspeed converted from
     * knots to meters/second (1 kt = 0.514444 m/s). Exclusively for a validated, trajectory-
     * gated target — [lockedAircraft] is expected to already be that filtered set, so this
     * simply takes the first (closest, per [evaluateTrajectoryLock]'s upstream ordering) entry.
     * Null whenever there's no locked aircraft, no LZ, or no usable position/speed reading.
     */
    private fun computeLockedTargetEta(lockedAircraft: List<Aircraft>): Long? {
        val target = _targetCoordinate.value ?: return null
        val aircraft = lockedAircraft.firstOrNull() ?: return null
        val position = aircraft.currentCoordinates ?: return null
        val groundSpeedKnots = aircraft.groundSpeedKts ?: return null

        val distance = distanceMeters(position, target)
        val speedMetersPerSecond = knotsToMetersPerSecond(groundSpeedKnots)
        val etaSeconds = remainingSeconds(distance, groundSpeedKnots)
        Log.d(
            HEMS_LOG_TAG,
            "[ETA_CALC] icao=${aircraft.icao} distance=%.1fm (%.2fkm) groundspeed=%.1fkt (%.2fm/s) rawEtaSeconds=%s formatted=%s"
                .format(distance, distance / 1000.0, groundSpeedKnots, speedMetersPerSecond, etaSeconds, formatEtaSeconds(etaSeconds ?: -1L)),
        )
        return etaSeconds
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

        /** The "Happy Medium" telemetry cadence — fresh network truth every 3s while a mission
         *  is active (see [isMissionActive]); [com.rf.airmedradar.MainActivity]'s marker
         *  interpolation covers the gap between ticks so the map still reads as smooth motion. */
        private const val POLL_INTERVAL_MS = 3_000L

        /** Extra cooldown applied only on top of [POLL_INTERVAL_MS] when adsb.lol responds
         *  429 — see [refreshAircraft]. adsb.lol is free and unauthenticated with no published
         *  per-IP rate limit; this value is a conservative guess, not a documented number. */
        private const val RATE_LIMIT_BACKOFF_MS = 15_000L

        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val LANDING_THRESHOLD_NM = 0.3
        private const val INBOUND_ALERT_THRESHOLD_NM = 5.0
        private const val UNKNOWN_ETA_SECONDS = -1L

        /** Trail cap per aircraft — sized to hold ~10 min of history at the 3s poll interval. */
        private const val MAX_TRAIL_POINTS = 200
    }
}
