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

/** Deliberately not exactly 180° off course — a reciprocal bearing is a degenerate edge case
 *  for "obviously wrong," and this still sits nowhere near the app's actual 30°
 *  [com.rf.airmedradar.util.TRAJECTORY_WINDOW_DEGREES] validation window. */
private const val AWAY_FROM_TARGET_OFFSET_DEGREES = 170.0

/** Fixed home-base helipad origin — University of Cincinnati Medical Center (FAA LID 8OH9,
 *  University Hospital Helipad). STATE 1 (Avionics Init) always boots — or reboots — the
 *  synthetic aircraft here. Every later stage is a continuation from wherever the aircraft
 *  actually got to, never a jump back to this point. */
private val HOME_BASE_ORIGIN = LatLng(39.13728, -84.50272)

private const val LIFT_OFF_GROUND_SPEED_KTS = 20.0
private const val LIFT_OFF_ALTITUDE_FT = 100
private const val LIFT_OFF_TICK_MS = 2_000L

/** Shared by both STATE 3 (the instantaneous turn) and STATE 4 (the continuous approach) — one
 *  cruise speed for the whole inbound leg, not a separate "turn speed" vs. "approach speed." */
private const val CRUISE_GROUND_SPEED_KTS = 135.0
private const val CRUISE_ALTITUDE_FT = 1_000
private const val TERMINAL_APPROACH_TICK_MS = 1_000L
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
 *
 * Spatial continuity is the core invariant here: [currentPosition] is the *only* place a
 * position is ever written, and every stage transition reads it rather than jumping to a
 * stage-specific hardcoded point. STATE 3 (Turn to LZ) in particular must never teleport —
 * it re-points the same aircraft, at the same coordinate STATE 2 left it at, toward the LZ.
 */
class MockHemsController(private val scope: CoroutineScope) {

    private var target: LatLng? = null
    private var movementJob: Job? = null

    /** The synthetic aircraft's one true position. Every stage — static or animated — reads
     *  and advances this same field; nothing ever resets it to a stage-specific waypoint
     *  except [SimulationStage.COLD_AND_DARK]/[SimulationStage.AVIONICS_INIT], which represent
     *  an actual reboot back on the pad. */
    private var currentPosition: LatLng = HOME_BASE_ORIGIN

    private val _stage = MutableStateFlow(SimulationStage.COLD_AND_DARK)
    val stage: StateFlow<SimulationStage> = _stage.asStateFlow()

    private val _mockAircraft = MutableStateFlow<Aircraft?>(null)
    val mockAircraft: StateFlow<Aircraft?> = _mockAircraft.asStateFlow()

    /** Time-warp for manual testing: multiplies the distance covered per movement tick in
     *  [startLiftOff]/[startTerminalApproach], so a dispatcher can watch the full UCMC-to-LZ
     *  route play out in compressed real time instead of waiting out the actual flight time.
     *  Defaults to 1.0x (real-world speed); the debug overlay offers 1x/5x/10x. Read fresh on
     *  every tick, so changing it mid-flight takes effect on the very next step. */
    private val _simSpeedMultiplier = MutableStateFlow(1.0)
    val simSpeedMultiplier: StateFlow<Double> = _simSpeedMultiplier.asStateFlow()

    fun setSimSpeedMultiplier(multiplier: Double) {
        _simSpeedMultiplier.value = multiplier
    }

    /** Called whenever the real dispatch target changes, so the simulator's synthetic
     *  aircraft always turns onto/approaches the actual active LZ rather than a stale one. */
    fun updateTarget(newTarget: LatLng?) {
        target = newTarget
    }

    fun advanceTo(newStage: SimulationStage) {
        movementJob?.cancel()
        _stage.value = newStage
        when (newStage) {
            // STATE 0: completely shut down — the telemetry stream has no entry at all for
            // this tail number, not a zeroed-out one. Resets the position too: the next
            // Avionics On is a fresh boot on the pad, not a resume mid-flight.
            SimulationStage.COLD_AND_DARK -> {
                currentPosition = HOME_BASE_ORIGIN
                _mockAircraft.value = null
            }

            // STATE 1: transponder on, broadcasting from the ramp — zero speed/altitude, and
            // always exactly at the UC Medical Center home-base pad. The map renders this
            // marker unconditionally (tail-locked aircraft always render, regardless of
            // trajectory lock — see AirMedTrackingService.processBatch); only the ETA
            // card/vector line stay hidden, since isTargetLocked is false here.
            SimulationStage.AVIONICS_INIT -> _mockAircraft.value = buildAircraft(
                groundSpeedKts = 0.0,
                altitudeFeet = 0,
                trackDegrees = 0.0,
                position = HOME_BASE_ORIGIN,
            )

            // STATE 2: airborne and creeping away from the LZ from wherever the aircraft
            // currently is — see startLiftOff(). Heading is well outside the validation
            // window, the "identical fleet aircraft on an unrelated run" case the trajectory
            // gate exists to reject, so the ETA card must stay hidden here even though the
            // marker itself renders and moves.
            SimulationStage.LIFT_OFF -> startLiftOff()

            // STATE 3: no movement — this is a discrete telemetry update, not a flight leg.
            // The aircraft stays at the *exact* coordinate STATE 2 left it at (currentPosition
            // is passed straight through, unmodified) and only its track/groundspeed change,
            // rotating onto a heading that points directly at the LZ. Because the position is
            // now real (not a hardcoded shortcut point), the true geodesic distance from here
            // to the LZ is whatever distanceMeters(currentPosition, target) actually is, and
            // AirMedTrackingService.computeLockedTargetEta — which reads this same position
            // and groundSpeedKts — derives its ETA (distance / speed) from that real distance,
            // not a canned one. 0° track deviation is well inside the app's 30° gate, so this
            // is what flips isTargetLocked on.
            SimulationStage.INTERCEPT_HEADING -> _mockAircraft.value = buildAircraft(
                groundSpeedKts = CRUISE_GROUND_SPEED_KTS,
                altitudeFeet = CRUISE_ALTITUDE_FT,
                trackDegrees = bearingToTargetFromCurrentPosition(),
                position = currentPosition,
            )

            // STATE 4: continuously closes the distance to the LZ along the bearing line,
            // continuing from wherever STATE 3 left it, until arrival — see
            // startTerminalApproach().
            SimulationStage.TERMINAL_APPROACH -> startTerminalApproach()
        }
    }

    private fun bearingToTargetFromCurrentPosition(): Double {
        val destination = target ?: return 0.0
        return initialBearingDegrees(currentPosition, destination)
    }

    /** Computed from [currentPosition] — wherever the aircraft actually is right now — never
     *  from a fixed/hardcoded reference point. Anchoring this anywhere else would make the
     *  "away" bearing correct only by coincidence of where the LZ happens to sit relative to
     *  that other point; the trajectory gate evaluates bearing-to-target from the aircraft's
     *  *real* position each tick, so this must be anchored there too. */
    private fun awayFromCurrentPositionBearing(): Double =
        (bearingToTargetFromCurrentPosition() + AWAY_FROM_TARGET_OFFSET_DEGREES) % 360.0

    /** Lifts off from wherever [currentPosition] currently is and creeps outbound on
     *  [awayFromCurrentPositionBearing] (computed once, at stage entry) forever — there's no
     *  arrival condition, unlike [startTerminalApproach]; this stage just needs to visibly
     *  move until the next button press cancels [movementJob]. [currentPosition] is advanced
     *  in place every tick, so STATE 3 picks up from exactly where this loop was cancelled. */
    private fun startLiftOff() {
        val bearing = awayFromCurrentPositionBearing()
        movementJob = scope.launch {
            while (isActive) {
                _mockAircraft.value = buildAircraft(LIFT_OFF_GROUND_SPEED_KTS, LIFT_OFF_ALTITUDE_FT, bearing, currentPosition)

                delay(LIFT_OFF_TICK_MS)

                val stepMeters = knotsToMetersPerSecond(LIFT_OFF_GROUND_SPEED_KTS) *
                    (LIFT_OFF_TICK_MS / 1_000.0) * _simSpeedMultiplier.value
                currentPosition = destinationPoint(currentPosition, bearing, stepMeters)
            }
        }
    }

    /** Ticks every [TERMINAL_APPROACH_TICK_MS] (1s), advancing [currentPosition] along the
     *  live bearing to the LZ at [CRUISE_GROUND_SPEED_KTS] — scaled by [_simSpeedMultiplier]
     *  for manual time-warp testing — starting from wherever STATE 3 left the aircraft, not a
     *  hardcoded restart point. Bearing is recomputed every tick (not just once) so the track
     *  stays a true bearing-to-target line even as the position moves. */
    private fun startTerminalApproach() {
        val destination = target ?: return

        movementJob = scope.launch {
            while (isActive) {
                val remaining = distanceMeters(currentPosition, destination)
                if (remaining <= ARRIVAL_THRESHOLD_METERS) {
                    // Arrived: hold in place rather than overshoot or oscillate.
                    _mockAircraft.value = buildAircraft(0.0, CRUISE_ALTITUDE_FT, 0.0, currentPosition)
                    return@launch
                }
                val bearing = initialBearingDegrees(currentPosition, destination)
                _mockAircraft.value = buildAircraft(CRUISE_GROUND_SPEED_KTS, CRUISE_ALTITUDE_FT, bearing, currentPosition)

                delay(TERMINAL_APPROACH_TICK_MS)

                val stepMeters = knotsToMetersPerSecond(CRUISE_GROUND_SPEED_KTS) *
                    (TERMINAL_APPROACH_TICK_MS / 1_000.0) * _simSpeedMultiplier.value
                currentPosition = destinationPoint(currentPosition, bearing, minOf(stepMeters, remaining))
            }
        }
    }

    /** The single point where the simulator's position of record ([currentPosition]) is
     *  written — every stage, static or animated, reports its position through here, which is
     *  what makes cross-stage continuity automatic rather than something each call site has to
     *  remember to preserve. */
    private fun buildAircraft(
        groundSpeedKts: Double,
        altitudeFeet: Int,
        trackDegrees: Double,
        position: LatLng,
    ): Aircraft {
        currentPosition = position
        return Aircraft(
            icao = MOCK_ICAO,
            callsign = MOCK_CALLSIGN,
            registration = MOCK_PROVIDER_TAIL_NUMBER,
            aircraftType = MOCK_AIRCRAFT_TYPE,
            category = MOCK_CATEGORY,
            lat = position.latitude,
            lon = position.longitude,
            altBaro = JsonPrimitive(altitudeFeet),
            altGeom = altitudeFeet.toDouble(),
            groundSpeedKts = groundSpeedKts,
            track = trackDegrees,
        )
    }
}
