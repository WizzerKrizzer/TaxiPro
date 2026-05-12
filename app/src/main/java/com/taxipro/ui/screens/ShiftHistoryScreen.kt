package com.taxipro.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.Ride
import com.taxipro.data.db.Shift
import com.taxipro.data.db.Zone
import com.taxipro.data.db.formatPrice
import com.taxipro.data.db.longestZoneWait
import com.taxipro.data.db.rideRouteLabels
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

// ── 50 distinct high-contrast polyline colors ────────────────────
// Colors ordered with step-11 interleave so consecutive cards always differ in hue
// (original list had 5-hue-family rows → every 5th card looked similar)
private val RIDE_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF039BE5), Color(0xFF00897B), Color(0xFFE65100), Color(0xFF4A148C),
    Color(0xFFE91E63), Color(0xFF0097A7), Color(0xFF2E7D32), Color(0xFF37474F), Color(0xFF311B92),
    Color(0xFFF4511E), Color(0xFF1E88E5), Color(0xFF1B5E20), Color(0xFF455A64), Color(0xFF8E24AA),
    Color(0xFF3949AB), Color(0xFF0288D1), Color(0xFF9E9D24), Color(0xFF9C6A00), Color(0xFF7B1FA2),
    Color(0xFFD81B60), Color(0xFF0277BD), Color(0xFF827717), Color(0xFFFFB300), Color(0xFF616161),
    Color(0xFFC62828), Color(0xFF00838F), Color(0xFF33691E), Color(0xFFFF8F00), Color(0xFF6A1B9A),
    Color(0xFFAD1457), Color(0xFF006064), Color(0xFF43A047), Color(0xFFF6BF26), Color(0xFF4527A0),
    Color(0xFF6D4C41), Color(0xFF01579B), Color(0xFF4CAF50), Color(0xFFF57F17), Color(0xFF283593),
    Color(0xFF4E342E), Color(0xFF00ACC1), Color(0xFF7CB342), Color(0xFFEF6C00), Color(0xFF1565C0),
    Color(0xFFBF360C), Color(0xFF00BCD4), Color(0xFF558B2F), Color(0xFFFF6F00), Color(0xFF880E4F),
)

// ── Route JSON parser ─────────────────────────────────────────────
private fun parseRideRouteJson(json: String): List<LatLng> {
    if (json == "[]" || json.isBlank()) return emptyList()
    return Regex("""\[([+-]?\d+\.?\d*),([+-]?\d+\.?\d*)\]""")
        .findAll(json)
        .map { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        .toList()
}

private fun formatDurationForShiftHistory(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}


@Composable
fun ShiftHistoryScreen(rideVm: RideViewModel) {
    val tc        = LocalThemeColors.current
    val st        = LocalStrings.current
    val allShifts by rideVm.allShifts.collectAsState(initial = emptyList())
    var expandedId by remember { mutableStateOf<Long?>(null) }

    val nowMs        = remember { System.currentTimeMillis() }
    val initWeek     = remember { pfCurrentWeekRange() }
    var filterFromMs by remember { mutableStateOf<Long?>(initWeek.first) }
    var filterToMs   by remember { mutableStateOf<Long?>(initWeek.second) }

    val displayed = remember(allShifts, filterFromMs, filterToMs) {
        if (filterFromMs != null && filterToMs != null)
            allShifts.filter { it.startTime in filterFromMs!!..filterToMs!! }
        else
            allShifts
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(st.shiftHistoryScreen, color = tc.textPrimary,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(st.shiftsRecorded.format(displayed.size), color = tc.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        PeriodFilterRow(onRangeChanged = { f, t -> filterFromMs = f; filterToMs = t })
        Spacer(Modifier.height(6.dp))

        var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }

        if (displayed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(st.noShifts, color = tc.muted, fontSize = 14.sp)
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayed.forEach { shift ->
                    SwipeToDeleteBox(onDeleteRequest = { pendingDelete = { rideVm.deleteShift(shift) } }) {
                        ShiftCard(
                            shift      = shift,
                            rideVm     = rideVm,
                            isExpanded = expandedId == shift.id,
                            onToggle   = { expandedId = if (expandedId == shift.id) null else shift.id },
                            onDelete   = { pendingDelete = { rideVm.deleteShift(shift) } },
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        pendingDelete?.let { action ->
            DeleteConfirmDialog(
                message   = st.history.deleteShiftConfirmMsg,
                onConfirm = { action(); pendingDelete = null },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@Composable
private fun ShiftCard(
    shift: Shift,
    rideVm: RideViewModel,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val rides    by rideVm.getRidesByShift(shift.id).collectAsState(initial = emptyList())

    val sdfDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    val endMs       = if (shift.endTime > 0) shift.endTime else System.currentTimeMillis()
    val durationMs  = endMs - shift.startTime
    val durationH   = durationMs / 3_600_000L
    val durationMin = (durationMs % 3_600_000L) / 60_000L
    val durStr      = if (durationH > 0)
        "${durationH}${st.hoursAbbr} ${durationMin}${st.minAbbr}"
    else
        "${durationMin}${st.minAbbr}"

    val revenue  = rides.sumOf { it.price }
    val tips     = rides.sumOf { it.tip }
    val km       = rides.sumOf { it.kilometers }
    // Use total shift km (includes dead miles); fall back to client km for legacy shifts
    val shiftKm  = if (shift.totalKm > 0.0) shift.totalKm else km
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            // ── Header row ───────────────────────────────────
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
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(
                            if (shift.isActive) tc.green.copy(alpha = 0.15f)
                            else tc.accent.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "#${shift.shiftNumber}",
                        color      = if (shift.isActive) tc.green else tc.accent,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            st.shiftNum.format(shift.shiftNumber),
                            color = tc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (shift.isActive) {
                            Box(
                                Modifier
                                    .background(tc.green.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(st.activeLabel, color = tc.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val durFormatted = "%02d:%02d${st.hoursAbbr}".format(durationH, durationMin)
                    Text(
                        "${sdfDate.format(Date(shift.startTime))}  " +
                        "${sdfTime.format(Date(shift.startTime))} – " +
                        if (shift.endTime > 0)
                            "${sdfTime.format(Date(shift.endTime))} ($durFormatted)"
                        else "...",
                        color = tc.muted, fontSize = 11.sp
                    )
                    val kmText = if (shiftKm > km + 0.05)
                        "${rides.size} ${st.ridesLabel}  •  $durStr  •  %.1f km (%.1f ${st.withClients})".format(shiftKm, km)
                    else
                        "${rides.size} ${st.ridesLabel}  •  $durStr  •  %.1f km".format(km)
                    Text(kmText, color = tc.muted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    if (tips > 0) {
                        // revenue
                        Text(
                            settings.formatPrice(revenue),
                            color = tc.muted, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        // tips
                        Text(
                            "+${settings.formatPrice(tips)} ${st.tipBadgeShort}",
                            color = tc.purple, fontSize = 11.sp
                        )
                        // total
                        Text(
                            settings.formatPrice(revenue + tips),
                            color = tc.accent, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            settings.formatPrice(revenue),
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
                    text        = { Text(st.history.deleteShiftLabel, color = tc.red) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = tc.red) },
                    onClick     = { showMenu = false; onDelete() },
                )
            }
            } // close Box

            // ── Expandable detail ────────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = tc.muted.copy(alpha = 0.15f))
                    ShiftDetailPanel(shift = shift, rides = rides, rideVm = rideVm)
                }
            }
        }
    }
}

@Composable
private fun ShiftDetailPanel(shift: Shift, rides: List<Ride>, rideVm: RideViewModel) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showMap       by remember { mutableStateOf(false) }
    var showDetail    by remember { mutableStateOf(false) }
    var showGate      by remember { mutableStateOf(false) }
    var selectedRide  by remember { mutableStateOf<Ride?>(null) }
    val zones         by rideVm.allZones.collectAsState(initial = emptyList())
    val zoneWaits     by rideVm.getZoneWaitsByShift(shift.id).collectAsState(initial = emptyList())
    val chartScale    by rideVm.chartScale.collectAsState()
    val isPremium      = LocalIsPremium.current
    val onUpgrade      = LocalOnUpgrade.current

    val totalRevenue = rides.sumOf { it.price }
    val totalTips    = rides.sumOf { it.tip }
    val totalKm      = rides.sumOf { it.kilometers }
    val totalWait    = rides.sumOf { it.waitMinutes }
    val avgPerRide   = if (rides.isNotEmpty()) totalRevenue / rides.size else 0.0
    val shiftKm      = if (shift.totalKm > 0.0) shift.totalKm else totalKm
    val avgFuelCostPerKm = if (rides.isNotEmpty())
        rides.sumOf { it.fuelCostPerKm } / rides.size else 0.0
    val fuelCost     = shiftKm * avgFuelCostPerKm
    val taxCost      = rides.sumOf { it.price * it.taxPercent / 100.0 }
    val netProfit    = totalRevenue - fuelCost - taxCost
    val longestZoneWait = remember(zoneWaits) { zoneWaits.longestZoneWait() }

    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            MiniStat(st.avgRideLabel, settings.formatPrice(avgPerRide), tc.accent)
            MiniStat(st.tipsLabel,    settings.formatPrice(totalTips),  tc.purple)
            MiniStat(st.profitLabel,  settings.formatPrice(netProfit),  tc.green)
            // Map button
            IconButton(
                onClick  = { if (isPremium) showMap = true else showGate = true },
                modifier = Modifier
                    .size(36.dp)
                    .background(tc.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.Map, null, tint = tc.accent, modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = tc.muted.copy(alpha = 0.15f))

        // ── Earnings timeline chart ──────────────────────────
        if (rides.isNotEmpty()) {
            val actualDurationHours = if (shift.endTime > 0)
                (shift.endTime - shift.startTime) / 3_600_000.0
            else 8.0
            ShiftEarningsChart(
                rides                 = rides,
                waitSessions          = zoneWaits,
                shiftStartMs          = shift.startTime,
                shiftEndMs            = if (shift.endTime > 0) shift.endTime
                                        else System.currentTimeMillis(),
                accentColor           = tc.accent,
                yAxisScale            = chartScale,
                expectedDurationHours = actualDurationHours,
                useActualShiftScale   = shift.endTime > 0,
            )
        }

        longestZoneWait?.let { wait ->
            Card(
                colors = CardDefaults.cardColors(containerColor = tc.cardAlt.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Най-дълго чакане в зона", color = tc.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${wait.zoneName}  •  ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wait.startTime))} - ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wait.endTime))}",
                            color = tc.muted,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        formatDurationForShiftHistory(wait.durationMs),
                        color = tc.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (rides.isEmpty()) {
            Text(st.noRidesInShift, color = tc.muted, fontSize = 12.sp)
        } else {
            val sortedRides = rides.sortedByDescending { it.startTime }

            // ── Compute "best fact" badges per ride ──────────
            val rideBadges: Map<Long, List<Pair<String, Color>>> = remember(rides) {
                if (rides.size < 2) return@remember emptyMap()
                val result = mutableMapOf<Long, MutableList<Pair<String, Color>>>()
                fun add(id: Long, label: String, value: String, color: Color) {
                    result.getOrPut(id) { mutableListOf() }
                        .add("$label  $value" to color)
                }

                val kmRides = rides.filter { it.kilometers > 0.01 }
                if (kmRides.size >= 2) {
                    kmRides.maxByOrNull { it.kilometers }?.let {
                        add(it.id, st.facts.factLongestKm, "%.1f km".format(it.kilometers), Color(0xFF29B6F6))
                    }
                    kmRides.minByOrNull { it.kilometers }?.let {
                        add(it.id, st.facts.factShortestKm, "%.1f km".format(it.kilometers), Color(0xFF78909C))
                    }
                }

                rides.maxByOrNull { it.price }?.let {
                    add(it.id, st.facts.factHighestFare, settings.formatPrice(it.price), Color(0xFF4CAF50))
                }

                val tipRides = rides.filter { it.tip > 0.01 }
                if (tipRides.isNotEmpty()) {
                    tipRides.maxByOrNull { it.tip }?.let {
                        add(it.id, st.facts.factBiggestTip, settings.formatPrice(it.tip), Color(0xFFCE93D8))
                    }
                }

                val waitRides = rides.filter { it.waitMinutes > 0.5 }
                if (waitRides.size >= 2) {
                    waitRides.maxByOrNull { it.waitMinutes }?.let {
                        add(it.id, st.facts.factLongestWait, "%.0f ${st.minAbbr}".format(it.waitMinutes), Color(0xFFFF9800))
                    }
                }

                val durRides = rides.filter { it.endTime > it.startTime }
                if (durRides.size >= 2) {
                    durRides.maxByOrNull { it.endTime - it.startTime }?.let {
                        val mins = ((it.endTime - it.startTime) / 60_000L).toInt()
                        add(it.id, st.facts.factLongestDuration, "$mins ${st.minAbbr}", Color(0xFFF5C842))
                    }
                }

                result
            }

            sortedRides.forEachIndexed { idx, ride ->
                val (zoneLabel, addrLabel) = remember(ride.id, zones) {
                    rideRouteLabels(ride, zones, st.zones.outsideZones)
                }
                val badges = rideBadges[ride.id] ?: emptyList()
                var showRideMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(tc.cardAlt.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
                            .border(1.dp, tc.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap       = { selectedRide = ride },
                                    onLongPress = { showRideMenu = true },
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.Top,
                    ) {
                        Text(
                            "${sortedRides.size - idx}.",
                            color    = tc.muted, fontSize = 11.sp,
                            modifier = Modifier.width(20.dp).padding(top = 2.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            // Zone line (if available)
                            if (zoneLabel.isNotEmpty()) {
                                Text(zoneLabel, color = tc.accent, fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Address line (or ride number fallback)
                            val mainLabel = addrLabel.ifEmpty { "${st.ridePrefix}${ride.globalId}" }
                            Text(mainLabel, color = tc.textPrimary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (badges.isNotEmpty()) {
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    badges.forEach { (text, color) ->
                                        Box(
                                            Modifier
                                                .background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(text, color = color, fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            settings.formatPrice(ride.price),
                            color = tc.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = tc.muted.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                        )
                    }
                    DropdownMenu(
                        expanded         = showRideMenu,
                        onDismissRequest = { showRideMenu = false },
                        modifier         = Modifier.background(tc.cardAlt),
                    ) {
                        DropdownMenuItem(
                            text        = { Text(st.history.deleteLabel, color = tc.red) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = tc.red) },
                            onClick     = { showRideMenu = false; pendingDelete = { rideVm.deleteRide(ride) } },
                        )
                    }
                }
                if (idx < sortedRides.lastIndex)
                    Spacer(Modifier.height(6.dp))
            }
        }
    }

    // ── More info button ─────────────────────────────────────────
    if (rides.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = tc.muted.copy(alpha = 0.15f))
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = { if (isPremium) showDetail = true else showGate = true },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor = tc.accent.copy(alpha = 0.15f),
                contentColor   = tc.accent,
            ),
            shape     = RoundedCornerShape(10.dp),
            elevation = ButtonDefaults.buttonElevation(0.dp),
        ) {
            Icon(Icons.Default.Analytics, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(st.detail.moreInfoBtn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

    if (showMap) {
        ShiftMapDialog(shift = shift, rides = rides, onDismiss = { showMap = false })
    }
    if (showDetail) {
        ShiftDetailDialog(shift = shift, rides = rides, zones = zones, rideVm = rideVm, onDismiss = { showDetail = false })
    }
    selectedRide?.let { ride ->
        ZoneRideDetailDialog(
            ride      = ride,
            allShifts = listOf(shift),
            settings  = settings,
            onDismiss = { selectedRide = null },
        )
    }
    if (showGate) {
        PremiumUpgradeDialog(
            hint      = st.premium.gateHintDetail,
            onUpgrade = { showGate = false; onUpgrade() },
            onDismiss = { showGate = false },
        )
    }

    pendingDelete?.let { action ->
        DeleteConfirmDialog(
            message   = st.history.deleteRideConfirmMsg,
            onConfirm = { action(); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ── Shift Map Dialog ─────────────────────────────────────────────

@Composable
private fun ShiftMapDialog(shift: Shift, rides: List<Ride>, onDismiss: () -> Unit) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdfTime  = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Parse all routes upfront; keep only rides with ≥2 points
    val rideRoutes: List<Pair<Ride, List<LatLng>>> = remember(rides) {
        rides.sortedBy { it.startTime }.mapIndexedNotNull { _, ride ->
            val pts = parseRideRouteJson(ride.routePointsJson)
            if (pts.size >= 2) ride to pts else null
        }
    }

    // All lat/lng for bounds computation
    val allPoints: List<LatLng> = remember(rideRoutes) {
        rideRoutes.flatMap { (_, pts) -> pts }
    }

    val cameraState = rememberCameraPositionState()
    var selectedRide by remember { mutableStateOf<Ride?>(null) }

    // Fit camera to all routes once map is ready
    LaunchedEffect(allPoints) {
        if (allPoints.isEmpty()) return@LaunchedEffect
        val builder = LatLngBounds.builder()
        allPoints.forEach { builder.include(it) }
        val bounds = builder.build()
        cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, 80))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tc.background)
        ) {
            // ── Map ──────────────────────────────────────────────
            GoogleMap(
                modifier            = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                uiSettings          = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                properties          = MapProperties(isMyLocationEnabled = false),
                onMapClick          = { selectedRide = null },
            ) {
                rideRoutes.forEachIndexed { idx, (ride, points) ->
                    val color = RIDE_COLORS[idx % RIDE_COLORS.size]
                    Polyline(
                        points    = points,
                        color     = color,
                        width     = 10f,
                        clickable = true,
                        onClick   = { selectedRide = ride },
                        zIndex    = idx.toFloat(),
                    )
                    // Start marker
                    Marker(
                        state = MarkerState(points.first()),
                        icon  = createLabeledMarker(
                            android.graphics.Color.argb(
                                (color.alpha * 255).toInt(),
                                (color.red   * 255).toInt(),
                                (color.green * 255).toInt(),
                                (color.blue  * 255).toInt(),
                            ),
                            "${idx + 1}"
                        ),
                        title = "${st.ridePrefix}${idx + 1}",
                        onClick = { m -> selectedRide = ride; m.showInfoWindow(); true }
                    )
                }
            }

            // ── Top bar ───────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tc.background.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                }
                Text(
                    "${st.shiftNum.format(shift.shiftNumber)}  •  " +
                    "${sdfTime.format(Date(shift.startTime))} – " +
                    if (shift.endTime > 0) sdfTime.format(Date(shift.endTime)) else "...",
                    color      = tc.textPrimary,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f)
                )
                // Color legend dots
                if (rideRoutes.size <= 10) {
                    rideRoutes.forEachIndexed { idx, _ ->
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(RIDE_COLORS[idx % RIDE_COLORS.size], RoundedCornerShape(5.dp))
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────
            if (rideRoutes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = tc.card.copy(alpha = 0.95f)),
                        shape  = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Map, null,
                                tint = tc.muted, modifier = Modifier.size(40.dp))
                            Text(st.prefs.noRoutesAvailable, color = tc.muted, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ── Ride info popup (bottom) ──────────────────────────
            selectedRide?.let { ride ->
                val rideIdx = rideRoutes.indexOfFirst { it.first.id == ride.id }
                val color   = if (rideIdx >= 0) RIDE_COLORS[rideIdx % RIDE_COLORS.size] else tc.accent
                val sdf     = SimpleDateFormat("HH:mm", Locale.getDefault())

                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = tc.card),
                    shape  = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Header with color indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(color, RoundedCornerShape(6.dp))
                            )
                            Text(
                                "${st.ridePrefix}${rideIdx + 1}  •  " +
                                "${sdf.format(Date(ride.startTime))} – ${sdf.format(Date(ride.endTime))}",
                                color = tc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick  = { selectedRide = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, null, tint = tc.muted,
                                    modifier = Modifier.size(16.dp))
                            }
                        }

                        // Address
                        val addr = when {
                            ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                                "${ride.fromAddress.substringBefore(",").take(22)} → " +
                                ride.toAddress.substringBefore(",").take(22)
                            ride.fromAddress.isNotEmpty() -> ride.fromAddress.take(40)
                            else -> ""
                        }
                        if (addr.isNotEmpty()) {
                            Text(addr, color = tc.muted, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }

                        HorizontalDivider(color = tc.surface)

                        // Stats row
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            RideInfoStat("%.1f km".format(ride.kilometers), st.kilometersLabel, tc.muted)
                            RideInfoStat("%.0f min".format(ride.waitMinutes), st.waitLabel, tc.muted)
                            if (ride.tip > 0) {
                                RideInfoStat(settings.formatPrice(ride.tip), st.tipsLabel, tc.purple)
                                RideInfoStat(settings.formatPrice(ride.price + ride.tip),
                                    st.totalRevenue, color)
                            } else {
                                RideInfoStat(settings.formatPrice(ride.price), st.totalRevenue, color)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideInfoStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = LocalThemeColors.current.muted, fontSize = 10.sp)
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    val tc = LocalThemeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = tc.muted, fontSize = 9.sp)
    }
}

// ── Shift Detail Dialog ──────────────────────────────────────────

@Composable
private fun ShiftDetailDialog(shift: Shift, rides: List<Ride>, zones: List<Zone>, rideVm: RideViewModel, onDismiss: () -> Unit) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdfDate  = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val sdfTime  = SimpleDateFormat("HH:mm",       Locale.getDefault())
    var selectedRide by remember { mutableStateOf<Ride?>(null) }

    // ── Computed values ──────────────────────────────────────
    val sortedRides   = remember(rides) { rides.sortedBy { it.startTime } }
    val totalRevenue  = rides.sumOf { it.price }
    val totalTips     = rides.sumOf { it.tip }
    val totalClientKm = rides.sumOf { it.kilometers }
    val shiftKm       = if (shift.totalKm > 0.0) shift.totalKm else totalClientKm
    val avgFuelPerKm  = if (rides.isNotEmpty()) rides.sumOf { it.fuelCostPerKm } / rides.size else 0.0
    val fuelCost      = shiftKm * avgFuelPerKm
    val taxCost       = rides.sumOf { it.price * it.taxPercent / 100.0 }
    val netProfit     = totalRevenue + totalTips - fuelCost - taxCost

    val avgFare       = if (rides.isNotEmpty()) totalRevenue / rides.size else 0.0
    val avgKmPerRide  = if (rides.isNotEmpty()) totalClientKm / rides.size else 0.0
    val clientRatio   = if (shiftKm > 0) totalClientKm / shiftKm * 100 else 0.0

    val maxSpeed      = rides.maxOfOrNull { it.maxSpeed } ?: 0.0
    val avgSpeedRides = rides.filter { it.avgSpeed > 0 }
    val avgSpeed      = if (avgSpeedRides.isNotEmpty()) avgSpeedRides.sumOf { it.avgSpeed } / avgSpeedRides.size else 0.0

    val endMs         = if (shift.endTime > 0) shift.endTime else System.currentTimeMillis()
    val shiftDurMs    = endMs - shift.startTime
    val shiftDurH     = shiftDurMs / 3_600_000L
    val shiftDurMin   = (shiftDurMs % 3_600_000L) / 60_000L
    val activeDurMs   = rides.sumOf { if (it.endTime > it.startTime) it.endTime - it.startTime else 0L }
    val activeH       = activeDurMs / 3_600_000L
    val activeMin     = (activeDurMs % 3_600_000L) / 60_000L
    val totalWaitMin  = rides.sumOf { it.waitMinutes }
    val efficiency    = if (shiftDurMs > 0) activeDurMs.toDouble() / shiftDurMs * 100 else 0.0

    // "Best fact" badges (same logic as ride list)
    val rideBadges: Map<Long, List<Pair<String, Color>>> = remember(rides) {
        if (rides.size < 2) return@remember emptyMap()
        val result = mutableMapOf<Long, MutableList<Pair<String, Color>>>()
        fun add(id: Long, label: String, value: String, color: Color) {
            result.getOrPut(id) { mutableListOf() }.add("$label  $value" to color)
        }
        val kmRides = rides.filter { it.kilometers > 0.01 }
        if (kmRides.size >= 2) {
            kmRides.maxByOrNull { it.kilometers }?.let {
                add(it.id, st.facts.factLongestKm, "%.1f km".format(it.kilometers), Color(0xFF29B6F6)) }
            kmRides.minByOrNull { it.kilometers }?.let {
                add(it.id, st.facts.factShortestKm, "%.1f km".format(it.kilometers), Color(0xFF78909C)) }
        }
        rides.maxByOrNull { it.price }?.let {
            add(it.id, st.facts.factHighestFare, settings.formatPrice(it.price), Color(0xFF4CAF50)) }
        rides.filter { it.tip > 0.01 }.maxByOrNull { it.tip }?.let {
            add(it.id, st.facts.factBiggestTip, settings.formatPrice(it.tip), Color(0xFFCE93D8)) }
        val waitRides = rides.filter { it.waitMinutes > 0.5 }
        if (waitRides.size >= 2) waitRides.maxByOrNull { it.waitMinutes }?.let {
            add(it.id, st.facts.factLongestWait, "%.0f ${st.minAbbr}".format(it.waitMinutes), Color(0xFFFF9800)) }
        val durRides = rides.filter { it.endTime > it.startTime }
        if (durRides.size >= 2) durRides.maxByOrNull { it.endTime - it.startTime }?.let {
            val m = ((it.endTime - it.startTime) / 60_000L).toInt()
            add(it.id, st.facts.factLongestDuration, "$m ${st.minAbbr}", Color(0xFFF5C842)) }
        result
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(tc.background)) {

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                // ── Top bar ──────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tc.card)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${st.detail.detailTitle}  •  ${st.shiftNum.format(shift.shiftNumber)}",
                            color = tc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${sdfDate.format(Date(shift.startTime))}  " +
                            "${sdfTime.format(Date(shift.startTime))} – " +
                            if (shift.endTime > 0) sdfTime.format(Date(shift.endTime)) else "...",
                            color = tc.muted, fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Earnings timeline chart ──────────────────────
                val chartScale by rideVm.chartScale.collectAsState()
                if (sortedRides.isNotEmpty()) {
                    // Use the actual shift duration for completed shifts (better X-axis fit)
                    val actualDurationHours = if (shift.endTime > 0)
                        (shift.endTime - shift.startTime) / 3_600_000.0
                    else 8.0
                    Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        ShiftEarningsChart(
                            rides                 = sortedRides,
                            shiftStartMs          = shift.startTime,
                            shiftEndMs            = if (shift.endTime > 0) shift.endTime
                                                    else System.currentTimeMillis(),
                            accentColor           = tc.accent,
                            yAxisScale            = chartScale,
                            expectedDurationHours = actualDurationHours,
                            useActualShiftScale   = shift.endTime > 0,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── Financial summary ────────────────────────────
                DetailSection(st.detail.financialSection, tc) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DetailStat(settings.formatPrice(totalRevenue), st.grossRevenue,  tc.textPrimary, tc)
                        DetailStat(settings.formatPrice(totalTips),    st.tipsLabel,     tc.purple,      tc)
                        DetailStat(
                            settings.formatPrice(totalRevenue + totalTips),
                            st.calc.totalLabel, tc.accent, tc
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = tc.surface)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DetailStat("-${settings.formatPrice(fuelCost)}", st.fuelLabel,       tc.red,   tc)
                        DetailStat("-${settings.formatPrice(taxCost)}",  st.detail.taxLabel, tc.red,   tc)
                        DetailStat(settings.formatPrice(netProfit),      st.netProfit,       tc.green, tc)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── Zone breakdown ───────────────────────────────
                ShiftZoneStatsSection(rides = rides, zones = zones)

                Spacer(Modifier.height(10.dp))

                // ── Statistics ───────────────────────────────────
                DetailSection(st.detail.statsSection, tc) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DetailStat("${rides.size}", st.ridesLabel, tc.accent, tc)
                        DetailStat(settings.formatPrice(avgFare), st.avgRideLabel, tc.textPrimary, tc)
                        DetailStat("%.1f km".format(avgKmPerRide), st.detail.avgKmRideLabel, tc.blue, tc)
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = tc.surface)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        DetailStat("%.1f km".format(totalClientKm), st.detail.clientKmLabel, tc.blue, tc)
                        DetailStat("%.1f km".format(shiftKm), st.detail.shiftKmLabel, tc.muted, tc)
                        DetailStat("%.0f%%".format(clientRatio), st.detail.clientRatioLabel, tc.green, tc)
                    }
                    if (maxSpeed > 0 || avgSpeed > 0) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = tc.surface)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            if (maxSpeed > 0)
                                DetailStat("%.0f km/h".format(maxSpeed), st.detail.maxSpeedLabel, tc.textPrimary, tc)
                            if (avgSpeed > 0)
                                DetailStat("%.0f km/h".format(avgSpeed), st.detail.avgSpeedLabel, tc.muted, tc)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── Time breakdown ───────────────────────────────
                DetailSection(st.detail.timeSection, tc) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val shiftDurStr = if (shiftDurH > 0)
                            "${shiftDurH}${st.hoursAbbr} ${shiftDurMin}${st.minAbbr}"
                        else "${shiftDurMin}${st.minAbbr}"
                        val activeStr = if (activeH > 0)
                            "${activeH}${st.hoursAbbr} ${activeMin}${st.minAbbr}"
                        else "${activeMin}${st.minAbbr}"
                        val activeLabelWithRatio = "${st.detail.activeTimeLabel} (%.0f%%)".format(clientRatio)
                        DetailStat(shiftDurStr, st.detail.shiftDurationLabel, tc.textPrimary, tc)
                        DetailStat(activeStr, activeLabelWithRatio, tc.blue, tc)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── All rides ────────────────────────────────────
                DetailSection(st.detail.allRidesSection, tc) {
                    sortedRides.forEachIndexed { idx, ride ->
                        val durationMin = if (ride.endTime > ride.startTime)
                            ((ride.endTime - ride.startTime) / 60_000L).toInt() else 0
                        val badges = rideBadges[ride.id] ?: emptyList()
                        val (zoneLabel, addrLabel) = remember(ride.id, zones) {
                            rideRouteLabels(ride, zones, st.zones.outsideZones)
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(tc.cardAlt.copy(alpha = 0.34f), RoundedCornerShape(10.dp))
                                .border(1.dp, tc.accent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                .clickable { selectedRide = ride }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.Top,
                        ) {
                            // Left: number circle
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .background(
                                        RIDE_COLORS[idx % RIDE_COLORS.size].copy(alpha = 0.2f),
                                        RoundedCornerShape(14.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${idx + 1}",
                                    color      = RIDE_COLORS[idx % RIDE_COLORS.size],
                                    fontSize   = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(10.dp))

                            // Center: zone + address + meta
                            Column(Modifier.weight(1f)) {
                                // Zone line
                                if (zoneLabel.isNotEmpty()) {
                                    Text(zoneLabel, color = tc.accent, fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                // Address line (or ride number)
                                val mainAddr = addrLabel.ifEmpty { "${st.ridePrefix}${ride.globalId}" }
                                Text(mainAddr, color = tc.textPrimary, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${sdfTime.format(Date(ride.startTime))} – ${sdfTime.format(Date(ride.endTime))}",
                                        color = tc.muted, fontSize = 10.sp
                                    )
                                    if (durationMin > 0)
                                        Text("${durationMin}${st.minAbbr}", color = tc.muted, fontSize = 10.sp)
                                }
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (ride.kilometers > 0)
                                        Text("%.1f km".format(ride.kilometers), color = tc.blue, fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold)
                                    if (ride.waitMinutes > 0.5)
                                        Text("%.0f ${st.minAbbr} ${st.waitLabel.lowercase()}".format(ride.waitMinutes),
                                            color = tc.muted, fontSize = 11.sp)
                                }
                                if (badges.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        badges.forEach { (text, color) ->
                                            Box(
                                                Modifier
                                                    .background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(text, color = color, fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            // Right: price + tip
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    settings.formatPrice(ride.price),
                                    color = tc.accent, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                                )
                                if (ride.tip > 0) {
                                    Text(
                                        "+${settings.formatPrice(ride.tip)}",
                                        color = tc.purple, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
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

                        if (idx < sortedRides.lastIndex)
                            Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    selectedRide?.let { ride ->
        ZoneRideDetailDialog(
            ride      = ride,
            allShifts = listOf(shift),
            settings  = settings,
            onDismiss = { selectedRide = null },
        )
    }
}

@Composable
private fun DetailSection(
    title: String,
    tc: ThemeColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors    = CardDefaults.cardColors(containerColor = tc.card),
        shape     = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = tc.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DetailStat(value: String, label: String, color: Color, tc: ThemeColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = tc.muted, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
