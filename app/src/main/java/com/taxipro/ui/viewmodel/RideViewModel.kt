package com.taxipro.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.taxipro.data.db.AppDatabase
import com.taxipro.data.db.Ride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val dao      = AppDatabase.getInstance(app).rideDao()
    private val shiftDao = AppDatabase.getInstance(app).shiftDao()

    val allRides: Flow<List<Ride>>   = dao.getAllRides()
    val allShifts: Flow<List<com.taxipro.data.db.Shift>> = shiftDao.getAllShifts()

    fun getRidesByShift(shiftId: Long): Flow<List<Ride>> = dao.getRidesByShiftId(shiftId)

    fun deleteRide(ride: Ride) = viewModelScope.launch {
        dao.deleteRide(ride)
    }
}