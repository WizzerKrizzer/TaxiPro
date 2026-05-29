package com.taxipro.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.input.KeyboardType
import com.taxipro.data.db.Currency
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.data.db.AppSettings
import com.taxipro.data.db.AppTheme
import com.taxipro.data.db.SettingsRepository
import com.taxipro.ui.theme.AppStrings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.TrackingViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    repo: SettingsRepository,
    vm: TrackingViewModel,
    onBack: () -> Unit,
) {
    val tc            = LocalThemeColors.current
    val st            = LocalStrings.current
    val savedSettings by repo.settings.collectAsState(initial = null)
    val scope         = rememberCoroutineScope()

    var draft            by remember { mutableStateOf(AppSettings()) }
    var draftInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(savedSettings) {
        if (savedSettings != null && !draftInitialized) {
            draft = savedSettings!!
            draftInitialized = true
        }
    }

    val hasChanges = savedSettings != null && draft != savedSettings!!

    var showWarning       by remember { mutableStateOf(false) }
    var showHoldButton    by remember { mutableStateOf(false) }
    var cleared           by remember { mutableStateOf(false) }

    var showCurrencyPicker by remember { mutableStateOf(false) }
    var pendingCurrency    by remember { mutableStateOf<Currency?>(null) }
    var showConvertDialog  by remember { mutableStateOf(false) }
    var convertRateInput   by remember { mutableStateOf("") }
    var isConverting       by remember { mutableStateOf(false) }
    var convertDone        by remember { mutableStateOf(false) }
    var currencySearchQuery by remember { mutableStateOf("") }

    Scaffold(
        containerColor = tc.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(st.advancedSection, color = tc.textPrimary,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) scope.launch { repo.saveAll(draft) }
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tc.background)
            )
        }
    ) { padding ->
        if (!draftInitialized) {
            Box(
                Modifier.fillMaxSize().background(tc.background).padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = tc.accent) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tc.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Theme ────────────────────────────────────────
            SectionCard(st.themeLabel) {
                ThemePicker(
                    current  = draft.theme,
                    st       = st,
                    tc       = tc,
                    onSelect = { theme ->
                        draft = draft.copy(theme = theme)
                        scope.launch { repo.setTheme(theme) }
                    }
                )
            }

            // ── Currency ──────────────────────────────────────
            SectionCard(st.currencySection) {
                Text(st.currencyChange.autoDetectNote, color = tc.muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "${draft.currency.code}  (${draft.currency.symbol})",
                            color = tc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            currencySearchQuery = ""
                            showCurrencyPicker  = true
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, tc.accent),
                        shape  = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
                    ) {
                        Text(st.currencyChange.changeCurrencyBtn, fontSize = 13.sp)
                    }
                }
            }

            // ── Preferences ──────────────────────────────────
            SectionCard(st.prefs.preferencesSection) {
                // Helper so each pref row looks the same
                @Composable
                fun PrefSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(title, color = tc.textPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(sub, color = tc.muted, fontSize = 11.sp)
                        }
                        Switch(
                            checked         = checked,
                            onCheckedChange = onChange,
                            colors          = SwitchDefaults.colors(
                                checkedThumbColor   = tc.background,
                                checkedTrackColor   = tc.accent,
                                uncheckedThumbColor = tc.muted,
                                uncheckedTrackColor = tc.surface,
                            )
                        )
                    }
                }

                PrefSwitch(
                    title   = st.prefs.inferKmTitle,
                    sub     = st.prefs.inferKmSubtitle,
                    checked = draft.inferKmFromAdjustment,
                    onChange = { v ->
                        draft = draft.copy(inferKmFromAdjustment = v)
                        scope.launch { repo.setInferKmFromAdjustment(v) }
                    }
                )

                HorizontalDivider(
                    modifier  = Modifier.padding(vertical = 10.dp),
                    color     = tc.surface,
                    thickness = 0.5.dp,
                )

                PrefSwitch(
                    title    = st.prefs.blockOverlapTitle,
                    sub      = st.prefs.blockOverlapSub,
                    checked  = draft.blockZoneOverlapPoints,
                    onChange = { v ->
                        draft = draft.copy(blockZoneOverlapPoints = v)
                        scope.launch { repo.setBlockZoneOverlap(v) }
                    }
                )

                HorizontalDivider(
                    modifier  = Modifier.padding(vertical = 10.dp),
                    color     = tc.surface,
                    thickness = 0.5.dp,
                )

                PrefSwitch(
                    title    = st.prefs.blockDupNameTitle,
                    sub      = st.prefs.blockDupNameSub,
                    checked  = draft.blockDuplicateZoneName,
                    onChange = { v ->
                        draft = draft.copy(blockDuplicateZoneName = v)
                        scope.launch { repo.setBlockDuplicateName(v) }
                    }
                )

                HorizontalDivider(
                    modifier  = Modifier.padding(vertical = 10.dp),
                    color     = tc.surface,
                    thickness = 0.5.dp,
                )

                PrefSwitch(
                    title    = st.prefs.midnightShiftDayTitle,
                    sub      = st.prefs.midnightShiftDaySub,
                    checked  = draft.countMidnightRidesToShiftDay,
                    onChange = { v ->
                        draft = draft.copy(countMidnightRidesToShiftDay = v)
                        scope.launch { repo.setMidnightToShiftDay(v) }
                    }
                )

                HorizontalDivider(
                    modifier  = Modifier.padding(vertical = 10.dp),
                    color     = tc.surface,
                    thickness = 0.5.dp,
                )

                PrefSwitch(
                    title    = st.prefs.includeTipsTitle,
                    sub      = st.prefs.includeTipsSub,
                    checked  = draft.includeTipsInTotal,
                    onChange = { v ->
                        draft = draft.copy(includeTipsInTotal = v)
                        scope.launch { repo.setIncludeTips(v) }
                    }
                )
            }

            // ── Danger zone ───────────────────────────────────
            SectionCard(st.clearDataLabel) {
                Text(st.clearDataSub, color = tc.muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))

                if (cleared) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Check, null, tint = tc.green, modifier = Modifier.size(18.dp))
                        Text(st.dataCleared, color = tc.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else if (showHoldButton) {
                    HoldToDeleteButton(label = st.holdToDelete, tc = tc) {
                        scope.launch {
                            vm.clearAllData()
                            showHoldButton = false
                            cleared = true
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showWarning = true },
                        modifier = Modifier.fillMaxWidth(),
                        border   = BorderStroke(1.dp, tc.red),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = tc.red),
                    ) {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(st.clearDataLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Save ──────────────────────────────────────────
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
    }

    // ── Currency picker dialog ────────────────────────────────
    if (showCurrencyPicker) {
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            containerColor   = tc.card,
            title = {
                Text(st.currencyChange.title, color = tc.textPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value         = currencySearchQuery,
                        onValueChange = { currencySearchQuery = it },
                        placeholder   = { Text("Search…", color = tc.muted, fontSize = 13.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = tc.accent,
                            unfocusedBorderColor = tc.surface,
                            focusedTextColor     = tc.textPrimary,
                            unfocusedTextColor   = tc.textPrimary,
                            cursorColor          = tc.accent,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = remember(currencySearchQuery) {
                        val q = currencySearchQuery.trim().lowercase()
                        if (q.isEmpty()) Currency.entries
                        else Currency.entries.filter {
                            it.code.lowercase().contains(q) || it.symbol.lowercase().contains(q)
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(items = filtered, key = { it.code }) { cur ->
                            val selected = cur == draft.currency
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showCurrencyPicker = false
                                        if (cur != draft.currency) {
                                            pendingCurrency   = cur
                                            convertRateInput  = ""
                                            convertDone       = false
                                            showConvertDialog = true
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${cur.code}  ${cur.symbol}",
                                    color      = if (selected) tc.accent else tc.textPrimary,
                                    fontSize   = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (selected)
                                    Icon(Icons.Default.Check, null,
                                        tint = tc.accent, modifier = Modifier.size(16.dp))
                            }
                            HorizontalDivider(color = tc.surface, thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCurrencyPicker = false }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            }
        )
    }

    // ── Conversion dialog ─────────────────────────────────────
    if (showConvertDialog) {
        val newCur = pendingCurrency
        AlertDialog(
            onDismissRequest = { if (!isConverting) showConvertDialog = false },
            containerColor   = tc.card,
            title = {
                Text(st.currencyChange.title, color = tc.textPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (convertDone) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = tc.green, modifier = Modifier.size(20.dp))
                            Text(st.currencyChange.doneMsg, color = tc.green, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            "${draft.currency.code} → ${newCur?.code ?: ""}",
                            color = tc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Text(st.currencyChange.ratePrompt, color = tc.muted, fontSize = 12.sp)
                        OutlinedTextField(
                            value         = convertRateInput,
                            onValueChange = { convertRateInput = it },
                            placeholder   = { Text(st.currencyChange.rateHint, color = tc.muted, fontSize = 12.sp) },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = tc.accent,
                                unfocusedBorderColor = tc.surface,
                                focusedTextColor     = tc.textPrimary,
                                unfocusedTextColor   = tc.textPrimary,
                                cursorColor          = tc.accent,
                            ),
                        )
                        Text(st.currencyChange.warningMsg, color = tc.red.copy(alpha = 0.8f), fontSize = 11.sp)
                        if (isConverting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color    = tc.accent,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(st.currencyChange.convertingMsg, color = tc.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!convertDone && !isConverting) {
                    val rate = convertRateInput.trim().toDoubleOrNull()
                    TextButton(
                        onClick = {
                            if (newCur != null && rate != null && rate > 0) {
                                isConverting = true
                                vm.convertAllRides(rate) {
                                    scope.launch {
                                        repo.setCurrency(newCur)
                                        draft = draft.copy(currency = newCur)
                                    }
                                    isConverting = false
                                    convertDone  = true
                                }
                            }
                        },
                        enabled = rate != null && rate > 0
                    ) {
                        Text(st.currencyChange.convertBtn, color = if (rate != null && rate > 0) tc.accent else tc.muted)
                    }
                } else if (convertDone) {
                    TextButton(onClick = { showConvertDialog = false }) {
                        Text("OK", color = tc.accent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!convertDone && !isConverting) {
                    TextButton(
                        onClick = {
                            if (newCur != null) {
                                scope.launch { repo.setCurrency(newCur) }
                                draft = draft.copy(currency = newCur)
                            }
                            showConvertDialog = false
                        }
                    ) {
                        Text(st.currencyChange.skipBtn, color = tc.muted, fontSize = 12.sp)
                    }
                }
            }
        )
    }

    // ── Warning dialog ────────────────────────────────────────
    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            containerColor   = tc.card,
            icon = {
                Icon(Icons.Default.Warning, null, tint = tc.red, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(st.clearDataWarningTitle, color = tc.textPrimary,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            },
            text = {
                Text(st.clearDataWarningMsg, color = tc.muted,
                    fontSize = 13.sp, textAlign = TextAlign.Center)
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    showHoldButton = true
                }) {
                    Text(st.clearDataProceed, color = tc.red, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ── HoldToDeleteButton ───────────────────────────────────────────

@Composable
private fun HoldToDeleteButton(label: String, tc: ThemeColors, onConfirmed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isHolding) {
        if (isHolding) {
            progress.animateTo(1f, animationSpec = tween(2500, easing = LinearEasing))
            if (progress.value >= 1f) onConfirmed()
        } else {
            progress.animateTo(0f, animationSpec = tween(250))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Border outline
        Box(
            Modifier
                .matchParentSize()
                .background(tc.card, RoundedCornerShape(14.dp))
                .then(
                    Modifier.clip(RoundedCornerShape(14.dp))
                )
        )
        // Fill from left
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(tc.red.copy(alpha = 0.85f))
                .align(Alignment.CenterStart)
        )
        // Border
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color.Transparent)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.DeleteForever, null,
                tint     = if (progress.value > 0.45f) tc.textPrimary else tc.red,
                modifier = Modifier.size(18.dp)
            )
            Text(
                label,
                color      = if (progress.value > 0.45f) tc.textPrimary else tc.red,
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp,
            )
        }
        // Outlined border drawn on top
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                color       = tc.red,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                style        = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

// ── ThemePicker ──────────────────────────────────────────────────

@Composable
private fun ThemePicker(
    current: AppTheme,
    st: AppStrings,
    tc: ThemeColors,
    onSelect: (AppTheme) -> Unit,
) {
    val items = listOf(
        Triple(AppTheme.DARK,     st.themeDark,     DarkColors),
        Triple(AppTheme.LIGHT,    st.themeLight,    LightColors),
        Triple(AppTheme.MIDNIGHT, st.themeMidnight, MidnightColors),
        Triple(AppTheme.SUNSET,   st.themeSunset,   SunsetColors),
        Triple(AppTheme.FOREST,   st.themeForest,   ForestColors),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (theme, label, colors) ->
                    val selected = current == theme
                    Card(
                        modifier = Modifier.weight(1f).clickable { onSelect(theme) },
                        colors   = CardDefaults.cardColors(
                            containerColor = if (selected) colors.accent.copy(alpha = 0.15f) else tc.card
                        ),
                        border = if (selected) BorderStroke(2.dp, colors.accent)
                                 else BorderStroke(1.dp, tc.surface),
                        shape  = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .background(colors.background, RoundedCornerShape(6.dp))
                            ) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(colors.accent, RoundedCornerShape(3.dp))
                                        .align(Alignment.BottomEnd)
                                )
                            }
                            Text(
                                label,
                                color      = if (selected) colors.accent else tc.muted,
                                fontSize   = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (selected) {
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Default.Check, null,
                                    tint     = colors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
