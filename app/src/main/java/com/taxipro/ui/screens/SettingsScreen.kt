package com.taxipro.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.taxipro.BuildConfig
import com.taxipro.ui.theme.AppStrings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.TrackingViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SettingsRepository, vm: TrackingViewModel, onNavigate: (String) -> Unit = {}) {
    val tc            = LocalThemeColors.current
    val savedSettings by repo.settings.collectAsState(initial = null)
    val tariffs       by vm.tariffs.collectAsState()
    val scope         = rememberCoroutineScope()
    val st            = LocalStrings.current
    val adActions     = LocalAdActions.current
    val adCredits     = LocalAdCreditsState.current
    val context       = androidx.compose.ui.platform.LocalContext.current
    val activity      = context.findActivity()

    var draft            by remember { mutableStateOf(AppSettings()) }
    var draftInitialized by remember { mutableStateOf(false) }
    var editingTariff    by remember { mutableStateOf<Tariff?>(null) }

    var pendingDistUnit      by remember { mutableStateOf<DistanceUnit?>(null) }
    var showDistConvertDialog by remember { mutableStateOf(false) }
    var isDistConverting     by remember { mutableStateOf(false) }

    LaunchedEffect(savedSettings) {
        if (savedSettings != null && !draftInitialized) {
            draft = savedSettings!!
            draftInitialized = true
        }
    }

    if (!draftInitialized) {
        Box(Modifier.fillMaxSize().background(tc.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = tc.accent)
        }
        return
    }

    val hasChanges = savedSettings != null && draft != savedSettings!!

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(st.settingsTitle, color = tc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

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
                    label = { Text(st.languageSection, color = tc.muted, fontSize = 12.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = tc.accent,
                        unfocusedBorderColor = tc.surface,
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                        focusedTrailingIconColor   = tc.accent,
                        unfocusedTrailingIconColor = tc.muted,
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(tc.cardAlt)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val isWorking = isImplementedLanguage(lang)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        lang.displayName,
                                        color = if (isWorking) tc.textPrimary else tc.muted,
                                        fontSize = 14.sp
                                    )
                                    if (!isWorking) {
                                        Text(st.languageComingSoon, color = tc.muted, fontSize = 10.sp)
                                    }
                                    if (draft.language == lang) {
                                        Icon(Icons.Default.Check, null, tint = tc.accent,
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
                            modifier = Modifier.background(tc.cardAlt)
                        )
                    }
                }
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
                        if (unit != draft.distanceUnit) {
                            draft = draft.copy(distanceUnit = unit)
                            // Only offer conversion if there's a saved setting to convert from
                            if (savedSettings != null && unit != savedSettings!!.distanceUnit) {
                                pendingDistUnit       = unit
                                isDistConverting      = false
                                showDistConvertDialog = true
                            }
                        }
                    }
                }
            }
        }

        // ── Custom tariffs ────────────────────────────────────
        SectionCard(st.tariffsSection) {
            if (tariffs.isEmpty()) {
                Text(st.noTariffs, color = tc.muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
            } else {
                tariffs.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = tc.textPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                if (t.autoEnabled)
                                    "${st.autoLabel}: ${formatAutoTime(t.autoStartMinuteOfDay())} - ${formatAutoTime(t.autoEndMinuteOfDay())}"
                                else st.manualLabel,
                                color = tc.muted, fontSize = 11.sp
                            )
                        }
                        Row {
                            IconButton(onClick = { editingTariff = t }) {
                                Icon(Icons.Default.Edit, null, tint = tc.accent)
                            }
                            IconButton(onClick = { vm.deleteTariff(t) }) {
                                Icon(Icons.Default.Delete, null, tint = tc.red)
                            }
                        }
                    }
                    HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = { editingTariff = Tariff() },
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(1.dp, tc.accent),
                shape    = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = tc.accent)
                Spacer(Modifier.width(6.dp))
                Text(st.addTariff, color = tc.accent, fontWeight = FontWeight.Bold)
            }
        }

        if (BuildConfig.DEBUG) {
            // ── Ads debug ────────────────────────────────────────────────────
            SectionCard("Ads debug") {
                Text(
                    "Remote Config: ${if (adCredits.config.firebaseReady) "connected" else "local defaults"}",
                    color = tc.muted,
                    fontSize = 12.sp,
                )
                Text(
                    "Ad IDs: ${if (adCredits.config.useTestAdIds) "test" else "real"} • Credits reward: ${adCredits.config.rewardedCredits}",
                    color = tc.muted,
                    fontSize = 12.sp,
                )
                Text(
                    "Limits: ${adCredits.config.dailyFreeRides} rides/day, ${adCredits.config.dailyFreeCalculator} calculator/day, ${adCredits.config.freeZoneLimit}+${adCredits.config.maxCreditZoneSlots} zones",
                    color = tc.muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            activity?.let {
                                adActions.openAdInspector(it) { error ->
                                    if (error != null) {
                                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = activity != null,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, tc.accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Ad Inspector", color = tc.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = adActions.refreshAdsConfig,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, tc.accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Refresh config", color = tc.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Advanced Settings (nav) ───────────────────────────
        Card(
            onClick   = { onNavigate("advanced_settings") },
            colors    = CardDefaults.cardColors(containerColor = tc.card),
            shape     = RoundedCornerShape(14.dp),
            modifier  = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(st.advancedSection, color = tc.textPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.advancedSectionSub, color = tc.muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = tc.muted)
            }
        }

        if (hasChanges) {
            Button(
                onClick  = { scope.launch { repo.saveAll(draft) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Text(st.saveChanges, color = tc.background, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // ── Distance unit conversion dialog ───────────────────────
    if (showDistConvertDialog && pendingDistUnit != null) {
        val newUnit = pendingDistUnit!!
        AlertDialog(
            onDismissRequest = { if (!isDistConverting) showDistConvertDialog = false },
            containerColor   = tc.card,
            title = {
                Text(st.distUnitChange.convertTitle,
                    color = tc.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                if (isDistConverting) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp),
                            color = tc.accent, strokeWidth = 2.dp)
                        Text(st.distUnitChange.convertingMsg, color = tc.muted, fontSize = 13.sp)
                    }
                } else {
                    Text(st.distUnitChange.convertBody, color = tc.muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                if (!isDistConverting) {
                    TextButton(onClick = {
                        isDistConverting = true
                        val factor = if (newUnit == DistanceUnit.MILES) 0.621371 else 1.60934
                        scope.launch {
                            repo.saveAll(draft)
                            vm.convertAllDistances(factor) {
                                isDistConverting      = false
                                showDistConvertDialog = false
                            }
                        }
                    }) {
                        Text(st.distUnitChange.convertBtn, color = tc.accent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isDistConverting) {
                    TextButton(onClick = {
                        scope.launch { repo.saveAll(draft) }
                        showDistConvertDialog = false
                    }) {
                        Text(st.distUnitChange.skipBtn, color = tc.muted)
                    }
                }
            },
        )
    }

    // ── Диалог за създаване / редактиране на тарифа ───────────
    if (editingTariff != null) {
        val allExpenses by vm.allExpenses.collectAsState()
        val tariffExpenses = allExpenses.filter { it.tariffId == editingTariff!!.id }
        TariffEditDialog(
            tariff           = editingTariff!!,
            currency         = draft.currency,
            distanceUnit     = draft.distanceUnit,
            existingTariffs  = tariffs,
            initialExpenses  = tariffExpenses,
            onSave           = { t, expenses ->
                vm.saveTariffWithExpenses(t, expenses)
                editingTariff = null
            },
            onDismiss        = { editingTariff = null }
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
    existingTariffs: List<Tariff> = emptyList(),
    initialExpenses: List<TariffExpense> = emptyList(),
    onSave: (Tariff, List<TariffExpense>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc   = LocalThemeColors.current
    val st   = LocalStrings.current
    val sym  = currency.symbol
    val unit = distanceUnit.shortLabel

    var name           by remember { mutableStateOf(tariff.name) }
    var startFee       by remember { mutableStateOf(tariff.startFee) }
    var pricePerKm     by remember { mutableStateOf(tariff.pricePerKm) }
    var pricePerMin    by remember { mutableStateOf(tariff.pricePerMinute) }
    var hourlyRate     by remember { mutableStateOf(tariff.hourlyRate) }
    var waitThreshold  by remember { mutableStateOf(tariff.waitThresholdKmh) }
    var taxPercent     by remember { mutableStateOf(tariff.taxPercent) }
    var fuelCostPerKm  by remember { mutableStateOf(tariff.fuelCostPerKm) }
    var autoEnabled    by remember { mutableStateOf(tariff.autoEnabled) }
    var autoStart      by remember { mutableIntStateOf(tariff.autoStartMinuteOfDay()) }
    var autoEnd        by remember { mutableIntStateOf(tariff.autoEndMinuteOfDay()) }
    var nameError      by remember { mutableStateOf(false) }
    var autoError      by remember { mutableStateOf<String?>(null) }

    // Custom expenses state
    var expenses       by remember { mutableStateOf(initialExpenses.toMutableList()) }
    var showAddExpense by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = tc.card,
        title = {
            Text(
                if (tariff.id == 0) st.newTariff else st.editTariffLabel,
                color = tc.textPrimary, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Ime
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it; nameError = false },
                    label         = { Text(st.tariffName, color = tc.muted) },
                    singleLine    = true,
                    isError       = nameError,
                    supportingText = if (nameError) {{ Text(st.nameRequired, color = tc.red) }} else null,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = tc.accent,
                        unfocusedBorderColor = tc.surface,
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                    )
                )

                HorizontalDivider(color = tc.surface)
                Text(st.pricingLabel, color = tc.muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)

                TariffNumRow(st.startFeeLabel,  startFee,      sym)          { startFee     = it }
                TariffNumRow("Per $unit",        pricePerKm,    "$sym/$unit") { pricePerKm   = it }
                TariffNumRow(st.perMinWait,      pricePerMin,   "$sym/min")   { pricePerMin  = it }
                TariffNumRow(st.hourlyRateLabel, hourlyRate,    "$sym/h")     { hourlyRate   = it }
                TariffNumRow(st.waitThreshold,   waitThreshold, "$unit/h")    { waitThreshold = it }

                HorizontalDivider(color = tc.surface)
                Text(st.costsLabel, color = tc.muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)

                TariffNumRow(st.taxInsurance,        taxPercent,    "%")          { taxPercent   = it }
                TariffNumRow(st.fuelPer + " $unit",  fuelCostPerKm, "$sym/$unit") { fuelCostPerKm = it }

                // ── Custom expenses ──────────────────────────
                HorizontalDivider(color = tc.surface)
                Text(st.customExpensesLabel, color = tc.muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)

                if (expenses.isEmpty()) {
                    Text(st.noExpenses, color = tc.muted, fontSize = 12.sp)
                } else {
                    expenses.forEachIndexed { idx, exp ->
                        val freqLabel = when (exp.freq) {
                            ExpenseFrequency.PER_DAY   -> "На ден"
                            ExpenseFrequency.PER_WEEK  -> "На седмица"
                            ExpenseFrequency.PER_RIDE  -> st.freqPerRide
                            ExpenseFrequency.PER_SHIFT -> st.freqPerShift
                            ExpenseFrequency.PER_MONTH -> st.freqPerMonth
                        }
                        val amtLabel = if (exp.expType == ExpenseType.PERCENT)
                            "%.2f%%".format(exp.amount)
                        else "$sym%.2f".format(exp.amount)

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(exp.name, color = tc.textPrimary, fontSize = 13.sp)
                                Text("$freqLabel · $amtLabel", color = tc.muted, fontSize = 11.sp)
                            }
                            IconButton(onClick = {
                                expenses = expenses.toMutableList().also { it.removeAt(idx) }
                            }) {
                                Icon(Icons.Default.Delete, null, tint = tc.red, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (idx < expenses.lastIndex)
                            HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                    }
                }

                OutlinedButton(
                    onClick  = { showAddExpense = true },
                    modifier = Modifier.fillMaxWidth(),
                    border   = BorderStroke(1.dp, tc.accent.copy(alpha = 0.6f)),
                    shape    = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text(st.addExpense, color = tc.accent, fontSize = 13.sp)
                }

                HorizontalDivider(color = tc.surface)

                // Auto-activate toggle
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text(st.autoActivate, color = tc.textPrimary, fontSize = 14.sp)
                        Text(st.autoActivateSub, color = tc.muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked         = autoEnabled,
                        onCheckedChange = { autoEnabled = it },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor   = tc.background,
                            checkedTrackColor   = tc.accent,
                            uncheckedThumbColor = tc.muted,
                            uncheckedTrackColor = tc.surface,
                        )
                    )
                }

                if (autoEnabled) {
                    autoError?.let {
                        Text(it, color = tc.red, fontSize = 11.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        TimePicker(st.fromLabel, autoStart, Modifier.weight(1f)) {
                            autoStart = it
                            autoError = null
                        }
                        Text("→", color = tc.muted, fontSize = 16.sp)
                        TimePicker(st.toLabel,   autoEnd,   Modifier.weight(1f)) {
                            autoEnd = it
                            autoError = null
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    if (autoEnabled) {
                        validateAutoWindow(tariff.id, autoStart, autoEnd, existingTariffs)?.let {
                            autoError = it
                            return@Button
                        }
                    }
                    onSave(
                        tariff.copy(
                            name             = name.trim(),
                            startFee         = startFee,
                            pricePerKm       = pricePerKm,
                            pricePerMinute   = pricePerMin,
                            hourlyRate       = hourlyRate,
                            waitThresholdKmh = waitThreshold,
                            taxPercent       = taxPercent,
                            fuelCostPerKm    = fuelCostPerKm,
                            autoEnabled      = autoEnabled,
                            autoStartHour    = autoStart / 60,
                            autoEndHour      = autoEnd / 60,
                            autoStartMinute  = autoStart % 60,
                            autoEndMinute    = autoEnd % 60,
                        ),
                        expenses
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = tc.accent)
            ) { Text(st.saveLabel, color = tc.background, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(st.cancelBtn, color = tc.muted) }
        }
    )

    // ── Add Expense Sub-Dialog ────────────────────────────────
    if (showAddExpense) {
        ExpenseEditDialog(
            currency = currency,
            onSave   = { newExp ->
                expenses = (expenses + newExp).toMutableList()
                showAddExpense = false
            },
            onDismiss = { showAddExpense = false }
        )
    }
}

// ── Expense Edit Dialog ─────────────────────────────────────────

@Composable
fun ExpenseEditDialog(
    currency: Currency,
    onSave: (TariffExpense) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc  = LocalThemeColors.current
    val st  = LocalStrings.current
    val sym = currency.symbol

    var name      by remember { mutableStateOf("") }
    var freq      by remember { mutableStateOf(ExpenseFrequency.PER_MONTH) }
    var type      by remember { mutableStateOf(ExpenseType.FIXED) }
    var amount    by remember { mutableStateOf(0.0) }
    var monthlyMode by remember { mutableStateOf(MonthlyExpenseMode.AUTO) }
    var manualWorkDays by remember { mutableIntStateOf(20) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = tc.card,
        title = {
            Text(st.newExpenseTitle, color = tc.textPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Name
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it; nameError = false },
                    label         = { Text(st.expenseNameLabel, color = tc.muted) },
                    singleLine    = true,
                    isError       = nameError,
                    supportingText = if (nameError) {{ Text(st.nameRequired, color = tc.red) }} else null,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = tc.accent,
                        unfocusedBorderColor = tc.surface,
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                    )
                )

                // Frequency — column of selectable rows (avoids truncation in narrow dialog)
                Text(st.expenseFreqLabel, color = tc.muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(tc.cardAlt, RoundedCornerShape(8.dp))
                ) {
                    listOf(
                        ExpenseFrequency.PER_DAY to "Per day",
                        ExpenseFrequency.PER_WEEK to "Per week",
                        ExpenseFrequency.PER_RIDE  to st.freqPerRide,
                        ExpenseFrequency.PER_SHIFT to st.freqPerShift,
                        ExpenseFrequency.PER_MONTH to st.freqPerMonth,
                    ).forEachIndexed { idx, (f, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { freq = f }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(label, color = if (freq == f) tc.accent else tc.textPrimary, fontSize = 13.sp)
                            if (freq == f)
                                Icon(Icons.Default.Check, null, tint = tc.accent, modifier = Modifier.size(16.dp))
                        }
                        if (idx < 4) HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                    }
                }

                if (freq == ExpenseFrequency.PER_MONTH) {
                    Text(
                        "Monthly distribution",
                        color = tc.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(tc.cardAlt, RoundedCornerShape(8.dp))
                    ) {
                        listOf(
                            MonthlyExpenseMode.AUTO to "Automatic (calendar days)",
                            MonthlyExpenseMode.MANUAL to "Manual (work days)",
                        ).forEachIndexed { idx, (mode, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { monthlyMode = mode }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, color = if (monthlyMode == mode) tc.accent else tc.textPrimary, fontSize = 13.sp)
                                if (monthlyMode == mode) {
                                    Icon(Icons.Default.Check, null, tint = tc.accent, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (idx == 0) HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                        }
                    }
                    if (monthlyMode == MonthlyExpenseMode.AUTO) {
                        Text(
                            "Automatic mode divides the monthly amount by the calendar days in the current month. Example: 190/month is about 6.1 per day in a 31-day month.",
                            color = tc.muted,
                            fontSize = 11.sp,
                        )
                    } else {
                        TariffNumRow("Work days / month", manualWorkDays.toDouble(), "days") {
                            manualWorkDays = it.toInt().coerceAtLeast(1)
                        }
                    }
                }

                // Type — two compact chips, labels are short so a Row is fine
                Text(st.expenseTypeLabel, color = tc.muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ExpenseType.FIXED   to st.typeFixed,
                        ExpenseType.PERCENT to st.typePercent,
                    ).forEach { (t2, label) ->
                        ToggleButton(label, type == t2, Modifier.weight(1f)) { type = t2 }
                    }
                }

                // Amount
                val suffix = if (type == ExpenseType.PERCENT) "%" else sym
                val amountLabel = if (type == ExpenseType.PERCENT) st.typePercent else st.typeFixed
                TariffNumRow(amountLabel, amount, suffix) { amount = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    onSave(TariffExpense(
                        name      = name.trim(),
                        frequency = freq.code,
                        type      = type.code,
                        amount    = amount,
                        monthlyMode = monthlyMode.code,
                        manualWorkDays = manualWorkDays,
                    ))
                },
                colors = ButtonDefaults.buttonColors(containerColor = tc.accent)
            ) { Text(st.saveLabel, color = tc.background, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(st.cancelBtn, color = tc.muted) }
        }
    )
}

// ── TariffNumRow ────────────────────────────────────────────────

private fun Double.toFieldText() = if (this == 0.0) "" else "%.2f".format(this)

@Composable
fun TariffNumRow(label: String, value: Double, suffix: String, onChange: (Double) -> Unit) {
    val tc        = LocalThemeColors.current
    var text      by remember { mutableStateOf(value.toFieldText()) }
    var isFocused by remember { mutableStateOf(false) }
    val focusMgr  = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(value) { if (!isFocused) text = value.toFieldText() }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = tc.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value         = text,
                onValueChange = { v ->
                    text = v
                    onChange(v.toDoubleOrNull() ?: 0.0)
                },
                modifier      = Modifier.width(88.dp).onFocusChanged { fs ->
                    if (isFocused && !fs.isFocused) {
                        val parsed = text.toDoubleOrNull() ?: 0.0
                        onChange(parsed)
                        text = parsed.toFieldText()
                    }
                    isFocused = fs.isFocused
                },
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        val parsed = text.toDoubleOrNull() ?: 0.0
                        onChange(parsed)
                        text = parsed.toFieldText()
                        focusMgr.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = tc.accent,
                    unfocusedBorderColor = tc.surface,
                    focusedTextColor     = tc.textPrimary,
                    unfocusedTextColor   = tc.textPrimary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Text(suffix, color = tc.muted, fontSize = 11.sp)
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val tc = LocalThemeColors.current
    Card(
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(title, color = tc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun ToggleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tc = LocalThemeColors.current
    Button(
        onClick  = onClick,
        modifier = modifier.height(42.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (selected) tc.accent else tc.background
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label,
            color      = if (selected) tc.background else tc.muted,
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
    val tc        = LocalThemeColors.current
    var text      by remember { mutableStateOf(value.toFieldText()) }
    var isFocused by remember { mutableStateOf(false) }
    val focusMgr  = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(value) { if (!isFocused) text = value.toFieldText() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = tc.textPrimary, fontSize = 13.sp)
            if (hint.isNotEmpty()) Text(hint, color = tc.muted, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value         = text,
                onValueChange = { v ->
                    text = v
                    onChange(v.toDoubleOrNull() ?: 0.0)
                },
                modifier      = Modifier.width(88.dp).onFocusChanged { fs ->
                    if (isFocused && !fs.isFocused) {
                        val parsed = text.toDoubleOrNull() ?: 0.0
                        onChange(parsed)
                        text = parsed.toFieldText()
                    }
                    isFocused = fs.isFocused
                },
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType  = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction     = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        val parsed = text.toDoubleOrNull() ?: 0.0
                        onChange(parsed)
                        text = parsed.toFieldText()
                        focusMgr.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = tc.accent,
                    unfocusedBorderColor = tc.surface,
                    focusedTextColor     = tc.textPrimary,
                    unfocusedTextColor   = tc.textPrimary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Text(suffix, color = tc.muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
        }
    }
    HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
}

@Composable
fun SettingsNumField(
    label: String, value: Double, suffix: String,
    modifier: Modifier = Modifier, onChange: (Double) -> Unit
) {
    val tc        = LocalThemeColors.current
    fun Double.toRateText() = if (this == 0.0) "" else "%.4f".format(this)

    var text      by remember { mutableStateOf(value.toRateText()) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) { if (!isFocused) text = value.toRateText() }

    Column(modifier) {
        Text("$label  $suffix", color = tc.muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = text,
            onValueChange = { v ->
                text = v
                onChange(v.toDoubleOrNull() ?: 0.0)
            },
            modifier      = Modifier.fillMaxWidth().onFocusChanged { fs ->
                if (isFocused && !fs.isFocused) {
                    val parsed = text.toDoubleOrNull() ?: 0.0
                    onChange(parsed)
                    text = parsed.toRateText()
                }
                isFocused = fs.isFocused
            },
            singleLine    = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    val parsed = text.toDoubleOrNull() ?: 0.0
                    onChange(parsed)
                    text = parsed.toRateText()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = tc.accent,
                unfocusedBorderColor = tc.surface,
                focusedTextColor     = tc.textPrimary,
                unfocusedTextColor   = tc.textPrimary,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}

@Composable
fun TimePicker(label: String, minuteOfDay: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val tc        = LocalThemeColors.current
    var text      by remember { mutableStateOf(formatAutoTime(minuteOfDay)) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(minuteOfDay) { if (!isFocused) text = formatAutoTime(minuteOfDay) }

    Column(modifier) {
        Text(label, color = tc.muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == ':' }.take(5)
            },
            modifier      = Modifier.fillMaxWidth().onFocusChanged { fs ->
                if (isFocused && !fs.isFocused) {
                    parseTimeOfDay(text)?.let {
                        onChange(it)
                        text = formatAutoTime(it)
                    }
                }
                isFocused = fs.isFocused
            },
            singleLine    = true,
            placeholder   = { Text("HH:MM", color = tc.muted, fontSize = 12.sp) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                imeAction    = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    parseTimeOfDay(text)?.let {
                        onChange(it)
                        text = formatAutoTime(it)
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = tc.accent,
                unfocusedBorderColor = tc.surface,
                focusedTextColor     = tc.textPrimary,
                unfocusedTextColor   = tc.textPrimary,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}

private fun parseTimeOfDay(value: String): Int? {
    val text = value.trim()
    if (text.isEmpty()) return null

    val parts = text.split(':')
    val (hour, minute) = when {
        parts.size == 2 -> {
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            h to m
        }
        parts.size == 1 && text.all { it.isDigit() } -> {
            when (text.length) {
                1, 2 -> (text.toIntOrNull() ?: return null) to 0
                3 -> (text.take(1).toIntOrNull() ?: return null) to (text.takeLast(2).toIntOrNull() ?: return null)
                4 -> (text.take(2).toIntOrNull() ?: return null) to (text.takeLast(2).toIntOrNull() ?: return null)
                else -> return null
            }
        }
        else -> return null
    }

    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun validateAutoWindow(
    currentTariffId: Int,
    start: Int,
    end: Int,
    existingTariffs: List<Tariff>,
): String? {
    if (start == end) return "Началният и крайният час трябва да са различни."

    val conflicting = existingTariffs
        .filter { it.autoEnabled && it.id != currentTariffId }
        .firstOrNull { other ->
            val otherStart = other.autoStartMinuteOfDay()
            val otherEnd = other.autoEndMinuteOfDay()
            start == otherStart || start == otherEnd || end == otherStart || end == otherEnd ||
                timeWindowsOverlapInclusive(start, end, otherStart, otherEnd)
        }

    return conflicting?.let {
        "Автоактивирането се препокрива с \"${it.name}\" (${formatAutoTime(it.autoStartMinuteOfDay())} - ${formatAutoTime(it.autoEndMinuteOfDay())})."
    }
}

private fun timeWindowsOverlapInclusive(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
    val rangesA = splitTimeWindow(startA, endA)
    val rangesB = splitTimeWindow(startB, endB)
    return rangesA.any { a -> rangesB.any { b -> a.first <= b.last && b.first <= a.last } }
}

private fun splitTimeWindow(start: Int, end: Int): List<IntRange> =
    if (start <= end) listOf(start..end)
    else listOf(start until 24 * 60, 0..end)
