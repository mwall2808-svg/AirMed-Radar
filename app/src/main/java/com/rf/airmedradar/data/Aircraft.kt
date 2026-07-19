package com.rf.airmedradar.data

import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    // Not part of the adsb.lol wire format — never present in the response, always attached
    // client-side by AirMedTrackingService as it tracks each aircraft's position history.
    // @Transient tells kotlinx.serialization to skip this entirely (LatLng has no
    // serializer) and always use the default when decoding a payload.
    @Transient val historyPoints: List<LatLng> = emptyList(),
) {
    val displayName: String
        get() = callsign?.trim()?.takeIf { it.isNotBlank() } ?: registration ?: icao

    val altitudeFeet: Int?
        get() = (altBaro as? JsonPrimitive)?.doubleOrNull?.toInt() ?: altGeom?.toInt()

    val hasPosition: Boolean
        get() = lat != null && lon != null

    val currentCoordinates: LatLng?
        get() = if (lat != null && lon != null) LatLng(lat, lon) else null

    // Safe, non-null fallbacks for consumers that structurally require a primitive value
    // (e.g. a Compose Marker's `rotation: Float` param, or flight-path math) rather than
    // repeating `?: 0f` / `?: 0` at every call site. Display code that wants to distinguish
    // "no data" from "genuinely zero" should keep using the nullable fields directly.
    val safeTrackDegrees: Float
        get() = track?.toFloat() ?: 0f

    val safeAltitudeFeet: Int
        get() = altitudeFeet ?: 0

    val safeGroundSpeedKts: Double
        get() = groundSpeedKts ?: 0.0
}

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
