package com.hudhelmet.controller.ui.screens

import android.view.MotionEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hudhelmet.controller.model.RoutePoint
import com.hudhelmet.controller.ui.theme.*
import com.hudhelmet.controller.viewmodel.HudViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Map Navigation Screen — provides in-app route search, display, and active navigation.
 *
 * State 1: Route planning (search, pick points, calculate route)
 * State 2: Active navigation (minimap, speed, turn instructions)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapNavigationScreen(
    viewModel: HudViewModel,
    modifier: Modifier = Modifier
) {
    val isNavigating by viewModel.isNavigating.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val mapRoute by viewModel.mapRoute.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val destinationPoint by viewModel.destinationPoint.collectAsState()
    val destinationName by viewModel.destinationName.collectAsState()
    val isRouteLoading by viewModel.isRouteLoading.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val currentSpeed by viewModel.currentSpeed.collectAsState()
    val currentBearing by viewModel.currentBearing.collectAsState()
    val navUpdateInfo by viewModel.navUpdateInfo.collectAsState()
    val originPoint by viewModel.originPoint.collectAsState()

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchBarExpanded by remember { mutableStateOf(false) }
    var isPickingOnMap by remember { mutableStateOf(false) }
    var useMyLocationAsOrigin by remember { mutableStateOf(true) }

    // Start GPS when screen loads
    LaunchedEffect(Unit) {
        viewModel.startLocationUpdates()
    }

    // MapView reference
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (isNavigating) {
            // ═══════════════════════════════════════════════════
            // STATE 2: Active Navigation Mode
            // ═══════════════════════════════════════════════════
            ActiveNavigationView(
                viewModel = viewModel,
                mapViewRef = mapViewRef,
                onMapViewCreated = { mapViewRef = it },
                currentLocation = currentLocation,
                currentSpeed = currentSpeed,
                currentBearing = currentBearing,
                navUpdateInfo = navUpdateInfo,
                mapRoute = mapRoute,
                destinationPoint = destinationPoint,
                onStopNavigation = { viewModel.stopNavigation() }
            )
        } else {
            // ═══════════════════════════════════════════════════
            // STATE 1: Route Planning Mode
            // ═══════════════════════════════════════════════════
            RoutePlanningView(
                viewModel = viewModel,
                mapViewRef = mapViewRef,
                onMapViewCreated = { mapViewRef = it },
                currentLocation = currentLocation,
                mapRoute = mapRoute,
                destinationPoint = destinationPoint,
                originPoint = originPoint,
                searchQuery = searchQuery,
                onSearchQueryChange = { query ->
                    searchQuery = query
                    viewModel.searchPlaces(query)
                },
                searchResults = searchResults,
                isSearchBarExpanded = isSearchBarExpanded,
                onSearchBarExpandedChange = { isSearchBarExpanded = it },
                isSearching = isSearching,
                isRouteLoading = isRouteLoading,
                isPickingOnMap = isPickingOnMap,
                onPickingOnMapChange = { isPickingOnMap = it },
                useMyLocationAsOrigin = useMyLocationAsOrigin,
                onUseMyLocationChange = { useMyLocationAsOrigin = it },
                destinationName = destinationName,
                onStartNavigation = { viewModel.startNavigation() }
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════
// Route Planning View
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutePlanningView(
    viewModel: HudViewModel,
    mapViewRef: MapView?,
    onMapViewCreated: (MapView) -> Unit,
    currentLocation: RoutePoint?,
    mapRoute: com.hudhelmet.controller.model.RouteData?,
    destinationPoint: RoutePoint?,
    originPoint: RoutePoint?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchResults: List<com.hudhelmet.controller.model.PlaceSearchResult>,
    isSearchBarExpanded: Boolean,
    onSearchBarExpandedChange: (Boolean) -> Unit,
    isSearching: Boolean,
    isRouteLoading: Boolean,
    isPickingOnMap: Boolean,
    onPickingOnMapChange: (Boolean) -> Unit,
    useMyLocationAsOrigin: Boolean,
    onUseMyLocationChange: (Boolean) -> Unit,
    destinationName: String,
    onStartNavigation: () -> Unit
) {

    Box(modifier = Modifier.fillMaxSize()) {
        // Map
        OsmMapView(
            mapViewRef = mapViewRef,
            onMapViewCreated = onMapViewCreated,
            currentLocation = currentLocation,
            routePoints = mapRoute?.points,
            destinationPoint = destinationPoint,
            originPoint = originPoint,
            isPickingOnMap = isPickingOnMap,
            onMapTapped = { lat, lon ->
                if (isPickingOnMap) {
                    viewModel.setDestination(RoutePoint(lat, lon), "Điểm đã chọn")
                    if (useMyLocationAsOrigin) {
                        viewModel.setOriginToMyLocation()
                    }
                    onPickingOnMapChange(false)
                }
            }
        )

        // Top search panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 12.dp, end = 12.dp)
        ) {
            // Search Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.95f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Origin row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = HudGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (useMyLocationAsOrigin) "Vị trí của tôi" else "Chọn điểm đi...",
                            color = if (useMyLocationAsOrigin) HudGreen else TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onUseMyLocationChange(!useMyLocationAsOrigin) }
                        )
                        if (!useMyLocationAsOrigin) {
                            IconButton(
                                onClick = { onUseMyLocationChange(true) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.GpsFixed,
                                    contentDescription = "Vị trí của tôi",
                                    tint = HudCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Divider(
                        color = DarkCardBorder,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    // Destination row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = HudRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        if (destinationPoint != null && !isSearchBarExpanded) {
                            Text(
                                text = destinationName.ifEmpty { "Điểm đến" },
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSearchBarExpandedChange(true) }
                            )
                            IconButton(
                                onClick = {
                                    viewModel.clearRoute()
                                    onSearchQueryChange("")
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Xóa",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { query ->
                                    onSearchQueryChange(query)
                                    onSearchBarExpandedChange(true)
                                },
                                placeholder = {
                                    Text("Tìm điểm đến...", color = TextTertiary)
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = HudCyan
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Pick on map button
                        IconButton(
                            onClick = {
                                onPickingOnMapChange(!isPickingOnMap)
                                onSearchBarExpandedChange(false)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isPickingOnMap) Icons.Default.TouchApp else Icons.Default.PinDrop,
                                contentDescription = "Chọn trên bản đồ",
                                tint = if (isPickingOnMap) HudMagenta else HudCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Search results dropdown
            AnimatedVisibility(
                visible = isSearchBarExpanded && (searchResults.isNotEmpty() || isSearching),
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(max = 300.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.95f))
                ) {
                    if (isSearching) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = HudCyan,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.padding(4.dp)) {
                            items(searchResults) { place ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.setDestination(
                                                RoutePoint(place.lat, place.lon),
                                                place.displayName.split(",").firstOrNull()
                                                    ?: place.displayName
                                            )
                                            if (useMyLocationAsOrigin) {
                                                viewModel.setOriginToMyLocation()
                                            }
                                            onSearchBarExpandedChange(false)
                                            onSearchQueryChange("")
                                            viewModel.clearSearchResults()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = place.displayName.split(",").firstOrNull()
                                                ?: place.displayName,
                                            color = TextPrimary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = place.displayName,
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (searchResults.last() != place) {
                                    Divider(color = DarkCardBorder.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pick on map instruction
        if (isPickingOnMap) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 140.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = HudMagenta)
            ) {
                Text(
                    text = "📍 Chạm vào bản đồ để chọn điểm đến",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Route loading indicator
        if (isRouteLoading) {
            Card(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.9f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = HudCyan,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Đang tính tuyến đường...", color = TextPrimary)
                }
            }
        }

        // Bottom route info + start button
        AnimatedVisibility(
            visible = mapRoute != null && !isPickingOnMap,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            mapRoute?.let { route ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .shadow(12.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.95f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = destinationName.ifEmpty { "Điểm đến" },
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        text = formatDistance(route.totalDistance),
                                        color = HudCyan,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = " · ",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = formatTime(route.totalTime),
                                        color = HudGreen,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Start navigation button
                        Button(
                            onClick = onStartNavigation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HudCyan,
                                contentColor = DarkBackground
                            )
                        ) {
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Bắt Đầu",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // My Location button (bottom right)
        FloatingActionButton(
            onClick = {
                currentLocation?.let { loc ->
                    mapViewRef?.controller?.animateTo(GeoPoint(loc.lat, loc.lon))
                    mapViewRef?.controller?.setZoom(17.0)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 12.dp,
                    bottom = if (mapRoute != null) 180.dp else 12.dp
                ),
            shape = CircleShape,
            containerColor = DarkCard.copy(alpha = 0.9f),
            contentColor = HudCyan
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Vị trí của tôi")
        }
    }
}


// ═══════════════════════════════════════════════════════════
// Active Navigation View
// ═══════════════════════════════════════════════════════════

@Composable
private fun ActiveNavigationView(
    @Suppress("UNUSED_PARAMETER") viewModel: HudViewModel,
    mapViewRef: MapView?,
    onMapViewCreated: (MapView) -> Unit,
    currentLocation: RoutePoint?,
    currentSpeed: Float,
    currentBearing: Float,
    navUpdateInfo: com.hudhelmet.controller.service.NavigationEngine.NavigationUpdate?,
    mapRoute: com.hudhelmet.controller.model.RouteData?,
    destinationPoint: RoutePoint?,
    onStopNavigation: () -> Unit
) {
    var isAutoCentered by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map centered on user
        OsmMapView(
            mapViewRef = mapViewRef,
            onMapViewCreated = onMapViewCreated,
            currentLocation = currentLocation,
            routePoints = mapRoute?.points,
            destinationPoint = destinationPoint,
            originPoint = null,
            isPickingOnMap = false,
            onMapTapped = { _, _ -> },
            isNavigationMode = true,
            bearing = currentBearing,
            isAutoCentered = isAutoCentered,
            onMapTouched = { isAutoCentered = false }
        )

        // Top overlay: speed + street name + turn instruction
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            // Speed & street name bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DarkCard.copy(alpha = 0.92f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speed display
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        HudCyan.copy(alpha = 0.3f),
                                        DarkBackground
                                    )
                                )
                            )
                            .border(2.dp, HudCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${currentSpeed.toInt()}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                lineHeight = 24.sp
                            )
                            Text(
                                text = "km/h",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                lineHeight = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Street name & navigation info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = navUpdateInfo?.currentStreetName ?: "Đang cập nhật...",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (navUpdateInfo != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row {
                                Text(
                                    text = formatDistance(navUpdateInfo.distanceRemaining),
                                    color = HudCyan,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = " · ",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = navUpdateInfo.navData.timeLeft,
                                    color = HudGreen,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = " · ETA ",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = navUpdateInfo.navData.eta,
                                    color = HudYellow,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Turn instruction card
            if (navUpdateInfo != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .shadow(6.dp, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (navUpdateInfo.navData.turnType) {
                            2 -> Color(0xFF1565C0).copy(alpha = 0.92f) // Left - blue
                            3 -> Color(0xFF2E7D32).copy(alpha = 0.92f) // Right - green
                            4 -> Color(0xFFC62828).copy(alpha = 0.92f) // U-turn - red
                            else -> DarkSurfaceVariant.copy(alpha = 0.92f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Turn arrow
                        Icon(
                            imageVector = when (navUpdateInfo.navData.turnType) {
                                2 -> Icons.Default.TurnLeft
                                3 -> Icons.Default.TurnRight
                                4 -> Icons.Default.UTurnLeft
                                else -> Icons.Default.Straight
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = navUpdateInfo.navData.instruction,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Distance to turn
                        Text(
                            text = formatDistance(navUpdateInfo.distanceToNextTurn),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
            }
        }

        // Stop button (bottom left)
        FloatingActionButton(
            onClick = onStopNavigation,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
            shape = CircleShape,
            containerColor = HudRed,
            contentColor = Color.White
        ) {
            Icon(Icons.Default.Close, contentDescription = "Dừng dẫn đường")
        }

        // Re-center button (bottom center pill, only when not auto-centered)
        AnimatedVisibility(
            visible = !isAutoCentered,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Button(
                onClick = {
                    isAutoCentered = true
                    currentLocation?.let { loc ->
                        mapViewRef?.let { map ->
                            map.controller.setZoom(19.0)
                            val offsetY = if (map.height > 0) (map.height * 0.25).toInt() else 0
                            map.setMapCenterOffset(0, offsetY)
                            map.controller.animateTo(GeoPoint(loc.lat, loc.lon))
                            map.mapOrientation = -currentBearing
                            map.invalidate()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = HudCyan,
                    contentColor = DarkBackground
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = DarkBackground
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Về giữa",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════
// OSMDroid Map Compose Wrapper
// ═══════════════════════════════════════════════════════════

@Composable
private fun OsmMapView(
    @Suppress("UNUSED_PARAMETER") mapViewRef: MapView?,
    onMapViewCreated: (MapView) -> Unit,
    currentLocation: RoutePoint?,
    routePoints: List<RoutePoint>?,
    destinationPoint: RoutePoint?,
    originPoint: RoutePoint?,
    isPickingOnMap: Boolean,
    onMapTapped: (Double, Double) -> Unit,
    isNavigationMode: Boolean = false,
    bearing: Float = 0f,
    isAutoCentered: Boolean = true,
    onMapTouched: () -> Unit = {}
) {
    val currentOnMapTouched by rememberUpdatedState(onMapTouched)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                // Default center (Hanoi)
                val defaultCenter = GeoPoint(21.0285, 105.8542)
                controller.setCenter(defaultCenter)

                // My location overlay
                val myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                myLocationOverlay.enableMyLocation()
                overlays.add(myLocationOverlay)

                // Detect user interaction
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                        currentOnMapTouched()
                    }
                    false
                }

                onMapViewCreated(this)
            }
        },
        update = { mapView ->
            // Handle tap-to-pick
            if (isPickingOnMap) {
                mapView.overlays.removeAll { it is MapTapOverlay }
                val tapOverlay = MapTapOverlay { lat, lon ->
                    onMapTapped(lat, lon)
                }
                mapView.overlays.add(tapOverlay)
            } else {
                mapView.overlays.removeAll { it is MapTapOverlay }
            }

            // Clear old route & marker overlays (keep MyLocationOverlay)
            mapView.overlays.removeAll { it is Polyline || it is Marker }

            // Draw route polyline
            if (!routePoints.isNullOrEmpty()) {
                val polyline = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.rgb(66, 133, 244) // Google blue
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.isAntiAlias = true
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    setPoints(routePoints.map { GeoPoint(it.lat, it.lon) })
                }
                mapView.overlays.add(polyline)
            }

            // Origin marker
            if (originPoint != null && !isNavigationMode) {
                val originMarker = Marker(mapView).apply {
                    position = GeoPoint(originPoint.lat, originPoint.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Điểm đi"
                }
                mapView.overlays.add(originMarker)
            }

            // Destination marker
            if (destinationPoint != null) {
                val destMarker = Marker(mapView).apply {
                    position = GeoPoint(destinationPoint.lat, destinationPoint.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Điểm đến"
                }
                mapView.overlays.add(destMarker)
            }

            // In navigation mode: center & rotate map
            if (isNavigationMode && currentLocation != null) {
                if (isAutoCentered) {
                    mapView.controller.setZoom(19.0)
                    val offsetY = if (mapView.height > 0) (mapView.height * 0.25).toInt() else 0
                    mapView.setMapCenterOffset(0, offsetY)
                    mapView.controller.animateTo(GeoPoint(currentLocation.lat, currentLocation.lon))
                    mapView.mapOrientation = -bearing
                } else {
                    mapView.setMapCenterOffset(0, 0)
                }
            } else if (currentLocation != null && routePoints.isNullOrEmpty() && destinationPoint == null) {
                // Center on user if no route
                mapView.setMapCenterOffset(0, 0)
                mapView.controller.animateTo(GeoPoint(currentLocation.lat, currentLocation.lon))
            } else {
                mapView.setMapCenterOffset(0, 0)
            }

            // Zoom to fit route
            if (!routePoints.isNullOrEmpty() && !isNavigationMode && destinationPoint != null) {
                try {
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(
                        routePoints.map { GeoPoint(it.lat, it.lon) }
                    )
                    mapView.zoomToBoundingBox(boundingBox.increaseByScale(1.3f), true)
                } catch (_: Exception) { }
            }

            mapView.invalidate()
        }
    )
}


/**
 * Overlay that captures map taps for point picking.
 */
private class MapTapOverlay(
    private val onTap: (Double, Double) -> Unit
) : org.osmdroid.views.overlay.Overlay() {

    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
        if (e != null && mapView != null) {
            val proj = mapView.projection
            val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            onTap(geoPoint.latitude, geoPoint.longitude)
            return true
        }
        return false
    }
}


// ═══════════════════════════════════════════════════════════
// Formatting helpers
// ═══════════════════════════════════════════════════════════

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format("%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}

private fun formatTime(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds} giây"
        seconds < 3600 -> "${seconds / 60} phút"
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            "${h} giờ ${m} phút"
        }
    }
}
