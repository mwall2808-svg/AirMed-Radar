package com.rf.airmedradar.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.data.PlacesAutocompleteRepository
import com.rf.airmedradar.service.AirMedTrackingService
import com.rf.airmedradar.service.InterceptStatus
import com.rf.airmedradar.service.TrackingSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin UI-facing façade over [AirMedTrackingService]: starts the always-running telemetry
 * engine, binds to it, and republishes its state as [StateFlow]s for Compose. Also owns
 * purely UI-local state — marker selection and notification re-entry focus requests — that
 * has no business living in the background engine.
 */
class AirMedRadarViewModel(application: Application) : AndroidViewModel(application) {

    private val _boundService = MutableStateFlow<AirMedTrackingService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _boundService.value = (binder as? AirMedTrackingService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _boundService.value = null
        }
    }

    init {
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AirMedTrackingService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshot: StateFlow<TrackingSnapshot> = _boundService
        .flatMapLatest { it?.snapshot ?: flowOf(TrackingSnapshot()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackingSnapshot())

    val aircraft: StateFlow<List<Aircraft>> =
        snapshot.map { it.aircraft }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isOffline: StateFlow<Boolean> =
        snapshot.map { it.isOffline }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val targetCoordinate: StateFlow<LatLng?> =
        snapshot.map { it.targetCoordinate }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val interceptStatus: StateFlow<InterceptStatus?> =
        snapshot.map { it.interceptStatus }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasLanded: StateFlow<Boolean> =
        snapshot.map { it.hasLanded }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _selectedAircraft = MutableStateFlow<Aircraft?>(null)
    val selectedAircraft: StateFlow<Aircraft?> = _selectedAircraft.asStateFlow()

    /**
     * Bumped each time the user re-enters the app via the tracking notification, so the map
     * can reframe on the active LZ even when the target coordinate itself hasn't changed.
     */
    private val _lzFocusRequestId = MutableStateFlow(0L)
    val lzFocusRequestId: StateFlow<Long> = _lzFocusRequestId.asStateFlow()

    // --- Places predictive autocomplete (purely UI-facing; the background engine doesn't
    // need to know about in-progress typing, only the final resolved target). ---

    private val placesClient = runCatching { Places.createClient(getApplication()) }.getOrNull()
    private val placesRepository = placesClient?.let { PlacesAutocompleteRepository(it) }
    private var sessionToken = AutocompleteSessionToken.newInstance()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _addressSuggestions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())
    val addressSuggestions: StateFlow<List<AutocompletePrediction>> = _addressSuggestions.asStateFlow()

    init {
        // Must run after _searchQuery/placesRepository are initialized above — an init block
        // runs at its source position, not after the whole constructor body completes.
        observeSearchQuery()
    }

    fun selectAircraft(aircraft: Aircraft?) {
        _selectedAircraft.value = aircraft
    }

    fun requestLzFocus() {
        _lzFocusRequestId.value += 1
    }

    fun onQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchQuery() {
        viewModelScope.launch {
            _searchQuery
                .debounce(250)
                .distinctUntilChanged()
                .collectLatest { query ->
                    _addressSuggestions.value = if (query.isBlank() || placesRepository == null) {
                        emptyList()
                    } else {
                        placesRepository.fetchPredictions(query, sessionToken, OPERATIONAL_CENTER)
                    }
                }
        }
    }

    /** A dispatcher picked a dropdown suggestion: lock in its full text and geocode it. */
    fun onSuggestionSelected(prediction: AutocompletePrediction) {
        val fullText = prediction.getFullText(null).toString()
        _searchQuery.value = fullText
        _addressSuggestions.value = emptyList()
        sessionToken = AutocompleteSessionToken.newInstance() // new billing session for next search
        searchTarget(fullText)
    }

    fun searchTarget(addressQuery: String) {
        _addressSuggestions.value = emptyList()
        _boundService.value?.searchTarget(addressQuery)
    }

    fun clearTarget() {
        _boundService.value?.clearTarget()
        _searchQuery.value = ""
        _addressSuggestions.value = emptyList()
        sessionToken = AutocompleteSessionToken.newInstance()
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unbindService(connection) }
    }

    companion object {
        private val OPERATIONAL_CENTER = LatLng(39.0, -84.9)
    }
}
