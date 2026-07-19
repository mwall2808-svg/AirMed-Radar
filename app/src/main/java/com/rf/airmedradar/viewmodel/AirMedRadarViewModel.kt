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
import com.rf.airmedradar.data.WeatherRepository
import com.rf.airmedradar.data.profile.AppDatabase
import com.rf.airmedradar.data.profile.ProfileRepository
import com.rf.airmedradar.data.profile.UserProfile
import com.rf.airmedradar.service.AirMedTrackingService
import com.rf.airmedradar.service.InterceptStatus
import com.rf.airmedradar.service.TrackingSnapshot
import com.rf.airmedradar.weather.WeatherMinimumsEvaluation
import com.rf.airmedradar.weather.evaluateFlightStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val WEATHER_POLL_INTERVAL_MS = 15 * 60 * 1_000L
private val FALLBACK_OPERATIONAL_CENTER = LatLng(39.0, -84.9)

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

    // --- Dynamic profile & geospatial scoping: the single source of truth for which region
    // the tracking Service, weather monitor, and map camera are all scoped to. The Service
    // holds its own [ProfileRepository] instance backed by the same Room DB singleton and
    // reacts to profile switches independently — this ViewModel only needs to write the new
    // active profile and re-scope its own weather polling / Places search bias off the same
    // Flow, no direct signal to the Service required. ---

    private val profileRepository = ProfileRepository(AppDatabase.getInstance(application).userProfileDao())

    val activeProfile: StateFlow<UserProfile?> =
        profileRepository.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profiles: StateFlow<List<UserProfile>> =
        profileRepository.allProfiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dispatcher picked a different region from the profile switcher sheet. */
    fun selectProfile(profileId: Long) {
        // Mirrors clearTarget()'s local-search reset: a stale address query/target from the
        // old region has no meaning once the map has jumped elsewhere.
        _searchQuery.value = ""
        _addressSuggestions.value = emptyList()
        sessionToken = AutocompleteSessionToken.newInstance()
        viewModelScope.launch { profileRepository.setActiveProfile(profileId) }
    }

    // --- HEMS weather minimums monitor: independent of the tracking Service/snapshot since
    // it has nothing to do with aircraft telemetry, just the local METAR feed. Polls on its
    // own 15-minute cadence — weather doesn't change fast enough to warrant the Service's
    // much shorter aircraft-poll interval, and a failed fetch simply skips that tick, leaving
    // the last-known-good evaluation on screen rather than clearing it. ---

    private val weatherRepository = WeatherRepository()

    private val _weatherEvaluation = MutableStateFlow<WeatherMinimumsEvaluation?>(null)
    val weatherEvaluation: StateFlow<WeatherMinimumsEvaluation?> = _weatherEvaluation.asStateFlow()

    init {
        observeWeatherMinimums()
    }

    /**
     * Same `collectLatest`-on-the-profile-Flow trick as the Service's polling loop: switching
     * regions cancels whatever delay the previous station's loop was mid-way through, clears
     * the now-stale evaluation so the banner disappears rather than showing the old region's
     * numbers, and immediately fetches the new station — no separate "force refresh" call needed.
     */
    private fun observeWeatherMinimums() {
        viewModelScope.launch {
            activeProfile.filterNotNull().collectLatest { profile ->
                _weatherEvaluation.value = null
                while (isActive) {
                    weatherRepository.fetchLatestMetar(profile.metarStationId)?.let { observation ->
                        _weatherEvaluation.value = evaluateFlightStatus(observation)
                    }
                    delay(WEATHER_POLL_INTERVAL_MS)
                }
            }
        }
    }

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
                        val bias = activeProfile.value?.let { LatLng(it.centerLat, it.centerLon) }
                            ?: FALLBACK_OPERATIONAL_CENTER
                        placesRepository.fetchPredictions(query, sessionToken, bias)
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
}
