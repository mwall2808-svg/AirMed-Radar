package com.rf.airmedradar.debug

import com.google.android.gms.maps.model.LatLng
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.util.destinationPoint
import com.rf.airmedradar.util.distanceMeters
import com.rf.airmedradar.util.initialBearingDegrees
import com.rf.airmedradar.util.knotsToMetersPerSecond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Provider this simulator is explicitly bound to — see [com.rf.airmedradar.viewmodel.AirMedRadarViewModel]'s
 * debug-only seeding of this exact row into the Phase 9.7 Room registry, so it's always
 * present in the dispatch popup regardless of what a prior manual test session left behind.
 */
const val MOCK_PROVIDER_NAME = "Tab Test Medical"
const val MOCK_PROVIDER_TAIL_NUMBER = "N911TT"

private const val MOCK_ICAO = "TESTTT"
private const val MOCK_CALLSIGN = "TTM01"
private const val MOCK_AIRCRAFT_TYPE = "H135"
private const val MOCK_CATEGORY = "A7"

/** ~5 NM from the LZ, arbitrary bearing — just needs to be far enough that TERMINAL_APPROACH
 *  has a real distance to visibly count down. */
private const val START_DISTANCE_METERS = 9_260.0
private const val START_BEARING_DEGREES = 300.0

/** Deliberately not exactly 180° off course — a reciprocal bearing is a degenerate edge case
 *  for "obviously wrong," and this still sits nowhere near either the 15° or 30° validation
 *  windows used anywhere in the app. */
private const val AWAY_FROM_TARGET_OFFSET_DEGREES = 170.0

/** Fixed home-base helipad origin — University of Cincinnati Medical Center (FAA LID 8OH9,
 *  University Hospital Helipad). STATE 1 (Avionics Init) and the initial frame of STATE 2
 *  (Lift Off) always boot the synthetic aircraft here, rather than at the target-relative
 *  [MockHemsController.startPosition] used by the later intercept/approach stages. */
private val HOME_BASE_ORIGIN = LatLng(39.13728, -84.50272)

private const val LIFT_OFF_GROUND_SPEED_KTS = 20.0
private const val LIFT_OFF_ALTITUDE_FT = 100
private const val LIFT_OFF_TICK_MS = 2_000L
private const val INTERCEPT_GROUND_SPEED_KTS = 120.0
private const val INTERCEPT_ALTITUDE_FT = 1_000
private const val TERMINAL_APPROACH_TICK_MS = 2_000L
private const val ARRIVAL_THRESHOLD_METERS = 150.0

/** The five stages of the Phase 9.11 pre-flight/launch simulation for [MOCK_PROVIDER_NAME]. */
enum class SimulationStage(val label: String) {
    COLD_AND_DARK("1. Cold"),
    AVIONICS_INIT("2. Avionics On"),
    LIFT_OFF("3. Ground Lift"),
    INTERCEPT_HEADING("4. Turn to LZ"),
    TERMINAL_APPROACH("5. Track Flight"),
}

/**
 * Drives a synthetic [Aircraft] through the five launch-sequence stages, for exercising the
 * Phase 9.9 tail-lock filter and Phase 9.10 trajectory validation gate against controlled,
 * repeatable inputs instead of waiting on real ADS-B traffic to coincidentally match. The
 * synthetic aircraft is merged into [com.rf.airmedradar.service.AirMedTrackingService]'s real
 * telemetry batch (see `updateMockAircraftLocally`/`triggerImmediateRefresh`) and run through
 * the exact same filter/gate/history code every real aircraft goes through — this is a fake
 * telemetry *source*, not a shortcut around the pipeline being tested.
 */
class MockHemsController(private val scope: CoroutineScope) {

    private var target: LatLng? = null
    private var startPosition: LatLng? = null
    private var movementJob: Job? = null

    private val _stage = MutableStateFlow(SimulationStage.COLD_AND_DARK)
    val stage: StateFlow<SimulationStage> = _stage.asStateFlow()

    private val _mockAircraft = MutableStateFlow<Aircraft?>(null)
    val mockAircraft: StateFlow<Aircraft?> = _mockAircraft.asStateFlow()

    /** Called whenever the real dispatch target changes, so the simulator's synthetic
     *  aircraft always orbits/approaches the actual active LZ rather than a stale one. */
    fun updateTarget(newTarget: LatLng?) {
        target = newTarget
        startPosition = newTarget?.let { destinationPoint(it, START_BEARING_DEGREES, START_DISTANCE_METERS) }
    }

    fun advanceTo(newStage: SimulationStage) {
        movementJob?.cancel()
        _stage.value = newStage
        when (newStage) {
            // STATE 0: completely shut down — the telemetry stream has no entry at all for
            // this tail number, not a zeroed-out one.
            SimulationStage.COLD_AND_DARK -> _mockAircraft.value = null

            // STATE 1: transponder on, broadcasting from the ramp — zero speed/altitude, and
            // always exactly at the UC Medical Center home-base pad, not a target-relative point.
            // The map renders this marker unconditionally (tail-locked aircraft always render,
            // regardless of trajectory lock — see AirMedTrackingService.processBatch); only the
            // ETA card/vector line stay hidden, since isTargetLocked is false here.
            SimulationStage.AVIONICS_INIT -> _mockAircraft.value = buildAircraft(
                groundSpeedKts = 0.0,
                altitudeFeet = 0,
                trackDegrees = 0.0,
                position = HOME_BASE_ORIGIN,
            )

            // STATE 2: airborne and creeping away from the LZ — see startLiftOff(). Heading is
            // well outside any validation window, the "identical fleet aircraft on an unrelated
            // run" case the trajectory gate exists to reject, so the ETA card must stay hidden
            // here even though the marker itself renders and moves.
            SimulationStage.LIFT_OFF -> startLiftOff()

            // STATE 3: turned onto a heading pointing exactly at the LZ (0° deviation — well
            // inside both the 15° called for here and the app's actual 30° gate).
            SimulationStage.INTERCEPT_HEADING -> _mockAircraft.value = buildAircraft(
                groundSpeedKts = INTERCEPT_GROUND_SPEED_KTS,
                altitudeFeet = INTERCEPT_ALTITUDE_FT,
                trackDegrees = towardTargetBearing(),
                position = startPosition,
            )

            // STATE 4: continuously closes the distance to the LZ along the bearing line
            // until arrival — see startTerminalApproach().
            SimulationStage.TERMINAL_APPROACH -> startTerminalApproach()
        }
    }

    private fun towardTargetBearing(): Double {
        val position = startPosition ?: return 0.0
        val destination = target ?: return 0.0
        return initialBearingDegrees(position, destination)
    }

    /** Deliberately computed from [HOME_BASE_ORIGIN] — LIFT_OFF's actual starting position —
     *  rather than [startPosition] (the unrelated LZ-relative point later stages start from).
     *  Using the wrong reference point here would make the "away" bearing correct only by
     *  coincidence of where the LZ happens to sit relative to [startPosition]; the trajectory
     *  gate evaluates bearing-to-target from the aircraft's *real* position each tick, so this
     *  must be anchored there too, or a nearby LZ can spuriously fall inside the lock window. */
    private fun awayFromHomeBearing(): Double {
        val destination = target ?: return 0.0
        val homeBearingToTarget = initialBearingDegrees(HOME_BASE_ORIGIN, destination)
        return (homeBearingToTarget + AWAY_FROM_TARGET_OFFSET_DEGREES) % 360.0
    }

    /** Lifts off from the home-base pad and creeps outbound on [awayFromHomeBearing] forever
     *  — there's no arrival condition, unlike [startTerminalApproach]; this stage just needs to
     *  visibly move until the next button press cancels [movementJob]. */
    private fun startLiftOff() {
        val bearing = awayFromHomeBearing()
        movementJob = scope.launch {
            var position = HOME_BASE_ORIGIN
            while (isActive) {
                _mockAircraft.value = buildAircraft(LIFT_OFF_GROUND_SPEED_KTS, LIFT_OFF_ALTITUDE_FT, bearing, position)

                delay(LIFT_OFF_TICK_MS)

                val stepMeters = knotsToMetersPerSecond(LIFT_OFF_GROUND_SPEED_KTS) * (LIFT_OFF_TICK_MS / 1_000.0)
                position = destinationPoint(position, bearing, stepMeters)
            }
        }
    }

    private fun startTerminalApproach() {
        val destination = target ?: return
        val initialPosition = startPosition ?: return

        movementJob = scope.launch {
            var position = initialPosition
            while (isActive) {
                val remaining = distanceMeters(position, destination)
                if (remaining <= ARRIVAL_THRESHOLD_METERS) {
                    // Arrived: hold in place rather than overshoot or oscillate.
                    _mockAircraft.value = buildAircraft(0.0, INTERCEPT_ALTITUDE_FT, 0.0, position)
                    return@launch
                }
                val bearing = initialBearingDegrees(position, destination)
                _mockAircraft.value = buildAircraft(INTERCEPT_GROUND_SPEED_KTS, INTERCEPT_ALTITUDE_FT, bearing, position)

                delay(TERMINAL_APPROACH_TICK_MS)

                val stepMeters = knotsToMetersPerSecond(INTERCEPT_GROUND_SPEED_KTS) * (TERMINAL_APPROACH_TICK_MS / 1_000.0)
                position = destinationPoint(position, bearing, minOf(stepMeters, remaining))
            }
        }
    }

    private fun buildAircraft(
        groundSpeedKts: Double,
        altitudeFeet: Int,
        trackDegrees: Double,
        position: LatLng?,
    ): Aircraft? {
        val pos = position ?: return null
        return Aircraft(
            icao = MOCK_ICAO,
            callsign = MOCK_CALLSIGN,
            registration = MOCK_PROVIDER_TAIL_NUMBER,
            aircraftType = MOCK_AIRCRAFT_TYPE,
            category = MOCK_CATEGORY,
            lat = pos.latitude,
            lon = pos.longitude,
            altBaro = JsonPrimitive(altitudeFeet),
            altGeom = altitudeFeet.toDouble(),
            groundSpeedKts = groundSpeedKts,
            track = trackDegrees,
        )
    }
}
