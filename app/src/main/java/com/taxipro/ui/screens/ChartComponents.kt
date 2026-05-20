package com.taxipro.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.taxipro.data.db.Ride
import com.taxipro.data.db.Shift
import com.taxipro.data.db.ShiftPauseSession
import com.taxipro.data.db.ZoneWaitSession
import com.taxipro.data.db.formatPrice
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ────────────────────────────────────────────────────────────────────────────
// Shift Earnings Area Chart
//
// Draws a staircase area chart: earnings jump up at each ride completion.
// Y-axis: 4 labels [0, step, 2*step, 3*step] — always starts from 0.
// X-axis: shift start time + round-hour marks (1h/2h/3h interval).
//
// Live animation:
//   • Pass isRideActive=true + currentPrice while a ride is ongoing.
//   • Chart recomposes every 150 ms.
// ────────────────────────────────────────────────────────────────────────────
private fun formatDurationShort(totalMs: Long): String {
    val totalMin = (totalMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
fun ShiftEarningsChart(
    rides                 : List<Ride>,
    waitSessions          : List<ZoneWaitSession> = emptyList(),
    pauseSessions         : List<ShiftPauseSession> = emptyList(),
    shiftStartMs          : Long,
    shiftEndMs            : Long,
    accentColor           : Color,
    yAxisScale            : YAxisScale? = null,   // null → derived from completedEarnings
    currentPrice          : Double      = 0.0,    // live price of the active ride
    currentRideStartMs    : Long        = 0L,
    isRideActive          : Boolean     = false,
    isShiftActive         : Boolean     = isRideActive,
    expectedDurationHours : Double      = 8.0,    // for X-axis interval selection
    useActualShiftScale   : Boolean     = false,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val density  = LocalDensity.current

    val sortedRides = remember(rides) {
        rides.filter { it.endTime > shiftStartMs }.sortedBy { it.endTime }
    }
    val completedEarned = sortedRides.sumOf { it.price + it.tip }
    val displayEarned   = completedEarned + if (isRideActive) currentPrice else 0.0
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var chartPadL by remember { mutableFloatStateOf(0f) }
    var chartPadT by remember { mutableFloatStateOf(0f) }
    var chartWidth by remember { mutableFloatStateOf(1f) }
    var chartHeight by remember { mutableFloatStateOf(1f) }
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var probeTouchX by remember { mutableFloatStateOf(0f) }
    var probeChartX by remember { mutableFloatStateOf(0f) }
    var probeTimeMs by remember { mutableLongStateOf(shiftStartMs) }
    var probeEarnings by remember { mutableDoubleStateOf(0.0) }
    var probeActive by remember { mutableStateOf(false) }

    // ── Y-axis scale: always fit the actual data ─────────────────────────────
    // Scale to displayEarned (real data) so the chart fills nicely.
    // Fall back to historical scale only when there is nothing earned yet.
    val scaleBucket = (displayEarned * 2).toLong()   // recompute when earnings change by ≥ €0.50
    val baseScale = yAxisScale ?: YAxisScale(listOf(20, 40, 60, 80), 80.0, 20)
    val scale: YAxisScale = remember(shiftStartMs, baseScale, scaleBucket, useActualShiftScale, isRideActive) {
        when {
            displayEarned > 0.0 -> calculateYAxisScaleForActualEarnings(displayEarned)
            else -> baseScale
        }
    }

    // ── X-axis scale: time labels — locked once per shift start + duration ─
    val elapsedHours = ((shiftEndMs - shiftStartMs).coerceAtLeast(0L)) / 3_600_000.0
    val scaleDurationHours = if (useActualShiftScale || isShiftActive) {
        elapsedHours
    } else {
        maxOf(expectedDurationHours, elapsedHours)
    }
    val durationBucket = Math.ceil(maxOf(scaleDurationHours, 0.01) * 2.0).toInt().coerceAtLeast(1)
    val xScale: XAxisScale = remember(shiftStartMs, durationBucket) {
        calculateXAxisScale(
            shiftStartMs = shiftStartMs,
            durationHours = scaleDurationHours,
            shiftEndMs = shiftEndMs,
        )
    }

    // ── Recompose every 150 ms while a ride is active ─────────────────────
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRideActive) {
        if (isRideActive) { while (true) { delay(10_000L); tick++ } }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {

            // ── Header ─────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top,
            ) {
                Column {
                    Text(
                        "EARNED THIS SHIFT",
                        color         = tc.muted, fontSize = 11.sp,
                        letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${timeFmt.format(Date(shiftStartMs))} – ${timeFmt.format(Date(shiftEndMs))}",
                        color = tc.muted, fontSize = 10.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        settings.formatPrice(displayEarned),
                        color      = accentColor, fontSize = 26.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    )
                    if (isRideActive && currentPrice > 0.0) {
                        Text(
                            "+${settings.formatPrice(currentPrice)} live",
                            color    = accentColor.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            if (sortedRides.isEmpty() && !isRideActive) {
                Spacer(Modifier.height(12.dp))
                Text("No completed rides yet", color = tc.muted, fontSize = 12.sp)
                return@Column
            }

            Spacer(Modifier.height(12.dp))

            // ── Canvas chart ────────────────────────────────────
            val fillColor      = accentColor.copy(alpha = 0.12f)
            val gridColor      = tc.muted.copy(alpha = 0.12f)
            val liveColor      = accentColor.copy(alpha = 0.55f)
            val axisTextStyle  = TextStyle(
                fontSize   = 8.5.sp,
                color      = tc.muted,
                fontFamily = FontFamily.Monospace,
            )
            val textMeasurer = rememberTextMeasurer()
            val xAxisEndMs = maxOf(shiftEndMs, xScale.labels.lastOrNull()?.first ?: shiftEndMs)
            val timeRangeMs = (xAxisEndMs - shiftStartMs).coerceAtLeast(1L)
            val liveStartMs = maxOf(
                if (currentRideStartMs > 0L) currentRideStartMs else shiftStartMs,
                sortedRides.lastOrNull()?.endTime ?: shiftStartMs,
            )
            val liveTotal = completedEarned + if (isRideActive) currentPrice else 0.0

            fun earningsAtTime(targetMs: Long): Double {
                val clamped = targetMs.coerceIn(shiftStartMs, shiftEndMs)
                var total = 0.0
                sortedRides.forEach { ride ->
                    val rideValue = ride.price + ride.tip
                    when {
                        clamped >= ride.endTime -> total += rideValue
                        clamped > ride.startTime && ride.endTime > ride.startTime -> {
                            val progress = (clamped - ride.startTime).toDouble() /
                                (ride.endTime - ride.startTime).toDouble()
                            total += rideValue * progress.coerceIn(0.0, 1.0)
                        }
                    }
                }
                if (isRideActive && currentPrice > 0.0 && clamped > liveStartMs && shiftEndMs > liveStartMs) {
                    val progress = (clamped - liveStartMs).toDouble() / (shiftEndMs - liveStartMs).toDouble()
                    total += currentPrice * progress.coerceIn(0.0, 1.0)
                }
                return total
            }

            fun updateProbeAt(touchX: Float) {
                val clampedChartX = touchX.coerceIn(chartPadL, chartPadL + chartWidth)
                val progress = ((clampedChartX - chartPadL) / chartWidth).coerceIn(0f, 1f)
                val targetMs = shiftStartMs + (timeRangeMs * progress).toLong()
                probeTouchX = touchX.coerceIn(0f, canvasWidth)
                probeChartX = clampedChartX
                probeTimeMs = targetMs
                probeEarnings = earningsAtTime(targetMs)
                probeActive = true
            }

            val activeWaitSession = remember(waitSessions, probeTimeMs) {
                waitSessions.firstOrNull { probeTimeMs in it.startTime..it.endTime }
            }
            val activePauseSession = remember(pauseSessions, probeTimeMs) {
                pauseSessions.firstOrNull {
                    val pauseEnd = if (it.endTime > 0L) it.endTime else shiftEndMs
                    probeTimeMs in it.startTime..pauseEnd
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp)
                        .pointerInput(sortedRides, shiftStartMs, shiftEndMs, currentPrice, isRideActive, currentRideStartMs, xScale, scale) {
                            detectTapGestures(
                                onTap = { offset -> updateProbeAt(offset.x) }
                            )
                        }
                        .pointerInput(sortedRides, shiftStartMs, shiftEndMs, currentPrice, isRideActive, currentRideStartMs, xScale, scale) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    updateProbeAt(offset.x)
                                },
                                onDragEnd = { },
                                onDragCancel = { },
                            ) { change, _ ->
                                updateProbeAt(change.position.x)
                                change.consume()
                            }
                        }
                ) {
                val w = size.width
                val h = size.height

                // ── Layout ────────────────────────────────────────
                val padL    = with(density) { 36.dp.toPx() }   // Y-axis label area
                val padR    = with(density) {  6.dp.toPx() }
                val padT    = with(density) {  8.dp.toPx() }
                val padB    = with(density) { 28.dp.toPx() }   // X-axis label area
                val chartW  = w - padL - padR
                val chartH  = h - padT - padB
                chartPadL = padL
                chartPadT = padT
                chartWidth = chartW.coerceAtLeast(1f)
                chartHeight = chartH.coerceAtLeast(1f)
                canvasWidth = w.coerceAtLeast(1f)

                val timeRange = timeRangeMs.toFloat()
                val maxEarn   = scale.maxValue.coerceAtLeast(0.01)

                fun toX(ms: Long)  = padL + (ms - shiftStartMs).toFloat() / timeRange * chartW
                fun toY(v: Double) = (padT + chartH - (v / maxEarn * chartH).toFloat())
                    .coerceIn(padT, padT + chartH)

                // ── Y-axis labels + horizontal gridlines ──────────
                scale.labels.forEach { labelVal ->
                    val gy = toY(labelVal.toDouble())
                    drawLine(gridColor.copy(alpha = 0.18f), Offset(padL, gy), Offset(padL + chartW, gy), 1f)
                    val labelStr = if (labelVal >= 1000) "${labelVal / 1000}k" else "$labelVal"
                    val measured = textMeasurer.measure(labelStr, axisTextStyle)
                    drawText(
                        textMeasurer = textMeasurer,
                        text         = labelStr,
                        style        = axisTextStyle,
                        topLeft      = Offset(
                            x = padL - measured.size.width - with(density) { 4.dp.toPx() },
                            y = gy - measured.size.height / 2f,
                        ),
                    )
                }

                val pauseFillColor = Color.Black.copy(alpha = 0.30f)
                val pauseEdgeColor = Color.Black.copy(alpha = 0.82f)
                pauseSessions.forEach { pause ->
                    val pauseEnd = (if (pause.endTime > 0L) pause.endTime else shiftEndMs)
                        .coerceAtMost(shiftEndMs)
                    if (pauseEnd <= pause.startTime) return@forEach
                    val left = toX(pause.startTime.coerceAtLeast(shiftStartMs))
                    val right = toX(pauseEnd.coerceAtLeast(shiftStartMs))
                    val bandWidth = (right - left).coerceAtLeast(1f)
                    drawRect(
                        color = pauseFillColor,
                        topLeft = Offset(left, padT),
                        size = Size(bandWidth, chartH),
                    )
                    drawLine(
                        color = pauseEdgeColor,
                        start = Offset(left, padT),
                        end = Offset(left, padT + chartH),
                        strokeWidth = 1.6f,
                    )
                    drawLine(
                        color = pauseEdgeColor,
                        start = Offset(right, padT),
                        end = Offset(right, padT + chartH),
                        strokeWidth = 1.6f,
                    )
                }

                // ── Build ride-aware path ────────────────────────
                //  • Between rides (idle): flat horizontal line
                //  • During a ride: diagonal rise proportional to earnings
                //  • Live ride: dashed diagonal from last point to now
                val nowX      = toX(shiftEndMs)

                fun addRideRise(
                    path: Path,
                    startMs: Long,
                    endMs: Long,
                    fromValue: Double,
                    toValue: Double,
                ) {
                    val safeEndMs = endMs.coerceAtLeast(startMs + 1L)
                    val samples = 14
                    for (i in 1..samples) {
                        val t = i / samples.toDouble()
                        val smooth = t * t * (3.0 - 2.0 * t)
                        val wave = Math.sin(t * Math.PI * 3.0) * 0.035 * t * (1.0 - t)
                        val progress = (smooth + wave).coerceIn(0.0, 1.0)
                        val ms = startMs + ((safeEndMs - startMs) * t).toLong()
                        path.lineTo(toX(ms), toY(fromValue + (toValue - fromValue) * progress))
                    }
                }

                // --- helper: trace path through all completed rides ---
                fun tracePath(path: Path) {
                    var cum = 0.0
                    sortedRides.forEach { ride ->
                        // flat from previous point → ride start (idle time)
                        path.lineTo(toX(ride.startTime), toY(cum))
                        // diagonal rise → ride end (earning time)
                        val beforeRide = cum
                        cum += ride.price + ride.tip
                        addRideRise(path, ride.startTime, ride.endTime, beforeRide, cum)
                    }
                }

                // ── Area fill ────────────────────────────────────
                val areaPath = Path()
                areaPath.moveTo(toX(shiftStartMs), padT + chartH)   // bottom-left
                areaPath.lineTo(toX(shiftStartMs), toY(0.0))        // start at €0
                tracePath(areaPath)
                val cumAfterRides = completedEarned
                if (isRideActive && currentPrice > 0.0) {
                    areaPath.lineTo(toX(liveStartMs), toY(cumAfterRides))
                    addRideRise(areaPath, liveStartMs, shiftEndMs, cumAfterRides, liveTotal)
                } else {
                    areaPath.lineTo(nowX, toY(cumAfterRides))        // flat to end
                }
                areaPath.lineTo(nowX, padT + chartH)                 // drop to bottom
                areaPath.close()
                drawPath(areaPath, fillColor)

                // ── Stroke line ──────────────────────────────────
                val strokePath = Path()
                strokePath.moveTo(toX(shiftStartMs), toY(0.0))
                tracePath(strokePath)
                if (isRideActive && currentPrice > 0.0) {
                    strokePath.lineTo(toX(liveStartMs), toY(cumAfterRides))
                    addRideRise(strokePath, liveStartMs, shiftEndMs, cumAfterRides, liveTotal)
                    // don't extend stroke into live area — dashed indicator handles it
                } else {
                    strokePath.lineTo(nowX, toY(cumAfterRides))      // flat to end
                }
                drawPath(
                    strokePath, accentColor,
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                // ── Dots at each completed ride ──────────────────
                var cumDots = 0.0
                sortedRides.forEach { ride ->
                    cumDots += ride.price + ride.tip
                    val x = toX(ride.endTime)
                    val y = toY(cumDots)
                    drawCircle(accentColor,                    radius = 4.5f, center = Offset(x, y))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 2.2f, center = Offset(x, y))
                }

                // ── Live indicator (dashed diagonal + pulsing dot) ─
                if (isRideActive && currentPrice > 0.0) {
                    val liveY     = toY(liveTotal)
                    val dashPath  = Path()
                    dashPath.moveTo(toX(liveStartMs), toY(cumAfterRides))
                    addRideRise(dashPath, liveStartMs, shiftEndMs, cumAfterRides, liveTotal)
                    drawPath(
                        dashPath, liveColor,
                        style = Stroke(
                            width      = 2.0f,
                            cap        = StrokeCap.Round,
                            join       = StrokeJoin.Round,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f, 4f), 0f
                            ),
                        ),
                    )
                    drawCircle(liveColor,                       radius = 5.5f, center = Offset(nowX, liveY))
                    drawCircle(Color.White.copy(alpha = 0.85f), radius = 2.5f, center = Offset(nowX, liveY))
                }

                // ── X-axis: baseline + time labels ────────────────
                val baselineY  = padT + chartH
                val tickH      = with(density) { 4.dp.toPx() }
                val labelY     = baselineY + tickH + with(density) { 3.dp.toPx() }
                val minLabelGap = with(density) { 28.dp.toPx() }  // avoid overlap

                drawLine(
                    color       = tc.muted.copy(alpha = 0.25f),
                    start       = Offset(padL, baselineY),
                    end         = Offset(padL + chartW, baselineY),
                    strokeWidth = 0.8f,
                )

                var lastLabelEndX = -Float.MAX_VALUE
                xScale.labels.forEach { (labelMs, labelStr) ->
                    val x = toX(labelMs)
                    if (x < padL || x > padL + chartW) return@forEach   // outside chart range
                    drawLine(
                        color       = tc.muted.copy(alpha = 0.14f),
                        start       = Offset(x, padT),
                        end         = Offset(x, baselineY),
                        strokeWidth = 0.9f,
                    )
                    val measured = textMeasurer.measure(labelStr, axisTextStyle)
                    val centeredX = (x - measured.size.width / 2f)
                        .coerceIn(padL, padL + chartW - measured.size.width)
                    // Skip if too close to previous label
                    if (centeredX < lastLabelEndX + minLabelGap) return@forEach
                    // Tick mark
                    drawLine(
                        color       = tc.muted.copy(alpha = 0.35f),
                        start       = Offset(x, baselineY),
                        end         = Offset(x, baselineY + tickH),
                        strokeWidth = 1f,
                    )
                    // Text
                    drawText(
                        textMeasurer = textMeasurer,
                        text         = labelStr,
                        style        = axisTextStyle,
                        topLeft      = Offset(centeredX, labelY),
                    )
                    lastLabelEndX = centeredX + measured.size.width
                }
                if (probeActive) {
                    val probeY = toY(probeEarnings)
                    drawLine(
                        color       = accentColor.copy(alpha = 0.30f),
                        start       = Offset(probeChartX, padT),
                        end         = Offset(probeChartX, baselineY),
                        strokeWidth = 1.4f,
                    )
                    drawLine(
                        color       = accentColor.copy(alpha = 0.24f),
                        start       = Offset(padL, probeY),
                        end         = Offset(padL + chartW, probeY),
                        strokeWidth = 1.2f,
                    )
                    drawCircle(accentColor, radius = 5f, center = Offset(probeChartX, probeY))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 2.4f, center = Offset(probeChartX, probeY))
                }
                }

                if (probeActive) {
                    Box(
                        modifier = Modifier
                            .offset {
                                val tooltipX = (probeTouchX - canvasWidth * 0.18f)
                                    .coerceIn(0f, (canvasWidth - canvasWidth * 0.36f).coerceAtLeast(0f))
                                IntOffset(tooltipX.toInt(), 4.dp.roundToPx())
                            }
                            .background(tc.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
                            .border(1.dp, accentColor.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                settings.formatPrice(probeEarnings),
                                color = accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                timeFmt.format(Date(probeTimeMs.coerceIn(shiftStartMs, shiftEndMs))),
                                color = tc.muted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            activePauseSession?.let { pause ->
                                Text(
                                    st.pausedLabel,
                                    color = tc.textPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    formatDurationShort(
                                        ((if (pause.endTime > 0L) pause.endTime else shiftEndMs) - pause.startTime)
                                            .coerceAtLeast(0L)
                                    ),
                                    color = tc.muted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            activeWaitSession?.let { wait ->
                                Text(
                                    wait.zoneName,
                                    color = tc.textPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${timeFmt.format(Date(wait.startTime))} - ${timeFmt.format(Date(wait.endTime))}  •  ${formatDurationShort(wait.durationMs)}",
                                    color = tc.muted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("${sortedRides.size} ${st.ridesLabel}")
                    if (isRideActive) append("  •  ${st.activeLabel}")
                },
                color = tc.muted, fontSize = 10.sp,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Weekly $/Hour Trend Chart
//
// Aggregates allRides into weekly buckets (up to last 8 weeks with data),
// computes $/hr for each, and draws a connected-dot line chart.
// The most recent data point is highlighted in pink/rose.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun WeeklyRateChart(
    allRides   : List<Ride>,
    allShifts  : List<Shift>,
    accentColor: Color,
) {
    val tc       = LocalThemeColors.current
    val settings = LocalSettings.current

    // ── Compute weekly $/hr data ──────────────────────────────
    val weeklyData: List<Pair<String, Double>> = remember(allRides, allShifts) {
        val now    = System.currentTimeMillis()
        val weekMs = 7L * 86_400_000L
        val sdf    = SimpleDateFormat("d MMM", Locale.getDefault())

        (7 downTo 0).mapNotNull { weeksAgo ->
            val weekEnd   = now - weeksAgo * weekMs
            val weekStart = weekEnd - weekMs

            val weekRides = allRides.filter { it.startTime in weekStart..weekEnd }
            if (weekRides.isEmpty()) return@mapNotNull null

            val revenue = weekRides.sumOf { it.price + it.tip }

            // Hours: prefer shift span; fall back to ride durations
            val shiftIds = weekRides.map { it.shiftId }.filter { it > 0L }.toSet()
            val shiftHrs = shiftIds.sumOf { id ->
                val s = allShifts.firstOrNull { it.id == id }
                if (s != null && s.endTime > s.startTime)
                    (s.endTime - s.startTime) / 3_600_000.0
                else
                    weekRides.filter { it.shiftId == id }
                        .sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) / 3_600_000.0 }
            }
            val noShiftHrs = weekRides.filter { it.shiftId <= 0L }
                .sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) / 3_600_000.0 }
            val totalH = shiftHrs + noShiftHrs

            if (totalH < 0.1) return@mapNotNull null

            val label = sdf.format(Date(weekStart + weekMs / 2))   // mid-week date
            label to (revenue / totalH)
        }
    }

    if (weeklyData.size < 2) return   // need at least 2 points to draw a trend

    val lastRate       = weeklyData.last().second
    val highlightColor = Color(0xFFFF6B9D)   // rose/pink

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {

            // ── Header ─────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "ТЕНДЕНЦИЯ $/ЧАС",
                        color = tc.muted, fontSize = 11.sp,
                        letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Средна печалба на час за всяка работна седмица",
                        color = tc.muted, fontSize = 10.sp,
                    )
                }
                Text(
                    settings.formatPrice(lastRate) + "/ч",
                    color      = highlightColor, fontSize = 20.sp,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Legend ─────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Regular week dot
                Canvas(Modifier.size(8.dp)) {
                    drawCircle(accentColor, radius = size.minDimension / 2)
                }
                Text("Минала седмица", color = tc.muted, fontSize = 10.sp)

                // Current week dot
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(highlightColor, radius = size.minDimension / 2)
                }
                Text("Тази седмица", color = highlightColor, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            // ── Canvas chart ────────────────────────────────────
            val lineColor = accentColor.copy(alpha = 0.65f)
            val gridColor = tc.muted.copy(alpha = 0.12f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                val w      = size.width
                val h      = size.height
                val padL   = 12f
                val padR   = 12f
                val padT   = 8f
                val padB   = 6f
                val chartW = w - padL - padR
                val chartH = h - padT - padB

                val n     = weeklyData.size
                val maxV  = weeklyData.maxOf { it.second }.coerceAtLeast(0.01)
                val minV  = weeklyData.minOf { it.second }
                val range = (maxV - minV).coerceAtLeast(maxV * 0.15)

                fun toX(i: Int)    = padL + (if (n > 1) i.toFloat() / (n - 1) else 0.5f) * chartW
                fun toY(v: Double) = (padT + chartH - ((v - minV) / range * chartH)).toFloat()

                // Grid
                for (i in 0..2) {
                    val gy = padT + chartH * (i / 2f)
                    drawLine(gridColor, Offset(padL, gy), Offset(padL + chartW, gy), 0.7f)
                }

                // Lines between points
                for (i in 0 until n - 1) {
                    drawLine(
                        lineColor,
                        Offset(toX(i),   toY(weeklyData[i].second)),
                        Offset(toX(i+1), toY(weeklyData[i+1].second)),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round,
                    )
                }

                // Dots
                weeklyData.forEachIndexed { i, (_, v) ->
                    val x      = toX(i)
                    val y      = toY(v)
                    val isLast = i == n - 1
                    val dotC   = if (isLast) highlightColor else accentColor
                    val dotR   = if (isLast) 7f else 4.5f
                    drawCircle(dotC,                           dotR,        Offset(x, y))
                    drawCircle(Color.White.copy(alpha = 0.9f), dotR - 2.5f, Offset(x, y))
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── X-axis: $/hr value + week date for each point ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weeklyData.forEachIndexed { i, (label, value) ->
                    val isLast = i == weeklyData.size - 1
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier            = Modifier.weight(1f),
                    ) {
                        Text(
                            settings.formatPrice(value) + "/ч",
                            color      = if (isLast) highlightColor else accentColor,
                            fontSize   = 8.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            textAlign  = TextAlign.Center,
                        )
                        Text(
                            label,
                            color     = tc.muted,
                            fontSize  = 9.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
