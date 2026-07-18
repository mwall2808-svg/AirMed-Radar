package com.rf.airmedradar.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "GeocodingService"

/**
 * Forward-geocodes free-text address strings into map coordinates using the
 * platform [Geocoder] — no API key, no extra network dependency.
 */
object GeocodingService {

    suspend fun resolveAddress(context: Context, query: String): LatLng? {
        if (query.isBlank()) return null
        val normalizedQuery = AddressQueryNormalizer.normalize(query)
        val geocoder = Geocoder(context)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resolveAsync(geocoder, normalizedQuery)
            } else {
                resolveBlocking(geocoder, normalizedQuery)
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Geocoder unreachable (no connectivity) for \"$normalizedQuery\"", e)
            null
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Geocoder request timed out for \"$normalizedQuery\"", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Geocoder I/O failure for \"$normalizedQuery\"", e)
            null
        } catch (e: Exception) {
            // Some OEM Geocoder backends throw undocumented runtime exceptions on failure;
            // this is the last line of defense so a bad search never crashes the service.
            Log.e(TAG, "Unexpected geocoding failure for \"$normalizedQuery\"", e)
            null
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
