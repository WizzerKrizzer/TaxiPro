package com.taxipro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.*
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneScreen(
    rideVm: RideViewModel,
    onNavigate: (String) -> Unit,
) {
    val tc    = LocalThemeColors.current
    val st    = LocalStrings.current
    val zones by rideVm.allZones.collectAsState(initial = emptyList())
    val rides by rideVm.allRides.collectAsState(initial = emptyList())

    var selectedTab  by remember { mutableIntStateOf(0) }
    var zoneToEdit   by remember { mutableStateOf<Zone?>(null) }

    // When editing a zone, show the editor full-screen (no navigation needed)
    if (zoneToEdit != null) {
        ZoneCreatorScreen(
            rideVm       = rideVm,
            existingZone = zoneToEdit,
            onBack       = { zoneToEdit = null },
        )
        return
    }

    Scaffold(
        containerColor = tc.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(st.zones.zonesTitle, color = tc.textPrimary,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(st.zones.zonesSub, color = tc.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onNavigate("more") }) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tc.background)
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                val isPremium  = LocalIsPremium.current
                val onUpgrade  = LocalOnUpgrade.current
                var showGate   by remember { mutableStateOf(false) }

                FloatingActionButton(
                    onClick        = {
                        if (!isPremium && zones.size >= 3) showGate = true
                        else onNavigate("zone_creator")
                    },
                    containerColor = tc.accent,
                    contentColor   = tc.background,
                    shape          = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                }

                if (showGate) {
                    PremiumUpgradeDialog(
                        hint      = st.premium.gateHintZones,
                        onUpgrade = { showGate = false; onUpgrade() },
                        onDismiss = { showGate = false },
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = tc.background,
                contentColor     = tc.accent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    text     = { Text(st.zones.zonesTitle,
                        color = if (selectedTab == 0) tc.accent else tc.muted) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    text     = { Text(st.zones.zoneStatsTitle,
                        color = if (selectedTab == 1) tc.accent else tc.muted) }
                )
            }

            when (selectedTab) {
                0 -> ZoneListTab(
                    zones      = zones,
                    rideVm     = rideVm,
                    onEdit     = { zoneToEdit = it },
                )
                1 -> ZoneStatsTab(zones = zones, rides = rides, rideVm = rideVm)
            }
        }
    }
}

// ── Tab 1: Zone list ──────────────────────────────────────────────────────────

@Composable
private fun ZoneListTab(
    zones: List<Zone>,
    rideVm: RideViewModel,
    onEdit: (Zone) -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    var pendingDelete  by remember { mutableStateOf<Zone?>(null) }
    var previewZone    by remember { mutableStateOf<Zone?>(null) }

    if (zones.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                st.zones.noZonesYet,
                color     = tc.muted,
                fontSize  = 14.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(32.dp)
            )
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            zones.forEach { zone ->
                ZoneCard(
                    zone     = zone,
                    onClick  = { previewZone = zone },
                    onEdit   = { onEdit(zone) },
                    onDelete = { pendingDelete = zone },
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    // Delete confirmation dialog
    pendingDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor   = tc.card,
            title = {
                Text(st.zones.zoneDeleteConfirm, color = tc.textPrimary,
                    fontWeight = FontWeight.Bold)
            },
            text = { Text("\"${zone.name}\"", color = tc.muted) },
            confirmButton = {
                TextButton(onClick = { rideVm.deleteZone(zone); pendingDelete = null }) {
                    Text(st.zones.zoneDeleteBtn, color = tc.red,
                        fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            }
        )
    }

    // Map preview dialog
    previewZone?.let { zone ->
        ZoneMapPreviewDialog(
            zone      = zone,
            allZones  = zones,
            onDismiss = { previewZone = null },
        )
    }
}

@Composable
private fun ZoneCard(
    zone: Zone,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tc     = LocalThemeColors.current
    val points = remember(zone.pointsJson) { parseZonePoints(zone.pointsJson) }
    val color  = Color(zone.color)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape  = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Color dot
            Box(
                Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(20.dp).background(color, CircleShape))
            }

            Column(Modifier.weight(1f)) {
                Text(zone.name, color = tc.textPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "${points.size} corners",
                    color = tc.muted, fontSize = 11.sp
                )
            }

            // Mini dot preview
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(minOf(points.size, 10)) {
                    Box(Modifier.size(5.dp).background(color, CircleShape))
                }
                if (points.size > 10) {
                    Text("+${points.size - 10}", color = tc.muted, fontSize = 8.sp)
                }
            }

            // Edit button
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, null, tint = tc.accent.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp))
            }

            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, null, tint = tc.red.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Zone map preview dialog ───────────────────────────────────────────────────

@Composable
private fun ZoneMapPreviewDialog(
    zone: Zone,
    allZones: List<Zone>,
    onDismiss: () -> Unit,
) {
    val tc     = LocalThemeColors.current
    val points = remember(zone.pointsJson) { parseZonePoints(zone.pointsJson) }
    val color  = Color(zone.color)

    val cameraState = rememberCameraPositionState {
        val center = if (points.isNotEmpty()) LatLng(
            points.map { it.latitude }.average(),
            points.map { it.longitude }.average()
        ) else LatLng(42.15, 24.75)
        position = CameraPosition.fromLatLngZoom(center, 12f)
    }

    // Fit camera to zone bounds once map is ready
    LaunchedEffect(points) {
        if (points.size >= 2) {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            cameraState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside   = true,
        )
    ) {
        Box(Modifier.fillMaxSize().background(tc.background)) {

            GoogleMap(
                modifier            = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                uiSettings          = MapUiSettings(
                    zoomControlsEnabled     = true,
                    myLocationButtonEnabled = false,
                ),
            ) {
                // All other zones faded
                allZones.filter { it.id != zone.id }.forEach { other ->
                    val pts = remember(other.pointsJson) { parseZonePoints(other.pointsJson) }
                    if (pts.size >= 3) {
                        Polygon(
                            points      = pts,
                            fillColor   = Color(other.color).copy(alpha = if (other.parentZoneId > 0L) 0.16f else 0.10f),
                            strokeColor = Color(other.color).copy(alpha = if (other.parentZoneId > 0L) 0.48f else 0.30f),
                            strokeWidth = if (other.parentZoneId > 0L) 3f else 2f,
                            clickable   = false,
                        )
                    }
                }

                // Selected zone highlighted
                if (points.size >= 3) {
                    Polygon(
                        points      = points,
                        fillColor   = color.copy(alpha = if (zone.parentZoneId > 0L) 0.42f else 0.35f),
                        strokeColor = color,
                        strokeWidth = if (zone.parentZoneId > 0L) 6f else 5f,
                        clickable   = false,
                    )
                }
            }

            // Top bar overlay
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tc.background.copy(alpha = 0.90f))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null, tint = tc.accent)
                }
                Box(
                    Modifier
                        .size(14.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    zone.name,
                    color      = tc.textPrimary,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Tab 2: Zone statistics ────────────────────────────────────────────────────

private enum class ZoneSortOrder { PICKUPS, DROPOFFS, TOTAL_REVENUE, AVG_REVENUE, AVG_KM, AVG_TIP, AVG_WAIT }

private data class ZoneRouteNames(
    val from: String,
    val to: String,
)

private fun rideZoneNames(
    ride: com.taxipro.data.db.Ride,
    zones: List<Zone>,
    outsideLabel: String,
): ZoneRouteNames {
    val (sLat, sLng) = rideStartLatLng(ride) ?: (0.0 to 0.0)
    val (eLat, eLng) = rideEndLatLng(ride) ?: (0.0 to 0.0)
    return ZoneRouteNames(
        from = primaryZoneLabel(sLat, sLng, zones, outsideLabel) ?: outsideLabel,
        to = primaryZoneLabel(eLat, eLng, zones, outsideLabel) ?: outsideLabel,
    )
}

@Composable
private fun ZoneDirectionFilters(
    zones: List<Zone>,
    outsideLabel: String,
    fromZoneName: String?,
    toZoneName: String?,
    kmText: String,
    fareText: String,
    durationText: String,
    kmIsMin: Boolean,
    fareIsMin: Boolean,
    durationIsMin: Boolean,
    onFromSelected: (String?) -> Unit,
    onToSelected: (String?) -> Unit,
    onKmChange: (String) -> Unit,
    onFareChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onToggleKmMode: () -> Unit,
    onToggleFareMode: () -> Unit,
    onToggleDurationMode: () -> Unit,
    onApplyKm: () -> Unit,
    onApplyFare: () -> Unit,
    onApplyDuration: () -> Unit,
    onClear: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val settings = LocalSettings.current
    val options = remember(zones, outsideLabel) {
        listOf<Pair<String?, String>>(null to "Всички") +
            zones.map { it.name to it.name } +
            (outsideLabel.takeIf { it.isNotBlank() }?.let { listOf(it to it) } ?: emptyList())
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Филтър по маршрут",
                    color = tc.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (fromZoneName != null || toZoneName != null ||
                    kmText.isNotBlank() || fareText.isNotBlank() || durationText.isNotBlank()
                ) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Clear, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Изчисти", fontSize = 11.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZoneFilterDropdown(
                    label = "От зона",
                    selected = fromZoneName,
                    options = options,
                    modifier = Modifier.weight(1f),
                    onSelected = onFromSelected,
                )
                ZoneFilterDropdown(
                    label = "До зона",
                    selected = toZoneName,
                    options = options,
                    modifier = Modifier.weight(1f),
                    onSelected = onToSelected,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZoneNumberFilter("Км", kmText, settings.distanceUnit.shortLabel, kmIsMin, Modifier.weight(1f), onToggleKmMode, onKmChange, onApplyKm)
                ZoneNumberFilter("Сума", fareText, settings.currency.symbol, fareIsMin, Modifier.weight(1f), onToggleFareMode, onFareChange, onApplyFare)
                ZoneNumberFilter("Време", durationText, "мин", durationIsMin, Modifier.weight(1f), onToggleDurationMode, onDurationChange, onApplyDuration)
            }
val summary = when {
                fromZoneName != null && toZoneName != null -> "Само курсове: $fromZoneName → $toZoneName"
                fromZoneName != null -> "Само курсове от: $fromZoneName"
                toZoneName != null -> "Само курсове до: $toZoneName"
                else -> "Показва всички начални и крайни зони"
            }
            Text(summary, color = tc.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ZoneNumberFilter(
    label: String,
    value: String,
    suffix: String,
    isMin: Boolean,
    modifier: Modifier = Modifier,
    onToggleMode: () -> Unit,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    val tc = LocalThemeColors.current
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() || it == '.' || it == ',' }) },
        modifier = modifier,
        label = {
            Text(
                label,
                color = tc.muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            TextButton(
                onClick = onToggleMode,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.width(44.dp),
            ) {
                Text(if (isMin) "Мин" else "Макс", color = tc.accent, fontSize = 10.sp)
            }
        },
        suffix = { Text(suffix, color = tc.muted, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onApply() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tc.accent,
            unfocusedBorderColor = tc.surface,
            focusedTextColor = tc.textPrimary,
            unfocusedTextColor = tc.textPrimary,
            cursorColor = tc.accent,
            focusedLabelColor = tc.accent,
        ),
    )
}

@Composable
private fun ZoneFilterDropdown(
    label: String,
    selected: String?,
    options: List<Pair<String?, String>>,
    modifier: Modifier = Modifier,
    onSelected: (String?) -> Unit,
) {
    val tc = LocalThemeColors.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, color = tc.muted, fontSize = 9.sp)
                Text(selected ?: "Всички", color = tc.textPrimary, fontSize = 12.sp, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = tc.accent)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(tc.card),
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (value == selected) tc.accent else tc.textPrimary,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneStatsTab(zones: List<Zone>, rides: List<com.taxipro.data.db.Ride>, rideVm: RideViewModel) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current

    var sortOrder    by remember { mutableStateOf(ZoneSortOrder.PICKUPS) }
    var sortAsc      by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showRoutes   by remember { mutableStateOf(false) }
    var selectedStat by remember { mutableStateOf<ZoneStat?>(null) }
    var fromZoneName by remember { mutableStateOf<String?>(null) }
    var toZoneName   by remember { mutableStateOf<String?>(null) }
    var kmFilterText       by remember { mutableStateOf("") }
    var fareFilterText     by remember { mutableStateOf("") }
    var durationFilterText by remember { mutableStateOf("") }
    var kmFilterIsMin       by remember { mutableStateOf(true) }
    var fareFilterIsMin     by remember { mutableStateOf(true) }
    var durationFilterIsMin by remember { mutableStateOf(true) }
    var appliedKmFilterText       by remember { mutableStateOf("") }
    var appliedFareFilterText     by remember { mutableStateOf("") }
    var appliedDurationFilterText by remember { mutableStateOf("") }
    var appliedKmFilterIsMin       by remember { mutableStateOf(true) }
    var appliedFareFilterIsMin     by remember { mutableStateOf(true) }
    var appliedDurationFilterIsMin by remember { mutableStateOf(true) }

    // Period filter state — initialised to today so the first render shows today's data
    val nowMs = remember { System.currentTimeMillis() }
    var filterFromMs by remember { mutableStateOf<Long?>(pfStartOfDay(nowMs)) }
    var filterToMs   by remember { mutableStateOf<Long?>(pfEndOfDay(nowMs)) }
    val allZoneWaitSessions by rideVm.allZoneWaitSessions.collectAsState(initial = emptyList())

    // ── Date filtering ──────────────────────────────────────────────────
    val dateFilteredRides = remember(rides, filterFromMs, filterToMs) {
        if (filterFromMs != null && filterToMs != null)
            rides.filter { it.startTime in filterFromMs!!..filterToMs!! }
        else
            rides
    }
    val kmFilter       = appliedKmFilterText.replace(",", ".").toDoubleOrNull()
    val fareFilter     = appliedFareFilterText.replace(",", ".").toDoubleOrNull()
    val durationFilter = appliedDurationFilterText.replace(",", ".").toDoubleOrNull()
    val minKm       = kmFilter.takeIf { appliedKmFilterIsMin }
    val minFare     = fareFilter.takeIf { appliedFareFilterIsMin }
    val minDuration = durationFilter.takeIf { appliedDurationFilterIsMin }
    val maxKm       = kmFilter.takeIf { !appliedKmFilterIsMin }
    val maxFare     = fareFilter.takeIf { !appliedFareFilterIsMin }
    val maxDuration = durationFilter.takeIf { !appliedDurationFilterIsMin }

    val filteredRides = remember(
        dateFilteredRides, zones, fromZoneName, toZoneName, minKm, minFare, minDuration,
        maxKm, maxFare, maxDuration, st.zones.outsideZones,
    ) {
        dateFilteredRides.filter { ride ->
            val route = rideZoneNames(ride, zones, st.zones.outsideZones)
            val durationMin = ((ride.endTime - ride.startTime).coerceAtLeast(0L)) / 60_000.0
            (fromZoneName == null || route.from == fromZoneName) &&
                (toZoneName == null || route.to == toZoneName) &&
                (minKm == null || ride.kilometers >= minKm) &&
                (minFare == null || ride.price + ride.tip >= minFare) &&
                (minDuration == null || durationMin >= minDuration) &&
                (maxKm == null || ride.kilometers <= maxKm) &&
                (maxFare == null || ride.price + ride.tip <= maxFare) &&
                (maxDuration == null || durationMin <= maxDuration)
        }
    }
    val filteredZoneWaits = remember(allZoneWaitSessions, filterFromMs, filterToMs) {
        if (filterFromMs != null && filterToMs != null)
            allZoneWaitSessions.filter { it.startTime in filterFromMs!!..filterToMs!! }
        else
            allZoneWaitSessions
    }

    val showLargeWarning = filterFromMs != null && filterToMs != null &&
        (filterToMs!! - filterFromMs!!) / (1000L * 60 * 60 * 24) > 90

    var isLoadingStats by remember { mutableStateOf(false) }
    var computedStats  by remember {
        mutableStateOf(Pair(emptyList<ZoneStat>(), emptyList<ZoneRouteStat>()))
    }

    LaunchedEffect(filteredRides, zones, filteredZoneWaits) {
        isLoadingStats = true
        val result = withContext(Dispatchers.Default) {
            computeZoneStats(filteredRides, zones, st.zones.outsideZones, filteredZoneWaits)
        }
        computedStats  = result
        isLoadingStats = false
    }

    val (zoneStats, routeStats) = computedStats

    val sortedStats = remember(zoneStats, sortOrder, sortAsc) {
        val comparator = when (sortOrder) {
            ZoneSortOrder.PICKUPS       -> compareBy<ZoneStat> { it.pickupCount }
            ZoneSortOrder.DROPOFFS      -> compareBy { it.dropoffCount }
            ZoneSortOrder.TOTAL_REVENUE -> compareBy { it.totalRevenue }
            ZoneSortOrder.AVG_REVENUE   -> compareBy { it.avgRevenue }
            ZoneSortOrder.AVG_KM        -> compareBy { it.avgKm }
            ZoneSortOrder.AVG_TIP       -> compareBy { it.avgTip }
            ZoneSortOrder.AVG_WAIT      -> compareBy { it.avgWaitMs }
        }
        if (sortAsc) zoneStats.sortedWith(comparator)
        else zoneStats.sortedWith(comparator.reversed())
    }

    val sortLabel = when (sortOrder) {
        ZoneSortOrder.PICKUPS       -> st.zones.sortByPickups
        ZoneSortOrder.DROPOFFS      -> st.zones.sortByDropoffs
        ZoneSortOrder.TOTAL_REVENUE -> "Оборот"
        ZoneSortOrder.AVG_REVENUE   -> st.zones.sortByRevenue
        ZoneSortOrder.AVG_KM        -> st.zones.sortByAvgKm
        ZoneSortOrder.AVG_TIP       -> st.zones.sortByAvgTip
        ZoneSortOrder.AVG_WAIT      -> "Чакане"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Date filter chips ───────────────────────────────────────────
        PeriodFilterRow(
            onRangeChanged = { f, t -> filterFromMs = f; filterToMs = t },
        )

        ZoneDirectionFilters(
            zones          = zones,
            outsideLabel   = st.zones.outsideZones,
            fromZoneName   = fromZoneName,
            toZoneName     = toZoneName,
            kmText = kmFilterText,
            fareText = fareFilterText,
            durationText = durationFilterText,
            kmIsMin = kmFilterIsMin,
            fareIsMin = fareFilterIsMin,
            durationIsMin = durationFilterIsMin,
            onFromSelected = { fromZoneName = it },
            onToSelected   = { toZoneName = it },
            onKmChange = { kmFilterText = it },
            onFareChange = { fareFilterText = it },
            onDurationChange = { durationFilterText = it },
            onToggleKmMode = { kmFilterIsMin = !kmFilterIsMin; appliedKmFilterIsMin = kmFilterIsMin; if (kmFilterText.isNotBlank()) appliedKmFilterText = kmFilterText },
            onToggleFareMode = { fareFilterIsMin = !fareFilterIsMin; appliedFareFilterIsMin = fareFilterIsMin; if (fareFilterText.isNotBlank()) appliedFareFilterText = fareFilterText },
            onToggleDurationMode = { durationFilterIsMin = !durationFilterIsMin; appliedDurationFilterIsMin = durationFilterIsMin; if (durationFilterText.isNotBlank()) appliedDurationFilterText = durationFilterText },
            onApplyKm = { appliedKmFilterText = kmFilterText; appliedKmFilterIsMin = kmFilterIsMin },
            onApplyFare = { appliedFareFilterText = fareFilterText; appliedFareFilterIsMin = fareFilterIsMin },
            onApplyDuration = { appliedDurationFilterText = durationFilterText; appliedDurationFilterIsMin = durationFilterIsMin },
            onClear        = {
                fromZoneName = null
                toZoneName = null
                kmFilterText = ""
                fareFilterText = ""
                durationFilterText = ""
                kmFilterIsMin = true
                fareFilterIsMin = true
                durationFilterIsMin = true
                appliedKmFilterText = ""
                appliedFareFilterText = ""
                appliedDurationFilterText = ""
                appliedKmFilterIsMin = true
                appliedFareFilterIsMin = true
                appliedDurationFilterIsMin = true
            },
        )

        // ── Large range warning ─────────────────────────────────────────
        if (showLargeWarning) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tc.accent.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = tc.accent, modifier = Modifier.size(16.dp))
                Text(st.zones.largeRangeWarning, color = tc.accent, fontSize = 12.sp)
            }
        }

        // ── Loading indicator ───────────────────────────────────────────
        if (isLoadingStats) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = tc.accent, modifier = Modifier.size(32.dp))
                Text(st.loadingLabel, color = tc.muted, fontSize = 13.sp)
            }
        }

        if (!isLoadingStats && (filteredRides.isEmpty() || zoneStats.isEmpty())) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center) {
                Text(
                    if (zones.isEmpty()) st.zones.noZonesYet else st.zones.noRidesForZones,
                    color = tc.muted, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        } else if (!isLoadingStats) {
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(
                            onClick = { showSortMenu = true },
                            shape   = RoundedCornerShape(10.dp),
                            colors  = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
                        ) {
                            Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sortLabel, fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded         = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier         = Modifier.background(tc.card),
                        ) {
                            listOf(
                                ZoneSortOrder.PICKUPS       to st.zones.sortByPickups,
                                ZoneSortOrder.DROPOFFS      to st.zones.sortByDropoffs,
                                ZoneSortOrder.TOTAL_REVENUE to "Оборот",
                                ZoneSortOrder.AVG_REVENUE   to st.zones.sortByRevenue,
                                ZoneSortOrder.AVG_KM        to st.zones.sortByAvgKm,
                                ZoneSortOrder.AVG_TIP       to st.zones.sortByAvgTip,
                                ZoneSortOrder.AVG_WAIT      to "Чакане",
                            ).forEach { (order, label) ->
                                DropdownMenuItem(
                                    text    = {
                                        Text(label,
                                            color = if (sortOrder == order) tc.accent else tc.textPrimary)
                                    },
                                    onClick = { sortOrder = order; showSortMenu = false },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { sortAsc = !sortAsc },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
                    ) {
                        Text(if (sortAsc) "↑" else "↓", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            sortedStats.forEach { stat ->
                ZoneStatCard(
                    stat     = stat,
                    settings = settings,
                    onClick  = { selectedStat = stat },
                )
            }

            HorizontalDivider(color = tc.surface, modifier = Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(st.zones.zoneRoutesTitle, color = tc.textPrimary,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showRoutes = !showRoutes }) {
                    Icon(
                        if (showRoutes) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = tc.muted
                    )
                }
            }

            if (showRoutes) {
                routeStats.take(20).forEach { route ->
                    RouteStatRow(route = route, settings = settings)
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    // ── Custom date range picker dialog ─────────────────────────────────
    // ── Zone drill-down dialog ──────────────────────────────────────────
    val allShifts by rideVm.allShifts.collectAsState(initial = emptyList())

    selectedStat?.let { stat ->
        ZoneDrillDownDialog(
            stat      = stat,
            allZones  = zones,
            allShifts = allShifts,
            settings  = settings,
            onDismiss = { selectedStat = null },
        )
    }
}

@Composable
private fun ZoneStatCard(
    stat: ZoneStat,
    settings: com.taxipro.data.db.AppSettings,
    onClick: () -> Unit = {},
) {
    val tc    = LocalThemeColors.current
    val st    = LocalStrings.current
    val color = if (stat.zone != null) Color(stat.zone.color) else tc.muted

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(stat.zoneName, color = tc.textPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniZoneStat("${stat.pickupCount}",  st.zones.pickupsLabel,      tc.accent)
                    MiniZoneStat("${stat.dropoffCount}", st.zones.dropoffsLabel,     tc.blue)
                    MiniZoneStat(settings.formatPrice(stat.avgRevenue), st.zones.avgPickupFareLabel, tc.green)
                    MiniZoneStat("%.1f km".format(stat.avgKm),          st.zones.avgPickupKmLabel,  tc.muted)
                }
            }
        }
    }
}

private fun formatZoneWait(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}

@Composable
private fun MiniZoneStat(value: String, label: String, color: Color) {
    val tc = LocalThemeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = tc.muted, fontSize = 9.sp)
    }
}

// ── Zone drill-down dialog ────────────────────────────────────────────────────

@Composable
private fun ZoneDrillDownDialog(
    stat      : ZoneStat,
    allZones  : List<Zone>,
    allShifts : List<Shift>,
    settings  : com.taxipro.data.db.AppSettings,
    onDismiss : () -> Unit,
) {
    val tc    = LocalThemeColors.current
    val st    = LocalStrings.current
    val color = if (stat.zone != null) Color(stat.zone.color) else tc.muted

    var selectedRide by remember { mutableStateOf<com.taxipro.data.db.Ride?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside   = true,
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tc.background)
        ) {
            Column(Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tc.card)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                    Box(Modifier.size(12.dp).background(color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stat.zoneName,
                            color      = tc.textPrimary,
                            fontSize   = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${stat.pickupCount} ${st.zones.pickupsLabel.lowercase()}  •  " +
                            "${stat.dropoffCount} ${st.zones.dropoffsLabel.lowercase()}",
                            color    = tc.muted,
                            fontSize = 11.sp,
                        )
                    }
                }

                // ── Scrollable content ────────────────────────────────────
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {

                    // Pickups section
                    DrillDownSectionHeader(
                        label = st.zones.pickupsSection,
                        count = stat.pickupCount,
                        color = tc.accent,
                    )
                    if (stat.pickupRides.isEmpty()) {
                        Text(
                            st.zones.noPickupsInZone,
                            color    = tc.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                        )
                    } else {
                        stat.pickupRides.forEach { ride ->
                            DrillDownRideCard(
                                ride      = ride,
                                allZones  = allZones,
                                allShifts = allShifts,
                                settings  = settings,
                                isPickup  = true,
                                onClick   = { selectedRide = ride },
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Dropoffs section
                    DrillDownSectionHeader(
                        label = st.zones.dropoffsSection,
                        count = stat.dropoffCount,
                        color = tc.blue,
                    )
                    if (stat.dropoffRides.isEmpty()) {
                        Text(
                            st.zones.noDropoffsInZone,
                            color    = tc.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                        )
                    } else {
                        stat.dropoffRides.forEach { ride ->
                            DrillDownRideCard(
                                ride      = ride,
                                allZones  = allZones,
                                allShifts = allShifts,
                                settings  = settings,
                                isPickup  = false,
                                onClick   = { selectedRide = ride },
                            )
                        }
                    }

                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }

    // ── Ride detail dialog (shift number + route map) ───────────────────
    selectedRide?.let { ride ->
        ZoneRideDetailDialog(
            ride      = ride,
            allShifts = allShifts,
            settings  = settings,
            onDismiss = { selectedRide = null },
        )
    }
}

@Composable
private fun DrillDownSectionHeader(label: String, count: Int, color: Color) {
    val tc = LocalThemeColors.current
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        Text(
            label,
            color      = tc.textPrimary,
            fontSize   = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier
                .background(color.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("$count", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider(color = color.copy(alpha = 0.25f), thickness = 1.dp)
}

@Composable
private fun DrillDownRideCard(
    ride      : com.taxipro.data.db.Ride,
    allZones  : List<Zone>,
    allShifts : List<Shift>,
    settings  : com.taxipro.data.db.AppSettings,
    isPickup  : Boolean,
    onClick   : () -> Unit = {},
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    val (zoneLabel, addrLabel) = remember(ride, allZones) { rideRouteLabels(ride, allZones, st.zones.outsideZones) }

    // Build directional label: for pickups show destination half, for dropoffs show origin half
    val routeDisplay = when {
        addrLabel.isNotEmpty() -> addrLabel
        zoneLabel.isNotEmpty() -> zoneLabel
        else                   -> "#${ride.id}"
    }

    // Duration
    val durationSec  = if (ride.endTime > ride.startTime) (ride.endTime - ride.startTime) / 1000L else 0L
    val durationText = when {
        durationSec >= 3600 -> "%dh %02dm".format(durationSec / 3600, (durationSec % 3600) / 60)
        durationSec >= 60   -> "%dm %02ds".format(durationSec / 60, durationSec % 60)
        durationSec > 0     -> "${durationSec}s"
        else                -> "—"
    }

    // Date label
    val dateFmt  = remember { java.text.SimpleDateFormat("dd.MM  HH:mm", java.util.Locale.getDefault()) }
    val dateText = remember(ride.startTime) { dateFmt.format(java.util.Date(ride.startTime)) }

    // Shift number badge
    val shiftNumber = remember(ride.shiftId, allShifts) {
        allShifts.firstOrNull { it.id == ride.shiftId }?.shiftNumber
    }

    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors    = CardDefaults.cardColors(containerColor = tc.cardAlt.copy(alpha = 0.55f)),
        shape     = RoundedCornerShape(10.dp),
        border    = BorderStroke(1.dp, tc.accent.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // Route label + date
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier              = Modifier.weight(1f),
                ) {
                    Icon(
                        if (isPickup) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        null,
                        tint     = if (isPickup) tc.accent else tc.blue,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        routeDisplay,
                        color      = tc.textPrimary,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(dateText, color = tc.muted, fontSize = 10.sp)
                            if (shiftNumber != null) {
                                Text(
                                    st.shiftNum.format(shiftNumber),
                                    color    = tc.muted,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = tc.muted.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Stats row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DrillStat(
                    value = settings.formatPrice(ride.price + ride.tip),
                    label = st.zones.fareLabel,
                    color = tc.green,
                )
                DrillStat(
                    value = "%.1f km".format(ride.kilometers),
                    label = st.kilometersLabel,
                    color = tc.muted,
                )
                DrillStat(
                    value = durationText,
                    label = st.durationField,
                    color = tc.muted,
                )
                if (ride.tip > 0.0) {
                    DrillStat(
                        value = settings.formatPrice(ride.tip),
                        label = st.tipLabel,
                        color = tc.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrillStat(value: String, label: String, color: Color) {
    val tc = LocalThemeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color      = color,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(label, color = tc.muted, fontSize = 9.sp)
    }
}

// ── Zone ride detail dialog (shift + route map) ───────────────────────────────

@Composable
internal fun ZoneRideDetailDialog(
    ride      : com.taxipro.data.db.Ride,
    allShifts : List<Shift>,
    settings  : com.taxipro.data.db.AppSettings,
    onDismiss : () -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    val shift = remember(ride.shiftId, allShifts) {
        allShifts.firstOrNull { it.id == ride.shiftId }
    }

    val routePoints = remember(ride.routePointsJson) {
        parseRoutePoints(ride.routePointsJson)
    }

    val dateFmt  = remember { java.text.SimpleDateFormat("dd.MM.yyyy  HH:mm", java.util.Locale.getDefault()) }
    val dateText = remember(ride.startTime) { dateFmt.format(java.util.Date(ride.startTime)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(tc.background)
        ) {
            Column {
                // ── Header ───────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tc.card)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            dateText,
                            color      = tc.textPrimary,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (shift != null) {
                            Text(
                                st.shiftNum.format(shift.shiftNumber),
                                color    = tc.muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    // Price badge
                    Box(
                        Modifier
                            .background(tc.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            settings.formatPrice(ride.price + ride.tip),
                            color      = tc.accent,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // ── Stats row ─────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tc.card)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    val durationSec = if (ride.endTime > ride.startTime)
                        (ride.endTime - ride.startTime) / 1000L else 0L
                    val durationText = when {
                        durationSec >= 3600 -> "%dh %02dm".format(durationSec / 3600, (durationSec % 3600) / 60)
                        durationSec >= 60   -> "%dm %02ds".format(durationSec / 60, durationSec % 60)
                        else                -> "${durationSec}s"
                    }
                    DrillStat("%.1f km".format(ride.kilometers), st.kilometersLabel, tc.muted)
                    DrillStat(durationText,                       st.durationField,  tc.blue)
                    if (ride.tip > 0.0)
                        DrillStat(settings.formatPrice(ride.tip), st.tipLabel,       tc.accent)
                    if (ride.waitMinutes > 0.0)
                        DrillStat("%.0f ${st.minAbbr}".format(ride.waitMinutes), st.waitLabel, tc.purple)
                }

                HorizontalDivider(color = tc.surface)

                // ── Route map ─────────────────────────────────────────
                if (routePoints.size >= 2) {
                    val isPremium = LocalIsPremium.current
                    val onUpgrade = LocalOnUpgrade.current
                    if (isPremium) {
                        ZoneRideRouteMap(routePoints, st.history.startMarker, st.history.endMarker)
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(tc.card),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.Lock, null,
                                    tint = tc.muted, modifier = Modifier.size(28.dp))
                                Text(st.premium.gateHintMap,
                                    color = tc.muted, fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp))
                                Button(
                                    onClick = { onDismiss(); onUpgrade() },
                                    colors  = ButtonDefaults.buttonColors(containerColor = tc.accent),
                                    shape   = RoundedCornerShape(10.dp),
                                ) {
                                    Text(st.premium.gateBtn, color = tc.background,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(st.history.noRoute, color = tc.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneRideRouteMap(points: List<LatLng>, startTitle: String, endTitle: String) {
    val tc            = LocalThemeColors.current
    val boundsBuilder = remember(points) {
        LatLngBounds.Builder().also { b -> points.forEach { b.include(it) } }
    }
    val bounds   = remember(points) { boundsBuilder.build() }
    val camState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 13f)
    }
    LaunchedEffect(points) {
        camState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 60), durationMs = 500)
    }
    val startMarkerState = remember(points) { MarkerState(position = points.first()) }
    val endMarkerState   = remember(points) { MarkerState(position = points.last()) }

    GoogleMap(
        modifier            = Modifier.fillMaxWidth().height(240.dp),
        cameraPositionState = camState,
        uiSettings          = MapUiSettings(
            zoomControlsEnabled     = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled       = false,
        ),
    ) {
        Polyline(points = points, color = tc.accent, width = 14f)
        Marker(state = startMarkerState, title = startTitle,
            icon = createLabeledMarker(android.graphics.Color.parseColor("#4CAF50"), "S"), zIndex = 2f)
        Marker(state = endMarkerState, title = endTitle,
            icon = createLabeledMarker(android.graphics.Color.parseColor("#F44336"), "E"), zIndex = 2f)
    }
}

// ── Route statistics row ──────────────────────────────────────────────────────

@Composable
private fun RouteStatRow(
    route: ZoneRouteStat,
    settings: com.taxipro.data.db.AppSettings,
) {
    val tc = LocalThemeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            "${route.fromZone}  →  ${route.toZone}",
            color    = tc.textPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text("${route.count}×", color = tc.accent,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("ø ${settings.formatPrice(route.avgRevenue)}",
                color = tc.muted, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
}


