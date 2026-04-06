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
import androidx.compose.ui.unit.*
import com.taxipro.data.db.Ride
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.viewmodel.RideViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: RideViewModel) {
    val allRides by vm.allRides.collectAsState(initial = emptyList())
    val st       = LocalStrings.current
    val settings = LocalSettings.current

    var period         by remember { mutableStateOf("week") }
    var customStart    by remember { mutableStateOf<Long?>(null) }
    var customEnd      by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var trendTab       by remember { mutableIntStateOf(0) }   // 0=dow, 1=hour, 2=month
    val dateRangeState = rememberDateRangePickerState()
    val sdfDate        = SimpleDateFormat("dd.MM", Locale.getDefault())

    val now   = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val filtered = when {
        period == "custom" && customStart != null && customEnd != null ->
            allRides.filter { it.startTime in customStart!!..customEnd!! }
        period == "today"  -> allRides.filter { it.date >= startOfDay(now) }
        period == "week"   -> allRides.filter { it.startTime >= now - 7 * dayMs }
        period == "month"  -> allRides.filter { it.startTime >= now - 30 * dayMs }
        else               -> allRides
    }

    val total      = filtered.sumOf { it.price }
    val totalKm    = filtered.sumOf { it.kilometers }
    val totalWait  = filtered.sumOf { it.waitMinutes }
    val totalTip   = filtered.sumOf { it.tip }
    val avgPerRide = if (filtered.isNotEmpty()) total / filtered.size else 0.0
    val avgPerKm   = if (totalKm > 0) total / totalKm else 0.0
    val netProfit  = total * 0.85

    // Разпределение по час
    val hourMap = mutableMapOf<Int, Int>()
    filtered.forEach { ride ->
        val h = Calendar.getInstance().apply { timeInMillis = ride.startTime }
            .get(Calendar.HOUR_OF_DAY)
        hourMap[h] = (hourMap[h] ?: 0) + 1
    }
    val peakHour = hourMap.maxByOrNull { it.value }?.key ?: 0

    // Топ маршрути
    val routeMap = mutableMapOf<String, Pair<Int, Double>>()
    filtered.forEach { ride ->
        val key = "${ride.fromAddress.take(10)} → ${ride.toAddress.take(10)}"
            .ifEmpty { "${st.ridePrefix}${ride.globalId}" }
        val cur = routeMap[key] ?: Pair(0, 0.0)
        routeMap[key] = Pair(cur.first + 1, cur.second + ride.price)
    }
    val topRoutes = routeMap.entries.sortedByDescending { it.value.first }.take(5)

    // ── Trend data ────────────────────────────────────────────
    val dayLabels   = st.dayLabels
    val monthLabels = st.monthLabels
    val earnByDow   = DoubleArray(7)
    val countByDow  = IntArray(7)
    val earnByHour  = DoubleArray(24)
    val earnByMonth = DoubleArray(12)
    filtered.forEach { ride ->
        val c     = Calendar.getInstance().apply { timeInMillis = ride.startTime }
        val dow   = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val hour  = c.get(Calendar.HOUR_OF_DAY)
        val month = c.get(Calendar.MONTH)
        earnByDow[dow]    += ride.price;  countByDow[dow]++
        earnByHour[hour]  += ride.price
        earnByMonth[month]+= ride.price
    }
    val bestDow  = earnByDow.indices.maxByOrNull  { earnByDow[it]  } ?: 0
    val bestHour = earnByHour.indices.maxByOrNull { earnByHour[it] } ?: 0

    val totalWorkMs  = filtered.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
    val totalWorkH   = totalWorkMs / 3_600_000.0
    val avgPerHour   = if (totalWorkH > 0.05) total / totalWorkH else 0.0
    val totalWorkStr = run {
        val h = (totalWorkMs / 3_600_000L).toInt()
        val m = ((totalWorkMs % 3_600_000L) / 60_000L).toInt()
        if (h > 0) "${h}ч ${m}мин" else "${m}мин"
    }
    val uniqueShifts = filtered.map { it.shiftId }.filter { it > 0L }.toSet().size
    val avgPerShift  = if (uniqueShifts > 0) total / uniqueShifts else avgPerRide

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(st.statistics, color = Color.White,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ── Period filter ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "today" to st.todayLabel,
                "week"  to st.weekLabel,
                "month" to st.monthLabel,
                "all"   to st.allLabel
            ).forEach { (k, l) ->
                val sel = period == k
                Button(
                    onClick = { period = k; customStart = null; customEnd = null },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (sel) Gold else Color(0xFF161A22)
                    ),
                    contentPadding = PaddingValues(4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(l, color = if (sel) Dark else Muted,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Date range row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (period == "custom" && customStart != null && customEnd != null) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label   = {
                        Text(
                            "${sdfDate.format(java.util.Date(customStart!!))} – ${sdfDate.format(java.util.Date(customEnd!!))}",
                            fontSize = 12.sp, color = Gold
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.DateRange, null, tint = Gold, modifier = Modifier.size(16.dp)) },
                    colors      = AssistChipDefaults.assistChipColors(containerColor = Gold.copy(alpha = 0.15f)),
                    border      = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Gold.copy(alpha = 0.4f))
                )
                IconButton(
                    onClick  = { period = "week"; customStart = null; customEnd = null },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = Muted, modifier = Modifier.size(18.dp))
                }
            } else {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, Muted.copy(alpha = 0.4f)),
                    shape   = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.DateRange, null, tint = Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.choosePeriod, color = Muted, fontSize = 12.sp)
                }
            }
        }

        // Date range picker dialog
        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton    = {
                    TextButton(onClick = {
                        val s = dateRangeState.selectedStartDateMillis
                        val e = dateRangeState.selectedEndDateMillis
                        if (s != null) {
                            customStart = s
                            customEnd   = (e ?: s) + 86_400_000L - 1L
                            period      = "custom"
                        }
                        showDatePicker = false
                    }) { Text("OK", color = Gold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(st.cancelBtn, color = Muted)
                    }
                },
                colors = DatePickerDefaults.colors(containerColor = Color(0xFF1A1E28))
            ) {
                DateRangePicker(
                    state    = dateRangeState,
                    modifier = Modifier.weight(1f, fill = false),
                    colors   = DatePickerDefaults.colors(
                        containerColor                    = Color(0xFF1A1E28),
                        titleContentColor                 = Muted,
                        headlineContentColor              = Color.White,
                        weekdayContentColor               = Muted,
                        subheadContentColor               = Muted,
                        navigationContentColor            = Color.White,
                        yearContentColor                  = Color.White,
                        currentYearContentColor           = Gold,
                        selectedYearContentColor          = Dark,
                        selectedYearContainerColor        = Gold,
                        dayContentColor                   = Color.White,
                        selectedDayContentColor           = Dark,
                        selectedDayContainerColor         = Gold,
                        todayContentColor                 = Gold,
                        todayDateBorderColor              = Gold,
                        dayInSelectionRangeContentColor   = Dark,
                        dayInSelectionRangeContainerColor = Gold.copy(alpha = 0.4f),
                    )
                )
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(st.noData)
            return@Column
        }

        // ── Summary cards ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard2(st.totalRevenue, settings.formatPrice(total),
                "${filtered.size} ${st.ridesLabel}", Gold, Modifier.weight(1f))
            StatCard2(st.netProfit, settings.formatPrice(netProfit),
                st.afterCosts, Green, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard2(st.totalKm, "%.1f km".format(totalKm),
                st.traveled, Blue, Modifier.weight(1f))
            StatCard2(st.avgRide, settings.formatPrice(avgPerRide),
                st.perRide, Purple, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard2(st.avgPerHour, if (avgPerHour > 0) settings.formatPrice(avgPerHour) else "–",
                st.revenuePerHour, Color(0xFFFF9A3C), Modifier.weight(1f))
            StatCard2(st.avgShift, if (uniqueShifts > 0) settings.formatPrice(avgPerShift) else "–",
                if (uniqueShifts > 0) "$uniqueShifts ${st.shiftsLabel}" else st.noShiftSub, Green, Modifier.weight(1f))
        }

        // ── Тенденции ─────────────────────────────────────────
        StatsSection(st.trendsTitle) {
            // Tab selector
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Dark, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(st.byDay, st.byHour, st.byMonth).forEachIndexed { i, label ->
                    val sel = trendTab == i
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (sel) Gold.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { trendTab = i }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label,
                            color = if (sel) Gold else Muted,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            when (trendTab) {
                0 -> {
                    Text(st.revenueByDow, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data  = dayLabels.mapIndexed { i, l -> l to earnByDow[i] },
                        color = Gold, highlightIndex = bestDow
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(st.rideCountByDay, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data  = dayLabels.mapIndexed { i, l -> l to countByDow[i].toDouble() },
                        color = Blue, highlightIndex = bestDow
                    )
                }
                1 -> {
                    Text(st.revenueByHour, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    BarChart(
                        data  = (0..23).map { h ->
                            (if (h % 3 == 0) "${h}ч" else "") to earnByHour[h]
                        },
                        color = Color(0xFFFF9A3C),
                        highlightIndex = bestHour,
                        maxBarHeight   = 60,
                        showValues     = false,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${st.bestHourColon} ${bestHour}:00–${bestHour+1}:00",
                            color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(settings.formatPrice(earnByHour[bestHour]),
                            color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                2 -> {
                    Text(st.revenueByMonth, color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    val curMonth = Calendar.getInstance().get(Calendar.MONTH)
                    BarChart(
                        data           = monthLabels.mapIndexed { i, l -> l to earnByMonth[i] },
                        color          = Green,
                        highlightIndex = curMonth,
                    )
                }
            }
        }

        // ── KPI таблица ──
        StatsSection(st.kpiTitle) {
            val workHStr = if (totalWorkH > 0.05) "%.1f ч".format(totalWorkH) else "–"
            listOf(
                st.revenuePerKm     to settings.formatPrice(avgPerKm),
                st.revenuePerHour   to (if (avgPerHour > 0) settings.formatPrice(avgPerHour) else "–"),
                st.avgShift         to (if (uniqueShifts > 0) settings.formatPrice(avgPerShift) else "–"),
                st.totalTips        to settings.formatPrice(totalTip),
                st.totalWaitKpi     to "%.0f мин".format(totalWait),
                st.workTime         to workHStr,
                st.bestDay          to dayLabels[bestDow],
                st.peakHour         to "${bestHour}:00 – ${bestHour+1}:00",
                st.rideCount        to "${filtered.size}",
            ).forEach { (k, v) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, color = Muted, fontSize = 13.sp)
                    Text(v, color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
            }
        }

        // ── Ефективност bars ──
        StatsSection(st.efficiencyTitle) {
            val totalTime = filtered.sumOf {
                (it.endTime - it.startTime) / 60_000.0
            }.coerceAtLeast(1.0)
            val drivePct = ((totalTime - totalWait) / totalTime * 100).coerceIn(0.0, 100.0)
            val waitPct  = (totalWait / totalTime * 100).coerceIn(0.0, 100.0)

            EffBar(st.timeWithClient, drivePct, Green)
            Spacer(Modifier.height(8.dp))
            EffBar(st.waitEfficiency, waitPct, Color(0xFFA78BFA))
        }

        // ── Топ маршрути ──
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
                            Text("${i+1}.", color = Gold, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Column {
                                Text(route, color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text("$count ${st.timesLabel}", color = Muted, fontSize = 10.sp)
                            }
                        }
                        Text(settings.formatPrice(totalPrice / count),
                            color = Gold, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    if (i < topRoutes.size - 1)
                        HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
                }
            }
        }

        // ── Разбивка разходи ──
        StatsSection(st.breakdownTitle) {
            val fuel = totalKm * 0.18
            val tax  = total * 0.15
            listOf(
                Triple(st.grossRevenue, "+${settings.formatPrice(total)}",   Color.White),
                Triple(st.fuelBreakdown, "-${settings.formatPrice(fuel)}",   Red),
                Triple(st.taxBreakdown, "-${settings.formatPrice(tax)}",     Red),
                Triple(st.netProfit, "+${settings.formatPrice(netProfit)}",  Green),
            ).forEach { (k, v, c) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, color = Muted, fontSize = 13.sp)
                    Text(v, color = c, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StatCard2(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    Card(modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
        shape  = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = color, fontSize = 20.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(sub, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun StatsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun EffBar(label: String, pct: Double, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Text("%.0f%%".format(pct), color = color, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(Color(0xFF1E2430), RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(pct.toFloat() / 100f).fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

private fun startOfDay(ms: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

@Composable
private fun BarChart(
    data: List<Pair<String, Double>>,
    color: Color,
    highlightIndex: Int = -1,
    maxBarHeight: Int = 80,
    showValues: Boolean = true,
) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(0.01) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((maxBarHeight + 36).dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.Bottom,
    ) {
        data.forEachIndexed { idx, (label, value) ->
            val frac     = (value / maxVal).toFloat().coerceIn(0f, 1f)
            val barColor = if (idx == highlightIndex) color else color.copy(alpha = 0.45f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f),
            ) {
                if (showValues && value > 0) {
                    Text(
                        text     = if (value >= 100) "%.0f".format(value)
                                   else              "%.1f".format(value),
                        color    = barColor,
                        fontSize = 7.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .fillMaxWidth()
                        .height(
                            (maxBarHeight * frac)
                                .coerceAtLeast(if (value > 0) 3f else 0f)
                                .dp
                        )
                        .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text     = label,
                    color    = if (idx == highlightIndex) color else Muted,
                    fontSize = 8.sp,
                )
            }
        }
    }
}
