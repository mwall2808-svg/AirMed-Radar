package com.rf.airmedradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.ui.theme.AirMedRadarTheme
import com.rf.airmedradar.viewmodel.AirMedRadarViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirMedRadarTheme {
                val viewModel: AirMedRadarViewModel = viewModel()
                val aircraft by viewModel.aircraft.collectAsStateWithLifecycle()
                RadarMap(
                    aircraft = aircraft,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun RadarMap(aircraft: List<Aircraft>, modifier: Modifier = Modifier) {
    // Operational coverage center: Southeastern Indiana / Cincinnati region
    val operationalCenter = LatLng(39.0, -84.9)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(operationalCenter, 9.5f)
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
    ) {
        aircraft.forEach { ac ->
            key(ac.icao) {
                AircraftMarker(ac)
            }
        }
    }
}

@Composable
private fun AircraftMarker(aircraft: Aircraft) {
    val lat = aircraft.lat ?: return
    val lon = aircraft.lon ?: return
    val position = LatLng(lat, lon)

    val markerState = rememberUpdatedMarkerState(position = position)

    val altitudeLabel = aircraft.altitudeFeet?.let { "$it ft" } ?: "Alt N/A"
    val speedLabel = aircraft.groundSpeedKts?.let { "${it.toInt()} kt" } ?: "GS N/A"

    Marker(
        state = markerState,
        // Rotates the icon to match true track over the ground.
        rotation = aircraft.track?.toFloat() ?: 0f,
        flat = true,
        anchor = Offset(0.5f, 0.5f),
        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
        title = aircraft.displayName,
        snippet = "$altitudeLabel • $speedLabel",
    )
}
