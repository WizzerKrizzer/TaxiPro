package com.taxipro.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import com.taxipro.data.db.primaryZoneLabel
import com.taxipro.data.db.rideEndLatLng
import com.taxipro.data.db.rideRouteLabels
import com.taxipro.data.db.rideStartLatLng
import com.taxipro.data.db.simplifyRideAddress
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

private data class RideHistoryZoneRoute(
    val from: String,
    val to: String,
)

private enum class RideHistorySort {
    NEWEST, REVENUE, DURATION, KM, WAIT, TIP
}

private fun rideHistoryZoneRoute(
    ride: Ride,
    zones: List<Zone>,
    outsideLabel: String,
): RideHistoryZoneRoute {
    val (sLat, sLng) = rideStartLatLng(ride) ?: (0.0 to 0.0)
    val (eLat, eLng) = rideEndLatLng(ride) ?: (0.0 to 0.0)
    return RideHistoryZoneRoute(
        from = primaryZoneLabel(sLat, sLng, zones, outsideLabel) ?: outsideLabel,
        to = primaryZoneLabel(eLat, eLng, zones, outsideLabel) ?: outsideLabel,
    )
}

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
    var sortBy       by remember { mutableStateOf(RideHistorySort.NEWEST) }
    var sortAsc      by remember { mutableStateOf(false) }
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
        allRides, allZones, filterFromMs, filterToMs, fromZoneName, toZoneName,
        minKm, minFare, minDuration, maxKm, maxFare, maxDuration, st.zones.outsideZones,
    ) {
        allRides.filter { ride ->
            val inPeriod = filterFromMs == null || filterToMs == null ||
                ride.startTime in filterFromMs!!..filterToMs!!
            val route = if (fromZoneName != null || toZoneName != null) {
                rideHistoryZoneRoute(ride, allZones, st.zones.outsideZones)
            } else null
            val durationMin = ((ride.endTime - ride.startTime).coerceAtLeast(0L)) / 60_000.0
            inPeriod &&
                (fromZoneName == null || route?.from == fromZoneName) &&
                (toZoneName == null || route?.to == toZoneName) &&
                (minKm == null || ride.kilometers >= minKm) &&
                (minFare == null || ride.price + ride.tip >= minFare) &&
                (minDuration == null || durationMin >= minDuration) &&
                (maxKm == null || ride.kilometers <= maxKm) &&
                (maxFare == null || ride.price + ride.tip <= maxFare) &&
                (maxDuration == null || durationMin <= maxDuration)
        }
    }
    val displayed = remember(filteredRides, sortBy, sortAsc) {
        val comparator = when (sortBy) {
            RideHistorySort.NEWEST   -> compareBy<Ride> { it.startTime }
            RideHistorySort.REVENUE  -> compareBy { it.price + it.tip }
            RideHistorySort.DURATION -> compareBy { (it.endTime - it.startTime).coerceAtLeast(0L) }
            RideHistorySort.KM       -> compareBy { it.kilometers }
            RideHistorySort.WAIT     -> compareBy { it.waitMinutes }
            RideHistorySort.TIP      -> compareBy { it.tip }
        }
        if (sortAsc) filteredRides.sortedWith(comparator)
        else filteredRides.sortedWith(comparator.reversed())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(st.history.historyTitle, color = tc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // ── Period filter ────────────────────────────────────
        PeriodFilterRow(onRangeChanged = { f, t -> filterFromMs = f; filterToMs = t })

        RideHistoryAdvancedFilters(
            zones = allZones,
            outsideLabel = st.zones.outsideZones,
            fromZoneName = fromZoneName,
            toZoneName = toZoneName,
            kmText = kmFilterText,
            fareText = fareFilterText,
            durationText = durationFilterText,
            kmIsMin = kmFilterIsMin,
            fareIsMin = fareFilterIsMin,
            durationIsMin = durationFilterIsMin,
            onFromSelected = { fromZoneName = it },
            onToSelected = { toZoneName = it },
            onKmChange = { kmFilterText = it },
            onFareChange = { fareFilterText = it },
            onDurationChange = { durationFilterText = it },
            onToggleKmMode = {
                kmFilterIsMin = !kmFilterIsMin
                appliedKmFilterIsMin = kmFilterIsMin
                if (kmFilterText.isNotBlank()) appliedKmFilterText = kmFilterText
            },
            onToggleFareMode = {
                fareFilterIsMin = !fareFilterIsMin
                appliedFareFilterIsMin = fareFilterIsMin
                if (fareFilterText.isNotBlank()) appliedFareFilterText = fareFilterText
            },
            onToggleDurationMode = {
                durationFilterIsMin = !durationFilterIsMin
                appliedDurationFilterIsMin = durationFilterIsMin
                if (durationFilterText.isNotBlank()) appliedDurationFilterText = durationFilterText
            },
            onApplyKm = {
                appliedKmFilterText = kmFilterText
                appliedKmFilterIsMin = kmFilterIsMin
            },
            onApplyFare = {
                appliedFareFilterText = fareFilterText
                appliedFareFilterIsMin = fareFilterIsMin
            },
            onApplyDuration = {
                appliedDurationFilterText = durationFilterText
                appliedDurationFilterIsMin = durationFilterIsMin
            },
            onClear = {
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

        RideHistorySortRow(
            selected = sortBy,
            ascending = sortAsc,
            onSelected = { sortBy = it },
            onToggleDirection = { sortAsc = !sortAsc },
        )

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
        var editTipFareRide by remember { mutableStateOf<Ride?>(null) }

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
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayed.forEach { ride ->
                    key(ride.id) {
                        SwipeToDeleteBox(
                            onDeleteRequest = { pendingDelete = { vm.deleteRide(ride) } }
                        ) {
                            HistoryRideCard(
                                ride          = ride,
                                zones         = allZones,
                                isExpanded    = expandedId == ride.id,
                                onToggle      = { expandedId = if (expandedId == ride.id) null else ride.id },
                                onDelete      = { pendingDelete = { vm.deleteRide(ride) } },
                                onEditRoute   = { editRouteRide = ride },
                                onEditTipFare = { editTipFareRide = ride },
                            )
                        }
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

        editTipFareRide?.let { ride ->
            EditTipFareDialog(
                ride      = ride,
                onSave    = { newPrice, newTip ->
                    vm.updateRide(ride.copy(price = newPrice, tip = newTip))
                    editTipFareRide = null
                },
                onDismiss = { editTipFareRide = null },
            )
        }
    }

}

@Composable
private fun RideHistorySortRow(
    selected: RideHistorySort,
    ascending: Boolean,
    onSelected: (RideHistorySort) -> Unit,
    onToggleDirection: () -> Unit,
) {
    val tc = LocalThemeColors.current
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        RideHistorySort.NEWEST to "Дата",
        RideHistorySort.REVENUE to "Оборот",
        RideHistorySort.DURATION to "Време",
        RideHistorySort.KM to "Км",
        RideHistorySort.WAIT to "Престой",
        RideHistorySort.TIP to "Бакшиш",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
            ) {
                Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Сортирай: ${labels[selected]}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(tc.card)) {
                labels.forEach { (sort, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (sort == selected) tc.accent else tc.textPrimary) },
                        onClick = { onSelected(sort); expanded = false },
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onToggleDirection,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
        ) {
            Text(if (ascending) "↑" else "↓", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RideHistoryAdvancedFilters(
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
    val hasFilters = fromZoneName != null || toZoneName != null ||
        kmText.isNotBlank() || fareText.isNotBlank() || durationText.isNotBlank()

    Card(
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Разширени филтри", color = tc.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (hasFilters) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Clear, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Изчисти", fontSize = 11.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RideHistoryZoneDropdown(
                    label = "От зона",
                    selected = fromZoneName,
                    options = options,
                    modifier = Modifier.weight(1f),
                    onSelected = onFromSelected,
                )
                RideHistoryZoneDropdown(
                    label = "До зона",
                    selected = toZoneName,
                    options = options,
                    modifier = Modifier.weight(1f),
                    onSelected = onToSelected,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RideHistoryNumberFilter(
                    label = "Км",
                    value = kmText,
                    suffix = settings.distanceUnit.shortLabel,
                    isMin = kmIsMin,
                    modifier = Modifier.weight(1f),
                    onToggleMode = onToggleKmMode,
                    onValueChange = onKmChange,
                    onApply = onApplyKm,
                )
                RideHistoryNumberFilter(
                    label = "Сума",
                    value = fareText,
                    suffix = settings.currency.symbol,
                    isMin = fareIsMin,
                    modifier = Modifier.weight(1f),
                    onToggleMode = onToggleFareMode,
                    onValueChange = onFareChange,
                    onApply = onApplyFare,
                )
                RideHistoryNumberFilter(
                    label = "Време",
                    value = durationText,
                    suffix = "мин",
                    isMin = durationIsMin,
                    modifier = Modifier.weight(1f),
                    onToggleMode = onToggleDurationMode,
                    onValueChange = onDurationChange,
                    onApply = onApplyDuration,
                )
            }
        }
    }
}

@Composable
private fun RideHistoryZoneDropdown(
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
                        Text(text, color = if (value == selected) tc.accent else tc.textPrimary, fontSize = 13.sp)
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

@Composable
private fun RideHistoryNumberFilter(
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
        onValueChange = { raw ->
            onValueChange(raw.filter { it.isDigit() || it == '.' || it == ',' })
        },
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
private fun HistoryRideCard(
    ride: Ride,
    zones: List<Zone>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEditRoute: () -> Unit,
    onEditTipFare: () -> Unit,
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
    val durationMinutes = ((ride.endTime - ride.startTime).coerceAtLeast(0L)) / 60_000L
    val fromAddress = simplifyRideAddress(ride.fromAddress)
    val toAddress = simplifyRideAddress(ride.toAddress)
    val addressRoute = when {
        fromAddress.isNotEmpty() && toAddress.isNotEmpty() -> "$fromAddress → $toAddress"
        fromAddress.isNotEmpty() -> fromAddress
        toAddress.isNotEmpty() -> toAddress
        else -> ""
    }

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
                    text        = { Text(st.history.editFareTipLabel, color = tc.accent) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = tc.accent) },
                    onClick     = { showMenu = false; onEditTipFare() },
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
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (zoneLabel.isNotEmpty()) {
                            RideHistoryDetailRow("Зони", zoneLabel)
                        }
                        if (addressRoute.isNotEmpty()) {
                            RideHistoryDetailRow("Адрес", addressRoute)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RideHistoryDetailChip(
                                label = "Времетраене",
                                value = "$durationMinutes мин",
                                modifier = Modifier.weight(1f),
                            )
                            RideHistoryDetailChip(
                                label = "Общо",
                                value = settings.formatPrice(ride.price + ride.tip),
                                modifier = Modifier.weight(1f),
                                valueColor = tc.accent,
                            )
                        }
                    }
                    if (routePoints.size >= 2) {
                        PremiumGate(
                            modifier    = Modifier.fillMaxWidth(),
                            featureHint = st.premium.gateHintMap,
                        ) {
                            RouteMapSection(routePoints, st.history.startMarker, st.history.endMarker)
                        }
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

@Composable
private fun RideHistoryDetailRow(label: String, value: String) {
    val tc = LocalThemeColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = tc.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        Text(
            value,
            color = tc.textPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RideHistoryDetailChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalThemeColors.current.textPrimary,
) {
    val tc = LocalThemeColors.current
    Column(
        modifier
            .background(tc.cardAlt, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = tc.muted, fontSize = 10.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
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

@Composable
private fun EditTipFareDialog(
    ride: Ride,
    onSave: (newPrice: Double, newTip: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    val settings = LocalSettings.current

    var priceText by remember {
        mutableStateOf(
            if (ride.price == ride.price.toLong().toDouble())
                ride.price.toLong().toString()
            else "%.2f".format(ride.price)
        )
    }
    var tipText by remember {
        mutableStateOf(
            if (ride.tip == ride.tip.toLong().toDouble())
                ride.tip.toLong().toString()
            else "%.2f".format(ride.tip)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = tc.card,
        title = {
            Text(
                st.history.editFareTipLabel,
                color = tc.textPrimary, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Fare field
                OutlinedTextField(
                    value         = priceText,
                    onValueChange = { priceText = it },
                    label         = { Text("${st.history.fareLabel} (${settings.currency.symbol})", color = tc.muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = tc.accent,
                        unfocusedBorderColor = tc.surface,
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                        cursorColor          = tc.accent,
                        focusedLabelColor    = tc.accent,
                    )
                )
                // Tip field
                OutlinedTextField(
                    value         = tipText,
                    onValueChange = { tipText = it },
                    label         = { Text("${st.tipLabel} (${settings.currency.symbol})", color = tc.muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = tc.purple,
                        unfocusedBorderColor = tc.surface,
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                        cursorColor          = tc.purple,
                        focusedLabelColor    = tc.purple,
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newPrice = priceText.replace(",", ".").toDoubleOrNull() ?: ride.price
                    val newTip   = tipText.replace(",", ".").toDoubleOrNull()   ?: ride.tip
                    onSave(newPrice, newTip)
                },
                colors = ButtonDefaults.buttonColors(containerColor = tc.accent),
            ) {
                Text(st.saveBtn, color = tc.background, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(st.cancelBtn, color = tc.muted)
            }
        }
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

