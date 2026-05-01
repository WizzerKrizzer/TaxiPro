package com.taxipro.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.google.android.gms.maps.model.LatLng
import com.taxipro.data.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val dao        = AppDatabase.getInstance(app).rideDao()
    private val shiftDao   = AppDatabase.getInstance(app).shiftDao()
    private val zoneDao    = AppDatabase.getInstance(app).zoneDao()
    private val tariffDao  = AppDatabase.getInstance(app).tariffDao()
    private val expenseDao = AppDatabase.getInstance(app).tariffExpenseDao()

    val allRides:    Flow<List<Ride>>           = dao.getAllRides()
    val allShifts:   Flow<List<Shift>>          = shiftDao.getAllShifts()
    val allZones:    Flow<List<Zone>>           = zoneDao.getAllZones()
    val allTariffs:  Flow<List<Tariff>>         = tariffDao.getAll()
    val allExpenses: Flow<List<TariffExpense>>  = expenseDao.getAll()

    fun getRidesByShift(shiftId: Long): Flow<List<Ride>> = dao.getRidesByShiftId(shiftId)

    fun updateRide(ride: Ride) = viewModelScope.launch { dao.updateRide(ride) }

    fun deleteRide(ride: Ride) = viewModelScope.launch {
        dao.deleteRide(ride)
    }

    fun deleteShift(shift: Shift) = viewModelScope.launch {
        dao.deleteByShiftId(shift.id)
        shiftDao.deleteShift(shift)
    }

    // ── Zone management ───────────────────────────────────────────

    fun addZone(name: String, points: List<LatLng>, colorArgb: Int) = viewModelScope.launch {
        zoneDao.insertZone(
            Zone(
                name       = name.trim(),
                pointsJson = serializeZonePoints(points),
                color      = colorArgb,
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
