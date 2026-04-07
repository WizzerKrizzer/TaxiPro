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
import com.taxipro.data.db.Shift
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ShiftHistoryScreen(rideVm: RideViewModel) {
    val st        = LocalStrings.current
    val allShifts by rideVm.allShifts.collectAsState(initial = emptyList())
    var expandedId by remember { mutableStateOf<Long?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(st.shiftHistoryScreen, color = Color.White,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(st.shiftsRecorded.format(allShifts.size), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        if (allShifts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(st.noShifts, color = Muted, fontSize = 14.sp)
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allShifts.forEach { shift ->
                    ShiftCard(
                        shift      = shift,
                        rideVm     = rideVm,
                        isExpanded = expandedId == shift.id,
                        onToggle   = {
                            expandedId = if (expandedId == shift.id) null else shift.id
                        }
                    )
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ShiftCard(
    shift: Shift,
    rideVm: RideViewModel,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
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

    val revenue = rides.sumOf { it.price }
    val tips    = rides.sumOf { it.tip }
    val km      = rides.sumOf { it.kilometers }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Card),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            // ── Header row ───────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(
                            if (shift.isActive) Green.copy(alpha = 0.15f)
                            else Gold.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "#${shift.shiftNumber}",
                        color      = if (shift.isActive) Green else Gold,
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
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (shift.isActive) {
                            Box(
                                Modifier
                                    .background(Green.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(st.activeLabel, color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        "${sdfDate.format(Date(shift.startTime))}  " +
                        "${sdfTime.format(Date(shift.startTime))} – " +
                        (if (shift.endTime > 0) sdfTime.format(Date(shift.endTime)) else "..."),
                        color = Muted, fontSize = 11.sp
                    )
                    Text(
                        "${rides.size} ${st.ridesLabel}  •  $durStr  •  %.1f km".format(km),
                        color = Muted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        settings.formatPrice(revenue),
                        color = Gold, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    if (tips > 0)
                        Text("+%.2f ${st.tipBadgeShort}".format(tips), color = Purple, fontSize = 10.sp)
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = Muted, modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── Expandable detail ────────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column {
                    HorizontalDivider(color = Muted.copy(alpha = 0.15f))
                    ShiftDetailPanel(shift = shift, rides = rides)
                }
            }
        }
    }
}

@Composable
private fun ShiftDetailPanel(shift: Shift, rides: List<com.taxipro.data.db.Ride>) {
    val st       = LocalStrings.current
    val settings = LocalSettings.current

    val totalRevenue = rides.sumOf { it.price }
    val totalTips    = rides.sumOf { it.tip }
    val totalKm      = rides.sumOf { it.kilometers }
    val totalWait    = rides.sumOf { it.waitMinutes }
    val avgPerRide   = if (rides.isNotEmpty()) totalRevenue / rides.size else 0.0
    val fuelCost     = totalKm * settings.fuelCostPerKm
    val taxCost      = totalRevenue * settings.taxPercent / 100.0
    val netProfit    = totalRevenue - fuelCost - taxCost

    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat(st.avgRideLabel,   settings.formatPrice(avgPerRide), Gold)
            MiniStat(st.tipsLabel,      settings.formatPrice(totalTips),  Purple)
            MiniStat(st.waitLabelShort, "%.0f ${st.minAbbr}".format(totalWait), Muted)
            MiniStat(st.profitLabel,    settings.formatPrice(netProfit),  Green)
        }

        HorizontalDivider(color = Muted.copy(alpha = 0.15f))

        if (rides.isEmpty()) {
            Text(st.noRidesInShift, color = Muted, fontSize = 12.sp)
        } else {
            rides.forEachIndexed { idx, ride ->
                val label = when {
                    ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                        ride.fromAddress.substringBefore(",").take(16) + " → " +
                        ride.toAddress.substringBefore(",").take(16)
                    ride.fromAddress.isNotEmpty() ->
                        ride.fromAddress.substringBefore(",").take(28)
                    else -> "${st.ridePrefix}${ride.globalId}"
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("${idx + 1}.", color = Muted, fontSize = 11.sp,
                            modifier = Modifier.width(20.dp))
                        Text(label, color = Color.White, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        settings.formatPrice(ride.price),
                        color = Gold, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                }
                if (idx < rides.lastIndex)
                    HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}
