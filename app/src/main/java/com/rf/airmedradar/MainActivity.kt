package com.rf.airmedradar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.service.AirMedTrackingService
import com.rf.airmedradar.service.InterceptStatus
import com.rf.airmedradar.service.SimulationStatus
import com.rf.airmedradar.ui.theme.AirMedRadarTheme
import com.rf.airmedradar.util.formatEtaSeconds
import com.rf.airmedradar.viewmodel.AirMedRadarViewModel
import kotlinx.coroutines.launch

private const val OPERATIONAL_LAT = 39.0
private const val OPERATIONAL_LON = -84.9
private const val OPERATIONAL_ZOOM = 9.5f
private const val CAMERA_ANIMATION_DURATION_MS = 800
private const val METERS_PER_NAUTICAL_MILE = 1852.0

class MainActivity : ComponentActivity() {

    private val viewModel: AirMedRadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AirMedRadarTheme {
                RadarScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AirMedTrackingService.EXTRA_FOCUS_LZ, false) == true) {
            viewModel.requestLzFocus()
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(viewModel: AirMedRadarViewModel, modifier: Modifier = Modifier) {
    val aircraft by viewModel.aircraft.collectAsStateWithLifecycle()
    val selectedAircraft by viewModel.selectedAircraft.collectAsStateWithLifecycle()
    val targetCoordinate by viewModel.targetCoordinate.collectAsStateWithLifecycle()
    val interceptStatus by viewModel.interceptStatus.collectAsStateWithLifecycle()
    val simulationStatus by viewModel.simulationStatus.collectAsStateWithLifecycle()
    val hasLanded by viewModel.hasLanded.collectAsStateWithLifecycle()
    val lzFocusRequestId by viewModel.lzFocusRequestId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val addressSuggestions by viewModel.addressSuggestions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val operationalCenter = remember { LatLng(OPERATIONAL_LAT, OPERATIONAL_LON) }

    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
    }

    var hasNotificationPermission by remember { mutableStateOf(hasNotificationPermission(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    // Frame the operational center + target whenever the target changes, or when the user
    // re-enters the app via the tracking notification (lzFocusRequestId bump) even if the
    // target coordinate itself hasn't changed since they left.
    LaunchedEffect(targetCoordinate, lzFocusRequestId) {
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
                    hasLanded = hasLanded,
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
                            AircraftEtaLabel(aircraft = ac, targetCoordinate = targetCoordinate)
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
                    query = searchQuery,
                    suggestions = addressSuggestions,
                    onQueryChange = viewModel::onQueryChanged,
                    onSearch = viewModel::searchTarget,
                    onSuggestionClick = viewModel::onSuggestionSelected,
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
        rotation = aircraft.safeTrackDegrees,
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

/**
 * A small always-visible telemetry tag anchored just beneath the aircraft icon: the live
 * ETA to the active LZ when a target is set, otherwise groundspeed/altitude. Rendered as a
 * second marker sibling (not merged into the icon's own bitmap) specifically so it can stay
 * upright on screen — `flat = false` billboards it regardless of map bearing/tilt, whereas
 * the icon marker uses `flat = true` so its rotation tracks true heading correctly as the
 * map itself rotates. Baking both into one rotated bitmap would tilt the text unreadable
 * whenever the dispatcher rotates the map.
 */
@Composable
private fun AircraftEtaLabel(aircraft: Aircraft, targetCoordinate: LatLng?) {
    val lat = aircraft.lat ?: return
    val lon = aircraft.lon ?: return
    val position = LatLng(lat, lon)
    val markerState = rememberUpdatedMarkerState(position = position)

    // Aircraft is a data class, so any position/speed/track change from the 3s sim tick or
    // 12s network poll produces a new instance — Compose recomposes this label automatically.
    val labelText = aircraftLabelText(aircraft, targetCoordinate)

    MarkerComposable(
        state = markerState,
        anchor = Offset(0.5f, 0f),
        flat = false,
        zIndex = 1f,
    ) {
        Text(
            text = labelText,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 22.dp) // clears the icon graphic above before the pill starts
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private fun aircraftLabelText(aircraft: Aircraft, targetCoordinate: LatLng?): String {
    if (targetCoordinate != null) {
        val etaSeconds = etaSecondsToTarget(aircraft, targetCoordinate)
        return "ETA ${formatEtaSeconds(etaSeconds)}"
    }
    val speedText = aircraft.groundSpeedKts?.let { "${it.toInt()}KT" } ?: "—KT"
    val altText = aircraft.altitudeFeet?.let { feet ->
        if (feet >= 1000) "${"%.1f".format(feet / 1000.0)}K FT" else "$feet FT"
    } ?: "—FT"
    return "$speedText / $altText"
}

/** This aircraft's own distance-derived ETA to [target] — independent of the service's
 *  single "closest inbound" tracking, so every marker shows its individual countdown. */
private fun etaSecondsToTarget(aircraft: Aircraft, target: LatLng): Long {
    val lat = aircraft.lat ?: return -1L
    val lon = aircraft.lon ?: return -1L
    val speed = aircraft.safeGroundSpeedKts
    if (speed <= 1.0) return -1L
    val results = FloatArray(1)
    Location.distanceBetween(lat, lon, target.latitude, target.longitude, results)
    val distanceNm = results[0] / METERS_PER_NAUTICAL_MILE
    return (distanceNm / speed * 3_600).toLong()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSearchBar(
    modifier: Modifier = Modifier,
    query: String,
    suggestions: List<AutocompletePrediction>,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSuggestionClick: (AutocompletePrediction) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    SearchBar(
        modifier = modifier.widthIn(max = 420.dp),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    onSearch(query)
                    expanded = false
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                placeholder = { Text("Address or intersection…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onClear()
                                expanded = false
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear target")
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(suggestions, key = { it.placeId }) { prediction ->
                ListItem(
                    headlineContent = { Text(prediction.getPrimaryText(null).toString()) },
                    supportingContent = { Text(prediction.getSecondaryText(null).toString()) },
                    modifier = Modifier.clickable {
                        onSuggestionClick(prediction)
                        expanded = false
                    },
                )
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
    hasLanded: Boolean,
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
        when {
            hasLanded -> Text(
                text = "AIRCRAFT ON SCENE / LANDED",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF00C853),
            )
            simulationStatus != null -> Text(
                text = "MOCK911 inbound — ${"%.1f".format(simulationStatus.distanceNm)} nm • " +
                    "ETA ${formatEtaSeconds(simulationStatus.etaSeconds)}",
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
