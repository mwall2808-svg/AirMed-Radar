package com.rf.airmedradar.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Forward-geocodes free-text address strings into map coordinates using the
 * platform [Geocoder] — no API key, no extra network dependency.
 */
object GeocodingService {

    suspend fun resolveAddress(context: Context, query: String): LatLng? {
        if (query.isBlank()) return null
        val normalizedQuery = AddressQueryNormalizer.normalize(query)
        val geocoder = Geocoder(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveAsync(geocoder, normalizedQuery)
        } else {
            resolveBlocking(geocoder, normalizedQuery)
        }
    }

    private suspend fun resolveAsync(geocoder: Geocoder, query: String): LatLng? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(query, 1) { addresses ->
                val match = addresses.firstOrNull()
                continuation.resume(match?.let { LatLng(it.latitude, it.longitude) })
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveBlocking(geocoder: Geocoder, query: String): LatLng? =
        withContext(Dispatchers.IO) {
            geocoder.getFromLocationName(query, 1)
                ?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        }
}
