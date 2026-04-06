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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.taxipro.data.db.AppLanguage
import com.taxipro.data.db.Ride
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(vm: RideViewModel) {
    val allRides by vm.allRides.collectAsState(initial = emptyList())
    val st       = LocalStrings.current
    val settings = LocalSettings.current

    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todayRides  = allRides.filter { it.date >= today }
    val monthStart  = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val monthRides  = allRides.filter { it.date >= monthStart }

    val todayRevenue  = todayRides.sumOf { it.price }
    val monthRevenue  = monthRides.sumOf { it.price }
    val todayKm       = todayRides.sumOf { it.kilometers }
    val todayTip      = todayRides.sumOf { it.tip }
    val avgPerRide    = if (allRides.isNotEmpty()) allRides.sumOf { it.price } / allRides.size else 0.0

    val dateLocale = if (settings.language == AppLanguage.BG) Locale("bg") else Locale.ENGLISH
    val sdf = SimpleDateFormat("dd MMMM yyyy", dateLocale)
    val today_str = sdf.format(Date())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Box(Modifier.padding(20.dp)) {
                Column {
                    Text(today_str.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(st.goodDay, color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        DashStat(settings.formatPrice(todayRevenue), st.todayLabel, Gold)
                        VertDivider()
                        DashStat("${todayRides.size}", st.ridesLabel, Green)
                        VertDivider()
                        DashStat("%.1f".format(todayKm), settings.distanceUnit.shortLabel, Color(0xFF4A9EFF))
                    }
                }
            }
        }

        // ── 4 карти ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashCard(st.monthlyRevenue, settings.formatPrice(monthRevenue),
                "${monthRides.size} ${st.ridesLabel}", Gold, Icons.Default.TrendingUp, Modifier.weight(1f))
            DashCard(st.avgRide, settings.formatPrice(avgPerRide),
                st.perRide, Green, Icons.Default.ShowChart, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashCard(st.tipsToday, settings.formatPrice(todayTip),
                st.received, Color(0xFFA78BFA), Icons.Default.Favorite, Modifier.weight(1f))
            DashCard(st.netProfit, settings.formatPrice(todayRevenue * 0.85),
                st.afterCosts, Color(0xFF4A9EFF), Icons.Default.AccountBalance, Modifier.weight(1f))
        }

        // ── Последни курсове ──
        if (allRides.isNotEmpty()) {
            Text(st.recentRides, color = Muted, fontSize = 11.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)

            allRides.take(5).forEach { ride ->
                RecentRideCard(ride)
            }
        } else {
            EmptyState(st.noRidesYet)
        }
    }
}

@Composable
fun DashStat(value: String, label: String, color: Color) {
    Column {
        Text(value, color = color, fontSize = 24.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun VertDivider() {
    Box(Modifier.width(1.dp).height(40.dp).background(Color(0xFF1E2430)))
}

@Composable
fun DashCard(label: String, value: String, sub: String, color: Color,
             icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, fontSize = 20.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(sub, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun RecentRideCard(ride: Ride) {
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val sdf   = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = sdf.format(Date(ride.startTime))
    val end   = sdf.format(Date(ride.endTime))

    val routeLabel = when {
        ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
            ride.fromAddress.substringBefore(",").take(22) + " → " +
            ride.toAddress.substringBefore(",").take(22)
        ride.fromAddress.isNotEmpty() -> ride.fromAddress.substringBefore(",").take(30)
        else -> "${st.ridePrefix}${ride.globalId}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier              = Modifier.weight(1f)
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .background(Color(0xFFF5C84220), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("#${ride.globalId}", color = Gold,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            routeLabel,
                            color    = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            "$start – $end • %.1f ${settings.distanceUnit.shortLabel}".format(ride.kilometers),
                            color = Muted, fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        settings.formatPrice(ride.price),
                        color      = Gold,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (ride.tip > 0)
                        Text("+%.2f ${st.tipBadge}".format(ride.tip),
                            color = Color(0xFFA78BFA), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
