package com.taxipro.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.Ride
import com.taxipro.data.db.Zone
import com.taxipro.data.db.formatPrice
import com.taxipro.data.db.rideRouteLabels
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryScreen(vm: RideViewModel) {
    val tc           = LocalThemeColors.current
    val allRidesOrNull by vm.allRides.collectAsState(initial = null)
    val allZones     by vm.allZones.collectAsState(initial = emptyList())
    val st           = LocalStrings.current
    val allRides     = allRidesOrNull ?: emptyList()
    val isLoading    = allRidesOrNull == null

    val nowMs        = remember { System.currentTimeMillis() }
    val initWeek     = remember { pfCurrentWeekRange() }
    var filterFromMs by remember { mutableStateOf<Long?>(initWeek.first) }
    var filterToMs   by remember { mutableStateOf<Long?>(initWeek.second) }

    val displayed = if (filterFromMs != null && filterToMs != null)
        allRides.filter { it.startTime in filterFromMs!!..filterToMs!! }
    else
        allRides

    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(st.history.historyTitle, color = tc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // ── Period filter ────────────────────────────────────
        PeriodFilterRow(onRangeChanged = { f, t -> filterFromMs = f; filterToMs = t })

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text("${displayed.size} ${st.ridesLabel}", color = tc.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))

        // ── Ride list ────────────────────────────────────────
        var expandedId    by remember { mutableStateOf<Long?>(null) }
        var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
        var editRouteRide by remember { mutableStateOf<Ride?>(null) }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = tc.accent)
                    Text(st.loadingLabel, color = tc.muted, fontSize = 13.sp)
                }
            }
        } else if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(st.history.noRides, color = tc.muted, fontSize = 14.sp)
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayed.forEach { ride ->
                    SwipeToDeleteBox(
                        onDeleteRequest = { pendingDelete = { vm.deleteRide(ride) } }
                    ) {
                        HistoryRideCard(
                            ride        = ride,
                            zones       = allZones,
                            isExpanded  = expandedId == ride.id,
                            onToggle    = { expandedId = if (expandedId == ride.id) null else ride.id },
                            onDelete    = { pendingDelete = { vm.deleteRide(ride) } },
                            onEditRoute = { editRouteRide = ride },
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        pendingDelete?.let { action ->
            DeleteConfirmDialog(
                message   = st.history.deleteRideConfirmMsg,
                onConfirm = { action(); pendingDelete = null },
                onDismiss = { pendingDelete = null },
            )
        }

        editRouteRide?.let { ride ->
            EditRouteDialog(
                ride      = ride,
                vm        = vm,
                onDismiss = { editRouteRide = null },
            )
        }
    }

}

@Composable
private fun HistoryRideCard(
    ride: Ride,
    zones: List<Zone>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEditRoute: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdfDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    val (zoneLabel, addrLabel) = remember(ride.id, zones) { rideRouteLabels(ride, zones, st.zones.outsideZones) }
    val mainLabel = addrLabel.ifEmpty { "${st.ridePrefix}${ride.globalId}" }

    // Parse stored route points once
    val routePoints = remember(ride.id) { parseRoutePoints(ride.routePointsJson) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            // ── Header row (always visible, tappable) ─────────
            Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap       = { onToggle() },
                            onLongPress = { showMenu = true },
                        )
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(tc.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("#${ride.globalId}", color = tc.accent,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    if (zoneLabel.isNotEmpty()) {
                        Text(
                            zoneLabel,
                            color = tc.accent, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        mainLabel,
                        color = tc.textPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${sdfDate.format(Date(ride.startTime))}  " +
                        "${sdfTime.format(Date(ride.startTime))} – ${sdfTime.format(Date(ride.endTime))}",
                        color = tc.muted, fontSize = 11.sp
                    )
                    Text(
                        "%.1f ${settings.distanceUnit.shortLabel}  •  %.0f ${st.history.waitSuffix}".format(ride.kilometers, ride.waitMinutes),
                        color = tc.muted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    if (ride.tip > 0) {
                        // fare
                        Text(
                            settings.formatPrice(ride.price),
                            color = tc.muted, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        // tip
                        Text(
                            "+${settings.formatPrice(ride.tip)} ${st.tipBadgeShort}",
                            color = tc.purple, fontSize = 11.sp
                        )
                        // total
                        Text(
                            settings.formatPrice(ride.price + ride.tip),
                            color = tc.accent, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            settings.formatPrice(ride.price),
                            color = tc.accent, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    }
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = tc.muted, modifier = Modifier.size(18.dp)
                    )
                }
            }
            DropdownMenu(
                expanded         = showMenu,
                onDismissRequest = { showMenu = false },
                modifier         = Modifier.background(tc.cardAlt),
            ) {
                DropdownMenuItem(
                    text        = { Text(st.history.editRouteLabel, color = tc.accent) },
                    leadingIcon = { Icon(Icons.Default.Directions, null, tint = tc.accent) },
                    onClick     = { showMenu = false; onEditRoute() },
                )
                HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                DropdownMenuItem(
                    text        = { Text(st.history.deleteLabel, color = tc.red) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = tc.red) },
                    onClick     = { showMenu = false; onDelete() },
                )
            }
            } // close Box

            // ── Expandable route map ───────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = tc.muted.copy(alpha = 0.15f))
                    if (routePoints.size >= 2) {
                        RouteMapSection(routePoints, st.history.startMarker, st.history.endMarker)
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(st.history.noRoute,
                                color = tc.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Inline map for a single ride route ──────────────────────
@Composable
private fun RouteMapSection(points: List<LatLng>, startTitle: String, endTitle: String) {
    val tc            = LocalThemeColors.current
    val boundsBuilder = remember(points) {
        LatLngBounds.Builder().also { b -> points.forEach { b.include(it) } }
    }
    val bounds = remember(points) { boundsBuilder.build() }

    val camState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 13f)
    }

    LaunchedEffect(points) {
        camState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 60), durationMs = 500)
    }

    val startMarkerState = remember(points) { MarkerState(position = points.first()) }
    val endMarkerState = remember(points) { MarkerState(position = points.last()) }

    GoogleMap(
        modifier            = Modifier
            .fillMaxWidth()
            .height(220.dp),
        cameraPositionState = camState,
        uiSettings          = MapUiSettings(
            zoomControlsEnabled     = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled       = false,
        ),
    ) {
        Polyline(
            points = points,
            color  = tc.accent,
            width  = 14f,
        )
        Marker(
            state  = startMarkerState,
            title  = startTitle,
            icon   = createLabeledMarker(android.graphics.Color.parseColor("#4CAF50"), "S"),
            zIndex = 2f,
        )
        Marker(
            state  = endMarkerState,
            title  = endTitle,
            icon   = createLabeledMarker(android.graphics.Color.parseColor("#F44336"), "E"),
            zIndex = 2f,
        )
    }
}

// ── Swipe-to-delete container ────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteBox(
    onDeleteRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tc = LocalThemeColors.current
    val state = rememberSwipeToDismissBoxState(
        // Always return false → item snaps back; onDeleteRequest shows the confirm dialog
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
            false
        },
        positionalThreshold = { it * 0.35f },
    )

    SwipeToDismissBox(
        state                      = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val scale by animateFloatAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0.75f,
                label = "scale"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tc.red.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete, null,
                    tint     = Color.White,
                    modifier = Modifier.size(26.dp).scale(scale),
                )
            }
        },
        content = { content() },
    )
}

// ── Delete confirmation dialog ───────────────────────────────
@Composable
fun DeleteConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = tc.cardAlt,
        titleContentColor = tc.textPrimary,
        textContentColor  = tc.muted,
        title = { Text(st.history.deleteConfirmTitle, fontWeight = FontWeight.Bold) },
        text  = { Text(message, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(st.history.confirmDelete, color = tc.red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(st.cancelBtn, color = tc.muted)
            }
        },
    )
}

// ── Parse routePointsJson → List<LatLng> ────────────────────
internal fun parseRoutePoints(json: String): List<LatLng> {
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val pt = arr.getJSONArray(i)
            LatLng(pt.getDouble(0), pt.getDouble(1))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
