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
import com.taxipro.ui.viewmodel.TrackingViewModel
import com.taxipro.ui.viewmodel.TrackingState
import kotlinx.coroutines.delay
import java.util.Calendar

// ── Единна цветова схема ──────────────────────────────────────
val Gold   = Color(0xFFF5C842)
val Dark   = Color(0xFF0A0C10)
val Card   = Color(0xFF161A22)
val Green  = Color(0xFF2ECC8A)
val Red    = Color(0xFFE85555)
val Muted  = Color(0xFF6B7280)
val Blue   = Color(0xFF4A9EFF)
val Purple = Color(0xFFA78BFA)

@Composable
fun ActiveRideScreen(vm: TrackingViewModel) {
    val state       by vm.state.collectAsState()
    val settings    = LocalSettings.current
    val activeShift by vm.activeShift.collectAsState()
    val st          = LocalStrings.current
    var showStop by remember { mutableStateOf(false) }
    var showNoShiftDialog by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tipInput by remember { mutableStateOf("0") }
    var paymentMethod by remember { mutableStateOf("CASH") } // CASH / CARD

    // Тарифа
    val tariffs        by vm.tariffs.collectAsState()
    val currentHour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    var selectedTariff by remember { mutableStateOf<Tariff?>(null) }

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
            .background(Dark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Смяна банер ──────────────────────────────────────
        ShiftBanner(activeShift = activeShift, onStart = { vm.startShift() }, onEnd = { vm.endShift() })

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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeedCard2(displaySpeed, settings, Modifier.weight(1f))
                WaitCard2(displayWaitSec, Modifier.weight(1f))
            }

            // КМ + Времетраене
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatMini2(st.distanceLabel, settings.formatDistance(displayKm), Gold, Modifier.weight(1f))
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
            containerColor   = Card,
            title = {
                Text(st.noShiftDialogTitle, color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    st.noShiftDialogMsg,
                    color = Muted, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoShiftDialog = false
                        pendingStart?.invoke()
                        pendingStart = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) { Text(st.yesContinue, color = Dark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNoShiftDialog = false; pendingStart = null }) {
                    Text(st.noGoBack, color = Muted)
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
            containerColor   = Card,
            title = {
                Text(st.endOfRide, color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // Финална цена
                    Text(settings.formatPrice(totalPrice),
                        color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Black,
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1017)),
                        shape  = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(st.rideSummary, color = Muted, fontSize = 10.sp,
                                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.distanceField,  color = Muted, fontSize = 13.sp)
                                Text(settings.formatDistance(finalKm), color = Color.White,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.durationField,  color = Muted, fontSize = 13.sp)
                                Text(durDisplay, color = Blue,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(st.waitTimeField, color = Muted, fontSize = 13.sp)
                                Text(waitDisplay, color = Purple,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (finalAdj != 0.0) {
                        Text("Adjustment: ${if (finalAdj > 0) "+" else ""}${settings.formatPrice(finalAdj)}",
                            color = if (finalAdj > 0) Green else Red, fontSize = 12.sp)
                    }

                    HorizontalDivider(color = Color(0xFF1E2430))

                    // Начин на плащане
                    Text(st.paymentMethod, color = Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("CASH" to st.cash, "CARD" to st.card).forEach { (k, l) ->
                            val sel = paymentMethod == k
                            Button(
                                onClick  = { paymentMethod = k },
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = if (sel) Gold else Color(0xFF0A0C10)
                                ),
                                shape  = RoundedCornerShape(8.dp),
                                border = if (!sel) BorderStroke(1.dp, Color(0xFF1E2430)) else null
                            ) {
                                Text(l, color = if (sel) Dark else Muted,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E2430))

                    // Бакшиш
                    OutlinedTextField(
                        value          = tipInput,
                        onValueChange  = { tipInput = it },
                        label          = { Text("${st.tipLabel} (${settings.currency.symbol})", color = Muted) },
                        singleLine     = true,
                        keyboardOptions= androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Gold,
                            unfocusedBorderColor = Muted,
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.stopAndSaveRide(tipInput.toDoubleOrNull() ?: 0.0)
                        showStop = false; tipInput = "0"; paymentMethod = "CASH"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) { Text(st.saveBtn, fontWeight = FontWeight.Bold, color = Dark) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStop = false
                    if (!wasPaused) vm.resumeRide()
                }) { Text(st.cancelBtn, color = Muted) }
            }
        )
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
    val st = LocalStrings.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Card),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(st.fareAdjTitle, color = Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                if (currentAdjust != 0.0) {
                    TextButton(onClick = onReset, contentPadding = PaddingValues(4.dp)) {
                        Text(st.resetBtn, color = Red, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Показва текущата корекция
            if (currentAdjust != 0.0) {
                Text(
                    "${if (currentAdjust > 0) "+" else ""}$symbol%.2f".format(currentAdjust),
                    color      = if (currentAdjust > 0) Green else Red,
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
                        border   = BorderStroke(1.dp, Red),
                        shape    = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("$symbol%.2f".format(amount), color = Red,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                listOf(0.1, 0.5, 1.0).forEach { amount ->
                    OutlinedButton(
                        onClick  = { onAdjust(amount) },
                        modifier = Modifier.weight(1f),
                        border   = BorderStroke(1.dp, Green),
                        shape    = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("+$symbol%.2f".format(amount), color = Green,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(st.fareAdjHint,
                color = Muted, fontSize = 10.sp)
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────

@Composable
fun BigPriceCard(priceFormatted: String, isWaiting: Boolean, adjustFormatted: String? = null) {
    val st = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Card),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.currentFare, color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
            Text(priceFormatted, color = Gold, fontSize = 52.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            if (adjustFormatted != null) {
                Text("${st.inclAdjustment} $adjustFormatted",
                    color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (isWaiting) st.statusWaiting else st.statusDriving,
                color      = if (isWaiting) Purple else Green,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// Точка 5 — Престой в секунди/минути
@Composable
fun WaitCard2(waitSeconds: Double, modifier: Modifier) {
    val st = LocalStrings.current
    val totalSec = waitSeconds.toLong()
    val display  = if (totalSec < 60) {
        "${totalSec}s"
    } else {
        val m = totalSec / 60
        val s = totalSec % 60
        "%d:%02dm".format(m, s)
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(st.waitLabel, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(display, color = Purple,
                fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(st.secondsMin, color = Muted, fontSize = 11.sp)
        }
    }
}

// Точка 2 — Скорост с правилна мерна единица
@Composable
fun SpeedCard2(speedKmh: Double, settings: AppSettings, modifier: Modifier) {
    val st = LocalStrings.current
    val displaySpeed = if (settings.distanceUnit == DistanceUnit.MILES)
        speedKmh * 0.621371 else speedKmh
    val unit = if (settings.distanceUnit == DistanceUnit.MILES) "mph" else "km/h"
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(st.speedLabel, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text("%.0f".format(displaySpeed), color = Color.White,
                fontSize = 32.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(unit, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun StatMini2(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(value, color = color, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun GpsStartButton(onClick: () -> Unit) {
    val st = LocalStrings.current
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold),
        shape  = RoundedCornerShape(14.dp)) {
        Icon(Icons.Default.PlayArrow, null, tint = Dark)
        Spacer(Modifier.width(8.dp))
        Text(st.startGpsRide, color = Dark, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
fun RideControls(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val st = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick  = { if (isPaused) onResume() else onPause() },
            modifier = Modifier.weight(1f).height(52.dp),
            shape    = RoundedCornerShape(12.dp),
            border   = BorderStroke(1.dp, Muted)
        ) {
            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Gold)
            Spacer(Modifier.width(6.dp))
            Text(if (isPaused) st.resumeBtn else st.pauseBtn, color = Gold)
        }
        Button(onClick = onStop, modifier = Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red),
            shape  = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Stop, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(st.endBtn, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InfoCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ℹ️", fontSize = 16.sp)
            Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun TimerCard(seconds: Long, modifier: Modifier) {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                color = Blue, fontSize = if (h > 0) 20.sp else 28.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("hh:mm:ss", color = Muted, fontSize = 11.sp)
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
    val st = LocalStrings.current
    Card(
        colors   = CardDefaults.cardColors(containerColor = Card),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(st.tariffLabel, color = Muted, fontSize = 11.sp,
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
                            containerColor = if (isSel) Gold else Color(0xFF0A0C10)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (isAutoActive) "⚡ ${t.name}" else t.name,
                            color      = if (isSel) Dark else Muted,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            fontSize   = 13.sp
                        )
                    }
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${selected.name}:  start ${selected.startFee}  •  " +
                    "${selected.pricePerKm}/km  •  ${selected.pricePerMinute}/min",
                    color = Muted, fontSize = 10.sp
                )
            }
        }
    }
}

// ── Shift banner ─────────────────────────────────────────────
@Composable
fun ShiftBanner(activeShift: Shift?, onStart: () -> Unit, onEnd: () -> Unit) {
    val st = LocalStrings.current
    if (activeShift == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.12f)),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(st.shiftNoneTitle, color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(st.shiftNoneWarning, color = Muted,
                    fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 17.sp)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Dark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.shiftStartBtn, color = Dark, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        var elapsed by remember { mutableLongStateOf(0L) }
        LaunchedEffect(activeShift.startTime) {
            while (true) {
                elapsed = (System.currentTimeMillis() - activeShift.startTime) / 1000L
                delay(1000L)
            }
        }
        val h   = elapsed / 3600
        val m   = (elapsed % 3600) / 60
        val dur = if (h > 0) "${h}ч ${m}мин" else "${m}мин"

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Green.copy(alpha = 0.1f)),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(Green, RoundedCornerShape(5.dp)))
                    Column {
                        Text(st.shiftActiveLabel.format(activeShift.shiftNumber),
                            color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(dur, color = Muted, fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = onEnd,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = 0.6f)),
                    shape   = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Stop, null, tint = Red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(st.shiftEndBtn, color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
