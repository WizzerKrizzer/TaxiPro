package com.taxipro.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.Ride
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryScreen(vm: RideViewModel) {
    val allRides by vm.allRides.collectAsState(initial = emptyList())
    val st       = LocalStrings.current

    val countOptions = listOf(10, 20, 50, 100, 250, 500)
    var countLimit   by remember { mutableIntStateOf(50) }

    var dateRangeStart by remember { mutableStateOf<Long?>(null) }
    var dateRangeEnd   by remember { mutableStateOf<Long?>(null) }
    var showPicker     by remember { mutableStateOf(false) }

    val dateRangePickerState = rememberDateRangePickerState()

    val sdfDate = SimpleDateFormat("dd.MM.yy", Locale.getDefault())

    val displayed = remember(allRides, countLimit, dateRangeStart, dateRangeEnd) {
        if (dateRangeStart != null && dateRangeEnd != null) {
            allRides.filter { it.startTime in dateRangeStart!!..dateRangeEnd!! }
        } else {
            allRides.take(countLimit)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(st.historyTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        // ── Count chips ──────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(st.showLabel, color = Muted, fontSize = 12.sp)
            countOptions.forEach { n ->
                val sel = countLimit == n && dateRangeStart == null
                FilterChip(
                    selected = sel,
                    onClick  = {
                        countLimit     = n
                        dateRangeStart = null
                        dateRangeEnd   = null
                    },
                    label  = { Text("$n", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Gold.copy(alpha = 0.2f),
                        selectedLabelColor     = Gold,
                        containerColor         = Card,
                        labelColor             = Muted,
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── Date range row ───────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dateRangeStart != null && dateRangeEnd != null) {
                AssistChip(
                    onClick = { showPicker = true },
                    label   = {
                        Text(
                            "${sdfDate.format(Date(dateRangeStart!!))} – ${sdfDate.format(Date(dateRangeEnd!!))}",
                            fontSize = 12.sp, color = Gold
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.DateRange, null, tint = Gold, modifier = Modifier.size(16.dp)) },
                    colors      = AssistChipDefaults.assistChipColors(containerColor = Gold.copy(alpha = 0.15f)),
                    border      = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Gold.copy(alpha = 0.4f))
                )
                IconButton(onClick = { dateRangeStart = null; dateRangeEnd = null }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Изчисти", tint = Muted, modifier = Modifier.size(18.dp))
                }
            } else {
                OutlinedButton(
                    onClick = { showPicker = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, Muted.copy(alpha = 0.5f)),
                    shape   = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.DateRange, null, tint = Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.choosePeriod, color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "${displayed.size} ${st.ridesLabel}",
                color = Muted, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── Ride list ────────────────────────────────────────
        var expandedId by remember { mutableStateOf<Long?>(null) }

        if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(st.noRides, color = Muted, fontSize = 14.sp)
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayed.forEach { ride ->
                    HistoryRideCard(
                        ride       = ride,
                        isExpanded = expandedId == ride.id,
                        onToggle   = {
                            expandedId = if (expandedId == ride.id) null else ride.id
                        }
                    )
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    // ── Date range picker dialog ─────────────────────────────
    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton    = {
                TextButton(onClick = {
                    val s = dateRangePickerState.selectedStartDateMillis
                    val e = dateRangePickerState.selectedEndDateMillis
                    if (s != null) {
                        dateRangeStart = s
                        dateRangeEnd   = (e ?: s) + 86_400_000L - 1L  // end of day
                    }
                    showPicker = false
                }) { Text("OK", color = Gold) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(st.cancelBtn, color = Muted)
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = Color(0xFF1A1E28)
            )
        ) {
            DateRangePicker(
                state    = dateRangePickerState,
                modifier = Modifier.weight(1f, fill = false),
                colors   = DatePickerDefaults.colors(
                    containerColor               = Color(0xFF1A1E28),
                    titleContentColor            = Muted,
                    headlineContentColor         = Color.White,
                    weekdayContentColor          = Muted,
                    subheadContentColor          = Muted,
                    navigationContentColor       = Color.White,
                    yearContentColor             = Color.White,
                    currentYearContentColor      = Gold,
                    selectedYearContentColor     = Dark,
                    selectedYearContainerColor   = Gold,
                    dayContentColor              = Color.White,
                    selectedDayContentColor      = Dark,
                    selectedDayContainerColor    = Gold,
                    todayContentColor            = Gold,
                    todayDateBorderColor         = Gold,
                    dayInSelectionRangeContentColor   = Dark,
                    dayInSelectionRangeContainerColor = Gold.copy(alpha = 0.4f),
                )
            )
        }
    }
}

@Composable
private fun HistoryRideCard(ride: Ride, isExpanded: Boolean, onToggle: () -> Unit) {
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdfDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    val routeLabel = when {
        ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
            ride.fromAddress.substringBefore(",").take(22) +
            " → " +
            ride.toAddress.substringBefore(",").take(22)
        ride.fromAddress.isNotEmpty() ->
            ride.fromAddress.substringBefore(",").take(40)
        else -> "${st.ridePrefix}${ride.globalId}"
    }

    // Parse stored route points once
    val routePoints = remember(ride.id) { parseRoutePoints(ride.routePointsJson) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Card),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            // ── Header row (always visible, tappable) ─────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("#${ride.globalId}", color = Gold,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        routeLabel,
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${sdfDate.format(Date(ride.startTime))}  " +
                        "${sdfTime.format(Date(ride.startTime))} – ${sdfTime.format(Date(ride.endTime))}",
                        color = Muted, fontSize = 11.sp
                    )
                    Text(
                        "%.1f ${settings.distanceUnit.shortLabel}  •  %.0f ${st.waitSuffix}".format(ride.kilometers, ride.waitMinutes),
                        color = Muted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        settings.formatPrice(ride.price),
                        color = Gold, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    if (ride.tip > 0)
                        Text("+%.2f".format(ride.tip),
                            color = Color(0xFFA78BFA), fontSize = 10.sp)
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = Muted, modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── Expandable route map ───────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = Muted.copy(alpha = 0.15f))
                    if (routePoints.size >= 2) {
                        RouteMapSection(routePoints, st.startMarker, st.endMarker)
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(st.noRoute,
                                color = Muted, fontSize = 12.sp)
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
            color  = Gold,
            width  = 14f,
        )
        // Start marker (green)
        Marker(
            state = MarkerState(position = points.first()),
            title = startTitle,
        )
        // End marker (default red)
        Marker(
            state = MarkerState(position = points.last()),
            title = endTitle,
        )
    }
}

// ── Parse routePointsJson → List<LatLng> ────────────────────
private fun parseRoutePoints(json: String): List<LatLng> {
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
