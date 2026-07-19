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
import com.rf.airmedradar.data.DiscoveredHemsProvider
import com.rf.airmedradar.data.PlacesAutocompleteRepository
import com.rf.airmedradar.data.WeatherRepository
import com.rf.airmedradar.data.fleet.HemsFleetDatabase
import com.rf.airmedradar.data.fleet.HemsProviderEntity
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
    /** The device's own live GPS fix — sourced from the Service (it already owns the single
     *  [com.rf.airmedradar.data.LocationRepository] instance backing the ADS-B scan center),
     *  republished here purely so the UI can snap its camera to it and bias Places search. */
    val deviceLocation: StateFlow<LatLng?> =
        snapshot.map { it.deviceLocation }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** HEMS operators identified live from the current poll's telemetry — see
     *  [com.rf.airmedradar.data.discoverHemsProviders]. Reflects "who's airborne right now,"
     *  not a fixed registry, so it can shrink back to empty between polls. */
    val discoveredProviders: StateFlow<List<DiscoveredHemsProvider>> =
        snapshot.map { it.discoveredProviders }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedAircraft = MutableStateFlow<Aircraft?>(null)
    val selectedAircraft: StateFlow<Aircraft?> = _selectedAircraft.asStateFlow()

    // --- Pre-flight provider selection: sourced from the Phase 9.7 Room registry (factory
    // baseline + anything the dispatcher has entered as a custom provider), not the live
    // per-poll ADS-B discovery above — [discoveredProviders] answers "who's actually airborne
    // right now," while this answers "which registered operator is dispatched to this LZ." ---

    private val hemsProviderDao = HemsFleetDatabase.getInstance(application).hemsProviderDao()

    /** Every known HEMS operator on file, factory-seeded or dispatcher-entered — reactive,
     *  since [HemsProviderDao.getAllProviders] is backed by a Room [kotlinx.coroutines.flow.Flow]. */
    val providers: StateFlow<List<HemsProviderEntity>> =
        hemsProviderDao.getAllProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The primary tracking state flag: the tail numbers the dispatcher confirmed as the
     *  actual responding unit from the pre-flight selection popup. Sourced straight from the
     *  Service's snapshot (not local ViewModel state) since the Service's poll loop is what
     *  actually applies the tail-lock filter — this is a read-only mirror of its state. */
    val activeWatchList: StateFlow<List<String>> =
        snapshot.map { it.activeWatchList }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether a provider has been dispatched and its tail numbers are actively being watched
     *  for in incoming telemetry — derived by the Service as `activeWatchList.isNotEmpty()`. */
    val isSearching: StateFlow<Boolean> =
        snapshot.map { it.isSearching }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether at least one tail-locked aircraft passed the Service's trigonometric
     *  trajectory validation gate this poll — see [AirMedTrackingService.applyTrajectoryGate]. */
    val isTargetLocked: StateFlow<Boolean> =
        snapshot.map { it.isTargetLocked }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Dispatcher confirmed [provider] as the responding unit from the selection popup: tells
     *  the Service to tail-lock its next poll onto exactly this fleet's registrations. */
    fun selectProvider(provider: HemsProviderEntity) {
        _boundService.value?.setActiveWatchList(provider.tailNumbers)
    }

    /**
     * Inserts a dispatcher-entered operator as a custom row (`isCustom = true`), distinguishing
     * it from the factory-seeded baseline. [providerName] and [tailNumbers] are expected
     * already validated/sanitized by the caller (see [com.rf.airmedradar.data.fleet.parseTailNumbers]
     * and the entry dialog's own blank-field checks) — this function does no validation of its
     * own, it just persists what it's given.
     */
    fun saveCustomProvider(providerName: String, tailNumbers: List<String>) {
        viewModelScope.launch {
            hemsProviderDao.insertProvider(
                HemsProviderEntity(providerName = providerName, tailNumbers = tailNumbers, isCustom = true),
            )
        }
    }

    /**
     * Bumped each time the user re-enters the app via the tracking notification, so the map
     * can reframe on the active LZ even when the target coordinate itself hasn't changed.
     */
    private val _lzFocusRequestId = MutableStateFlow(0L)
    val lzFocusRequestId: StateFlow<Long> = _lzFocusRequestId.asStateFlow()

    // --- HEMS weather minimums monitor: independent of the tracking Service/snapshot's
    // telemetry state, but scoped off the same [deviceLocation] GPS fix the Service publishes.
    // Polls on its own 15-minute cadence — weather doesn't change fast enough to warrant the
    // Service's much shorter aircraft-poll interval, and a failed fetch simply skips that tick,
    // leaving the last-known-good evaluation on screen rather than clearing it. ---

    private val weatherRepository = WeatherRepository()

    private val _weatherEvaluation = MutableStateFlow<WeatherMinimumsEvaluation?>(null)
    val weatherEvaluation: StateFlow<WeatherMinimumsEvaluation?> = _weatherEvaluation.asStateFlow()

    init {
        observeWeatherMinimums()
    }

    /**
     * Resolves the device fix to its nearest major METAR station and re-derives whenever that
     * *resolved station* changes — not on every GPS tick, which would otherwise flicker the
     * banner on ordinary position jitter even though the dispatcher hasn't gone anywhere
     * meaningful. `collectLatest` cancels whatever delay the previous station's loop was
     * mid-way through the moment the device crosses into a new station's catchment, clears the
     * now-stale evaluation so the banner disappears rather than showing the old area's numbers,
     * and immediately fetches the new station — no separate "force refresh" call needed.
     */
    private fun observeWeatherMinimums() {
        viewModelScope.launch {
            deviceLocation
                .filterNotNull()
                .map { weatherRepository.resolveNearestStationId(it.latitude, it.longitude) }
                .distinctUntilChanged()
                .collectLatest { stationId ->
                    _weatherEvaluation.value = null
                    while (isActive) {
                        weatherRepository.fetchLatestMetar(stationId)?.let { observation ->
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
                        val bias = deviceLocation.value ?: FALLBACK_OPERATIONAL_CENTER
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
        // Also clears the Service's tail-lock watch list — see AirMedTrackingService.clearTarget().
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
