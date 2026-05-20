package com.taxipro.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.text.SimpleDateFormat
import com.taxipro.data.db.ExpenseFrequency
import com.taxipro.data.db.ExpenseType
import com.taxipro.data.db.Ride
import com.taxipro.data.db.Shift
import com.taxipro.data.db.TariffExpense
import com.taxipro.data.db.Zone
import com.taxipro.data.db.effectiveShiftKm
import com.taxipro.data.db.expType
import com.taxipro.data.db.findZone
import com.taxipro.data.db.freq
import com.taxipro.data.db.formatPrice
import com.taxipro.data.db.formatDistance
import com.taxipro.data.db.longestZoneWait
import com.taxipro.data.db.primaryZoneLabel
import com.taxipro.data.db.rideEndLatLng
import com.taxipro.data.db.rideStartLatLng
import com.taxipro.data.db.zoneDisplayName
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.taxipro.data.db.SettingsRepository
import kotlinx.coroutines.launch
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.util.*

private fun formatStatsDuration(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}

private data class StatsRouteFilter(
    val from: String,
    val to: String,
)

private fun statsRouteFilter(ride: Ride, zones: List<Zone>, outsideLabel: String): StatsRouteFilter {
    val (sLat, sLng) = rideStartLatLng(ride) ?: (0.0 to 0.0)
    val (eLat, eLng) = rideEndLatLng(ride) ?: (0.0 to 0.0)
    return StatsRouteFilter(
        from = primaryZoneLabel(sLat, sLng, zones, outsideLabel) ?: outsideLabel,
        to = primaryZoneLabel(eLat, eLng, zones, outsideLabel) ?: outsideLabel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: RideViewModel, repo: SettingsRepository) {
    val tc          = LocalThemeColors.current
    val allRides    by vm.allRides.collectAsState(initial = emptyList())
    val allShifts   by vm.allShifts.collectAsState(initial = emptyList())
    val allExpenses by vm.allExpenses.collectAsState(initial = emptyList())
    val allZones    by vm.allZones.collectAsState(initial = emptyList())
    val allZoneWaitSessions by vm.allZoneWaitSessions.collectAsState(initial = emptyList())
    val st          = LocalStrings.current
    val settings    = LocalSettings.current

    // ── Trend state ───────────────────────────────────────────
    var trendMode    by remember { mutableStateOf(TrendMode.DAY) }
    var trendOffset  by remember { mutableIntStateOf(0) }
    var trendSortByValue by remember { mutableStateOf(false) }
    val nowMs        = remember { System.currentTimeMillis() }
    val initWeek     = remember { pfCurrentWeekRange() }
    var filterFromMs by remember { mutableStateOf<Long?>(initWeek.first) }
    var filterToMs   by remember { mutableStateOf<Long?>(initWeek.second) }
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

    val now   = System.currentTimeMillis()
    val dayMs = 86_400_000L

    // When the midnight-to-shift-day setting is ON, rides between 00:00–03:59
    // are treated as belonging to the previous calendar day (4-hour logical shift).
    val midnightShiftMs = if (settings.countMidnightRidesToShiftDay) 4 * 3_600_000L else 0L
    fun Ride.logicalTime(): Long {
        if (midnightShiftMs == 0L) return startTime
        val hour = Calendar.getInstance().apply { timeInMillis = startTime }
            .get(Calendar.HOUR_OF_DAY)
        return if (hour < 4) startTime - midnightShiftMs else startTime
    }
    // "Today" start: if it's currently before 04:00, today's logical day started yesterday
    val effectiveTodayStart = run {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val base = if (settings.countMidnightRidesToShiftDay && currentHour < 4)
            startOfDay(now - dayMs) else startOfDay(now)
        base
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

    val dateFiltered = if (filterFromMs != null && filterToMs != null)
        allRides.filter { it.logicalTime() in filterFromMs!!..filterToMs!! }
    else
        allRides
    val filtered = remember(
        dateFiltered, allZones, fromZoneName, toZoneName, minKm, minFare, minDuration,
        maxKm, maxFare, maxDuration, st.zones.outsideZones,
    ) {
        dateFiltered.filter { ride ->
            val route = if (fromZoneName != null || toZoneName != null) {
                statsRouteFilter(ride, allZones, st.zones.outsideZones)
            } else null
            val durationMin = ((ride.endTime - ride.startTime).coerceAtLeast(0L)) / 60_000.0
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
    val filteredZoneWaits = if (filterFromMs != null && filterToMs != null)
        allZoneWaitSessions.filter { it.startTime in filterFromMs!!..filterToMs!! }
    else
        allZoneWaitSessions
    val longestZoneWait = remember(filteredZoneWaits) { filteredZoneWaits.longestZoneWait() }

    val totalTip   = filtered.sumOf { it.tip }
    val total      = filtered.sumOf { it.price } + totalTip   // gross incl. tips
    val totalKm    = filtered.sumOf { it.kilometers }          // client km only
    val totalWait  = filtered.sumOf { it.waitMinutes }
    val avgPerRide = if (filtered.isNotEmpty()) total / filtered.size else 0.0
    val avgPerKm   = if (totalKm > 0) total / totalKm else 0.0

    // Total km including dead miles: sum shift.totalKm for shifts in filtered set
    val filteredShiftIds = filtered.map { it.shiftId }.filter { it > 0L }.toSet()
    val clientKmByShift  = filtered.filter { it.shiftId > 0L }.groupBy { it.shiftId }.mapValues { (_, rides) ->
        rides.sumOf { it.kilometers }
    }
    val shiftKmMap       = allShifts.associate { shift ->
        shift.id to effectiveShiftKm(shift.totalKm, clientKmByShift[shift.id] ?: 0.0)
    }
    val totalShiftKm     = run {
        val fromShifts = filteredShiftIds.sumOf { id -> shiftKmMap[id] ?: 0.0 }
        // Rides without a shiftId contribute their client km as best estimate
        val noShiftKm  = filtered.filter { it.shiftId <= 0L }.sumOf { it.kilometers }
        // Calculator rides saved inside a shift (startTime == endTime, set by saveCalculatedRide)
        // are not tracked by GPS, so their km is absent from shift.totalKm — add them separately.
        val calcInShift = filtered
            .filter { it.shiftId > 0L && it.startTime == it.endTime }
            .sumOf { it.kilometers }
        fromShifts + noShiftKm + calcInShift
    }
    val taxCost        = filtered.sumOf { it.price * it.taxPercent / 100.0 }
    val totalGpsKm     = filtered.sumOf { it.kilometers - it.adjustmentKm }
    // Weighted average fuel rate from the rides in the selected period.
    val clientFuelCost = filtered.sumOf { (it.kilometers - it.adjustmentKm) * it.fuelCostPerKm }
    val avgFuelRate    = if (totalGpsKm > 0) clientFuelCost / totalGpsKm else 0.0
    val fuelCost       = totalShiftKm * avgFuelRate

    // Разпределение по час
    val hourMap = mutableMapOf<Int, Int>()
    filtered.forEach { ride ->
        val h = Calendar.getInstance().apply { timeInMillis = ride.startTime }
            .get(Calendar.HOUR_OF_DAY)
        hourMap[h] = (hourMap[h] ?: 0) + 1
    }
    val peakHour = hourMap.maxByOrNull { it.value }?.key ?: 0

    // Най-чести маршрути по зони
    val outsideLabel = st.zones.outsideZones
    val routeMap = mutableMapOf<String, Pair<Int, Double>>()
    filtered.forEach { ride ->
        val startCoords = rideStartLatLng(ride)
        val endCoords   = rideEndLatLng(ride)
        // Skip rides with no GPS data — can't assign zones
        if (startCoords == null && endCoords == null) return@forEach
        val (sLat, sLng) = startCoords ?: (0.0 to 0.0)
        val (eLat, eLng) = endCoords   ?: (0.0 to 0.0)
        val fromZone = findZone(sLat, sLng, allZones)
        val toZone   = findZone(eLat, eLng, allZones)
        val fromLabel = fromZone?.let { zoneDisplayName(it, allZones) } ?: if (startCoords != null) outsideLabel else outsideLabel
        val toLabel   = toZone?.let { zoneDisplayName(it, allZones) }   ?: if (endCoords   != null) outsideLabel else outsideLabel
        val key = "$fromLabel → $toLabel"
        val cur = routeMap[key] ?: Pair(0, 0.0)
        routeMap[key] = Pair(cur.first + 1, cur.second + ride.price)
    }
    val topRoutes = routeMap.entries.sortedByDescending { it.value.first }.take(5)

    // ── KPI-table trend data (uses global filtered set) ──────────
    val dayLabels   = st.dayLabels
    val monthLabels = st.monthLabels
    val earnByHour  = DoubleArray(24)
    val earnByDow   = DoubleArray(7)
    filtered.forEach { ride ->
        val tipAdd  = if (settings.includeTipsInTotal) ride.tip else 0.0
        val rev     = ride.price + tipAdd
        val cLogical = Calendar.getInstance().apply { timeInMillis = ride.logicalTime() }
        val dow      = (cLogical.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val hour     = Calendar.getInstance().apply { timeInMillis = ride.startTime }.get(Calendar.HOUR_OF_DAY)
        earnByDow[dow]   += rev
        earnByHour[hour] += rev
    }
    val bestDow  = earnByDow.indices.maxByOrNull  { earnByDow[it]  } ?: 0
    val bestHour = earnByHour.indices.maxByOrNull { earnByHour[it] } ?: 0

    // Use full shift span (first ride start → last ride end per shift) so that
    // idle time between rides is included — gives accurate "revenue per hour worked".
    val totalWorkMs = run {
        val withShift = filtered.filter { it.shiftId > 0L }
        val noShift   = filtered.filter { it.shiftId <= 0L }
        val shiftMs = withShift
            .groupBy { it.shiftId }
            .values
            .sumOf { rides ->
                val s = rides.minOf { it.startTime }
                val e = rides.maxOf { it.endTime }
                (e - s).coerceAtLeast(0L)
            }
        val noShiftMs = noShift.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
        shiftMs + noShiftMs
    }
    val totalWorkH   = totalWorkMs / 3_600_000.0
    val avgPerHour   = if (totalWorkH > 0.05) total / totalWorkH else 0.0
    val totalWorkStr = run {
        val h = (totalWorkMs / 3_600_000L).toInt()
        val m = ((totalWorkMs % 3_600_000L) / 60_000L).toInt()
        if (h > 0) "${h}ч ${m}мин" else "${m}мин"
    }
    val uniqueShifts = filtered.map { it.shiftId }.filter { it > 0L }.toSet().size
    val avgPerShift  = if (uniqueShifts > 0) total / uniqueShifts else avgPerRide
    val completedShiftsAllTime = remember(allShifts) {
        allShifts.filter { it.endTime > it.startTime }
    }
    val ridesByShiftAllTime = remember(allRides) {
        allRides.filter { it.shiftId > 0L && it.endTime > 0L }.groupBy { it.shiftId }
    }
    val avgWorkTimePerShiftAllTime = remember(completedShiftsAllTime) {
        if (completedShiftsAllTime.isEmpty()) 0L
        else completedShiftsAllTime.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) } / completedShiftsAllTime.size
    }
    val avgGrossPerShiftAllTime = remember(completedShiftsAllTime, ridesByShiftAllTime) {
        if (completedShiftsAllTime.isEmpty()) 0.0
        else completedShiftsAllTime.sumOf { shift ->
            ridesByShiftAllTime[shift.id].orEmpty().sumOf { it.price + it.tip }
        } / completedShiftsAllTime.size
    }
    val avgKmPerShiftAllTime = remember(completedShiftsAllTime, ridesByShiftAllTime) {
        if (completedShiftsAllTime.isEmpty()) 0.0
        else completedShiftsAllTime.sumOf { shift ->
            val shiftClientKm = ridesByShiftAllTime[shift.id].orEmpty().sumOf { it.kilometers }
            effectiveShiftKm(shift.totalKm, shiftClientKm)
        } / completedShiftsAllTime.size
    }

    val nowMsClamped = System.currentTimeMillis()
    val periodStartMs = when {
        filtered.isEmpty() -> null
        filterFromMs != null -> filterFromMs
        else -> filtered.minOfOrNull { it.startTime }
    }
    val periodEndMs = when {
        filtered.isEmpty() -> null
        filterToMs != null -> minOf(filterToMs!!, nowMsClamped)
        else -> nowMsClamped
    }
    val msPerMonth = 30.44 * 86_400_000.0
    val filteredMonths: Double = when {
        periodStartMs == null || periodEndMs == null -> 0.0
        else -> ((periodEndMs - periodStartMs).coerceAtLeast(1L)) / msPerMonth
    }
    val rideCount    = filtered.size
    val shiftCount   = uniqueShifts.coerceAtLeast(if (rideCount > 0) 1 else 0)
    val workedDaysCount = filtered.map { startOfDay(it.startTime) }.distinct().size
    val workedWeeksCount = filtered.map { startOfWeek(it.startTime) }.distinct().size
    data class CustomExpRow(val name: String, val cost: Double)
    val customRows = allExpenses
        .groupBy { it.name }
        .map { (_, exps) ->
            val exp  = exps.first()
            val cost = when (exp.freq) {
                ExpenseFrequency.PER_DAY -> if (exp.expType == ExpenseType.FIXED) {
                    exp.amount * workedDaysCount
                } else {
                    exp.amount / 100.0 * total
                }
                ExpenseFrequency.PER_WEEK -> if (exp.expType == ExpenseType.FIXED) {
                    exp.amount * workedWeeksCount
                } else {
                    exp.amount / 100.0 * total
                }
                ExpenseFrequency.PER_RIDE  -> if (exp.expType == ExpenseType.FIXED)
                    exp.amount * rideCount
                    else exp.amount / 100.0 * total
                ExpenseFrequency.PER_SHIFT -> if (exp.expType == ExpenseType.FIXED)
                    exp.amount * shiftCount
                    else exp.amount / 100.0 * total
                ExpenseFrequency.PER_MONTH -> if (exp.expType == ExpenseType.FIXED)
                    exp.amount * filteredMonths
                    else exp.amount / 100.0 * total
            }
            CustomExpRow(exp.name, cost)
        }
        .filter { it.cost > 0.0 }
    val totalCustomCost = customRows.sumOf { it.cost }
    val adjustedNet     = total - fuelCost - taxCost - totalCustomCost

    // ── Goal state ────────────────────────────────────────────
    val scope = rememberCoroutineScope()
    var showGoalDialog  by remember { mutableStateOf(false) }
    var goalInput       by remember { mutableStateOf("") }
    var activePeriod    by remember { mutableStateOf(ActivePeriod.WEEK) }

    // Auto-switch trend to DoW view when month period is selected
    LaunchedEffect(activePeriod) {
        if (activePeriod == ActivePeriod.MONTH) trendMode = TrendMode.DAY
    }

    val weeklyGoal  = settings.weeklyGoal
    val monthlyGoal = weeklyGoal * 4.33

    // Revenue for whatever range is currently selected in the filter
    val goalRevenue = remember(allRides, filterFromMs, filterToMs, settings.includeTipsInTotal) {
        val from = filterFromMs ?: return@remember 0.0
        val to   = filterToMs   ?: return@remember 0.0
        allRides.filter { it.startTime in from..to }
            .sumOf { it.price + if (settings.includeTipsInTotal) it.tip else 0.0 }
    }

    val showGoalCard = weeklyGoal > 0 &&
        (activePeriod == ActivePeriod.WEEK || activePeriod == ActivePeriod.MONTH)
    val currentGoal  = if (activePeriod == ActivePeriod.MONTH) monthlyGoal else weeklyGoal
    val goalLabel    = if (activePeriod == ActivePeriod.MONTH) st.goal.monthlyGoalLabel else st.goal.weeklyGoalLabel
    val goalProgress = if (currentGoal > 0) (goalRevenue / currentGoal).coerceIn(0.0, 1.0) else 0.0
    val daysRemaining = run {
        val endMs = filterToMs ?: (pfCurrentWeekRange().second)
        val msLeft = (endMs - System.currentTimeMillis()).coerceAtLeast(0L)
        ((msLeft / 86_400_000L) + 1).toInt().coerceAtMost(if (activePeriod == ActivePeriod.MONTH) 31 else 7)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(st.statistics, color = tc.textPrimary,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ── Period filter ──
        PeriodFilterRow(
            onRangeChanged  = { f, t -> filterFromMs = f; filterToMs = t },
            onPeriodChanged = { activePeriod = it },
        )

        StatsAdvancedFilters(
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

        // ── Premium gate: historical data beyond current week ──
        run {
            val isPremium = LocalIsPremium.current
            if (!isPremium && activePeriod !in setOf(ActivePeriod.TODAY, ActivePeriod.WEEK)) {
                PremiumBanner(message = st.premium.gateHintStats)
            }
        }

        // ── Goal card (WEEK or MONTH only) ───────────────────
        if (showGoalCard || (weeklyGoal == 0.0 && (activePeriod == ActivePeriod.WEEK || activePeriod == ActivePeriod.MONTH))) {
            Card(
                onClick = {
                    goalInput      = if (weeklyGoal > 0) "%.0f".format(weeklyGoal) else ""
                    showGoalDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(
                    containerColor = if (goalProgress >= 1.0) tc.green.copy(alpha = 0.12f) else tc.card
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            "🎯  $goalLabel",
                            color      = if (goalProgress >= 1.0) tc.green else tc.textPrimary,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (weeklyGoal > 0) {
                            Text(
                                "${settings.formatPrice(goalRevenue)} / ${settings.formatPrice(currentGoal)}",
                                color      = if (goalProgress >= 1.0) tc.green else tc.accent,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    if (weeklyGoal > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress  = { goalProgress.toFloat() },
                            modifier  = Modifier.fillMaxWidth().height(6.dp),
                            color     = if (goalProgress >= 1.0) tc.green else tc.accent,
                            trackColor = tc.surface,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (goalProgress >= 1.0) {
                            Text(st.goal.goalReached, color = tc.green, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                        } else {
                            val remaining = currentGoal - goalRevenue
                            Text(
                                "${settings.formatPrice(remaining)} ${st.goal.remaining}  •  " +
                                "${st.goal.daysLeft.format(daysRemaining)} ${st.goal.daysSuffix}",
                                color    = tc.muted,
                                fontSize = 11.sp,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(st.goal.tapToSetGoal, color = tc.muted, fontSize = 12.sp)
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(st.noData)
            return@Column
        }

        // ── Summary cards ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard2(st.totalRevenue, settings.formatPrice(total),
                "${filtered.size} ${st.ridesLabel}", tc.accent, Modifier.weight(1f))
            StatCard2(st.netProfit, settings.formatPrice(adjustedNet),
                st.afterCosts, tc.green, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hasTrackedShiftKm = filteredShiftIds.isNotEmpty() || totalShiftKm > totalKm + 0.05
            StatCard2(
                st.totalKm,
                "%.1f km".format(if (hasTrackedShiftKm) totalShiftKm else totalKm),
                if (hasTrackedShiftKm)
                    "%.1f km ${st.withClients}".format(totalKm)
                else st.traveled,
                tc.blue, Modifier.weight(1f)
            )
            StatCard2(st.avgRide, settings.formatPrice(avgPerRide),
                st.perRide, tc.purple, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard2(st.avgPerHour, if (avgPerHour > 0) settings.formatPrice(avgPerHour) else "–",
                st.revenuePerHour, Color(0xFFFF9A3C), Modifier.weight(1f))
            StatCard2(st.avgShift, if (uniqueShifts > 0) settings.formatPrice(avgPerShift) else "–",
                if (uniqueShifts > 0) "$uniqueShifts ${st.shiftsLabel}" else st.noShiftSub, tc.green, Modifier.weight(1f))
        }

        // ── Тенденции ─────────────────────────────────────────
        StatsSection(st.trendsTitle) {
            // Compute period label for navigation header
            val sdfNav  = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
            val sdfMon  = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
            val nowCal  = remember { Calendar.getInstance() }
            val curDow  = remember { (nowCal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7 }
            val curMon  = remember { nowCal.get(Calendar.MONTH) }
            val curYear = remember { nowCal.get(Calendar.YEAR) }

            val periodLabel = remember(trendMode, trendOffset) {
                when (trendMode) {
                    TrendMode.DAY -> {
                        val c = Calendar.getInstance()
                        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
                        c.add(Calendar.DAY_OF_MONTH, -(c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7)
                        c.add(Calendar.DAY_OF_MONTH, trendOffset * 7)
                        val monMs = c.timeInMillis
                        val sunMs = monMs + 6 * 86_400_000L
                        "${sdfNav.format(Date(monMs))} – ${sdfNav.format(Date(sunMs))}"
                    }
                    TrendMode.WEEK -> {
                        val c = Calendar.getInstance()
                        c.add(Calendar.MONTH, trendOffset)
                        sdfMon.format(c.time).replaceFirstChar { it.uppercase() }
                    }
                    TrendMode.MONTH -> "${curYear + trendOffset}"
                    TrendMode.YEAR, TrendMode.HOUR -> ""
                }
            }

            // ── Dropdown mode selector ────────────────────────
            var showModeMenu by remember { mutableStateOf(false) }
            val modeOptions = listOf(
                TrendMode.DAY   to st.byDay,
                TrendMode.WEEK  to st.byWeek,
                TrendMode.MONTH to st.byMonth,
                TrendMode.YEAR  to st.byYear,
                TrendMode.HOUR  to st.byHour,
            )
            val currentModeLabel = modeOptions.first { it.first == trendMode }.second

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Dropdown trigger
                Box {
                    OutlinedButton(
                        onClick = { showModeMenu = true },
                        shape  = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, tc.accent.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(currentModeLabel, color = tc.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, null, tint = tc.accent, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                        containerColor = tc.card,
                    ) {
                        modeOptions.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (mode == trendMode) tc.accent else tc.textPrimary, fontSize = 13.sp) },
                                onClick = {
                                    if (mode != trendMode) { trendMode = mode; trendOffset = 0 }
                                    showModeMenu = false
                                },
                                leadingIcon = if (mode == trendMode) ({
                                    Icon(Icons.Default.Check, null, tint = tc.accent, modifier = Modifier.size(14.dp))
                                }) else null,
                            )
                        }
                    }
                }

                // Sort toggle
                if (trendMode != TrendMode.HOUR) {
                    IconButton(onClick = { trendSortByValue = !trendSortByValue }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (trendSortByValue) Icons.Default.SortByAlpha else Icons.Default.Sort,
                            contentDescription = if (trendSortByValue) st.trendSortByValue else st.trendSortChronological,
                            tint = if (trendSortByValue) tc.accent else tc.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // ── Navigation arrows (not for YEAR/HOUR/MONTH-period) ───
            if (trendMode != TrendMode.YEAR && trendMode != TrendMode.HOUR && activePeriod != ActivePeriod.MONTH) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { trendOffset-- }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ChevronLeft, null, tint = tc.accent, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        periodLabel,
                        color      = tc.textPrimary,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick  = { if (trendOffset < 0) trendOffset++ },
                        enabled  = trendOffset < 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ChevronRight, null,
                            tint = if (trendOffset < 0) tc.accent else tc.muted,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Legend ────────────────────────────────────────
            if (trendMode != TrendMode.HOUR) {
                val revenueLabel = if (settings.includeTipsInTotal)
                    "${st.grossRevenue} (${st.totalTips.lowercase()})" else st.grossRevenue
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    LegendDot(color = tc.accent, label = revenueLabel)
                    LegendDot(color = tc.muted,  label = settings.distanceUnit.shortLabel)
                    LegendDot(color = Color.White.copy(alpha = 0.5f), label = st.rideCount)
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Chart data per mode ───────────────────────────
            when (trendMode) {

                // ── By day ────────────────────────────────────
                TrendMode.DAY -> key(activePeriod == ActivePeriod.MONTH) {
                  if (activePeriod == ActivePeriod.MONTH) {
                    // DoW aggregation: sum all Mondays, Tuesdays, etc. in the filtered month
                    val (dowRev, dowKm, dowCnt) = remember(allRides, filterFromMs, filterToMs, settings.includeTipsInTotal) {
                        val rev = DoubleArray(7); val km = DoubleArray(7); val cnt = IntArray(7)
                        val from = filterFromMs ?: Long.MIN_VALUE
                        val to   = filterToMs   ?: Long.MAX_VALUE
                        allRides.forEach { ride ->
                            if (ride.logicalTime() !in from..to) return@forEach
                            val dow = Calendar.getInstance().apply { timeInMillis = ride.logicalTime() }
                                .get(Calendar.DAY_OF_WEEK)
                            val idx = (dow - Calendar.MONDAY + 7) % 7  // Mon=0 .. Sun=6
                            rev[idx] += ride.price + if (settings.includeTipsInTotal) ride.tip else 0.0
                            km[idx]  += ride.kilometers
                            cnt[idx]++
                        }
                        Triple(rev, km, cnt)
                    }
                    val indices = if (trendSortByValue)
                        (0..6).sortedByDescending { dowRev[it] }
                    else (0..6).toList()

                    Text(st.revenueByDow, color = tc.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data           = indices.map { i -> dayLabels[i] to dowRev[i] },
                        color          = tc.accent,
                        highlightIndex = -1,
                        kmData         = indices.map { i -> dowKm[i] },
                        countData      = indices.map { i -> dowCnt[i] },
                    )
                  } else {
                    // Standard view: 7 days of selected week
                    val mondayMs = remember(trendOffset) {
                        val c = Calendar.getInstance()
                        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
                        c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
                        c.add(Calendar.DAY_OF_MONTH, trendOffset * 7)
                        c.timeInMillis
                    }
                    val (rev7, km7, cnt7) = remember(allRides, mondayMs, settings.includeTipsInTotal) {
                        val rev = DoubleArray(7); val km = DoubleArray(7); val cnt = IntArray(7)
                        allRides.forEach { ride ->
                            val rc = Calendar.getInstance().apply {
                                timeInMillis = ride.logicalTime()
                                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                            }
                            val diff = ((rc.timeInMillis - mondayMs) / 86_400_000L).toInt()
                            if (diff in 0..6) {
                                rev[diff] += ride.price + if (settings.includeTipsInTotal) ride.tip else 0.0
                                km[diff]  += ride.kilometers
                                cnt[diff]++
                            }
                        }
                        Triple(rev, km, cnt)
                    }
                    // Day labels: "Пн\n14"
                    val dayBarLabels = remember(mondayMs) {
                        (0..6).map { d ->
                            val c = Calendar.getInstance().apply { timeInMillis = mondayMs + d * 86_400_000L }
                            "${dayLabels[d]}\n${c.get(Calendar.DAY_OF_MONTH)}"
                        }
                    }
                    val highlightDay = if (trendOffset == 0) curDow else -1

                    // Sort if needed
                    val indices = if (trendSortByValue)
                        (0..6).sortedByDescending { rev7[it] }
                    else (0..6).toList()

                    val barData  = indices.map { i -> dayBarLabels[i] to rev7[i] }
                    val barKm    = indices.map { i -> km7[i] }
                    val barCount = indices.map { i -> cnt7[i] }
                    val hlIdx    = if (trendSortByValue) -1 else highlightDay

                    Text(st.revenueByDow, color = tc.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data           = barData,
                        color          = tc.accent,
                        highlightIndex = hlIdx,
                        kmData         = barKm,
                        countData      = barCount,
                    )
                  } // end else (standard week view)
                } // end key

                // ── By week (weeks of selected month) ─────────
                TrendMode.WEEK -> {
                    val (wYear, wMonth) = remember(trendOffset) {
                        val c = Calendar.getInstance()
                        c.add(Calendar.MONTH, trendOffset)
                        c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
                    }
                    val weeks = remember(wYear, wMonth) { pfWeeksInMonth(wYear, wMonth) }
                    val weekFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
                    val (wRev, wKm, wCnt) = remember(allRides, weeks, settings.includeTipsInTotal) {
                        val rev = DoubleArray(weeks.size)
                        val km  = DoubleArray(weeks.size)
                        val cnt = IntArray(weeks.size)
                        allRides.forEach { ride ->
                            val t = ride.logicalTime()
                            weeks.forEachIndexed { idx, (ws, we) ->
                                if (t in ws..we) {
                                    rev[idx] += ride.price + if (settings.includeTipsInTotal) ride.tip else 0.0
                                    km[idx]  += ride.kilometers
                                    cnt[idx]++
                                }
                            }
                        }
                        Triple(rev, km, cnt)
                    }
                    // Highlight current week
                    val nowMs = System.currentTimeMillis()
                    val hlWeek = if (trendOffset == 0)
                        weeks.indexOfFirst { (ws, we) -> nowMs in ws..we } else -1
                    val wLabels = weeks.mapIndexed { idx, (ws, we) ->
                        "${weekFmt.format(Date(ws))}\n–${weekFmt.format(Date(we))}"
                    }

                    val indices = if (trendSortByValue)
                        weeks.indices.sortedByDescending { wRev[it] }
                    else weeks.indices.toList()

                    Text(st.revenueByDow, color = tc.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data           = indices.map { i -> wLabels[i] to wRev[i] },
                        color          = tc.blue,
                        highlightIndex = if (trendSortByValue) -1 else hlWeek,
                        kmData         = indices.map { i -> wKm[i] },
                        countData      = indices.map { i -> wCnt[i] },
                    )
                }

                // ── By month (months of selected year) ────────
                TrendMode.MONTH -> {
                    val targetYear = curYear + trendOffset
                    val (mRev, mKm, mCnt) = remember(allRides, targetYear, settings.includeTipsInTotal) {
                        val rev = DoubleArray(12); val km = DoubleArray(12); val cnt = IntArray(12)
                        allRides.forEach { ride ->
                            val c = Calendar.getInstance().apply { timeInMillis = ride.logicalTime() }
                            if (c.get(Calendar.YEAR) == targetYear) {
                                val m = c.get(Calendar.MONTH)
                                rev[m] += ride.price + if (settings.includeTipsInTotal) ride.tip else 0.0
                                km[m]  += ride.kilometers
                                cnt[m]++
                            }
                        }
                        Triple(rev, km, cnt)
                    }
                    val hlMonth = if (trendOffset == 0) curMon else -1

                    val indices = if (trendSortByValue)
                        (0..11).sortedByDescending { mRev[it] }
                    else (0..11).toList()

                    Text(st.revenueByMonth, color = tc.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data           = indices.map { i -> monthLabels[i] to mRev[i] },
                        color          = tc.green,
                        highlightIndex = if (trendSortByValue) -1 else hlMonth,
                        kmData         = indices.map { i -> mKm[i] },
                        countData      = indices.map { i -> mCnt[i] },
                    )
                }

                // ── By year ────────────────────────────────────
                TrendMode.YEAR -> {
                    val yearData = remember(allRides, settings.includeTipsInTotal) {
                        val map = sortedMapOf<Int, Triple<Double, Double, Int>>()
                        allRides.forEach { ride ->
                            val y = Calendar.getInstance().apply { timeInMillis = ride.logicalTime() }
                                .get(Calendar.YEAR)
                            val cur = map[y] ?: Triple(0.0, 0.0, 0)
                            val tipAdd = if (settings.includeTipsInTotal) ride.tip else 0.0
                            map[y] = Triple(cur.first + ride.price + tipAdd, cur.second + ride.kilometers, cur.third + 1)
                        }
                        map
                    }
                    if (yearData.isEmpty()) {
                        Text(st.noData, color = tc.muted, fontSize = 12.sp)
                    } else {
                        val hlYear = yearData.keys.indexOf(curYear).let { if (it >= 0) it else -1 }
                        val entries = if (trendSortByValue)
                            yearData.entries.sortedByDescending { it.value.first }
                        else yearData.entries.toList()

                        Text(st.revenueByMonth, color = tc.muted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        BarChart(
                            data           = entries.map { (y, v) -> "$y" to v.first },
                            color          = Color(0xFFFF9A3C),
                            highlightIndex = if (trendSortByValue) -1 else hlYear,
                            kmData         = entries.map { (_, v) -> v.second },
                            countData      = entries.map { (_, v) -> v.third },
                        )
                    }
                }

                // ── By hour (all-time, uses allRides — not filtered) ───
                TrendMode.HOUR -> {
                    val hourKm = remember(allRides) {
                        val km = DoubleArray(24)
                        allRides.forEach { ride ->
                            val h = Calendar.getInstance().apply { timeInMillis = ride.startTime }
                                .get(Calendar.HOUR_OF_DAY)
                            km[h] += ride.kilometers
                        }
                        km
                    }
                    // Revenue per hour — computed from ALL rides, not the period filter,
                    // so the chart shows the true all-time hourly earning pattern.
                    val hourRevAll = remember(allRides, settings.includeTipsInTotal) {
                        val rev = DoubleArray(24)
                        allRides.forEach { ride ->
                            val h = Calendar.getInstance().apply { timeInMillis = ride.startTime }
                                .get(Calendar.HOUR_OF_DAY)
                            rev[h] += ride.price + if (settings.includeTipsInTotal) ride.tip else 0.0
                        }
                        rev
                    }
                    val bestHourAll  = hourRevAll.indices.maxByOrNull { hourRevAll[it] } ?: 0
                    val currentHour  = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

                    Text(st.revenueByHour, color = tc.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data  = (0..23).map { h ->
                            (if (h % 6 == 0) "${h}${st.hoursAbbr}" else "") to hourRevAll[h]
                        },
                        color          = Color(0xFFFF9A3C),
                        highlightIndex = currentHour,     // orange bar = current hour of day
                        highlightColor = TodayBarColor,
                        maxBarHeight   = 60,
                        showValues     = false,
                        kmData         = (0..23).map { hourKm[it] },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${st.bestHourColon} ${bestHourAll}:00–${bestHourAll+1}:00",
                            color = tc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(settings.formatPrice(hourRevAll[bestHourAll]),
                            color = tc.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── KPI таблица ──
        StatsSection(st.kpiTitle) {
            val cardCount = filtered.count { it.paymentMethod == "CARD" }
            val cashCount = filtered.size - cardCount
            val cardPct   = if (filtered.isNotEmpty()) cardCount * 100 / filtered.size else 0
            val rows = mutableListOf(
                "${st.workTime} / ${st.avgShift.lowercase()}" to formatStatsDuration(avgWorkTimePerShiftAllTime),
                "${st.grossRevenue} / ${st.avgShift.lowercase()}" to settings.formatPrice(avgGrossPerShiftAllTime),
                "${st.totalKm} / ${st.avgShift.lowercase()}" to settings.formatDistance(avgKmPerShiftAllTime),
                st.totalTips to settings.formatPrice(totalTip),
                st.bestDay   to dayLabels[bestDow],
                st.rideCount to "${filtered.size}",
                "${st.card.replace("💳 ", "")} / ${st.cash.replace("💵 ", "")}" to "$cardCount / $cashCount  ($cardPct%)",
                st.peakHour  to "${bestHour}:00 – ${bestHour+1}:00",
            )
            rows += "Най-дълго чакане в зона" to (
                longestZoneWait?.let {
                    "${it.zoneName}  •  ${formatStatsDuration(it.durationMs)}  •  " +
                        "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))}-${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.endTime))}"
                } ?: "—"
            )
            rows.forEach { (k, v) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        k,
                        color = tc.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(v, color = tc.textPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
            }
        }

        // ── Най-чести маршрути ──
        if (topRoutes.isNotEmpty()) {
            StatsSection(st.topRoutesTitle) {
                topRoutes.forEachIndexed { i, (route, data) ->
                    val (count, totalPrice) = data
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)) {
                            Text("${i+1}.", color = tc.accent, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Column {
                                Text(route, color = tc.textPrimary, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text("$count ${st.timesLabel}", color = tc.muted, fontSize = 10.sp)
                            }
                        }
                        Text(settings.formatPrice(totalPrice / count),
                            color = tc.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    if (i < topRoutes.size - 1)
                        HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                }
            }
        }

        // ── Разбивка разходи ──
        StatsSection(st.breakdownTitle) {
            val avgTaxRate  = if (total > 0) taxCost / total * 100.0 else 0.0
            val fuelLbl     = "${st.fuelLabel} (~%.2f/${settings.distanceUnit.shortLabel})".format(avgFuelRate)
            val taxLbl      = "${st.taxInsurance} (~%.1f%%)".format(avgTaxRate)

            val rows = mutableListOf(
                Triple(st.grossRevenue, "+${settings.formatPrice(total)}",      tc.textPrimary),
                Triple(fuelLbl,         "-${settings.formatPrice(fuelCost)}",   tc.red),
                Triple(taxLbl,          "-${settings.formatPrice(taxCost)}",    tc.red),
            )
            customRows.forEach { exp ->
                rows.add(Triple(exp.name, "-${settings.formatPrice(exp.cost)}", tc.red))
            }
            rows.add(Triple(st.netProfit,
                (if (adjustedNet >= 0) "+" else "") + settings.formatPrice(adjustedNet),
                if (adjustedNet >= 0) tc.green else tc.red))

            rows.forEach { (k, v, c) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, color = tc.muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        overflow = TextOverflow.Ellipsis, maxLines = 1)
                    Text(v, color = c, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // ── Goal dialog ───────────────────────────────────────────
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            containerColor   = LocalThemeColors.current.card,
            title = {
                Text(st.goal.setGoalTitle, color = LocalThemeColors.current.textPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                OutlinedTextField(
                    value           = goalInput,
                    onValueChange   = { goalInput = it },
                    placeholder     = { Text(st.goal.setGoalHint,
                        color = LocalThemeColors.current.muted, fontSize = 13.sp) },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix          = { Text(settings.currency.symbol,
                        color = LocalThemeColors.current.muted) },
                    modifier        = Modifier.fillMaxWidth(),
                    colors          = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = LocalThemeColors.current.accent,
                        unfocusedBorderColor = LocalThemeColors.current.surface,
                        focusedTextColor     = LocalThemeColors.current.textPrimary,
                        unfocusedTextColor   = LocalThemeColors.current.textPrimary,
                        cursorColor          = LocalThemeColors.current.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = goalInput.trim().toDoubleOrNull() ?: 0.0
                        scope.launch { repo.setWeeklyGoal(v) }
                        showGoalDialog = false
                    },
                    enabled = goalInput.trim().toDoubleOrNull() != null,
                ) {
                    Text("OK", color = LocalThemeColors.current.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (weeklyGoal > 0) {
                    TextButton(onClick = {
                        scope.launch { repo.setWeeklyGoal(0.0) }
                        showGoalDialog = false
                    }) {
                        Text(st.goal.removeGoal, color = LocalThemeColors.current.red, fontSize = 12.sp)
                    }
                } else {
                    TextButton(onClick = { showGoalDialog = false }) {
                        Text(st.cancelBtn, color = LocalThemeColors.current.muted)
                    }
                }
            }
        )
    }
}

@Composable
private fun StatsAdvancedFilters(
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

    Card(colors = CardDefaults.cardColors(containerColor = tc.card), shape = RoundedCornerShape(14.dp)) {
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
                StatsZoneDropdown("От зона", fromZoneName, options, Modifier.weight(1f), onFromSelected)
                StatsZoneDropdown("До зона", toZoneName, options, Modifier.weight(1f), onToSelected)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsNumberFilter("Км", kmText, settings.distanceUnit.shortLabel, kmIsMin, Modifier.weight(1f), onToggleKmMode, onKmChange, onApplyKm)
                StatsNumberFilter("Сума", fareText, settings.currency.symbol, fareIsMin, Modifier.weight(1f), onToggleFareMode, onFareChange, onApplyFare)
                StatsNumberFilter("Време", durationText, "мин", durationIsMin, Modifier.weight(1f), onToggleDurationMode, onDurationChange, onApplyDuration)
            }
}
    }
}

@Composable
private fun StatsZoneDropdown(
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(tc.card)) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, color = if (value == selected) tc.accent else tc.textPrimary, fontSize = 13.sp) },
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
private fun StatsNumberFilter(
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
fun StatCard2(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    val tc = LocalThemeColors.current
    Card(modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape  = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = tc.muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = color, fontSize = 20.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(sub, color = tc.muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun StatsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val tc = LocalThemeColors.current
    Card(colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = tc.textPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun EffBar(label: String, pct: Double, color: Color) {
    val tc = LocalThemeColors.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = tc.textPrimary, fontSize = 12.sp)
            Text("%.0f%%".format(pct), color = color, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(tc.surface, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(pct.toFloat() / 100f).fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

enum class TrendMode { DAY, WEEK, MONTH, YEAR, HOUR }

private fun startOfDay(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun startOfWeek(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
    return startOfDay(c.timeInMillis)
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val tc = LocalThemeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
        Text(label, color = tc.muted, fontSize = 10.sp)
    }
}

private val TodayBarColor = Color(0xFFFF9A3C)   // vivid orange — marks "today / current"

@Composable
private fun BarChart(
    data: List<Pair<String, Double>>,   // (x-label, revenue)
    color: Color,
    highlightIndex: Int = -1,
    highlightColor: Color = TodayBarColor,  // colour used for the highlighted (today) bar
    maxBarHeight: Int = 80,
    showValues: Boolean = true,
    kmData: List<Double>? = null,       // optional km per bar
    countData: List<Int>? = null,       // optional ride count per bar (shown inside bar)
) {
    val tc       = LocalThemeColors.current
    val settings = LocalSettings.current
    val maxVal   = data.maxOfOrNull { it.second }?.coerceAtLeast(0.01) ?: return
    // Extra top space: revenue label + optional km label
    val topPad = if (showValues) (if (kmData != null) 26 else 14) else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((maxBarHeight + 40 + topPad).dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.Bottom,
    ) {
        data.forEachIndexed { idx, (label, value) ->
            val frac     = (value / maxVal).toFloat().coerceIn(0f, 1f)
            val barColor = if (idx == highlightIndex) highlightColor else color.copy(alpha = 0.55f)
            val km       = kmData?.getOrNull(idx) ?: 0.0
            val cnt      = countData?.getOrNull(idx) ?: 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f),
            ) {
                // Revenue value — use currency symbol from user settings
                if (showValues && value > 0) {
                    Text(
                        text      = settings.formatPrice(value),
                        color     = barColor,
                        fontSize  = 7.sp,
                        maxLines  = 1,
                        textAlign = TextAlign.Center,
                    )
                }
                // km sub-label
                if (showValues && kmData != null && km > 0) {
                    Text(
                        text      = "%.0f km".format(km),
                        color     = tc.muted,
                        fontSize  = 6.sp,
                        maxLines  = 1,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(2.dp))
                // Bar with optional ride count inside
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .fillMaxWidth()
                        .height(
                            (maxBarHeight * frac)
                                .coerceAtLeast(if (value > 0) 3f else 0f)
                                .dp
                        )
                        .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (countData != null && cnt > 0 && frac > 0.15f) {
                        Text(
                            text      = "$cnt",
                            color     = Color.White.copy(alpha = 0.5f),
                            fontSize  = 6.sp,
                            fontWeight = FontWeight.Bold,
                            modifier  = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                // X-axis label (supports "\n" for two-line labels like "Пн\n14")
                Text(
                    text       = label,
                    color      = if (idx == highlightIndex) highlightColor else tc.muted,
                    fontSize   = if (idx == highlightIndex) 7.5.sp else 7.sp,
                    fontWeight = if (idx == highlightIndex) FontWeight.Bold else FontWeight.Normal,
                    textAlign  = TextAlign.Center,
                    lineHeight = 9.sp,
                    maxLines   = 2,
                )
            }
        }
    }
}

