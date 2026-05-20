package com.taxipro.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.google.android.gms.maps.model.LatLng
import com.taxipro.data.db.*
import com.taxipro.ui.screens.YAxisScale
import com.taxipro.ui.screens.calculateYAxisScale
import com.taxipro.ui.screens.computeAverageShiftEarnings
import com.taxipro.ui.screens.computeAverageShiftHours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val dao        = AppDatabase.getInstance(app).rideDao()
    private val shiftDao   = AppDatabase.getInstance(app).shiftDao()
    private val zoneDao    = AppDatabase.getInstance(app).zoneDao()
    private val zoneWaitDao = AppDatabase.getInstance(app).zoneWaitDao()
    private val shiftPauseDao = AppDatabase.getInstance(app).shiftPauseDao()
    private val tariffDao  = AppDatabase.getInstance(app).tariffDao()
    private val expenseDao = AppDatabase.getInstance(app).tariffExpenseDao()

    val allRides:    Flow<List<Ride>>           = dao.getAllRides()
    val allShifts:   Flow<List<Shift>>          = shiftDao.getAllShifts()
    val allZones:    Flow<List<Zone>>           = zoneDao.getAllZones()
    val allZoneWaitSessions: Flow<List<ZoneWaitSession>> = zoneWaitDao.getAllSessions()
    val allShiftPauseSessions: Flow<List<ShiftPauseSession>> = shiftPauseDao.getAllSessions()
    val allTariffs:  Flow<List<Tariff>>         = tariffDao.getAll()
    val allExpenses: Flow<List<TariffExpense>>  = expenseDao.getAll()

    // ── Chart scales (based on last 5 completed shifts) ──────────────────
    private val _chartScale      = MutableStateFlow<YAxisScale?>(null)
    val chartScale: StateFlow<YAxisScale?> = _chartScale.asStateFlow()

    private val _avgShiftHours   = MutableStateFlow(8.0)
    val avgShiftHours: StateFlow<Double> = _avgShiftHours.asStateFlow()

    init {
        viewModelScope.launch {
            val avgEarnings = computeAverageShiftEarnings(shiftDao, dao, countShifts = 5)
            _chartScale.value = calculateYAxisScale(avgEarnings)
            _avgShiftHours.value = computeAverageShiftHours(shiftDao, countShifts = 5)
        }
    }

    fun getRidesByShift(shiftId: Long): Flow<List<Ride>> = dao.getRidesByShiftId(shiftId)

    fun getRidesForShift(shift: Shift): Flow<List<Ride>> =
        dao.getRidesForShift(shift.id, shift.shiftNumber, shift.startTime, shift.endTime)

    fun updateRide(ride: Ride) = viewModelScope.launch { dao.updateRide(ride) }

    fun deleteRide(ride: Ride) = viewModelScope.launch {
        dao.deleteRide(ride)
    }

    fun deleteShift(shift: Shift) = viewModelScope.launch {
        dao.deleteByShiftId(shift.id)
        zoneWaitDao.deleteByShiftId(shift.id)
        shiftPauseDao.deleteByShiftId(shift.id)
        shiftDao.deleteShift(shift)
    }

    fun getZoneWaitsByShift(shiftId: Long): Flow<List<ZoneWaitSession>> = zoneWaitDao.getSessionsByShift(shiftId)
    fun getShiftPausesByShift(shiftId: Long): Flow<List<ShiftPauseSession>> = shiftPauseDao.getSessionsByShift(shiftId)

    // ── Zone management ───────────────────────────────────────────

    fun addZone(name: String, points: List<LatLng>, colorArgb: Int, parentZoneId: Long = 0L) = viewModelScope.launch {
        zoneDao.insertZone(
            Zone(
                name       = name.trim(),
                pointsJson = serializeZonePoints(points),
                color      = colorArgb,
                parentZoneId = parentZoneId,
            )
        )
    }

    fun updateZone(zone: Zone) = viewModelScope.launch {
        zoneDao.updateZone(zone)
    }

    fun deleteZone(zone: Zone) = viewModelScope.launch {
        zoneDao.deleteZone(zone)
    }
}
