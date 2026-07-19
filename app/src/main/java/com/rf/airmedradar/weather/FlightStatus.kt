package com.rf.airmedradar.weather

import com.rf.airmedradar.data.MetarObservation

/** FAA Part 135 HEMS weather-minimums status for non-mountainous local terrain. */
enum class FlightStatus { GREEN, YELLOW, RED }

data class WeatherMinimumsEvaluation(
    val status: FlightStatus,
    val ceilingFtAgl: Int?,
    val visibilitySm: Double?,
)

private const val RED_CEILING_FT = 1000
private const val YELLOW_CEILING_FT = 1500
private const val RED_VISIBILITY_SM = 3.0
private const val YELLOW_VISIBILITY_SM = 5.0

/**
 * Evaluates a METAR observation against FAA Part 135 HEMS local-flight weather minimums for
 * non-mountainous terrain:
 *  - RED (no go): ceiling < 1000 ft, visibility < 3 SM, or an active severe hazard (TS/FZRA
 *    weather codes, or sustained/gust winds over 35 kt).
 *  - YELLOW (questionable): ceiling 1000-1500 ft, or visibility 3-5 SM.
 *  - GREEN (good to fly): ceiling > 1500 ft and visibility > 5 SM, with no severe hazard.
 *
 * A missing ceiling or visibility reading is treated as "unknown, not disqualifying" — RED and
 * YELLOW are only ever driven by a value actually observed below minimums, or a confirmed
 * hazard, never by absence of data.
 */
fun evaluateFlightStatus(observation: MetarObservation): WeatherMinimumsEvaluation {
    val ceiling = observation.ceilingFtAgl
    val visibility = observation.visibilitySm

    val status = when {
        observation.hasSevereHazard -> FlightStatus.RED
        ceiling != null && ceiling < RED_CEILING_FT -> FlightStatus.RED
        visibility != null && visibility < RED_VISIBILITY_SM -> FlightStatus.RED
        ceiling != null && ceiling <= YELLOW_CEILING_FT -> FlightStatus.YELLOW
        visibility != null && visibility <= YELLOW_VISIBILITY_SM -> FlightStatus.YELLOW
        else -> FlightStatus.GREEN
    }

    return WeatherMinimumsEvaluation(status, ceiling, visibility)
}
