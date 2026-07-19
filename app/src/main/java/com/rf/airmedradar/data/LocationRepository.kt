package com.rf.airmedradar.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive

private const val HIGH_ACCURACY_UPDATE_INTERVAL_MS = 30_000L
private const val PERMISSION_POLL_INTERVAL_MS = 3_000L

/**
 * Wraps [com.google.android.gms.location.FusedLocationProviderClient] as a cold [Flow]: an
 * immediate cached fix the moment one is available, then live high-accuracy updates as they
 * arrive. Never emits a placeholder (0.0, 0.0) coordinate — if no cached fix exists yet, this
 * simply doesn't emit until the first real one arrives, so callers scoping a network request
 * around the result never fire against the equator/prime-meridian by accident.
 */
class LocationRepository(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // gated by the hasLocationPermission spin-wait below
    fun observeLocation(): Flow<Location> = callbackFlow {
        while (!hasLocationPermission(context) && isActive) {
            // Permission may not be granted yet when this Service starts — MainActivity's own
            // request races it on first launch. Poll instead of giving up outright, so the
            // pipeline comes alive the moment the user grants it without needing an app restart.
            delay(PERMISSION_POLL_INTERVAL_MS)
        }
        if (!isActive) {
            close()
            return@callbackFlow
        }

        // Immediate placeholder: whatever fix the OS already has cached (seconds or hours
        // old), so the very first ADS-B/METAR request has a real coordinate to scope around
        // instead of waiting — possibly a long time indoors — on a fresh high-accuracy fix.
        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) trySend(cached)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, HIGH_ACCURACY_UPDATE_INTERVAL_MS).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
