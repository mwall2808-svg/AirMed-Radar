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
