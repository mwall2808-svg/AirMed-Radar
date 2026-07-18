package com.rf.airmedradar.data

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.tasks.await

/** Loose regional bias box (~50 mi at this latitude) so local results rank first, not a hard filter. */
private const val REGION_BIAS_DEGREES = 0.75

/** Thin wrapper over the Places SDK's predictive autocomplete endpoint. */
class PlacesAutocompleteRepository(private val placesClient: PlacesClient) {

    suspend fun fetchPredictions(
        query: String,
        sessionToken: AutocompleteSessionToken,
        biasCenter: LatLng,
    ): List<AutocompletePrediction> {
        if (query.isBlank()) return emptyList()

        val bounds = LatLngBounds(
            LatLng(biasCenter.latitude - REGION_BIAS_DEGREES, biasCenter.longitude - REGION_BIAS_DEGREES),
            LatLng(biasCenter.latitude + REGION_BIAS_DEGREES, biasCenter.longitude + REGION_BIAS_DEGREES),
        )
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(sessionToken)
            .setCountries(listOf("US"))
            .setLocationBias(RectangularBounds.newInstance(bounds))
            .build()

        return runCatching {
            placesClient.findAutocompletePredictions(request).await().autocompletePredictions
        }.getOrDefault(emptyList())
    }
}
