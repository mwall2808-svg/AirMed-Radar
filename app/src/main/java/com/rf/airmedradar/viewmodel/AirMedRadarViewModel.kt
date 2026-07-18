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
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.service.AirMedTrackingService
import com.rf.airmedradar.service.InterceptStatus
import com.rf.airmedradar.service.SimulationStatus
import com.rf.airmedradar.service.TrackingSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    val simulationStatus: StateFlow<SimulationStatus?> =
        snapshot.map { it.simulationStatus }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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

    fun selectAircraft(aircraft: Aircraft?) {
        _selectedAircraft.value = aircraft
    }

    fun requestLzFocus() {
        _lzFocusRequestId.value += 1
    }

    fun searchTarget(addressQuery: String) {
        _boundService.value?.searchTarget(addressQuery)
    }

    fun clearTarget() {
        _boundService.value?.clearTarget()
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unbindService(connection) }
    }
}
