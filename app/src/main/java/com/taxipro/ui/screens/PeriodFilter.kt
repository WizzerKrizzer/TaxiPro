package com.taxipro.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.ui.theme.LocalStrings
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.*

// ── Exported date helpers ─────────────────────────────────────────────────────

fun pfStartOfDay(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0);       c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
fun pfEndOfDay(ms: Long): Long = pfStartOfDay(ms) + 86_399_999L

/** Monday 00:00 → Sunday 23:59:59.999 of the current calendar week. */
fun pfCurrentWeekRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
    }
    return cal.timeInMillis to (cal.timeInMillis + 7 * 86_400_000L - 1)
}

fun pfRangeForYear(year: Int): Pair<Long, Long> {
    val s = Calendar.getInstance().apply {
        set(year, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val e = Calendar.getInstance().apply {
        set(year, Calendar.DECEMBER, 31, 23, 59, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis
    return s to e
}

fun pfRangeForMonth(year: Int, month: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(year, month, 1, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
    val s = cal.timeInMillis
    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
    cal.set(Calendar.SECOND, 59);       cal.set(Calendar.MILLISECOND, 999)
    return s to cal.timeInMillis
}

/** All Mon–Sun weeks that overlap with the given month. */
fun pfWeeksInMonth(year: Int, month: Int): List<Pair<Long, Long>> {
    val (_, monthEnd) = pfRangeForMonth(year, month)
    val monthStart    = pfStartOfDay(
        Calendar.getInstance().apply { set(year, month, 1, 0, 0, 0) }.timeInMillis
    )
    // Find the Monday on or before the 1st of the month
    val cal = Calendar.getInstance()
    cal.set(year, month, 1, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
    val daysBack = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    cal.add(Calendar.DAY_OF_MONTH, -daysBack)

    val weeks = mutableListOf<Pair<Long, Long>>()
    while (cal.timeInMillis <= monthEnd) {
        val weekStart = cal.timeInMillis
        val endCal    = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 6)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59);       set(Calendar.MILLISECOND, 999)
        }
        val weekEnd = endCal.timeInMillis
        if (weekEnd >= monthStart && weekStart <= monthEnd) weeks.add(weekStart to weekEnd)
        cal.add(Calendar.DAY_OF_MONTH, 7)
    }
    return weeks
}

// ── Period enum (public — used by all screens) ────────────────────────────────
enum class ActivePeriod { ALL_TIME, TODAY, WEEK, MONTH, YEAR, CUSTOM }

// ── Main composable ───────────────────────────────────────────────────────────
/**
 * Horizontal row of period-filter chips.
 * The component owns all picker UI state; the parent only stores fromMs/toMs.
 * Fires onRangeChanged(null, null) for ALL_TIME, timestamps for everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodFilterRow(
    onRangeChanged: (from: Long?, to: Long?) -> Unit,
    onPeriodChanged: ((ActivePeriod) -> Unit)? = null,
    initialPeriod: ActivePeriod = ActivePeriod.WEEK,
    modifier: Modifier = Modifier,
) {
    val tc     = LocalThemeColors.current
    val st     = LocalStrings.current
    val nowCal = remember { Calendar.getInstance() }

    var activePeriod  by remember { mutableStateOf(initialPeriod) }
    var showPickerFor by remember { mutableStateOf<ActivePeriod?>(null) }
    var showCustom    by remember { mutableStateOf(false) }

    // Fire the initial range so the parent filter is in sync with the selected chip on first render.
    LaunchedEffect(initialPeriod) {
        val range = when (initialPeriod) {
            ActivePeriod.ALL_TIME -> null
            ActivePeriod.TODAY -> {
                val day = pfStartOfDay(System.currentTimeMillis())
                day to pfEndOfDay(day)
            }
            ActivePeriod.WEEK -> pfCurrentWeekRange()
            ActivePeriod.MONTH -> pfRangeForMonth(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH))
            ActivePeriod.YEAR -> pfRangeForYear(nowCal.get(Calendar.YEAR))
            ActivePeriod.CUSTOM -> null
        }
        if (range == null) onRangeChanged(null, null) else onRangeChanged(range.first, range.second)
        onPeriodChanged?.invoke(initialPeriod)
    }

    // Picker selection — defaults to current date context
    var pickerYear by remember { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var pickerMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }
    var pickerWeekIdx by remember {
        mutableIntStateOf(run {
            val today = Calendar.getInstance()
            val weeks = pfWeeksInMonth(today.get(Calendar.YEAR), today.get(Calendar.MONTH))
            val now   = System.currentTimeMillis()
            weeks.indexOfFirst { (s, e) -> now in s..e }.coerceAtLeast(0)
        })
    }

    var pickerDayMs by remember { mutableLongStateOf(pfStartOfDay(System.currentTimeMillis())) }

    val dateRangeState = rememberDateRangePickerState()

    // Chip labels
    val weekFmt  = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val monthFmt = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }

    val weekLabel = remember(pickerYear, pickerMonth, pickerWeekIdx) {
        val weeks = pfWeeksInMonth(pickerYear, pickerMonth)
        if (weeks.isEmpty()) st.zones.filterThisWeek
        else {
            val (s, e) = weeks.getOrElse(pickerWeekIdx) { weeks.last() }
            "${weekFmt.format(Date(s))} – ${weekFmt.format(Date(e))}"
        }
    }
    val monthLabel = remember(pickerYear, pickerMonth) {
        monthFmt.format(Calendar.getInstance().apply { set(pickerYear, pickerMonth, 1) }.time)
    }

    val todayStartMs = pfStartOfDay(System.currentTimeMillis())
    val dayLabel = remember(pickerDayMs) {
        if (pickerDayMs >= todayStartMs) st.zones.filterToday
        else weekFmt.format(Date(pickerDayMs))
    }

    fun chipLabel(p: ActivePeriod) = when (p) {
        ActivePeriod.ALL_TIME -> st.zones.filterAllTime
        ActivePeriod.TODAY    -> if (activePeriod == ActivePeriod.TODAY) dayLabel else st.zones.filterToday
        ActivePeriod.WEEK     -> if (activePeriod == ActivePeriod.WEEK)  weekLabel   else st.zones.filterThisWeek
        ActivePeriod.MONTH    -> if (activePeriod == ActivePeriod.MONTH) monthLabel  else st.zones.filterThisMonth
        ActivePeriod.YEAR     -> if (activePeriod == ActivePeriod.YEAR)  "$pickerYear" else st.zones.filterYear
        ActivePeriod.CUSTOM   -> st.zones.filterCustom
    }

    fun fireCurrentRange() {
        val range = when (activePeriod) {
            ActivePeriod.TODAY -> pickerDayMs to pfEndOfDay(pickerDayMs)
            ActivePeriod.YEAR  -> pfRangeForYear(pickerYear)
            ActivePeriod.MONTH -> pfRangeForMonth(pickerYear, pickerMonth)
            ActivePeriod.WEEK  -> {
                val weeks = pfWeeksInMonth(pickerYear, pickerMonth)
                weeks.getOrElse(pickerWeekIdx) { pfRangeForMonth(pickerYear, pickerMonth) }
            }
            else -> return
        }
        onRangeChanged(range.first, range.second)
    }

    fun canNavNext(): Boolean {
        val ny = nowCal.get(Calendar.YEAR)
        val nm = nowCal.get(Calendar.MONTH)
        return when (activePeriod) {
            ActivePeriod.TODAY -> pickerDayMs < todayStartMs
            ActivePeriod.YEAR  -> pickerYear < ny
            ActivePeriod.MONTH -> pickerYear < ny || (pickerYear == ny && pickerMonth < nm)
            ActivePeriod.WEEK  -> {
                val weeks = pfWeeksInMonth(pickerYear, pickerMonth)
                val (_, wEnd) = weeks.getOrElse(pickerWeekIdx) { return false }
                wEnd < System.currentTimeMillis()
            }
            else -> false
        }
    }

    fun navPrev() {
        when (activePeriod) {
            ActivePeriod.TODAY -> { pickerDayMs -= 86_400_000L }
            ActivePeriod.YEAR  -> { pickerYear -= 1 }
            ActivePeriod.MONTH -> {
                if (pickerMonth == 0) { pickerMonth = 11; pickerYear -= 1 }
                else pickerMonth -= 1
                pickerWeekIdx = 0
            }
            ActivePeriod.WEEK  -> {
                val weeks = pfWeeksInMonth(pickerYear, pickerMonth)
                if (pickerWeekIdx > 0) {
                    pickerWeekIdx -= 1
                } else {
                    if (pickerMonth == 0) { pickerMonth = 11; pickerYear -= 1 }
                    else pickerMonth -= 1
                    val prevWeeks = pfWeeksInMonth(pickerYear, pickerMonth)
                    pickerWeekIdx = (prevWeeks.size - 1).coerceAtLeast(0)
                }
            }
            else -> return
        }
        fireCurrentRange()
    }

    fun navNext() {
        if (!canNavNext()) return
        when (activePeriod) {
            ActivePeriod.TODAY -> { pickerDayMs += 86_400_000L }
            ActivePeriod.YEAR  -> { pickerYear += 1 }
            ActivePeriod.MONTH -> {
                if (pickerMonth == 11) { pickerMonth = 0; pickerYear += 1 }
                else pickerMonth += 1
                pickerWeekIdx = 0
            }
            ActivePeriod.WEEK  -> {
                val weeks = pfWeeksInMonth(pickerYear, pickerMonth)
                if (pickerWeekIdx < weeks.size - 1) {
                    pickerWeekIdx += 1
                } else {
                    if (pickerMonth == 11) { pickerMonth = 0; pickerYear += 1 }
                    else pickerMonth += 1
                    pickerWeekIdx = 0
                }
            }
            else -> return
        }
        fireCurrentRange()
    }

    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivePeriod.entries.forEach { p ->
            val isNavPeriod = p == ActivePeriod.TODAY || p == ActivePeriod.WEEK || p == ActivePeriod.MONTH || p == ActivePeriod.YEAR
            val isActive    = activePeriod == p

            if (isNavPeriod && isActive) {
                IconButton(
                    onClick  = { navPrev() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.ChevronLeft, null,
                        tint     = tc.accent,
                        modifier = Modifier.size(20.dp))
                }
            }
            FilterChip(
                selected    = isActive,
                onClick     = {
                    when (p) {
                        ActivePeriod.ALL_TIME -> {
                            activePeriod = p
                            onRangeChanged(null, null)
                            onPeriodChanged?.invoke(p)
                        }
                        ActivePeriod.TODAY -> {
                            pickerDayMs  = pfStartOfDay(System.currentTimeMillis())
                            activePeriod = p
                            onRangeChanged(pickerDayMs, pfEndOfDay(pickerDayMs))
                            onPeriodChanged?.invoke(p)
                        }
                        ActivePeriod.WEEK,
                        ActivePeriod.MONTH,
                        ActivePeriod.YEAR  -> showPickerFor = p
                        ActivePeriod.CUSTOM -> showCustom = true
                    }
                },
                label       = { Text(chipLabel(p), fontSize = 12.sp) },
                leadingIcon = if (p == ActivePeriod.CUSTOM) ({
                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(14.dp))
                }) else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tc.accent.copy(alpha = 0.18f),
                    selectedLabelColor     = tc.accent,
                    containerColor         = tc.card,
                    labelColor             = tc.muted,
                ),
            )
            if (isNavPeriod && isActive) {
                IconButton(
                    onClick  = { navNext() },
                    modifier = Modifier.size(30.dp),
                    enabled  = canNavNext(),
                ) {
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = if (canNavNext()) tc.accent else tc.muted.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    // ── Hierarchical picker dialog ────────────────────────────────────────────
    showPickerFor?.let { forPeriod ->
        PeriodPickerDialog(
            forPeriod    = forPeriod,
            initialYear  = pickerYear,
            initialMonth = pickerMonth,
            initialWeek  = pickerWeekIdx,
            onDismiss    = { showPickerFor = null },
            onConfirm    = { year, month, weekIdx ->
                pickerYear    = year
                pickerMonth   = month
                pickerWeekIdx = weekIdx
                activePeriod  = forPeriod
                showPickerFor = null
                val range = when (forPeriod) {
                    ActivePeriod.YEAR  -> pfRangeForYear(year)
                    ActivePeriod.MONTH -> pfRangeForMonth(year, month)
                    ActivePeriod.WEEK  -> {
                        val weeks = pfWeeksInMonth(year, month)
                        weeks.getOrElse(weekIdx) { pfRangeForMonth(year, month) }
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        pfStartOfDay(now) to pfEndOfDay(now)
                    }
                }
                onRangeChanged(range.first, range.second)
                onPeriodChanged?.invoke(forPeriod)
            },
        )
    }

    // ── Custom date-range picker ──────────────────────────────────────────────
    if (showCustom) {
        DatePickerDialog(
            onDismissRequest = { showCustom = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val from = dateRangeState.selectedStartDateMillis
                        val to   = dateRangeState.selectedEndDateMillis
                        if (from != null) {
                            activePeriod = ActivePeriod.CUSTOM
                            onRangeChanged(from, pfEndOfDay(to ?: from))
                            onPeriodChanged?.invoke(ActivePeriod.CUSTOM)
                        }
                        showCustom = false
                    },
                    enabled = dateRangeState.selectedStartDateMillis != null,
                ) { Text("OK", color = tc.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCustom = false }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            },
            colors = DatePickerDefaults.colors(containerColor = tc.card),
        ) {
            DateRangePicker(
                state    = dateRangeState,
                modifier = Modifier.weight(1f),
                colors   = DatePickerDefaults.colors(
                    containerColor                    = tc.card,
                    titleContentColor                 = tc.textPrimary,
                    headlineContentColor              = tc.textPrimary,
                    weekdayContentColor               = tc.muted,
                    subheadContentColor               = tc.muted,
                    yearContentColor                  = tc.textPrimary,
                    currentYearContentColor           = tc.accent,
                    selectedYearContentColor          = tc.background,
                    selectedYearContainerColor        = tc.accent,
                    dayContentColor                   = tc.textPrimary,
                    selectedDayContentColor           = tc.background,
                    selectedDayContainerColor         = tc.accent,
                    todayContentColor                 = tc.accent,
                    todayDateBorderColor              = tc.accent,
                    dayInSelectionRangeContentColor   = tc.textPrimary,
                    dayInSelectionRangeContainerColor = tc.accent.copy(alpha = 0.18f),
                ),
            )
        }
    }
}

// ── Hierarchical picker dialog ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPickerDialog(
    forPeriod    : ActivePeriod,
    initialYear  : Int,
    initialMonth : Int,
    initialWeek  : Int,
    onDismiss    : () -> Unit,
    onConfirm    : (year: Int, month: Int, weekIdx: Int) -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    var year    by remember { mutableIntStateOf(initialYear) }
    var month   by remember { mutableIntStateOf(initialMonth) }
    var weekIdx by remember { mutableIntStateOf(initialWeek) }

    // Reset week index when year/month changes to avoid out-of-bounds
    LaunchedEffect(year, month) { weekIdx = 0 }

    val currentYear      = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years            = (currentYear - 4..currentYear).toList()
    val monthShortNames  = remember {
        DateFormatSymbols.getInstance(Locale.getDefault()).shortMonths.take(12)
    }
    val weeks   = remember(year, month) { pfWeeksInMonth(year, month) }
    val weekFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    val showMonth = forPeriod == ActivePeriod.MONTH || forPeriod == ActivePeriod.WEEK
    val showWeek  = forPeriod == ActivePeriod.WEEK

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = tc.card,
        title = {
            Text(
                when (forPeriod) {
                    ActivePeriod.YEAR  -> st.zones.filterYear
                    ActivePeriod.MONTH -> st.zones.filterThisMonth
                    ActivePeriod.WEEK  -> st.zones.filterThisWeek
                    else               -> ""
                },
                color      = tc.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Year ─────────────────────────────────────────────────
                PDialogSectionLabel(st.zones.filterYear, tc.muted)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    years.forEach { y ->
                        PDialogChip("$y", y == year, tc.accent, tc.surface, tc.background) { year = y }
                    }
                }

                // ── Month ─────────────────────────────────────────────────
                if (showMonth) {
                    HorizontalDivider(color = tc.surface)
                    PDialogSectionLabel(st.zones.filterThisMonth, tc.muted)
                    for (row in 0..2) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (col in 0..3) {
                                val m = row * 4 + col
                                PDialogChip(
                                    label    = monthShortNames[m].replaceFirstChar { it.uppercase() },
                                    selected = m == month,
                                    accent   = tc.accent,
                                    surface  = tc.surface,
                                    bg       = tc.background,
                                    modifier = Modifier.weight(1f),
                                    onSelect = { month = m },
                                )
                            }
                        }
                    }
                }

                // ── Week ──────────────────────────────────────────────────
                if (showWeek) {
                    HorizontalDivider(color = tc.surface)
                    PDialogSectionLabel(st.zones.filterThisWeek, tc.muted)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        weeks.forEachIndexed { idx, (s, e) ->
                            PDialogChip(
                                label    = "${weekFmt.format(Date(s))} – ${weekFmt.format(Date(e))}",
                                selected = idx == weekIdx,
                                accent   = tc.accent,
                                surface  = tc.surface,
                                bg       = tc.background,
                                modifier = Modifier.fillMaxWidth(),
                                onSelect = { weekIdx = idx },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(year, month, weekIdx) }) {
                Text("OK", color = tc.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(st.cancelBtn, color = tc.muted)
            }
        },
    )
}

@Composable
private fun PDialogSectionLabel(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PDialogChip(
    label    : String,
    selected : Boolean,
    accent   : Color,
    surface  : Color,
    bg       : Color,
    modifier : Modifier = Modifier,
    onSelect : () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick  = onSelect,
        modifier = modifier,
        label    = { Text(label, fontSize = 12.sp) },
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent,
            selectedLabelColor     = bg,
            containerColor         = surface,
            labelColor             = accent.copy(alpha = 0.8f),
        ),
    )
}
