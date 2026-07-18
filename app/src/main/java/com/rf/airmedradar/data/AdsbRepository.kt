package com.rf.airmedradar.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

private const val ADSB_LOL_BASE_URL = "https://api.adsb.lol/v2"
private const val TAG = "AdsbRepository"

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Thin client over the public, unauthenticated adsb.lol REST API. No API key
 * or auth headers required.
 */
class AdsbRepository(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    /** Fetches all currently-tracked aircraft within [radiusNm] nautical miles of the given point. */
    suspend fun fetchAircraftNear(lat: Double, lon: Double, radiusNm: Int): List<Aircraft> {
        val root: JsonObject = httpClient.get("$ADSB_LOL_BASE_URL/lat/$lat/lon/$lon/dist/$radiusNm").body()
        val aircraftArray = root["ac"]?.jsonArray ?: return emptyList()

        // Decode each aircraft record independently so a single malformed entry (e.g. an
        // upstream field showing up with an unexpected type) only drops that one aircraft
        // for this tick instead of discarding the whole batch.
        return aircraftArray.mapNotNull { element ->
            runCatching { lenientJson.decodeFromJsonElement<Aircraft>(element) }
                .onFailure { Log.w(TAG, "Skipping malformed aircraft record: ${it.message}") }
                .getOrNull()
        }
    }

    companion object {
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
