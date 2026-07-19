package com.rf.airmedradar.data

/**
 * ICAO type-designator codes for helicopter airframes heavily used in HEMS operations
 * worldwide (H135/H145 = Airbus H135/H145, EC35/EC45 = the pre-rebrand Eurocopter designators
 * for those same airframes, B407 = Bell 407, AS35 = Airbus/Aerospatiale AS350). Used as a live
 * discovery filter instead of any fixed operator list — an aircraft counts as a HEMS asset
 * here purely by flying a HEMS-typical airframe, not by who the operator is.
 */
private val HEMS_TYPE_CODES = setOf("H135", "H145", "B407", "EC35", "EC45", "AS35")

/**
 * Maps a callsign's alpha prefix (after stripping its trailing flight-number digits) to a
 * friendly operator name. Deliberately small and best-effort: an unrecognized prefix still
 * surfaces as a discovered provider — keyed by the raw prefix itself — rather than being
 * dropped, since the point of this pipeline is discovering operators live from telemetry
 * rather than requiring them to be pre-registered.
 */
private val CALLSIGN_PROVIDER_NAMES = mapOf(
    "STF" to "StatFlight",
    "ACH" to "Air Care",
    "AE" to "Air Evac",
    "LIFEGD" to "Life Flight",
    "CAREFL" to "CareFlight",
    "STARFL" to "StarFlight",
    "ANGEL" to "Angel One",
    "GRDWNG" to "Guardian Flight",
    "HEALNT" to "HealthNet Aeromedical",
    "REACH" to "REACH Air Medical",
    // Phase 9.11 launch simulator's synthetic callsign ("TTM01") — see
    // com.rf.airmedradar.debug.MockHemsController — so a mock aircraft discovers consistently
    // by name here too, not just via the Phase 9.9 tail-lock filter it primarily exercises.
    "TTM" to "Tab Test Medical",
)

/** A HEMS operator identified live from the current telemetry batch, not a pre-seeded list. */
data class DiscoveredHemsProvider(
    val name: String,
    val tailNumbers: List<String>,
)

/**
 * Filters [aircraft] — a single ADS-B poll's full response array within the operational
 * radius — down to HEMS-typical airframes by ICAO type code, then groups the survivors by the
 * operator implied by their callsign prefix, pairing each with the distinct tail numbers
 * (registrations) currently active on that provider's flights.
 *
 * Pure and stateless by design: called fresh on every 12s poll tick, so the result always
 * reflects exactly what's airborne right now — a provider silently drops off the list the
 * moment none of its aircraft remain in the current batch, with no separate "reset" needed.
 */
fun discoverHemsProviders(aircraft: List<Aircraft>): List<DiscoveredHemsProvider> {
    return aircraft
        .filter { it.aircraftType?.uppercase() in HEMS_TYPE_CODES }
        .mapNotNull { ac -> callsignProviderKey(ac.callsign)?.let { key -> key to ac.registration } }
        .groupBy({ (key, _) -> CALLSIGN_PROVIDER_NAMES[key] ?: key }, { (_, registration) -> registration })
        .map { (name, registrations) -> DiscoveredHemsProvider(name, registrations.filterNotNull().distinct()) }
}

/** Strips a callsign's trailing flight-number digits, e.g. "STF5" -> "STF", "AE102" -> "AE". */
private fun callsignProviderKey(callsign: String?): String? {
    val trimmed = callsign?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
    return trimmed.trimEnd { it.isDigit() }.takeIf { it.isNotBlank() }
}
