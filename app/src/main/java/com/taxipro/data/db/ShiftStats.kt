package com.taxipro.data.db

import kotlin.math.max

fun effectiveShiftKm(
    recordedShiftKm: Double,
    clientKm: Double,
): Double = max(recordedShiftKm, clientKm)
