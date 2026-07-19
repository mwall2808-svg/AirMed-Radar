package com.rf.airmedradar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.rf.airmedradar.data.Aircraft
import com.rf.airmedradar.service.AirMedTrackingService
import com.rf.airmedradar.service.InterceptStatus
import com.rf.airmedradar.ui.theme.AirMedRadarTheme
import com.rf.airmedradar.util.formatEtaSeconds
import com.rf.airmedradar.viewmodel.AirMedRadarViewModel
import com.rf.airmedradar.weather.FlightStatus
import com.rf.airmedradar.weather.WeatherMinimumsEvaluation
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
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val weatherEvaluation by viewModel.weatherEvaluation.collectAsStateWithLifecycle()
    val selectedAircraft by viewModel.selectedAircraft.collectAsStateWithLifecycle()
    val targetCoordinate by viewModel.targetCoordinate.collectAsStateWithLifecycle()
    val interceptStatus by viewModel.interceptStatus.collectAsStateWithLifecycle()
    val hasLanded by viewModel.hasLanded.collectAsStateWithLifecycle()
    val lzFocusRequestId by viewModel.lzFocusRequestId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val addressSuggestions by viewModel.addressSuggestions.collectAsStateWithLifecycle()
    val deviceLocation by viewModel.deviceLocation.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Falls back to the legacy Cincinnati constants only for the brief window before the very
    // first GPS fix (cached or live) arrives on a cold start — once it does, this always
    // reflects the device's own live position instead of any fixed coordinate.
    val operationalCenter = remember(deviceLocation) {
        deviceLocation ?: LatLng(OPERATIONAL_LAT, OPERATIONAL_LON)
    }

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

    // The one aircraft the breadcrumb trail + destination projection line are drawn for:
    // the system-identified closest inbound responder (interceptStatus), NOT selectedAircraft
    // (a marker tap is just "show me details" and shouldn't spawn lines for an arbitrary
    // aircraft). Null whenever there's no active target or the responder has already landed,
    // so the lines disappear the instant the search is cleared or the aircraft arrives.
    val activeTargetAircraft = if (targetCoordinate != null && !hasLanded) interceptStatus?.aircraft else null

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

    // Snap the camera to the device's own position the moment a fresh GPS lock is established
    // — but only that once. `hasSnappedToLocation` (not `deviceLocation` itself) is the
    // LaunchedEffect key so ordinary high-accuracy refinement afterward updates
    // `operationalCenter`/the recenter FAB target without repeatedly yanking the camera every
    // ~30s while the dispatcher is trying to look at something else on the map.
    var hasSnappedToLocation by remember { mutableStateOf(false) }
    LaunchedEffect(hasSnappedToLocation, deviceLocation) {
        if (hasSnappedToLocation) return@LaunchedEffect
        val location = deviceLocation ?: return@LaunchedEffect
        hasSnappedToLocation = true
        runCatching {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(location, OPERATIONAL_ZOOM),
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
                    aircraftCount = aircraft.size,
                    selectedAircraft = selectedAircraft,
                    interceptStatus = interceptStatus,
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
                            // Strict gate: only the confirmed responding aircraft gets a
                            // breadcrumb trail or a projection line. Every other aircraft —
                            // including one the dispatcher merely tapped for details — renders
                            // as a clean icon with no trailing paths.
                            if (ac.icao == activeTargetAircraft?.icao) {
                                AircraftHistoryTrail(aircraft = ac)
                                targetCoordinate?.let { target ->
                                    DestinationProjectionLine(aircraft = ac, target = target)
                                }
                            }
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

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Pushes the banner stack (and everything below it, including the
                        // search bar) below the status bar/camera cutout — GoogleMap is a
                        // separate sibling above in this Box and is untouched, so the map
                        // itself keeps rendering fullscreen behind the status bar exactly as
                        // enableEdgeToEdge() intends. Only this overlay column needs to avoid
                        // that space.
                        .statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // HEMS weather minimums banner: the highest-priority safety signal on
                    // screen, so it stacks above the feed-staleness warning. Absent (no
                    // AnimatedVisibility trigger) until the first METAR fetch resolves —
                    // there's no "unknown" visual state, it simply isn't shown yet.
                    AnimatedVisibility(
                        visible = weatherEvaluation != null,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    ) {
                        weatherEvaluation?.let { FlightStatusBanner(it) }
                    }
                    // Feed-staleness warning: purely a UI reflection of the ViewModel's
                    // isOffline StateFlow (already plumbed end-to-end since Phase 6's network
                    // resilience work — the Service was setting this correctly the whole time,
                    // it just had no on-screen consumer). Slides in the instant a poll fails
                    // and back out the instant one succeeds, with zero effect on the map/marker
                    // layers underneath: they keep rendering whatever aircraft/history state
                    // they last held, since the Service never clears state on a failed poll.
                    AnimatedVisibility(
                        visible = isOffline,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    ) {
                        OfflineStatusBanner()
                    }
                    TargetSearchBar(
                        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                        query = searchQuery,
                        suggestions = addressSuggestions,
                        onQueryChange = viewModel::onQueryChanged,
                        onSearch = viewModel::searchTarget,
                        onSuggestionClick = viewModel::onSuggestionSelected,
                        onClear = viewModel::clearTarget,
                    )
                }

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

/**
 * Thin, high-contrast warning that the telemetry feed has gone stale. Deliberately opaque
 * and full-width (not a translucent overlay) so it reads instantly against any map terrain,
 * but confined to a single line so it never blocks marker taps, the search bar, or the FAB.
 */
@Composable
private fun OfflineStatusBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFFFA000), // dark amber
    ) {
        Text(
            text = "⚠️ RADAR FEED OFFLINE — SHOWING LAST KNOWN POSITIONS",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * FAA Part 135 HEMS weather-minimums status, styled identically to [OfflineStatusBanner]
 * (opaque, full-width, single line) so the two stack cleanly, but color-coded per
 * [FlightStatus] rather than a fixed amber — this banner's color *is* the go/no-go signal.
 */
@Composable
private fun FlightStatusBanner(evaluation: WeatherMinimumsEvaluation, modifier: Modifier = Modifier) {
    val ceilingText = evaluation.ceilingFtAgl?.let { "$it ft" } ?: "N/A"
    val visibilityText = evaluation.visibilitySm?.let { "%.1f SM".format(it) } ?: "N/A"

    val (backgroundColor, textColor, message) = when (evaluation.status) {
        FlightStatus.GREEN -> Triple(
            Color(0xFF1B5E20), // soft dark green
            Color.White,
            "🟢 FLIGHT RADAR OPEN - WEATHER GOOD TO FLY",
        )
        FlightStatus.YELLOW -> Triple(
            Color(0xFFFF8F00), // deep amber
            Color.Black,
            "⚠️ MARGINAL CONDITIONS - FLIGHT STATUS QUESTIONABLE (Ceiling: $ceilingText, Vis: $visibilityText)",
        )
        FlightStatus.RED -> Triple(
            Color(0xFFB71C1C), // sharp crimson
            Color.White,
            "🛑 NO GO FOR FLIGHT - WEATHER BELOW HEMS MINIMUMS (Ceiling: $ceilingText, Vis: $visibilityText)",
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private val AIRCRAFT_ICON_COLOR = Color(0xFF2979FF) // azure
private val AIRCRAFT_ICON_COLOR_SELECTED = Color(0xFFFFD600) // yellow
private val AIRCRAFT_ICON_SIZE = 40.dp

@Composable
private fun AircraftMarker(aircraft: Aircraft, isSelected: Boolean, onClick: () -> Unit) {
    val position = aircraft.currentCoordinates ?: return
    val markerState = rememberUpdatedMarkerState(position = position)

    val altitudeLabel = aircraft.altitudeFeet?.let { "$it ft" } ?: "Alt N/A"
    val speedLabel = aircraft.groundSpeedKts?.let { "${it.toInt()} kt" } ?: "GS N/A"

    val tint = if (isSelected) AIRCRAFT_ICON_COLOR_SELECTED else AIRCRAFT_ICON_COLOR
    val icon = rememberHelicopterBitmapDescriptor(tint = tint)

    Marker(
        state = markerState,
        // The Maps SDK rotates the icon bitmap around `anchor` natively and efficiently —
        // there is no need to (and we must not) also pre-rotate the bitmap ourselves via a
        // Canvas Matrix transform, or the icon would be rotated twice.
        rotation = aircraft.safeTrackDegrees,
        // `flat = true` pins the icon to the map plane so its rotation tracks true heading
        // as the map itself is panned/rotated/tilted, instead of always facing the camera.
        flat = true,
        // Centers the rotation axis on the icon's physical center rather than the default
        // bottom-center pin-tip — required for a vehicle icon, or it swings like a weather
        // vane around a point well below its actual body.
        anchor = Offset(0.5f, 0.5f),
        icon = icon,
        title = aircraft.displayName,
        snippet = "$altitudeLabel • $speedLabel",
        onClick = {
            onClick()
            true
        },
    )
}

/**
 * Converts `ic_helicopter` into a tinted [BitmapDescriptor] sized for the map, generated via
 * an off-screen [Canvas] rather than [BitmapDescriptorFactory.fromResource] — the latter
 * doesn't reliably respect a vector drawable's intended density scaling on every API level,
 * which is the actual cause of the icon-quality issue this replaces. Result is memoized per
 * [tint]: there are only ever two distinct tints in practice (selected / not), so this runs
 * at most twice per composition, never on every telemetry tick or recomposition.
 */
@Composable
private fun rememberHelicopterBitmapDescriptor(
    tint: Color,
    sizeDp: Dp = AIRCRAFT_ICON_SIZE,
): BitmapDescriptor {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(tint, sizeDp) {
        val sizePx = with(density) { sizeDp.roundToPx() }.coerceAtLeast(1)
        vectorDrawableToBitmapDescriptor(context, R.drawable.ic_helicopter, tint.toArgb(), sizePx)
    }
}

private fun vectorDrawableToBitmapDescriptor(
    context: Context,
    drawableRes: Int,
    tintColor: Int,
    sizePx: Int,
): BitmapDescriptor {
    val source = requireNotNull(ContextCompat.getDrawable(context, drawableRes)) {
        "Missing drawable resource $drawableRes"
    }
    val drawable = DrawableCompat.wrap(source).mutate()
    DrawableCompat.setTint(drawable, tintColor)
    drawable.setBounds(0, 0, sizePx, sizePx)

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.draw(Canvas(bitmap))

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Historical position trail: renders once the aircraft has at least two recorded points. */
@Composable
private fun AircraftHistoryTrail(aircraft: Aircraft) {
    if (aircraft.historyPoints.size < 2) return
    Polyline(
        points = aircraft.historyPoints,
        color = Color(0xFFFFC107), // amber — breadcrumb trail
        width = 4f,
    )
}

/**
 * A dashed straight-line projection from the aircraft's current position to the active LZ —
 * visually distinct (bright cyan, dashed) from the solid amber breadcrumb trail so the
 * "where it's been" and "where it's headed" lines are never confused at a glance.
 */
@Composable
private fun DestinationProjectionLine(aircraft: Aircraft, target: LatLng) {
    val current = aircraft.currentCoordinates ?: return
    Polyline(
        points = listOf(current, target),
        color = Color(0xFF00E5FF), // bright cyan
        width = 5f,
        pattern = listOf(Dash(30f), Gap(20f)),
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
    val position = aircraft.currentCoordinates ?: return

    // Aircraft is a data class, so any position/speed/track change from the 3s sim tick or
    // 12s network poll produces a new instance, recomposing this function with fresh text —
    // but MarkerComposable bakes its content into a bitmap once and doesn't reliably observe
    // later content-only recompositions. Keying on the text forces the marker's composition
    // slot to be torn down and recreated whenever it changes, guaranteeing a fresh bake.
    val labelText = aircraftLabelText(aircraft, targetCoordinate)

    key(labelText) {
        val markerState = rememberUpdatedMarkerState(position = position)
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
    val current = aircraft.currentCoordinates ?: return -1L
    val speed = aircraft.safeGroundSpeedKts
    if (speed <= 1.0) return -1L
    val results = FloatArray(1)
    Location.distanceBetween(current.latitude, current.longitude, target.latitude, target.longitude, results)
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
        if (hasLanded) {
            Text(
                text = "AIRCRAFT ON SCENE / LANDED",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF00C853),
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
