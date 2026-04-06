package com.taxipro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao
interface RideDao {
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    fun getAllRides(): kotlinx.coroutines.flow.Flow<List<Ride>>

    @Query("SELECT * FROM rides WHERE date >= :start AND date < :end ORDER BY startTime DESC")
    fun getRidesByDateRange(start: Long, end: Long): kotlinx.coroutines.flow.Flow<List<Ride>>

    @Query("SELECT COUNT(*) FROM rides")
    suspend fun getTotalCount(): Long

    @Query("SELECT COUNT(*) FROM rides WHERE date >= :dayStart AND date < :dayEnd")
    suspend fun getDailyCount(dayStart: Long, dayEnd: Long): Long

    @Query("SELECT COUNT(*) FROM rides WHERE date >= :weekStart AND date < :weekEnd")
    suspend fun getWeeklyCount(weekStart: Long, weekEnd: Long): Long

    @Query("SELECT COUNT(*) FROM rides WHERE date >= :monthStart AND date < :monthEnd")
    suspend fun getMonthlyCount(monthStart: Long, monthEnd: Long): Long

    @Query("SELECT COUNT(*) FROM rides WHERE date >= :yearStart AND date < :yearEnd")
    suspend fun getYearlyCount(yearStart: Long, yearEnd: Long): Long

    @Insert
    suspend fun insertRide(ride: Ride): Long

    @Update
    suspend fun updateRide(ride: Ride)

    @Delete
    suspend fun deleteRide(ride: Ride)

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getRideById(id: Long): Ride?

    @Query("SELECT * FROM rides WHERE shiftId = :shiftId ORDER BY startTime ASC")
    fun getRidesByShiftId(shiftId: Long): Flow<List<Ride>>
}
