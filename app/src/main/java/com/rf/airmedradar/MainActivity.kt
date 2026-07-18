package com.rf.airmedradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.ui.theme.AirMedRadarTheme
import com.rf.airmedradar.viewmodel.AirMedRadarViewModel
import com.rf.airmedradar.viewmodel.InterceptStatus
import com.rf.airmedradar.viewmodel.SimulationStatus
import kotlinx.coroutines.launch

private const val OPERATIONAL_LAT = 39.0
private const val OPERATIONAL_LON = -84.9
private const val OPERATIONAL_ZOOM = 9.5f
private const val CAMERA_ANIMATION_DURATION_MS = 800

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirMedRadarTheme {
                val viewModel: AirMedRadarViewModel = viewModel()
                RadarScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(viewModel: AirMedRadarViewModel, modifier: Modifier = Modifier) {
    val aircraft by viewModel.aircraft.collectAsStateWithLifecycle()
    val selectedAircraft by viewModel.selectedAircraft.collectAsStateWithLifecycle()
    val targetCoordinate by viewModel.targetCoordinate.collectAsStateWithLifecycle()
    val interceptStatus by viewModel.interceptStatus.collectAsStateWithLifecycle()
    val simulationStatus by viewModel.simulationStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val operationalCenter = remember { LatLng(OPERATIONAL_LAT, OPERATIONAL_LON) }

    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(operationalCenter, OPERATIONAL_ZOOM)
    }
    val mapProperties = remember(hasLocationPermission) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            mapStyleOptions = MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark),
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            zoomGesturesEnabled = true,
            scrollGesturesEnabled = true,
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
        )
    }

    // Frame both the operational center and the searched target once one is set.
    LaunchedEffect(targetCoordinate) {
        val target = targetCoordinate ?: return@LaunchedEffect
        val bounds = LatLngBounds.Builder()
            .include(operationalCenter)
            .include(target)
            .build()
        runCatching {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(bounds, 150),
                CAMERA_ANIMATION_DURATION_MS,
            )
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded),
    )
    // Expand the sheet to detail view whenever a marker is tapped; collapse when deselected.
    LaunchedEffect(selectedAircraft) {
        if (selectedAircraft != null) {
            scaffoldState.bottomSheetState.expand()
        } else {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val peekHeight = maxHeight * 0.08f
        val expandedHeight = maxHeight * 0.30f

        BottomSheetScaffold(
            sheetContent = {
                StatusSheetContent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(expandedHeight),
                    aircraftCount = aircraft.count { !it.isSimulated },
                    selectedAircraft = selectedAircraft,
                    interceptStatus = interceptStatus,
                    simulationStatus = simulationStatus,
                )
            },
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = mapProperties,
                    uiSettings = mapUiSettings,
                    onMapClick = { viewModel.selectAircraft(null) },
                ) {
                    aircraft.forEach { ac ->
                        key(ac.icao) {
                            AircraftMarker(
                                aircraft = ac,
                                isSelected = ac.icao == selectedAircraft?.icao,
                                onClick = { viewModel.selectAircraft(ac) },
                            )
                        }
                    }
                    targetCoordinate?.let { target ->
                        SceneLzMarker(position = target)
                    }
                }

                TargetSearchBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp),
                    hasActiveTarget = targetCoordinate != null,
                    onSearch = viewModel::searchTarget,
                    onClear = viewModel::clearTarget,
                )

                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.fromLatLngZoom(operationalCenter, OPERATIONAL_ZOOM),
                                ),
                                CAMERA_ANIMATION_DURATION_MS,
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Recenter on operational area")
                }
            }
        }
    }
}

@Composable
private fun AircraftMarker(aircraft: Aircraft, isSelected: Boolean, onClick: () -> Unit) {
    val lat = aircraft.lat ?: return
    val lon = aircraft.lon ?: return
    val position = LatLng(lat, lon)

    val markerState = rememberUpdatedMarkerState(position = position)

    val altitudeLabel = aircraft.altitudeFeet?.let { "$it ft" } ?: "Alt N/A"
    val speedLabel = aircraft.groundSpeedKts?.let { "${it.toInt()} kt" } ?: "GS N/A"
    val simulatedSuffix = if (aircraft.isSimulated) " • SIMULATED" else ""

    val hue = when {
        isSelected -> BitmapDescriptorFactory.HUE_YELLOW
        aircraft.isSimulated -> BitmapDescriptorFactory.HUE_VIOLET
        else -> BitmapDescriptorFactory.HUE_AZURE
    }

    Marker(
        state = markerState,
        // Rotates the icon to match true track over the ground.
        rotation = aircraft.track?.toFloat() ?: 0f,
        flat = true,
        anchor = Offset(0.5f, 0.5f),
        icon = BitmapDescriptorFactory.defaultMarker(hue),
        title = aircraft.displayName,
        snippet = "$altitudeLabel • $speedLabel$simulatedSuffix",
        onClick = {
            onClick()
            true
        },
    )
}

/** The searched intercept point: a highly visible amber landing-zone pin + reticle. */
@Composable
private fun SceneLzMarker(position: LatLng) {
    val markerState = rememberUpdatedMarkerState(position = position)
    LaunchedEffect(position) {
        // Auto-reveal the label instead of requiring a tap — this pin needs to read at a glance.
        markerState.showInfoWindow()
    }
    Marker(
        state = markerState,
        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
        title = "DESIGNATED SCENE LZ",
        snippet = "Intercept target",
        zIndex = 2f,
    )
    Circle(
        center = position,
        radius = 1200.0,
        strokeColor = Color(0xFFFFA000),
        strokeWidth = 5f,
        fillColor = Color(0x22FFA000),
    )
}

@Composable
private fun TargetSearchBar(
    modifier: Modifier = Modifier,
    hasActiveTarget: Boolean,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Address or intersection…") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSearch(query)
                        keyboardController?.hide()
                    },
                ),
            )
            if (hasActiveTarget || query.isNotEmpty()) {
                IconButton(
                    onClick = {
                        query = ""
                        onClear()
                        keyboardController?.hide()
                    },
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Clear target")
                }
            }
        }
    }
}

@Composable
private fun StatusSheetContent(
    modifier: Modifier = Modifier,
    aircraftCount: Int,
    selectedAircraft: Aircraft?,
    interceptStatus: InterceptStatus?,
    simulationStatus: SimulationStatus?,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "$aircraftCount aircraft tracked in region",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (simulationStatus != null) {
            Text(
                text = "MOCK911 inbound — ${"%.1f".format(simulationStatus.distanceNm)} nm • " +
                    "ETA ${formatEta(simulationStatus.etaSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFA000),
            )
        }
        Spacer(Modifier.height(16.dp))
        when {
            selectedAircraft != null -> AircraftDetailGrid(selectedAircraft)
            interceptStatus != null -> InterceptDetailLine(interceptStatus)
            else -> Text(
                text = "Tap an aircraft marker for details",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AircraftDetailGrid(aircraft: Aircraft) {
    Column {
        Text(
            text = aircraft.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatField(label = "Groundspeed", value = aircraft.groundSpeedKts?.let { "${it.toInt()} kt" } ?: "—")
            StatField(label = "Altitude", value = aircraft.altitudeFeet?.let { "$it ft" } ?: "—")
            StatField(label = "Heading", value = aircraft.track?.let { "${it.toInt()}°" } ?: "—")
        }
    }
}

@Composable
private fun StatField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InterceptDetailLine(status: InterceptStatus) {
    val trend = if (status.isClosing) "closing" else "opening"
    Text(
        text = "${status.aircraft.displayName} $trend on target — ${"%.1f".format(status.distanceNm)} nm",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun formatEta(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
