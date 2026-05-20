package com.taxipro.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.unit.*
import androidx.compose.foundation.horizontalScroll
import com.taxipro.data.db.AppSettings
import com.taxipro.data.db.DistanceUnit
import com.taxipro.data.db.Shift
import com.taxipro.data.db.Tariff
import com.taxipro.data.db.formatPrice
import com.taxipro.data.db.formatDistance
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.data.db.Ride
import com.taxipro.data.db.TariffExpense
import com.taxipro.data.db.ExpenseFrequency
import com.taxipro.data.db.ExpenseType
import com.taxipro.data.db.freq
import com.taxipro.data.db.expType
import com.taxipro.ui.viewmodel.RideViewModel
import com.taxipro.ui.viewmodel.TrackingViewModel
import com.taxipro.ui.viewmodel.TrackingState
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun ActiveRideScreen(vm: TrackingViewModel, rideVm: RideViewModel) {
    val tc          = LocalThemeColors.current
    val state       by vm.state.collectAsState()
    val settings    = LocalSettings.current
    val activeShift by vm.activeShift.collectAsState()
    val st          = LocalStrings.current
    var showStop by remember { mutableStateOf(false) }
    var showNoShiftDialog by remember { mutableStateOf(false) }
    var showEndShiftDialog by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tipInput by remember { mutableStateOf("0") }
    var paymentMethod by remember { mutableStateOf("CASH") } // CASH / CARD

    // Тарифа
    val tariffs        by vm.tariffs.collectAsState()
    val currentHour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    var selectedTariff by remember { mutableStateOf<Tariff?>(null) }

    // Статистики за Profit Preview
    val allRides    by rideVm.allRides.collectAsState(initial = emptyList())
    val allShifts   by rideVm.allShifts.collectAsState(initial = emptyList())
    val allExpenses by vm.allExpenses.collectAsState()

    // Авто-избор на тарифа при зареждане
    LaunchedEffect(tariffs) {
        if (selectedTariff == null && tariffs.isNotEmpty()) {
            selectedTariff = tariffs.firstOrNull { t ->
                t.autoEnabled && tariffInHourRange(currentHour, t.autoStartHour, t.autoEndHour)
            } ?: tariffs.first()
        }
    }

    // Замразени стойности при отваряне на диалога (спират да се обновяват)
    var frozenKm      by remember { mutableDoubleStateOf(0.0) }
    var frozenWaitMin by remember { mutableDoubleStateOf(0.0) }
    var frozenPrice   by remember { mutableDoubleStateOf(0.0) }
    var frozenElapsed by remember { mutableLongStateOf(0L) }
    var frozenAdj     by remember { mutableDoubleStateOf(0.0) }
    var wasPaused     by remember { mutableStateOf(false) }

    // ── Финална цена с корекция ───────────────────────────────
    val finalPrice = state.currentPrice + state.fareAdjustment

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Смяна банер ──────────────────────────────────────
        ShiftBanner(
            activeShift   = activeShift,
            isShiftPaused = state.isShiftPaused,
            shiftPausedMs = state.shiftPausedMs,
            shiftTotalKm  = state.shiftTotalKm,
            shiftClientKm = activeShift?.let { shift ->
                val completedKm = allRides
                    .filter { it.shiftId == shift.id && it.endTime > 0 }
                    .sumOf { it.kilometers }
                completedKm + if (state.isTracking) state.totalKm else 0.0
            } ?: 0.0,
            shiftTotalEarned = activeShift?.let { shift ->
                val completedEarned = allRides
                    .filter { it.shiftId == shift.id && it.endTime > 0 }
                    .sumOf { it.price + it.tip }
                completedEarned + if (state.isTracking) finalPrice else 0.0
            } ?: 0.0,
            onStart  = { vm.startShift() },
            onEnd    = { showEndShiftDialog = true },
            onPause  = { vm.pauseShift() },
            onResume = { vm.resumeShift() },
        )

        // ── Shift earnings chart (shown when riding or rides exist) ──────
        activeShift?.let { shift ->
            val shiftRides = remember(allRides, shift.id) {
                allRides.filter { it.shiftId == shift.id && it.endTime > 0 }
            }
            val savedPauseSessions by rideVm.getShiftPausesByShift(shift.id).collectAsState(initial = emptyList())
            val chartPauseSessions = remember(
                savedPauseSessions,
                state.isShiftPaused,
                state.currentShiftPauseStartedAt,
            ) {
                if (state.isShiftPaused && state.currentShiftPauseStartedAt > 0L) {
                    savedPauseSessions + com.taxipro.data.db.ShiftPauseSession(
                        shiftId = shift.id,
                        startTime = state.currentShiftPauseStartedAt,
                        endTime = System.currentTimeMillis(),
                        durationMs = (System.currentTimeMillis() - state.currentShiftPauseStartedAt).coerceAtLeast(0L),
                    )
                } else {
                    savedPauseSessions
                }
            }
            val chartScale   by rideVm.chartScale.collectAsState()
            val avgShiftHours by rideVm.avgShiftHours.collectAsState()
            if (shiftRides.isNotEmpty() || state.isTracking) {
                ShiftEarningsChart(
                    rides                 = shiftRides,
                    waitSessions          = emptyList(),
                    pauseSessions         = chartPauseSessions,
                    shiftStartMs          = shift.startTime,
                    shiftEndMs            = System.currentTimeMillis(),
                    accentColor           = tc.accent,
                    yAxisScale            = chartScale,
                    currentPrice          = if (state.isTracking) finalPrice else 0.0,
                    currentRideStartMs    = state.startTime,
                    isRideActive          = state.isTracking,
                    isShiftActive         = true,
                    expectedDurationHours = avgShiftHours,
                )
            }
        }

        // ── Тарифа избор ──
        if (!state.isTracking) {
            if (tariffs.isNotEmpty()) {
                TariffSelectorBar(tariffs, selectedTariff, currentHour) { selectedTariff = it }
            }
        }

        // ── Активен курс ──
        if (state.isTracking) {
            val displayPrice   = finalPrice
            val displayWaitSec = state.waitSeconds
            val displayKm      = state.totalKm
            val displaySpeed   = state.speedKmh
            val displayWaiting = state.isWaiting
            val displayElapsed = state.elapsedSeconds

            // Голяма цена
            BigPriceCard(settings.formatPrice(displayPrice), displayWaiting,
                if (state.fareAdjustment != 0.0)
                    settings.formatPrice(state.fareAdjustment)
                else null)

            // Скорост / Таймер + Престой
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SpeedCard2(displaySpeed, settings, Modifier.weight(1f).fillMaxHeight())
                WaitCard2(displayWaitSec, Modifier.weight(1f).fillMaxHeight())
            }

            // КМ + Времетраене
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatMini2(st.distanceLabel, settings.formatDistance(displayKm), tc.accent, Modifier.weight(1f))
            }

            // ── Диагностичен ред (активни тарифни ставки) ──────
            // Показва точно какви стойности са подадени към GPS сервиза.
            // Ако pkm е 0.00, сервизът не начислява приход от км.
            val sym  = settings.currency.symbol
            val unit = settings.distanceUnit.shortLabel
            val pkm  = state.activePricePerKm
            val pmin = state.activePricePerMin
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (pkm == 0.0) tc.red.copy(alpha = 0.12f)
                        else tc.surface.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pkm == 0.0) {
                    Icon(Icons.Default.Warning, null,
                        tint = tc.red, modifier = Modifier.size(12.dp))
                }
                Text(
                    "Ставка: $sym${"%.2f".format(state.activeStartFee)} старт  •  " +
                    "$sym${"%.2f".format(pkm)}/$unit  •  " +
                    "$sym${"%.2f".format(pmin)}/мин",
                    color    = if (pkm == 0.0) tc.red else tc.muted,
                    fontSize = 10.sp,
                )
            }

            // ── Корекция на сумата ──
            FareAdjustCard(
                currentAdjust = state.fareAdjustment,
                symbol        = settings.currency.symbol,
                onAdjust      = { amount -> vm.adjustFare(amount) },
                onReset       = { vm.resetFareAdjustment() }
            )
        }

        // ── Контроли ──
        when {
            !state.isTracking -> {
                GpsStartButton {
                    if (activeShift == null) {
                        pendingStart = { vm.startRide(selectedTariff) }
                        showNoShiftDialog = true
                    } else {
                        vm.startRide(selectedTariff)
                    }
                }

                // ── Profit Preview ────────────────────────────
                ProfitPreviewCard(selectedTariff, settings, allRides, allShifts, allExpenses)
            }
            state.isTracking -> {
                RideControls(
                    isPaused = state.isPaused,
                    onPause  = { vm.pauseRide() },
                    onResume = { vm.resumeRide() },
                    onStop   = {
                        frozenKm      = state.totalKm
                        frozenWaitMin = state.waitMinutes
                        frozenPrice   = finalPrice
                        frozenElapsed = if (state.startTime > 0L)
                            (System.currentTimeMillis() - state.startTime) / 1000L
                        else state.elapsedSeconds
                        frozenAdj     = state.fareAdjustment
                        wasPaused     = state.isPaused
                        if (!state.isPaused) vm.pauseRide()
                        showStop      = true
                    }
                )
            }
        }
    }

    // ── Диалог: няма активна смяна ──
    if (showNoShiftDialog) {
        AlertDialog(
            onDismissRequest = { showNoShiftDialog = false; pendingStart = null },
            containerColor   = tc.card,
            title = {
                Text(st.noShiftDialogTitle, color = tc.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    st.noShiftDialogMsg,
                    color = tc.muted, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoShiftDialog = false
                        pendingStart?.invoke()
                        pendingStart = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tc.accent)
                ) { Text(st.yesContinue, color = tc.background, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNoShiftDialog = false; pendingStart = null }) {
                    Text(st.noGoBack, color = tc.muted)
                }
            }
        )
    }

    // ── Диалог: потвърди приключване на смяна ──
    if (showEndShiftDialog) {
        AlertDialog(
            onDismissRequest = { showEndShiftDialog = false },
            containerColor   = tc.card,
            icon = {
                Icon(Icons.Default.Flag, null, tint = tc.accent, modifier = Modifier.size(28.dp))
            },
            title = {
                Text(st.endShiftConfirmTitle, color = tc.textPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            },
            text = {
                Text(st.endShiftConfirmMsg, color = tc.muted, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            },
            confirmButton = {
                Button(
                    onClick = { showEndShiftDialog = false; vm.endShift() },
                    colors  = ButtonDefaults.buttonColors(containerColor = tc.accent),
                    shape   = RoundedCornerShape(10.dp),
                ) { Text(st.shiftEndBtn, color = tc.background, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showEndShiftDialog = false }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            }
        )
    }

    // ── Диалог край на курс ──
    if (showStop) {
        val finalKm    = frozenKm
        val finalWait  = frozenWaitMin
        val finalAdj   = frozenAdj
        val totalPrice = frozenPrice

        AlertDialog(
            onDismissRequest = {
                showStop = false
                if (!wasPaused) vm.resumeRide()
            },
            containerColor   = tc.card,
            title = {
                Text(st.endOfRide, color = tc.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // Финална цена
                    Text(settings.formatPrice(totalPrice),
                        color = tc.accent, fontSize = 32.sp, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace)

                    // Обобщение на курса
                    val durSec = frozenElapsed
                    val durDisplay = when {
                        durSec < 60  -> "${durSec} sec"
                        else         -> "%02d:%02d min".format(durSec / 60, durSec % 60)
                    }
                    val waitSec = (finalWait * 60).toLong()
                    val waitDisplay = when {
                        waitSec < 60 -> "${waitSec} sec"
                        else         -> "%02d:%02d min".format(waitSec / 60, waitSec % 60)
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = tc.cardAlt),
                        shape  = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(st.rideSummary, color = tc.muted, fontSize = 10.sp,
                                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.distanceField,  color = tc.muted, fontSize = 13.sp)
                                Text(settings.formatDistance(finalKm), color = tc.textPrimary,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.durationField,  color = tc.muted, fontSize = 13.sp)
                                Text(durDisplay, color = tc.blue,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.waitTimeField, color = tc.muted, fontSize = 13.sp)
                                Text(waitDisplay, color = tc.purple,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (finalAdj != 0.0) {
                        Text("Adjustment: ${if (finalAdj > 0) "+" else ""}${settings.formatPrice(finalAdj)}",
                            color = if (finalAdj > 0) tc.green else tc.red, fontSize = 12.sp)
                    }

                    HorizontalDivider(color = tc.surface)

                    // Начин на плащане
                    Text(st.paymentMethod, color = tc.muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("CASH" to st.cash, "CARD" to st.card).forEach { (k, l) ->
                            val sel = paymentMethod == k
                            Button(
                                onClick  = { paymentMethod = k },
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = if (sel) tc.accent else tc.background
                                ),
                                shape  = RoundedCornerShape(8.dp),
                                border = if (!sel) BorderStroke(1.dp, tc.surface) else null
                            ) {
                                Text(l, color = if (sel) tc.background else tc.muted,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = tc.surface)

                    // Бакшиш
                    OutlinedTextField(
                        value          = tipInput,
                        onValueChange  = { tipInput = it },
                        label          = { Text("${st.tipLabel} (${settings.currency.symbol})", color = tc.muted) },
                        singleLine     = true,
                        keyboardOptions= androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = tc.accent,
                            unfocusedBorderColor = tc.muted,
                            focusedTextColor     = tc.textPrimary,
                            unfocusedTextColor   = tc.textPrimary,
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.stopAndSaveRide(tipInput.toDoubleOrNull() ?: 0.0, paymentMethod = paymentMethod)
                        showStop = false; tipInput = "0"; paymentMethod = "CASH"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tc.green)
                ) { Text(st.saveBtn, fontWeight = FontWeight.Bold, color = tc.background) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStop = false
                    if (!wasPaused) vm.resumeRide()
                }) { Text(st.cancelBtn, color = tc.muted) }
            }
        )
    }
}

// ── Profit Preview Card ─────────────────────────────────────────
@Composable
fun ProfitPreviewCard(
    tariff: Tariff?,
    settings: AppSettings,
    allRides: List<Ride>,
    allShifts: List<Shift>,
    allExpenses: List<TariffExpense>,
) {
    val tc  = LocalThemeColors.current
    val st  = LocalStrings.current
    val sym = settings.currency.symbol

    // ── Stats from history ───────────────────────────────────
    val completedShifts   = allShifts.filter { !it.isActive && it.endTime > 0 }
    val totalHours        = completedShifts.sumOf { (it.endTime - it.startTime) / 3_600_000.0 }
    val totalRevenue      = allRides.sumOf { it.price }
    val totalKm           = allRides.sumOf { it.kilometers }
    val totalRideCount    = allRides.size.toDouble()
    val hasEnoughData     = completedShifts.isNotEmpty() && totalHours >= 0.5 && totalRevenue > 0.0

    // Averages
    val avgRevenuePerHour = if (totalHours > 0) totalRevenue / totalHours else 0.0
    val avgKmPerHour      = if (totalHours > 0) totalKm / totalHours else 0.0
    val avgRidesPerHour   = if (totalHours > 0) totalRideCount / totalHours else 0.0
    // Custom expenses for this tariff
    val tariffExpenses = if (tariff != null) allExpenses.filter { it.tariffId == tariff.id } else emptyList()

    // ── Helper: cost of custom expenses for an 8h shift ─────
    // PER_MONTH: fixed costs accrue every calendar day (÷30), not per shift
    fun expenseCost8h(gross: Double): Double = tariffExpenses.sumOf { exp ->
        when {
            exp.expType == ExpenseType.PERCENT     -> gross * exp.amount / 100.0
            exp.freq == ExpenseFrequency.PER_RIDE  -> exp.amount * (avgRidesPerHour * 8.0)
            exp.freq == ExpenseFrequency.PER_SHIFT -> exp.amount
            exp.freq == ExpenseFrequency.PER_MONTH -> exp.amount / 30.0
            else -> 0.0
        }
    }

    // ── 8h estimate ──────────────────────────────────────────
    val gross8h      = avgRevenuePerHour * 8.0
    val fuel8h       = avgKmPerHour * 8.0 * (tariff?.fuelCostPerKm ?: 0.0)
    val tax8h        = gross8h * (tariff?.taxPercent ?: 0.0) / 100.0
    val customCost8h = expenseCost8h(gross8h)
    val net8h        = gross8h - fuel8h - tax8h - customCost8h

    Card(
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(st.profitPreviewTitle, color = tc.textPrimary,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)

            if (tariff == null) {
                Text(st.profitPreviewNoTariff, color = tc.muted, fontSize = 12.sp)
                return@Column
            }

            // ── Section 1: 8h estimate ───────────────────────
            Text(st.shift8hTitle, color = tc.accent,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

            if (!hasEnoughData) {
                Text(st.notEnoughDataLabel, color = tc.muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(st.notEnoughDataSub, color = tc.muted, fontSize = 11.sp)
            } else {
                val rows8h = buildList {
                    add(Triple(st.shift8hAvgPerHour, "$sym%.2f".format(avgRevenuePerHour), tc.textPrimary))
                    add(Triple(st.shift8hGross,      "+$sym%.2f".format(gross8h),          tc.textPrimary))
                    add(Triple(st.fuelLabel,         "-$sym%.2f".format(fuel8h),           tc.red))
                    add(Triple(st.taxInsurance,      "-$sym%.2f".format(tax8h),            tc.red))
                    if (customCost8h > 0)
                        add(Triple(st.customCostsRow, "-$sym%.2f".format(customCost8h),   tc.red))
                    add(Triple(st.shift8hNet,        "+$sym%.2f".format(net8h),            tc.green))
                }
                PreviewRows(rows8h)
            }
        }
    }
}

@Composable
private fun PreviewRows(rows: List<Triple<String, String, Color>>) {
    val tc = LocalThemeColors.current
    rows.forEachIndexed { idx, (label, value, color) ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(label, color = tc.muted, fontSize = 12.sp)
            Text(value, color = color, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        if (idx < rows.lastIndex)
            HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
    }
}

// ── Fare Adjustment Card ────────────────────────────────────────
@Composable
fun FareAdjustCard(
    currentAdjust: Double,
    symbol: String,
    onAdjust: (Double) -> Unit,
    onReset: () -> Unit
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    Card(
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(st.fareAdjTitle, color = tc.muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                if (currentAdjust != 0.0) {
                    TextButton(onClick = onReset, contentPadding = PaddingValues(4.dp)) {
                        Text(st.resetBtn, color = tc.red, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Показва текущата корекция
            if (currentAdjust != 0.0) {
                Text(
                    "${if (currentAdjust > 0) "+" else ""}$symbol%.2f".format(currentAdjust),
                    color      = if (currentAdjust > 0) tc.green else tc.red,
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
            }

            // Бутони за корекция
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-1.0, -0.5, -0.1).forEach { amount ->
                    OutlinedButton(
                        onClick  = { onAdjust(amount) },
                        modifier = Modifier.weight(1f),
                        border   = BorderStroke(1.dp, tc.red),
                        shape    = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("$symbol%.2f".format(amount), color = tc.red,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                listOf(0.1, 0.5, 1.0).forEach { amount ->
                    OutlinedButton(
                        onClick  = { onAdjust(amount) },
                        modifier = Modifier.weight(1f),
                        border   = BorderStroke(1.dp, tc.green),
                        shape    = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("+$symbol%.2f".format(amount), color = tc.green,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(st.fareAdjHint,
                color = tc.muted, fontSize = 10.sp)
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────

@Composable
fun BigPriceCard(priceFormatted: String, isWaiting: Boolean, adjustFormatted: String? = null) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.currentFare, color = tc.muted, fontSize = 11.sp, letterSpacing = 1.sp)
            Text(priceFormatted, color = tc.accent, fontSize = 52.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            if (adjustFormatted != null) {
                Text("${st.inclAdjustment} $adjustFormatted",
                    color = tc.muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (isWaiting) st.statusWaiting else st.statusDriving,
                color      = if (isWaiting) tc.purple else tc.green,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// Точка 5 — Престой в секунди/минути
@Composable
fun WaitCard2(waitSeconds: Double, modifier: Modifier) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val totalSec = waitSeconds.toLong()
    val h        = totalSec / 3600
    val m        = (totalSec % 3600) / 60
    val s        = totalSec % 60
    val display  = "%02d:%02d:%02d".format(h, m, s)
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.padding(14.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(st.waitLabel, color = tc.muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(display, color = tc.purple,
                fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("чч:мм:сс", color = tc.muted, fontSize = 11.sp)
        }
    }
}

// Точка 2 — Скорост с правилна мерна единица
@Composable
fun SpeedCard2(speedKmh: Double, settings: AppSettings, modifier: Modifier) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    val displaySpeed = if (settings.distanceUnit == DistanceUnit.MILES)
        speedKmh * 0.621371 else speedKmh
    val unit = if (settings.distanceUnit == DistanceUnit.MILES) "mph" else "km/h"
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(st.speedLabel, color = tc.muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("%.0f".format(displaySpeed), color = tc.textPrimary,
                fontSize = 32.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(unit, color = tc.muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun StatMini2(label: String, value: String, color: Color, modifier: Modifier) {
    val tc = LocalThemeColors.current
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = tc.muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(value, color = color, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun GpsStartButton(onClick: () -> Unit) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tc.accent),
        shape  = RoundedCornerShape(14.dp)) {
        Icon(Icons.Default.PlayArrow, null, tint = tc.background)
        Spacer(Modifier.width(8.dp))
        Text(st.startGpsRide, color = tc.background, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
fun RideControls(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick  = { if (isPaused) onResume() else onPause() },
            modifier = Modifier.weight(1f).height(52.dp),
            shape    = RoundedCornerShape(12.dp),
            border   = BorderStroke(1.dp, tc.muted)
        ) {
            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = tc.accent)
            Spacer(Modifier.width(6.dp))
            Text(if (isPaused) st.resumeBtn else st.pauseBtn, color = tc.accent)
        }
        Button(onClick = onStop, modifier = Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = tc.red),
            shape  = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Stop, null, tint = tc.background)
            Spacer(Modifier.width(6.dp))
            Text(st.endBtn, color = tc.background, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InfoCard(text: String) {
    val tc = LocalThemeColors.current
    Card(colors = CardDefaults.cardColors(containerColor = tc.cardAlt),
        shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ℹ️", fontSize = 16.sp)
            Text(text, color = tc.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun TimerCard(seconds: Long, modifier: Modifier) {
    val tc = LocalThemeColors.current
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                color = tc.blue, fontSize = if (h > 0) 20.sp else 28.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("hh:mm:ss", color = tc.muted, fontSize = 11.sp)
        }
    }
}

// ── Tariff Selector ─────────────────────────────────────────────

fun tariffInHourRange(hour: Int, start: Int, end: Int): Boolean =
    if (start > end) hour >= start || hour < end
    else hour >= start && hour < end

@Composable
fun TariffSelectorBar(
    tariffs: List<Tariff>,
    selected: Tariff?,
    currentHour: Int,
    onSelect: (Tariff?) -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    Card(
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(st.tariffLabel, color = tc.muted, fontSize = 11.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tariffs.forEach { t ->
                    val isAutoActive = t.autoEnabled &&
                        tariffInHourRange(currentHour, t.autoStartHour, t.autoEndHour)
                    val isSel = selected?.id == t.id
                    Button(
                        onClick  = { onSelect(t) },
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (isSel) tc.accent else tc.cardAlt
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (isAutoActive) "⚡ ${t.name}" else t.name,
                            color      = if (isSel) tc.background else tc.muted,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            fontSize   = 13.sp
                        )
                    }
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(6.dp))
                val gSettings = LocalSettings.current
                fun eff(tariffVal: Double, globalVal: Double) =
                    if (tariffVal > 0.0) tariffVal else globalVal
                val effStart = eff(selected.startFee,       gSettings.startFee)
                val effKm    = eff(selected.pricePerKm,     gSettings.pricePerKm)
                val effMin   = eff(selected.pricePerMinute, gSettings.pricePerMinute)
                val sym      = gSettings.currency.symbol
                val unit     = gSettings.distanceUnit.shortLabel
                Text(
                    "${selected.name}:  $sym${"%.2f".format(effStart)} start  •  $sym${"%.2f".format(effKm)}/$unit  •  $sym${"%.2f".format(effMin)}/min",
                    color = tc.muted, fontSize = 10.sp
                )
                if (effKm == 0.0) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint     = tc.red,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            "Цена/км не е настроена — приходът от км ще е 0",
                            color    = tc.red,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

// ── Shift banner ─────────────────────────────────────────────
@Composable
fun ShiftBanner(
    activeShift   : Shift?,
    isShiftPaused : Boolean = false,
    shiftPausedMs : Long    = 0L,
    shiftTotalKm  : Double  = 0.0,
    shiftClientKm : Double  = 0.0,
    shiftTotalEarned : Double = 0.0,
    onStart  : () -> Unit,
    onEnd    : () -> Unit,
    onPause  : () -> Unit = {},
    onResume : () -> Unit = {},
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current
    val settings = LocalSettings.current
    if (activeShift == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = tc.accent.copy(alpha = 0.12f)),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(st.shiftNoneTitle, color = tc.textPrimary,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(st.shiftNoneWarning, color = tc.muted,
                    fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 17.sp)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = tc.background, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.shiftStartBtn, color = tc.background, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        val amber = androidx.compose.ui.graphics.Color(0xFFFFA726)
        val dotColor  = if (isShiftPaused) amber else tc.green
        val cardColor = if (isShiftPaused) amber.copy(alpha = 0.1f) else tc.green.copy(alpha = 0.1f)

        var elapsed by remember { mutableLongStateOf(0L) }
        // Capture pause-start wall-clock time so the timer freezes while paused
        val pauseStartRef = remember { mutableLongStateOf(0L) }
        LaunchedEffect(isShiftPaused) {
            pauseStartRef.longValue = if (isShiftPaused) System.currentTimeMillis() else 0L
        }
        LaunchedEffect(activeShift.startTime, isShiftPaused) {
            while (true) {
                val now = System.currentTimeMillis()
                val currentPauseDur = if (isShiftPaused && pauseStartRef.longValue > 0L)
                    now - pauseStartRef.longValue else 0L
                elapsed = maxOf(0L, (now - activeShift.startTime - shiftPausedMs - currentPauseDur) / 1000L)
                delay(1000L)
            }
        }
        val h   = elapsed / 3600
        val m   = (elapsed % 3600) / 60
        val dur = if (h > 0) "${h}${st.hoursAbbr} ${m}${st.minAbbr}" else "${m}${st.minAbbr}"
        val statusText = if (isShiftPaused) "${st.pausedLabel}  •  $dur" else dur
        val kmText = "%.1f km total  •  %.1f ${st.withClients}  •  %.1f без клиент"
            

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = cardColor),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.size(10.dp).background(dotColor, RoundedCornerShape(5.dp)))
                    Column {
                        Text(st.shiftActiveLabel.format(activeShift.shiftNumber),
                            color = dotColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isShiftPaused) "${st.pausedLabel}  •  $dur" else dur,
                            color = tc.muted, fontSize = 11.sp
                        )
                        Text("%.1f km".format(shiftTotalKm), color = tc.muted, fontSize = 10.sp)
                        Text(
                            settings.formatPrice(shiftTotalEarned),
                            color = tc.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Pause / Resume button
                    OutlinedButton(
                        onClick = if (isShiftPaused) onResume else onPause,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = amber),
                        border  = androidx.compose.foundation.BorderStroke(1.dp, amber.copy(alpha = 0.7f)),
                        shape   = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        if (isShiftPaused) {
                            Icon(Icons.Default.PlayArrow, null, tint = amber, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Pause, null, tint = amber, modifier = Modifier.size(16.dp))
                        }
                    }
                    // End button
                    OutlinedButton(
                        onClick = onEnd,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = tc.red),
                        border  = androidx.compose.foundation.BorderStroke(1.dp, tc.red.copy(alpha = 0.6f)),
                        shape   = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Stop, null, tint = tc.red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(st.shiftEndBtn, color = tc.red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
