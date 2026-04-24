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
import com.taxipro.data.db.Shift
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ShiftSummaryScreen(
    shift: Shift,
    rideVm: RideViewModel,
    onDismiss: () -> Unit,
) {
    val rides by rideVm.getRidesByShift(shift.id).collectAsState(initial = emptyList())
    ShiftSummaryContent(shift = shift, rides = rides, onDismiss = onDismiss)
}

@Composable
fun ShiftSummaryContent(shift: Shift, rides: List<Ride>, onDismiss: () -> Unit) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdf      = SimpleDateFormat("HH:mm", Locale.getDefault())

    val durationMs  = (if (shift.endTime > 0) shift.endTime else System.currentTimeMillis()) - shift.startTime
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
    val shiftKm      = if (shift.totalKm > 0.0) shift.totalKm else totalGpsKm
    val avgFuelCostPerKm = if (rides.isNotEmpty())
        rides.sumOf { it.fuelCostPerKm } / rides.size else 0.0
    val fuelCost     = shiftKm * avgFuelCostPerKm
    val taxCost      = rides.sumOf { it.price * it.taxPercent / 100.0 }
    val netProfit    = totalRevenue + totalTips - fuelCost - taxCost

    val avgFuelRate  = if (shiftKm > 0) fuelCost / shiftKm else 0.0
    val avgTaxRate   = if (totalRevenue > 0) taxCost / totalRevenue * 100.0 else 0.0
    val fuelRowLabel = "${st.fuelLabel} (~%.2f/${settings.distanceUnit.shortLabel})".format(avgFuelRate)
    val taxRowLabel  = "${st.taxInsurance} (~%.1f%%)".format(avgTaxRate)

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
