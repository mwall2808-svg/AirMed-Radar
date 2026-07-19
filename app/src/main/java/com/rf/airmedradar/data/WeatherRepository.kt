package com.rf.airmedradar.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos

private const val AVIATION_WEATHER_BASE_URL = "https://aviationweather.gov/api/data/metar"
private const val TAG = "WeatherRepository"

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
data class MetarCloudLayer(
    val cover: String? = null,
    val base: Int? = null,
)

@Serializable
data class MetarReport(
    val icaoId: String? = null,
    val rawOb: String? = null,
    // Reported as a bare number ("10"), a "greater than" ceiling ("10+"), a fraction
    // ("1/2"), or a below-minimum marker ("M1/4") — kept as a raw string and parsed by
    // [parseVisibilitySm] rather than typed, since it isn't a fixed numeric shape upstream.
    val visib: String? = null,
    val wxString: String? = null,
    val wspd: Int? = null,
    val wgst: Int? = null,
    val clouds: List<MetarCloudLayer> = emptyList(),
)

/** A parsed, HEMS-relevant subset of the latest METAR for one station. */
data class MetarObservation(
    val stationId: String,
    val rawText: String,
    val ceilingFtAgl: Int?,
    val visibilitySm: Double?,
    val hasSevereHazard: Boolean,
)

private val CEILING_LAYER_COVERS = setOf("BKN", "OVC")
private val SEVERE_WX_TOKENS = listOf("TS", "FZRA")
private const val SEVERE_WIND_KT = 35

/**
 * Thin client over the public, unauthenticated NOAA Aviation Weather Center METAR API. No
 * API key or auth headers required.
 */
class WeatherRepository(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    /**
     * Coordinate-to-ICAO mapping: the NOAA API takes a 4-letter station code, not raw lat/lon,
     * so a device fix has to be resolved to "the nearest major METAR-reporting airport" before
     * it can be queried at all. Deliberately a local, offline distance check against a curated
     * list of major hub airports rather than [android.location.Geocoder] — Geocoder needs an
     * on-device geocoding backend that isn't guaranteed present, returns a place name rather
     * than an ICAO code (requiring a second lookup anyway), and would add a second network/IPC
     * round trip to what should be an instant, always-available calculation.
     */
    fun resolveNearestStationId(lat: Double, lon: Double): String = findNearestIcao(lat, lon)

    /**
     * Fetches and parses the latest METAR for [stationId] (default the operational center's
     * home station, KCVG). Returns null on any network/parse failure or if the station has no
     * current observation — callers should hold onto the last-known-good evaluation rather
     * than treat a null as "conditions are fine."
     */
    suspend fun fetchLatestMetar(stationId: String = DEFAULT_STATION_ID): MetarObservation? {
        return runCatching {
            val reports: List<MetarReport> = httpClient.get(AVIATION_WEATHER_BASE_URL) {
                parameter("ids", stationId)
                parameter("format", "json")
            }.body()
            reports.firstOrNull()?.let { toObservation(stationId, it) }
        }.onFailure {
            Log.w(TAG, "METAR fetch failed for $stationId: ${it.message}")
        }.getOrNull()
    }

    private fun toObservation(stationId: String, report: MetarReport): MetarObservation {
        val ceiling = report.clouds
            .filter { it.cover in CEILING_LAYER_COVERS && it.base != null }
            .minOfOrNull { it.base!! }
        return MetarObservation(
            stationId = stationId,
            rawText = report.rawOb.orEmpty(),
            ceilingFtAgl = ceiling,
            visibilitySm = parseVisibilitySm(report.visib),
            hasSevereHazard = hasSevereHazard(report),
        )
    }

    private fun hasSevereHazard(report: MetarReport): Boolean {
        val wx = report.wxString.orEmpty()
        val hasWxHazard = SEVERE_WX_TOKENS.any { wx.contains(it) }
        val hasWindHazard = (report.wspd ?: 0) > SEVERE_WIND_KT || (report.wgst ?: 0) > SEVERE_WIND_KT
        return hasWxHazard || hasWindHazard
    }

    companion object {
        const val DEFAULT_STATION_ID = "KCVG"

        private fun defaultHttpClient(): HttpClient = HttpClient(Android) {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(lenientJson)
            }
        }
    }
}

private data class MetarStation(val icao: String, val lat: Double, val lon: Double)

/**
 * Major CONUS hub airports with reliable, always-on automated METAR reporting — not an
 * exhaustive FAA station table (~1,800 entries), which would be overkill for what this needs:
 * picking "the nearest big airport" to scope a HEMS weather check around, where neighbors are
 * routinely tens to hundreds of miles apart.
 */
private val MAJOR_METAR_STATIONS = listOf(
    MetarStation("KCVG", 39.0489, -84.6678),
    MetarStation("KHOU", 29.6454, -95.2789),
    MetarStation("KIAH", 29.9902, -95.3368),
    MetarStation("KATL", 33.6407, -84.4277),
    MetarStation("KORD", 41.9742, -87.9073),
    MetarStation("KMDW", 41.7868, -87.7522),
    MetarStation("KDFW", 32.8998, -97.0403),
    MetarStation("KDAL", 32.8481, -96.8512),
    MetarStation("KLAX", 33.9416, -118.4085),
    MetarStation("KSAN", 32.7338, -117.1933),
    MetarStation("KSFO", 37.6213, -122.3790),
    MetarStation("KOAK", 37.7213, -122.2208),
    MetarStation("KSJC", 37.3639, -121.9289),
    MetarStation("KSEA", 47.4502, -122.3088),
    MetarStation("KPDX", 45.5898, -122.5951),
    MetarStation("KDEN", 39.8561, -104.6737),
    MetarStation("KPHX", 33.4342, -112.0116),
    MetarStation("KLAS", 36.0840, -115.1537),
    MetarStation("KSLC", 40.7899, -111.9791),
    MetarStation("KMSP", 44.8848, -93.2223),
    MetarStation("KDTW", 42.2124, -83.3534),
    MetarStation("KSTL", 38.7487, -90.3700),
    MetarStation("KMCI", 39.2976, -94.7139),
    MetarStation("KIND", 39.7173, -86.2944),
    MetarStation("KCMH", 39.9980, -82.8919),
    MetarStation("KCLE", 41.4117, -81.8498),
    MetarStation("KPIT", 40.4915, -80.2329),
    MetarStation("KBWI", 39.1774, -76.6684),
    MetarStation("KIAD", 38.9531, -77.4565),
    MetarStation("KDCA", 38.8512, -77.0402),
    MetarStation("KPHL", 39.8721, -75.2411),
    MetarStation("KEWR", 40.6895, -74.1745),
    MetarStation("KJFK", 40.6413, -73.7781),
    MetarStation("KLGA", 40.7772, -73.8726),
    MetarStation("KBOS", 42.3656, -71.0096),
    MetarStation("KBDL", 41.9389, -72.6832),
    MetarStation("KPVD", 41.7240, -71.4283),
    MetarStation("KALB", 42.7483, -73.8017),
    MetarStation("KBUF", 42.9405, -78.7322),
    MetarStation("KROC", 43.1189, -77.6724),
    MetarStation("KSYR", 43.1112, -76.1063),
    MetarStation("KBGM", 42.2087, -75.9797),
    MetarStation("KMSY", 29.9934, -90.2580),
    MetarStation("KBTR", 30.5332, -91.1496),
    MetarStation("KSHV", 32.4466, -93.8256),
    MetarStation("KLIT", 34.7294, -92.2243),
    MetarStation("KOKC", 35.3931, -97.6007),
    MetarStation("KTUL", 36.1984, -95.8881),
    MetarStation("KAUS", 30.1975, -97.6664),
    MetarStation("KSAT", 29.5337, -98.4698),
    MetarStation("KELP", 31.8072, -106.3778),
    MetarStation("KABQ", 35.0402, -106.6091),
    MetarStation("KTUS", 32.1161, -110.9410),
    MetarStation("KBOI", 43.5644, -116.2228),
    MetarStation("KGEG", 47.6199, -117.5338),
    MetarStation("KMKE", 42.9472, -87.8966),
    MetarStation("KDSM", 41.5340, -93.6631),
    MetarStation("KOMA", 41.3032, -95.8941),
    MetarStation("KICT", 37.6499, -97.4331),
    MetarStation("KFSD", 43.5820, -96.7419),
    MetarStation("KRDU", 35.8776, -78.7875),
    MetarStation("KCLT", 35.2140, -80.9431),
    MetarStation("KGSO", 36.0978, -79.9373),
    MetarStation("KJAX", 30.4941, -81.6879),
    MetarStation("KMCO", 28.4294, -81.3089),
    MetarStation("KTPA", 27.9755, -82.5332),
    MetarStation("KMIA", 25.7959, -80.2870),
    MetarStation("KFLL", 26.0726, -80.1527),
    MetarStation("KPBI", 26.6832, -80.0956),
    MetarStation("KRSW", 26.5362, -81.7552),
    MetarStation("KTLH", 30.3965, -84.3503),
    MetarStation("KBHM", 33.5629, -86.7535),
    MetarStation("KMGM", 32.3006, -86.3940),
    MetarStation("KMOB", 30.6912, -88.2428),
    MetarStation("KJAN", 32.3112, -90.0759),
    MetarStation("KMEM", 35.0424, -89.9767),
    MetarStation("KBNA", 36.1245, -86.6782),
    MetarStation("KTYS", 35.8110, -83.9940),
    MetarStation("KCHS", 32.8986, -80.0405),
    MetarStation("KCAE", 33.9388, -81.1195),
    MetarStation("KGSP", 34.8957, -82.2189),
    MetarStation("KRIC", 37.5052, -77.3197),
    MetarStation("KORF", 36.8946, -76.2012),
)

/**
 * Nearest-neighbor search over [MAJOR_METAR_STATIONS] using longitude-corrected planar
 * distance (not true great-circle) — accurate enough to rank candidates that are routinely
 * hundreds of miles apart, which is the only precision this needs.
 */
internal fun findNearestIcao(lat: Double, lon: Double): String {
    val cosLat = cos(Math.toRadians(lat))
    return MAJOR_METAR_STATIONS.minBy { station ->
        val dLat = station.lat - lat
        val dLon = (station.lon - lon) * cosLat
        dLat * dLat + dLon * dLon
    }.icao
}

/** Parses aviationweather.gov's `visib` field into statute miles; see [MetarReport.visib]. */
internal fun parseVisibilitySm(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim().removePrefix("M").removeSuffix("+")
    return if ("/" in trimmed) {
        val parts = trimmed.split("/", limit = 2)
        val numerator = parts.getOrNull(0)?.toDoubleOrNull()
        val denominator = parts.getOrNull(1)?.toDoubleOrNull()
        if (numerator != null && denominator != null && denominator != 0.0) numerator / denominator else null
    } else {
        trimmed.toDoubleOrNull()
    }
}
