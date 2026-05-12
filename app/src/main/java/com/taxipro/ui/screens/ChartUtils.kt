package com.taxipro.ui.screens

import com.taxipro.data.db.RideDao
import com.taxipro.data.db.ShiftDao
import java.text.SimpleDateFormat
import java.util.*

// ────────────────────────────────────────────────────────────────────────────
// Y-Axis Scale — 4 labels: [0, step, 2*step, 3*step]
// Labels start from 0 and are multiples of 5 ("nice" numbers)
// ────────────────────────────────────────────────────────────────────────────

data class YAxisScale(
    /** 4 ascending labels, first is always 0. */
    val labels   : List<Int>,
    /** The top of the chart range = labels.last() */
    val maxValue : Double,
    /** Spacing between labels */
    val stepSize : Int,
)

/**
 * Round a raw step value up to the nearest "nice" number (multiple of 5).
 * Examples: 7.5 → 10, 12.5 → 15, 22.5 → 25, 42.5 → 50, 75 → 75
 */
private fun roundToNiceStep(raw: Double): Int {
    val niceSteps = listOf(5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 300, 500)
    return niceSteps.firstOrNull { it >= raw }
        ?: (Math.ceil(raw / 100.0) * 100).toInt()
}

/**
 * Build a smart Y-axis scale from an average shift earnings figure.
 *
 * Rules:
 *  - 4 labels: always [0, step, 2*step, 3*step]  — starts from 0
 *  - step chosen so 3*step ≈ 1.5 × average (headroom above avg)
 *  - step rounded to a "nice" multiple of 5
 *  - Default (no data): [0, 20, 40, 60]
 *
 * Examples:
 *   avg €15  → step=10 → [0, 10, 20, 30]
 *   avg €25  → step=15 → [0, 15, 30, 45]
 *   avg €45  → step=25 → [0, 25, 50, 75]
 *   avg €85  → step=50 → [0, 50, 100, 150]
 *   avg €150 → step=75 → [0, 75, 150, 225]
 *   avg €300 → step=150→ [0, 150, 300, 450]
 */
fun calculateYAxisScale(averageEarnings: Double): YAxisScale {
    if (averageEarnings < 1.0) {
        return YAxisScale(listOf(20, 40, 60, 80), 80.0, 20)
    }
    // Four visible labels; baseline 0 remains implicit.
    val rawStep = averageEarnings * 1.5 / 4.0
    val step    = roundToNiceStep(rawStep)
    val labels  = listOf(step, step * 2, step * 3, step * 4)
    return YAxisScale(labels, (step * 4).toDouble(), step)
}

/** Scale a finished shift by its actual earnings, with a little headroom. */
fun calculateYAxisScaleForActualEarnings(actualEarnings: Double): YAxisScale {
    if (actualEarnings < 1.0) {
        return YAxisScale(listOf(10, 20, 30, 40), 40.0, 10)
    }
    val rawStep = actualEarnings / 3.5
    val step = roundToNiceStep(rawStep)
    val labels = listOf(step, step * 2, step * 3, step * 4)
    return YAxisScale(labels, (step * 4).toDouble(), step)
}

// ────────────────────────────────────────────────────────────────────────────
// X-Axis Scale — 6 time labels: shift start + 5 round-hour marks
// Interval adapts to average shift length: 1h / 2h / 3h
// Handles shifts > 12h: uses 3h intervals → covers up to 15h
// ────────────────────────────────────────────────────────────────────────────

data class XAxisScale(
    /** List of (timestampMs, "HH:mm") pairs — always 6 entries. */
    val labels       : List<Pair<Long, String>>,
    /** Hours between consecutive round-hour labels. */
    val intervalHours: Int,
)

/**
 * Build an X-axis scale for a shift starting at [shiftStartMs].
 *
 * [expectedDurationHours]: average (or actual) shift length in hours.
 *  - ≤ 6h  → 1h intervals  → covers ~5h after start
 *  - ≤ 12h → 2h intervals  → covers ~10h after start
 *  - > 12h → 3h intervals  → covers ~15h after start
 *
 * Label layout: "15:12", "16:00", "17:00", "18:00", "19:00", "20:00"
 */
fun calculateXAxisScale(shiftStartMs: Long, expectedDurationHours: Double): XAxisScale {
    val intervalHours = when {
        expectedDurationHours <= 6  -> 1
        expectedDurationHours <= 12 -> 2
        expectedDurationHours <= 18 -> 3
        else                        -> Math.ceil(expectedDurationHours / 5.0).toInt().coerceAtLeast(4)
    }

    val fmt    = SimpleDateFormat("HH:mm", Locale.getDefault())
    val labels = mutableListOf<Pair<Long, String>>()

    // First label: exact shift start time
    labels.add(shiftStartMs to fmt.format(Date(shiftStartMs)))

    // Find the next full hour after shift start
    val cal = Calendar.getInstance().apply {
        timeInMillis = shiftStartMs
        set(Calendar.SECOND,      0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.MINUTE,      0)
        add(Calendar.HOUR_OF_DAY, 1)   // advance to next full hour
    }
    // Round up to next multiple of intervalHours
    val rawHour     = cal.get(Calendar.HOUR_OF_DAY)
    val roundedHour = ((rawHour + intervalHours - 1) / intervalHours) * intervalHours
    cal.add(Calendar.HOUR_OF_DAY, roundedHour - rawHour)

    val targetEndMs = shiftStartMs +
        ((expectedDurationHours + intervalHours) * 3_600_000.0).toLong()

    // Add round-hour labels until there is one interval of headroom, capped at 6 labels.
    while (labels.size < 6 && cal.timeInMillis <= targetEndMs) {
        labels.add(cal.timeInMillis to fmt.format(cal.time))
        cal.add(Calendar.HOUR_OF_DAY, intervalHours)
    }

    return XAxisScale(labels, intervalHours)
}

// ────────────────────────────────────────────────────────────────────────────
// Database helpers
// ────────────────────────────────────────────────────────────────────────────

/** Average total earnings per completed shift (last [countShifts] shifts). */
suspend fun computeAverageShiftEarnings(
    shiftDao    : ShiftDao,
    rideDao     : RideDao,
    countShifts : Int = 5,
): Double {
    val shifts = shiftDao.getLastNCompletedShifts(countShifts)
    if (shifts.isEmpty()) return 0.0
    val total = shifts.sumOf { rideDao.getTotalEarningsForShift(it.id) }
    return total / shifts.size
}

/** Average shift duration in hours (last [countShifts] completed shifts). */
suspend fun computeAverageShiftHours(
    shiftDao    : ShiftDao,
    countShifts : Int = 5,
): Double {
    val shifts = shiftDao.getLastNCompletedShifts(countShifts)
    if (shifts.isEmpty()) return 8.0   // sensible default: 8-hour shift
    val totalMs = shifts.sumOf { it.endTime - it.startTime }
    return totalMs / shifts.size / 3_600_000.0
}
