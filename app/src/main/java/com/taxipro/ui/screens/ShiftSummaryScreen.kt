package com.taxipro.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.data.db.Ride
import com.taxipro.data.db.ExpenseFrequency
import com.taxipro.data.db.ExpenseType
import com.taxipro.data.db.MonthlyExpenseMode
import com.taxipro.data.db.Shift
import com.taxipro.data.db.ShiftPauseSession
import com.taxipro.data.db.TariffExpense
import com.taxipro.data.db.Zone
import com.taxipro.data.db.ZoneStat
import com.taxipro.data.db.ZoneWaitSession
import com.taxipro.data.db.computeZoneStats
import com.taxipro.data.db.effectiveShiftKm
import com.taxipro.data.db.formatPrice
import com.taxipro.data.db.longestZoneWait
import com.taxipro.data.db.expType
import com.taxipro.data.db.freq
import com.taxipro.data.db.monthMode
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private fun formatDurationForSummary(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes}м"
}

private fun pauseDurationInsideShift(
    pauseSessions: List<ShiftPauseSession>,
    shiftStartMs: Long,
    shiftEndMs: Long,
): Long = pauseSessions.sumOf { pause ->
    val pauseEnd = when {
        pause.endTime > 0L -> pause.endTime
        pause.durationMs > 0L -> pause.startTime + pause.durationMs
        else -> shiftEndMs
    }
    val overlapStart = maxOf(pause.startTime, shiftStartMs)
    val overlapEnd = minOf(pauseEnd, shiftEndMs)
    (overlapEnd - overlapStart).coerceAtLeast(0L)
}

private fun startOfDay(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun startOfWeek(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
    return startOfDay(c.timeInMillis)
}

private fun startOfMonth(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.DAY_OF_MONTH, 1)
    return startOfDay(c.timeInMillis)
}

private fun daysInMonth(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
}

private fun estimateShiftCustomExpenses(
    expenses: List<TariffExpense>,
    rideCount: Int,
    grossTotal: Double,
    shiftsSameDay: Int,
    shiftsSameWeek: Int,
    shiftMonthDays: Int,
): Double = expenses.sumOf { exp ->
    when (exp.freq) {
        ExpenseFrequency.PER_DAY -> if (exp.expType == ExpenseType.FIXED) {
            exp.amount / shiftsSameDay.coerceAtLeast(1)
        } else {
            grossTotal * exp.amount / 100.0
        }
        ExpenseFrequency.PER_WEEK -> if (exp.expType == ExpenseType.FIXED) {
            exp.amount / shiftsSameWeek.coerceAtLeast(1)
        } else {
            grossTotal * exp.amount / 100.0
        }
        ExpenseFrequency.PER_RIDE -> if (exp.expType == ExpenseType.FIXED) {
            exp.amount * rideCount
        } else {
            grossTotal * exp.amount / 100.0
        }
        ExpenseFrequency.PER_SHIFT -> if (exp.expType == ExpenseType.FIXED) {
            exp.amount
        } else {
            grossTotal * exp.amount / 100.0
        }
        ExpenseFrequency.PER_MONTH -> if (exp.expType == ExpenseType.FIXED) {
            val divisor = when (exp.monthMode) {
                MonthlyExpenseMode.MANUAL -> exp.manualWorkDays.coerceAtLeast(1)
                MonthlyExpenseMode.AUTO -> shiftMonthDays.coerceAtLeast(1)
            }
            (exp.amount / divisor) / shiftsSameDay.coerceAtLeast(1)
        } else {
            grossTotal * exp.amount / 100.0
        }
    }
        .toDouble()
}

private fun normalizedShiftExpenses(allExpenses: List<TariffExpense>): List<TariffExpense> =
    allExpenses
        .groupBy { listOf(it.name, it.frequency, it.type, it.amount).joinToString("|") }
        .map { it.value.first() }

@Composable
fun ShiftSummaryScreen(
    shift: Shift,
    rideVm: RideViewModel,
    onDismiss: () -> Unit,
) {
    val rides by rideVm.getRidesByShift(shift.id).collectAsState(initial = emptyList())
    val zones by rideVm.allZones.collectAsState(initial = emptyList())
    val zoneWaits by rideVm.getZoneWaitsByShift(shift.id).collectAsState(initial = emptyList())
    val pauseSessions by rideVm.getShiftPausesByShift(shift.id).collectAsState(initial = emptyList())
    val allExpenses by rideVm.allExpenses.collectAsState(initial = emptyList())
    val allShifts by rideVm.allShifts.collectAsState(initial = emptyList())
    ShiftSummaryContent(
        shift = shift,
        rides = rides,
        zones = zones,
        zoneWaits = zoneWaits,
        pauseSessions = pauseSessions,
        allExpenses = allExpenses,
        allShifts = allShifts,
        onDismiss = onDismiss,
    )
}

@Composable
fun ShiftSummaryContent(
    shift: Shift,
    rides: List<Ride>,
    zones: List<Zone> = emptyList(),
    zoneWaits: List<ZoneWaitSession> = emptyList(),
    pauseSessions: List<ShiftPauseSession> = emptyList(),
    allExpenses: List<TariffExpense> = emptyList(),
    allShifts: List<Shift> = emptyList(),
    onDismiss: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdf      = SimpleDateFormat("HH:mm", Locale.getDefault())

    val shiftEndMs = if (shift.endTime > 0) shift.endTime else System.currentTimeMillis()
    val grossDurationMs = (shiftEndMs - shift.startTime).coerceAtLeast(0L)
    val pauseDurationMs = pauseDurationInsideShift(pauseSessions, shift.startTime, shiftEndMs)
    val durationMs = (grossDurationMs - pauseDurationMs).coerceAtLeast(0L)
    val durationH   = durationMs / 3_600_000L
    val durationMin = (durationMs % 3_600_000L) / 60_000L
    val durationHD  = durationMs / 3_600_000.0

    val totalRevenue = rides.sumOf { it.price }
    val totalTips    = rides.sumOf { it.tip }
    val totalKm      = rides.sumOf { it.kilometers }
    val totalWait    = rides.sumOf { it.waitMinutes }
    val avgPerRide   = if (rides.isNotEmpty()) totalRevenue / rides.size else 0.0
    val avgPerHour   = if (durationHD > 0.01) (totalRevenue + totalTips) / durationHD else 0.0
    // Fuel cost uses total shift km (GPS-only, no adjustment km).
    // Fall back to GPS ride km sum if shift.totalKm wasn't recorded (legacy shifts).
    val totalGpsKm   = rides.sumOf { it.kilometers - it.adjustmentKm }
    val shiftKm      = effectiveShiftKm(shift.totalKm, totalKm)
    val avgFuelCostPerKm = if (rides.isNotEmpty())
        rides.sumOf { it.fuelCostPerKm } / rides.size else 0.0
    val fuelCost     = shiftKm * avgFuelCostPerKm
    val taxCost      = rides.sumOf { it.price * it.taxPercent / 100.0 }
    val shiftDayStart = remember(shift.startTime) { startOfDay(shift.startTime) }
    val shiftWeekStart = remember(shift.startTime) { startOfWeek(shift.startTime) }
    val shiftsSameDay = remember(allShifts, shiftDayStart) {
        allShifts.count { startOfDay(it.startTime) == shiftDayStart }.coerceAtLeast(1)
    }
    val shiftsSameWeek = remember(allShifts, shiftWeekStart) {
        allShifts.count { startOfWeek(it.startTime) == shiftWeekStart }.coerceAtLeast(1)
    }
    val shiftMonthDays = remember(shift.startTime) { daysInMonth(shift.startTime) }
    val shiftExpenses = remember(allExpenses) { normalizedShiftExpenses(allExpenses) }
    val customExpenseCost = estimateShiftCustomExpenses(
        shiftExpenses,
        rides.size,
        totalRevenue + totalTips,
        shiftsSameDay,
        shiftsSameWeek,
        shiftMonthDays,
    )
    val netProfit    = totalRevenue + totalTips - fuelCost - taxCost - customExpenseCost

    val avgFuelRate  = if (shiftKm > 0) fuelCost / shiftKm else 0.0
    val avgTaxRate   = if (totalRevenue > 0) taxCost / totalRevenue * 100.0 else 0.0
    val fuelRowLabel = "${st.fuelLabel} (~%.2f/${settings.distanceUnit.shortLabel})".format(avgFuelRate)
    val taxRowLabel  = "${st.taxInsurance} (~%.1f%%)".format(avgTaxRate)
    val longestZoneWait = remember(zoneWaits) { zoneWaits.longestZoneWait() }

    val durStr = buildString {
        if (durationH > 0) append("${durationH}${st.hoursAbbr} ")
        append("${durationMin}${st.minAbbr}")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    st.shiftCompleted.format(shift.shiftNumber),
                    color = tc.green, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "${sdf.format(Date(shift.startTime))} – ${sdf.format(Date(shift.endTime))}  •  $durStr",
                    color = tc.muted, fontSize = 13.sp
                )
            }
            Icon(Icons.Default.CheckCircle, null, tint = tc.green, modifier = Modifier.size(36.dp))
        }

        // ── Main revenue card ────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = tc.accent.copy(alpha = 0.12f)),
            shape  = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStat(st.totalRevenue,  settings.formatPrice(totalRevenue + totalTips), tc.accent)
                SummaryStat(st.netProfit,     settings.formatPrice(netProfit),               tc.green)
                SummaryStat(st.tipsLabel,     settings.formatPrice(totalTips),               tc.purple)
            }
        }

        // ── Stats grid ───────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(
                Icons.Default.DirectionsCar, st.ridesCompleted,
                "${rides.size}", st.completedLabel, tc.blue, Modifier.weight(1f)
            )
            SummaryCard(
                Icons.Default.Straighten, st.kilometersLabel,
                "%.1f".format(shiftKm),
                "%.1f ${st.withClients}".format(totalKm),
                tc.muted, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(
                Icons.Default.ShowChart, st.avgRideLabel,
                settings.formatPrice(avgPerRide), st.avgRidePerRide, tc.accent, Modifier.weight(1f)
            )
            SummaryCard(
                Icons.Default.Timer, st.workTime,
                durStr, "", tc.blue, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(
                Icons.Default.TrendingUp, st.avgPerHour,
                if (avgPerHour > 0) settings.formatPrice(avgPerHour) else "–",
                st.revenuePerHour, Color(0xFFFF9A3C), Modifier.fillMaxWidth()
            )
        }

        // ── Payment method breakdown ──────────────────────────
        longestZoneWait?.let { wait ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(
                    Icons.Default.Schedule,
                    "Най-дълго чакане",
                    wait.zoneName,
                    "${formatDurationForSummary(wait.durationMs)} • ${sdf.format(Date(wait.startTime))} - ${sdf.format(Date(wait.endTime))}",
                    Color(0xFF26A69A),
                    Modifier.fillMaxWidth()
                )
            }
        }
        val cardRides = rides.count { it.paymentMethod == "CARD" }
        val cashRides = rides.size - cardRides
        if (rides.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(
                    Icons.Default.CreditCard, st.card.replace("💳 ", ""),
                    "$cardRides", st.ridesLabel, tc.blue, Modifier.weight(1f)
                )
                SummaryCard(
                    Icons.Default.Payments, st.cash.replace("💵 ", ""),
                    "$cashRides", st.ridesLabel, tc.green, Modifier.weight(1f)
                )
            }
        }

        // ── Expense breakdown ─────────────────────────────────
        StatsSection(st.financialBreakdown) {
            listOf(
                Triple(st.grossRevenue,  "+${settings.formatPrice(totalRevenue)}", tc.textPrimary),
                Triple(st.tipsLabel,     "+${settings.formatPrice(totalTips)}",    tc.purple),
                Triple(fuelRowLabel,     "-${settings.formatPrice(fuelCost)}",     tc.red),
                Triple(taxRowLabel,      "-${settings.formatPrice(taxCost)}",      tc.red),
                Triple(st.expensesSection.replace("💸  ", ""), "-${settings.formatPrice(customExpenseCost)}", tc.red),
                Triple(st.netProfit,     "+${settings.formatPrice(netProfit)}",    tc.green),
            ).forEach { (label, value, color) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = tc.muted, fontSize = 13.sp)
                    Text(value, color = color, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
            }
        }

        // ── Zone breakdown ───────────────────────────────────
        ShiftZoneStatsSection(rides = rides, zones = zones)

        // ── Ride list ────────────────────────────────────────
        if (rides.isNotEmpty()) {
            StatsSection(st.ridesInShift) {
                val sortedRides = rides.sortedByDescending { it.startTime }
                sortedRides.forEachIndexed { idx, ride ->
                    val label = when {
                        ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                            ride.fromAddress.substringBefore(",").take(18) + " → " +
                            ride.toAddress.substringBefore(",").take(18)
                        ride.fromAddress.isNotEmpty() ->
                            ride.fromAddress.substringBefore(",").take(30)
                        else -> "${st.ridePrefix}${ride.globalId}"
                    }
                    val displayNum = sortedRides.size - idx
                    val hasTip = ride.tip > 0.0
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Label
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$displayNum.", color = tc.muted, fontSize = 12.sp,
                                modifier = Modifier.width(22.dp))
                            Text(label, color = tc.textPrimary, fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        // Fare
                        Text(
                            settings.formatPrice(ride.price),
                            color = tc.muted, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        // Tip (only when > 0)
                        if (hasTip) {
                            Text(
                                " +${settings.formatPrice(ride.tip)}",
                                color = tc.purple, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        // Total
                        Text(
                            " = ${settings.formatPrice(ride.price + ride.tip)}",
                            color = tc.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    }
                    if (idx < sortedRides.lastIndex)
                        HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                }
            }
        }

        // ── Done button ──────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onDismiss,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Done, null, tint = tc.background)
            Spacer(Modifier.width(8.dp))
            Text(st.doneBtn, color = tc.background, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Zone breakdown for a shift ───────────────────────────────
@Composable
fun ShiftZoneStatsSection(rides: List<Ride>, zones: List<Zone>) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    if (zones.isEmpty() || rides.isEmpty()) return

    var zoneStats by remember { mutableStateOf<List<ZoneStat>>(emptyList()) }

    LaunchedEffect(rides, zones) {
        val (stats, _) = withContext(Dispatchers.Default) {
            computeZoneStats(rides, zones, st.zones.outsideZones)
        }
        // Exclude "outside zones" entries for the top-3 highlights
        zoneStats = stats.filter { it.zone != null }
    }

    if (zoneStats.isEmpty()) return

    val topPickup  = zoneStats.maxByOrNull { it.pickupCount }  ?: return
    val topDropoff = zoneStats.maxByOrNull { it.dropoffCount } ?: return
    val topRevenue = zoneStats.maxByOrNull { it.avgRevenue }   ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    st.zones.shiftZoneStatsTitle,
                    color      = tc.textPrimary,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(Icons.Default.Place, null, tint = tc.accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))

            ZoneHighlightRow(
                icon       = Icons.Default.ArrowUpward,
                iconColor  = tc.accent,
                label      = st.zones.topPickupZone,
                zoneName   = topPickup.zoneName,
                zoneColor  = Color(topPickup.zone!!.color),
                statText   = "${topPickup.pickupCount} ${st.zones.timesLabel}",
            )
            HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
            ZoneHighlightRow(
                icon       = Icons.Default.ArrowDownward,
                iconColor  = tc.blue,
                label      = st.zones.topDropoffZone,
                zoneName   = topDropoff.zoneName,
                zoneColor  = Color(topDropoff.zone!!.color),
                statText   = "${topDropoff.dropoffCount} ${st.zones.timesLabel}",
            )
            HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
            ZoneHighlightRow(
                icon       = Icons.Default.TrendingUp,
                iconColor  = tc.green,
                label      = st.zones.mostProfitableZone,
                zoneName   = topRevenue.zoneName,
                zoneColor  = Color(topRevenue.zone!!.color),
                statText   = "${st.zones.avgFareLabel}: ${LocalSettings.current.formatPrice(topRevenue.avgRevenue)}",
            )
        }
    }
}

@Composable
private fun ZoneHighlightRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    label: String,
    zoneName: String,
    zoneColor: Color,
    statText: String,
) {
    val tc = LocalThemeColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = iconColor, modifier = Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = tc.muted, fontSize = 10.sp)
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(8.dp).background(zoneColor, RoundedCornerShape(2.dp)))
                Text(zoneName, color = tc.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(statText, color = tc.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── Small helpers ────────────────────────────────────────────
@Composable
private fun SummaryStat(label: String, value: String, color: Color) {
    val tc = LocalThemeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(label, color = tc.muted, fontSize = 10.sp)
    }
}

@Composable
private fun SummaryCard(
    icon: ImageVector, label: String, value: String,
    sub: String, color: Color, modifier: Modifier,
) {
    val tc = LocalThemeColors.current
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = tc.muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, fontSize = 20.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(sub, color = tc.muted, fontSize = 10.sp)
        }
    }
}

