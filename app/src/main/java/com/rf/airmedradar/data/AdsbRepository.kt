package com.rf.airmedradar.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val ADSB_LOL_BASE_URL = "https://api.adsb.lol/v2"

/**
 * Thin client over the public, unauthenticated adsb.lol REST API. No API key
 * or auth headers required.
 */
class AdsbRepository(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    /** Fetches all currently-tracked aircraft within [radiusNm] nautical miles of the given point. */
    suspend fun fetchAircraftNear(lat: Double, lon: Double, radiusNm: Int): List<Aircraft> {
        val response: AdsbLolResponse =
            httpClient.get("$ADSB_LOL_BASE_URL/lat/$lat/lon/$lon/dist/$radiusNm").body()
        return response.aircraft
    }

    companion object {
        private fun defaultHttpClient(): HttpClient = HttpClient(Android) {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }
    }
}
