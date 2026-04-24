package com.taxipro.data.db

import androidx.room.*

@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Номерации
    val globalId: Long = 0,
    val dailyId: Long = 0,
    val weeklyId: Long = 0,
    val monthlyId: Long = 0,
    val yearlyId: Long = 0,

    // Данни
    val date: Long = System.currentTimeMillis(),        // epoch ms
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = System.currentTimeMillis(),

    val fromAddress: String = "",    // Reverse-geocoded start address
    val toAddress: String = "",      // Reverse-geocoded end address
    val fromLat: Double = 0.0,
    val fromLng: Double = 0.0,
    val toLat: Double = 0.0,
    val toLng: Double = 0.0,

    val kilometers: Double = 0.0,
    val waitMinutes: Double = 0.0,   // Престой (скорост < threshold)
    val tip: Double = 0.0,
    val price: Double = 0.0,
    val currency: String = "BGN",

    // GPS пътека — JSON масив от "lat,lng" точки
    val routePointsJson: String = "[]",

    // Статистики
    val maxSpeed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val isPaused: Boolean = false,

    // Смяна
    val shiftId: Long = 0,

    // Снимка на тарифните разходи при запазване на курса
    val taxPercent: Double    = 0.0,
    val fuelCostPerKm: Double = 0.0,

    // km added via fare-adjustment inference (not GPS-measured).
    // Fuel cost is calculated only on GPS km = kilometers - adjustmentKm.
    @ColumnInfo(defaultValue = "0") val adjustmentKm: Double = 0.0,

    // True when start and end are in the same zone (internal ride).
    // Stored for future statistics; not yet displayed anywhere in the UI.
    @ColumnInfo(defaultValue = "0") val isInternal: Boolean = false,

    // Payment method: "CASH" or "CARD"
    @ColumnInfo(defaultValue = "CASH") val paymentMethod: String = "CASH",
)
