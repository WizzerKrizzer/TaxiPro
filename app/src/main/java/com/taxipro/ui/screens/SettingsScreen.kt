package com.taxipro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.taxipro.data.db.*
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.TrackingViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SettingsRepository, vm: TrackingViewModel) {
    val savedSettings by repo.settings.collectAsState(initial = null)
    val tariffs       by vm.tariffs.collectAsState()
    val scope         = rememberCoroutineScope()
    val st            = LocalStrings.current

    var draft            by remember { mutableStateOf(AppSettings()) }
    var draftInitialized by remember { mutableStateOf(false) }
    var editingTariff    by remember { mutableStateOf<Tariff?>(null) }

    LaunchedEffect(savedSettings) {
        if (savedSettings != null && !draftInitialized) {
            draft = savedSettings!!
            draftInitialized = true
        }
    }

    if (!draftInitialized) {
        Box(Modifier.fillMaxSize().background(Dark), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Gold)
        }
        return
    }

    val hasChanges = savedSettings != null && draft != savedSettings!!

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(st.settingsTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ── Language ──────────────────────────────────────────
        SectionCard(st.languageSection) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = draft.language.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(st.languageSection, color = Muted, fontSize = 12.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Gold,
                        unfocusedBorderColor = Color(0xFF1E2430),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        focusedTrailingIconColor   = Gold,
                        unfocusedTrailingIconColor = Muted,
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color(0xFF1A1E28))
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val isWorking = lang == AppLanguage.EN || lang == AppLanguage.BG
                        DropdownMenuItem(
                            text = {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        lang.displayName,
                                        color = if (isWorking) Color.White else Muted,
                                        fontSize = 14.sp
                                    )
                                    if (!isWorking) {
                                        Text(st.languageComingSoon, color = Muted, fontSize = 10.sp)
                                    }
                                    if (draft.language == lang) {
                                        Icon(Icons.Default.Check, null, tint = Gold,
                                            modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                if (isWorking) {
                                    draft = draft.copy(language = lang)
                                    scope.launch { repo.saveAll(draft.copy(language = lang)) }
                                }
                                expanded = false
                            },
                            modifier = Modifier.background(Color(0xFF1A1E28))
                        )
                    }
                }
            }
        }

        // ── Currency ──────────────────────────────────────────
        SectionCard(st.currencySection) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { cur ->
                    ToggleButton("${cur.symbol} ${cur.code}", draft.currency == cur, Modifier.weight(1f)) {
                        draft = draft.copy(currency = cur)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(st.exchangeRatesNote,
                color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsNumField(
                    label    = "1 EUR =",
                    value    = draft.usdRate,
                    suffix   = "USD",
                    modifier = Modifier.weight(1f),
                    onChange = { draft = draft.copy(usdRate = it) }
                )
                SettingsNumField(
                    label    = "1 EUR =",
                    value    = draft.gbpRate,
                    suffix   = "GBP",
                    modifier = Modifier.weight(1f),
                    onChange = { draft = draft.copy(gbpRate = it) }
                )
            }
        }

        // ── Distance unit ─────────────────────────────────────
        SectionCard(st.distanceSection) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DistanceUnit.entries.forEach { unit ->
                    ToggleButton(
                        if (unit == DistanceUnit.KM) st.kilometres else st.miles,
                        draft.distanceUnit == unit,
                        Modifier.weight(1f)
                    ) {
                        draft = draft.copy(distanceUnit = unit)
                    }
                }
            }
        }

        // ── Custom tariffs ────────────────────────────────────
        SectionCard(st.tariffsSection) {
            if (tariffs.isEmpty()) {
                Text(st.noTariffs, color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
            } else {
                tariffs.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                if (t.autoEnabled)
                                    "${st.autoLabel}: %02d:00 – %02d:00".format(t.autoStartHour, t.autoEndHour)
                                else st.manualLabel,
                                color = Muted, fontSize = 11.sp
                            )
                        }
                        Row {
                            IconButton(onClick = { editingTariff = t }) {
                                Icon(Icons.Default.Edit, null, tint = Gold)
                            }
                            IconButton(onClick = { vm.deleteTariff(t) }) {
                                Icon(Icons.Default.Delete, null, tint = Red)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = { editingTariff = Tariff() },
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, Gold),
                shape    = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Gold)
                Spacer(Modifier.width(6.dp))
                Text(st.addTariff, color = Gold, fontWeight = FontWeight.Bold)
            }
        }

        // ── Expenses ──────────────────────────────────────────
        SectionCard(st.expensesSection) {
            SettingsRow2(st.taxInsurance, draft.taxPercent, "%") {
                draft = draft.copy(taxPercent = it)
            }
            SettingsRow2("${st.fuelPer} ${draft.distanceUnit.shortLabel}", draft.fuelCostPerKm,
                "${draft.currency.symbol}/${draft.distanceUnit.shortLabel}") {
                draft = draft.copy(fuelCostPerKm = it)
            }
        }

        // ── GPS ───────────────────────────────────────────────
        SectionCard(st.gpsSection) {
            SettingsRow2(st.waitThreshold, draft.waitSpeedThresholdKmh,
                "${draft.distanceUnit.shortLabel}/h",
                hint = st.waitThresholdHint) {
                draft = draft.copy(waitSpeedThresholdKmh = it)
            }
            SettingsRow2(st.updateInterval, draft.gpsIntervalMs.toDouble(), "ms",
                hint = st.updateIntervalHint) {
                draft = draft.copy(gpsIntervalMs = it.toLong().coerceAtLeast(1000L))
            }
        }

        // ── Profit preview ────────────────────────────────────
        val sym  = draft.currency.symbol
        val rev  = 100.0
        val fuel = 80 * draft.fuelCostPerKm
        val tax  = rev * draft.taxPercent / 100
        SectionCard("📊  At $sym%.0f revenue (~80 ${draft.distanceUnit.shortLabel})".format(rev)) {
            listOf(
                Triple("Gross revenue",   "+$sym%.2f".format(rev),             Color.White),
                Triple("Fuel",            "-$sym%.2f".format(fuel),            Red),
                Triple("Tax & insurance", "-$sym%.2f".format(tax),             Red),
                Triple("Net profit",      "+$sym%.2f".format(rev - fuel - tax), Green),
            ).forEach { (k, v, c) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = Muted, fontSize = 13.sp)
                    Text(v, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
            }
        }

        // ── Save Changes ──────────────────────────────────────
        if (hasChanges) {
            Button(
                onClick  = { scope.launch { repo.saveAll(draft) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Text(st.saveChanges, color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // ── Диалог за създаване / редактиране на тарифа ───────────
    if (editingTariff != null) {
        TariffEditDialog(
            tariff       = editingTariff!!,
            currency     = draft.currency,
            distanceUnit = draft.distanceUnit,
            onSave       = { vm.saveTariff(it); editingTariff = null },
            onDismiss    = { editingTariff = null }
        )
    }
}

// ── Tariff Edit Dialog ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffEditDialog(
    tariff: Tariff,
    currency: Currency,
    distanceUnit: DistanceUnit,
    onSave: (Tariff) -> Unit,
    onDismiss: () -> Unit,
) {
    val st   = LocalStrings.current
    val sym  = currency.symbol
    val unit = distanceUnit.shortLabel

    var name        by remember { mutableStateOf(tariff.name) }
    var startFee    by remember { mutableStateOf(tariff.startFee) }
    var pricePerKm  by remember { mutableStateOf(tariff.pricePerKm) }
    var pricePerMin by remember { mutableStateOf(tariff.pricePerMinute) }
    var hourlyRate  by remember { mutableStateOf(tariff.hourlyRate) }
    var autoEnabled by remember { mutableStateOf(tariff.autoEnabled) }
    var autoStart   by remember { mutableStateOf(tariff.autoStartHour) }
    var autoEnd     by remember { mutableStateOf(tariff.autoEndHour) }
    var nameError   by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF161A22),
        title = {
            Text(
                if (tariff.id == 0) st.newTariff else st.editTariffLabel,
                color = Color.White, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Име
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it; nameError = false },
                    label         = { Text(st.tariffName, color = Muted) },
                    singleLine    = true,
                    isError       = nameError,
                    supportingText = if (nameError) {{ Text(st.nameRequired, color = Red) }} else null,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Gold,
                        unfocusedBorderColor = Color(0xFF1E2430),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                    )
                )

                HorizontalDivider(color = Color(0xFF1E2430))
                Text(st.pricingLabel, color = Muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)

                TariffNumRow(st.startFeeLabel, startFee,   sym)        { startFee    = it }
                TariffNumRow("Per $unit",      pricePerKm, "$sym/$unit") { pricePerKm  = it }
                TariffNumRow(st.perMinWait,    pricePerMin,"$sym/min") { pricePerMin = it }
                TariffNumRow(st.hourlyRateLabel, hourlyRate, "$sym/h")  { hourlyRate  = it }

                HorizontalDivider(color = Color(0xFF1E2430))

                // Auto-activate toggle
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text(st.autoActivate, color = Color.White, fontSize = 14.sp)
                        Text(st.autoActivateSub, color = Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked         = autoEnabled,
                        onCheckedChange = { autoEnabled = it },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor   = Dark,
                            checkedTrackColor   = Gold,
                            uncheckedThumbColor = Muted,
                            uncheckedTrackColor = Color(0xFF1E2430),
                        )
                    )
                }

                if (autoEnabled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        HourPicker(st.fromLabel, autoStart, Modifier.weight(1f)) { autoStart = it }
                        Text("→", color = Muted, fontSize = 16.sp)
                        HourPicker(st.toLabel,   autoEnd,   Modifier.weight(1f)) { autoEnd   = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    onSave(tariff.copy(
                        name           = name.trim(),
                        startFee       = startFee,
                        pricePerKm     = pricePerKm,
                        pricePerMinute = pricePerMin,
                        hourlyRate     = hourlyRate,
                        autoEnabled    = autoEnabled,
                        autoStartHour  = autoStart,
                        autoEndHour    = autoEnd,
                    ))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gold)
            ) { Text(st.saveLabel, color = Dark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(st.cancelBtn, color = Muted) }
        }
    )
}

// ── TariffNumRow ────────────────────────────────────────────────

@Composable
fun TariffNumRow(label: String, value: Double, suffix: String, onChange: (Double) -> Unit) {
    var text      by remember { mutableStateOf("%.2f".format(value)) }
    var isFocused by remember { mutableStateOf(false) }
    val focusMgr  = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(value) { if (!isFocused) text = "%.2f".format(value) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                modifier      = Modifier.width(88.dp).onFocusChanged { fs ->
                    if (isFocused && !fs.isFocused) text.toDoubleOrNull()?.let { onChange(it) }
                    isFocused = fs.isFocused
                },
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        text.toDoubleOrNull()?.let { onChange(it) }
                        focusMgr.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Gold,
                    unfocusedBorderColor = Color(0xFF1E2430),
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Text(suffix, color = Muted, fontSize = 11.sp)
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF161A22)),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun ToggleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(42.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (selected) Gold else Color(0xFF0A0C10)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label,
            color      = if (selected) Dark else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize   = 13.sp,
            maxLines   = 1
        )
    }
}

@Composable
fun SettingsRow2(
    label: String,
    value: Double,
    suffix: String,
    hint: String = "",
    onChange: (Double) -> Unit,
) {
    var text      by remember { mutableStateOf("%.2f".format(value)) }
    var isFocused by remember { mutableStateOf(false) }
    val focusMgr  = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(value) { if (!isFocused) text = "%.2f".format(value) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = Color.White, fontSize = 13.sp)
            if (hint.isNotEmpty()) Text(hint, color = Muted, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                modifier      = Modifier.width(88.dp).onFocusChanged { fs ->
                    if (isFocused && !fs.isFocused) text.toDoubleOrNull()?.let { onChange(it) }
                    isFocused = fs.isFocused
                },
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType  = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction     = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        text.toDoubleOrNull()?.let { onChange(it) }
                        focusMgr.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Gold,
                    unfocusedBorderColor = Color(0xFF1E2430),
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Text(suffix, color = Muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
        }
    }
    HorizontalDivider(color = Color(0xFF1E2430), thickness = 0.5.dp)
}

@Composable
fun SettingsNumField(
    label: String, value: Double, suffix: String,
    modifier: Modifier = Modifier, onChange: (Double) -> Unit
) {
    var text      by remember { mutableStateOf("%.4f".format(value)) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) { if (!isFocused) text = "%.4f".format(value) }

    Column(modifier) {
        Text("$label  $suffix", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = text,
            onValueChange = { text = it },
            modifier      = Modifier.fillMaxWidth().onFocusChanged { fs ->
                if (isFocused && !fs.isFocused) text.toDoubleOrNull()?.let { onChange(it) }
                isFocused = fs.isFocused
            },
            singleLine    = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { text.toDoubleOrNull()?.let { onChange(it) } }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Gold,
                unfocusedBorderColor = Color(0xFF1E2430),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}

@Composable
fun HourPicker(label: String, hour: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text      by remember { mutableStateOf("%02d:00".format(hour)) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(hour) { if (!isFocused) text = "%02d:00".format(hour) }

    Column(modifier) {
        Text(label, color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = text,
            onValueChange = { text = it },
            modifier      = Modifier.fillMaxWidth().onFocusChanged { fs ->
                if (isFocused && !fs.isFocused) {
                    val h = text.replace(":00", "").trim().toIntOrNull()
                    if (h != null && h in 0..23) onChange(h)
                }
                isFocused = fs.isFocused
            },
            singleLine    = true,
            placeholder   = { Text("HH:00", color = Muted, fontSize = 12.sp) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    val h = text.replace(":00", "").trim().toIntOrNull()
                    if (h != null && h in 0..23) onChange(h)
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Gold,
                unfocusedBorderColor = Color(0xFF1E2430),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}
