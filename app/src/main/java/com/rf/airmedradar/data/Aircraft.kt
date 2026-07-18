package com.rf.airmedradar.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Raw telemetry payload for a single aircraft, as returned by the adsb.lol
 * `/v2/lat/{lat}/lon/{lon}/dist/{radius}` endpoint. Field names mirror the
 * upstream dump1090/readsb JSON schema, so most are short and cryptic.
 */
@Serializable
data class Aircraft(
    @SerialName("hex") val icao: String,
    @SerialName("flight") val callsign: String? = null,
    @SerialName("r") val registration: String? = null,
    @SerialName("t") val aircraftType: String? = null,
    // ADS-B emitter category, e.g. "A7" = rotorcraft (DO-260B table).
    @SerialName("category") val category: String? = null,
    @SerialName("lat") val lat: Double? = null,
    @SerialName("lon") val lon: Double? = null,
    // alt_baro is usually a number but can be the string "ground" on the pad.
    @SerialName("alt_baro") val altBaro: JsonElement? = null,
    @SerialName("alt_geom") val altGeom: Double? = null,
    @SerialName("gs") val groundSpeedKts: Double? = null,
    @SerialName("track") val track: Double? = null,
    // Not part of the adsb.lol wire format; flags the calibration aircraft ("MOCK911")
    // so it renders distinctly and is excluded from real-telemetry refresh matching.
    val isSimulated: Boolean = false,
) {
    val displayName: String
        get() = callsign?.trim()?.takeIf { it.isNotBlank() } ?: registration ?: icao

    val altitudeFeet: Int?
        get() = (altBaro as? JsonPrimitive)?.doubleOrNull?.toInt() ?: altGeom?.toInt()

    val hasPosition: Boolean
        get() = lat != null && lon != null
}

@Serializable
data class AdsbLolResponse(
    @SerialName("ac") val aircraft: List<Aircraft> = emptyList(),
    @SerialName("total") val total: Int = 0,
    @SerialName("now") val serverTimestamp: Double = 0.0,
)

/** ADS-B emitter category for rotorcraft per DO-260B Table 2-38. */
private const val ROTORCRAFT_CATEGORY = "A7"

/**
 * Known regional HEMS callsign/registration prefixes used as a fallback when
 * an aircraft's broadcast category is missing or incorrectly tagged (common
 * with older transponders that never report emitter category).
 */
private val KNOWN_HEMS_CALLSIGN_PREFIXES = listOf(
    "LIFEGD",   // Air Evac / Life Flight variants
    "CAREFL",   // CareFlight
    "STARFL",   // StarFlight
    "MEDCTR",   // Medical Center helicopters
    "ANGEL",    // Angel One / Angel Flight
    "GRDWNG",   // Guardian
    "HEALNT",   // HealthNet Aeromedical
    "REACH",    // REACH Air Medical
    "AIRVAC",   // Air Evac Lifeteam
)

fun Aircraft.isRotorcraft(): Boolean {
    if (category == ROTORCRAFT_CATEGORY) return true
    val candidates = listOfNotNull(callsign, registration).map { it.trim().uppercase() }
    return candidates.any { candidate ->
        KNOWN_HEMS_CALLSIGN_PREFIXES.any { prefix -> candidate.startsWith(prefix) }
    }
}
