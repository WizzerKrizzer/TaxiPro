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
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdf      = SimpleDateFormat("HH:mm", Locale.getDefault())

    val durationMs  = (if (shift.endTime > 0) shift.endTime else System.currentTimeMillis()) - shift.startTime
    val durationH   = durationMs / 3_600_000L
    val durationMin = (durationMs % 3_600_000L) / 60_000L

    val totalRevenue = rides.sumOf { it.price }
    val totalTips    = rides.sumOf { it.tip }
    val totalKm      = rides.sumOf { it.kilometers }
    val totalWait    = rides.sumOf { it.waitMinutes }
    val avgPerRide   = if (rides.isNotEmpty()) totalRevenue / rides.size else 0.0
    val fuelCost     = totalKm * settings.fuelCostPerKm
    val taxCost      = totalRevenue * settings.taxPercent / 100.0
    val netProfit    = totalRevenue - fuelCost - taxCost

    val durStr = buildString {
        if (durationH > 0) append("${durationH}${st.hoursAbbr} ")
        append("${durationMin}${st.minAbbr}")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dark)
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
                    color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "${sdf.format(Date(shift.startTime))} – ${sdf.format(Date(shift.endTime))}  •  $durStr",
                    color = Muted, fontSize = 13.sp
                )
            }
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(36.dp))
        }

        // ── Main revenue card ────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.12f)),
            shape  = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStat(st.totalRevenue,  settings.formatPrice(totalRevenue), Gold)
                SummaryStat(st.netProfit,     settings.formatPrice(netProfit),    Green)
                SummaryStat(st.tipsLabel,     settings.formatPrice(totalTips),    Purple)
            }
        }

        // ── Stats grid ───────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(
                Icons.Default.DirectionsCar, st.ridesCompleted,
                "${rides.size}", st.completedLabel, Blue, Modifier.weight(1f)
            )
            SummaryCard(
                Icons.Default.Straighten, st.kilometersLabel,
                "%.1f".format(totalKm), st.withClients, Muted, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard(
                Icons.Default.ShowChart, st.avgRideLabel,
                settings.formatPrice(avgPerRide), st.avgRidePerRide, Gold, Modifier.weight(1f)
            )
            SummaryCard(
                Icons.Default.PauseCircle, st.totalWaitLabel,
                "%.0f ${st.minAbbr}".format(totalWait), st.noMovement, Muted, Modifier.weight(1f)
            )
        }

        // ── Expense breakdown ─────────────────────────────────
        StatsSection(st.financialBreakdown) {
            listOf(
                Triple(st.grossRevenue,  "+${settings.formatPrice(totalRevenue)}", Color.White),
                Triple(st.tipsLabel,     "+${settings.formatPrice(totalTips)}",    Purple),
                Triple(st.fuelBreakdown, "-${settings.formatPrice(fuelCost)}",     Red),
                Triple(st.taxBreakdown,  "-${settings.formatPrice(taxCost)}",      Red),
                Triple(st.netProfit,     "+${settings.formatPrice(netProfit)}",    Green),
            ).forEach { (label, value, color) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = Muted, fontSize = 13.sp)
                    Text(value, color = color, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
            }
        }

        // ── Ride list ────────────────────────────────────────
        if (rides.isNotEmpty()) {
            StatsSection(st.ridesInShift) {
                rides.forEachIndexed { idx, ride ->
                    val label = when {
                        ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                            ride.fromAddress.substringBefore(",").take(18) + " → " +
                            ride.toAddress.substringBefore(",").take(18)
                        ride.fromAddress.isNotEmpty() ->
                            ride.fromAddress.substringBefore(",").take(30)
                        else -> "${st.ridePrefix}${ride.globalId}"
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("${idx + 1}.", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.width(22.dp))
                            Text(label, color = Color.White, fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Text(
                            settings.formatPrice(ride.price),
                            color = Gold, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    }
                    if (idx < rides.lastIndex)
                        HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
                }
            }
        }

        // ── Done button ──────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onDismiss,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Gold),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Done, null, tint = Dark)
            Spacer(Modifier.width(8.dp))
            Text(st.doneBtn, color = Dark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ── Small helpers ────────────────────────────────────────────
@Composable
private fun SummaryStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SummaryCard(
    icon: ImageVector, label: String, value: String,
    sub: String, color: Color, modifier: Modifier,
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Card),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, fontSize = 20.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(sub, color = Muted, fontSize = 10.sp)
        }
    }
}
