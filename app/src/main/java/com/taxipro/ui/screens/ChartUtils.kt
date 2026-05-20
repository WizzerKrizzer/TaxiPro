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
// X-Axis Scale — dynamic time labels that expand like the Y-axis.
// Short shifts start dense (30 min), then thin out to 1h / 2h / 3h+ intervals.
// ────────────────────────────────────────────────────────────────────────────

data class XAxisScale(
    /** List of (timestampMs, "HH:mm") pairs. */
    val labels       : List<Pair<Long, String>>,
    /** Minutes between consecutive labels. */
    val intervalMinutes: Int,
)

/**
 * Build an X-axis scale for a shift starting at [shiftStartMs].
 *
 * [durationHours]: real or expected shift length in hours.
 * [shiftEndMs]: when supplied, the returned labels are guaranteed to extend beyond it.
 *
 * Label layout examples:
 *  - short: "15:00", "15:30", "16:00", "16:30"
 *  - longer: "15:30", "16:30", "17:30", "18:30"
 */
fun calculateXAxisScale(
    shiftStartMs: Long,
    durationHours: Double,
    shiftEndMs: Long = shiftStartMs,
): XAxisScale {
    val fmt    = SimpleDateFormat("HH:mm", Locale.getDefault())
    val labels = mutableListOf<Pair<Long, String>>()

    val actualEndMs = maxOf(shiftEndMs, shiftStartMs)
    val actualDurationMs = (actualEndMs - shiftStartMs).coerceAtLeast(0L)
    val requestedDurationMs = (durationHours.coerceAtLeast(0.0) * 3_600_000.0).toLong()
    val baseDurationMs = maxOf(actualDurationMs, requestedDurationMs, 60L * 60_000L)

    // Keep roughly four-to-five visible time marks, just like the earnings scale.
    val intervalMinutes = when {
        baseDurationMs <= 90L * 60_000L -> 30
        baseDurationMs <= 3L * 3_600_000L -> 60
        baseDurationMs <= 6L * 3_600_000L -> 120
        baseDurationMs <= 9L * 3_600_000L -> 180
        baseDurationMs <= 12L * 3_600_000L -> 240
        else -> (Math.ceil(baseDurationMs / 60_000.0 / 4.0 / 60.0) * 60).toInt()
    }

    val intervalMs = intervalMinutes * 60_000L
    val targetEndMs = actualEndMs + intervalMs

    // Align labels to the selected interval, so they naturally slide from :00/:30 to hourly marks.
    val firstTickMs = Math.floorDiv(shiftStartMs, intervalMs) * intervalMs
    val cal = Calendar.getInstance().apply {
        timeInMillis = firstTickMs
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    while (labels.size < 6 && cal.timeInMillis <= targetEndMs) {
        labels.add(cal.timeInMillis to fmt.format(cal.time))
        cal.add(Calendar.MINUTE, intervalMinutes)
    }

    if (labels.none { it.first >= actualEndMs }) {
        labels.add(cal.timeInMillis to fmt.format(cal.time))
    }

    return XAxisScale(labels, intervalMinutes)
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
