package com.taxipro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shifts")
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftNumber: Long = 1,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0L,      // 0 = still active
    val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0") val totalKm: Double = 0.0,
)

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shifts ORDER BY startTime DESC")
    fun getAllShifts(): Flow<List<Shift>>

    @Query("SELECT * FROM shifts")
    suspend fun getAllShiftsOnce(): List<Shift>

    @Query("SELECT * FROM shifts WHERE isActive = 1 LIMIT 1")
    fun getActiveShift(): Flow<Shift?>

    @Insert
    suspend fun insertShift(shift: Shift): Long

    @Update
    suspend fun updateShift(shift: Shift)

    @Query("SELECT COUNT(*) FROM shifts")
    suspend fun getTotalCount(): Long

    @Delete
    suspend fun deleteShift(shift: Shift)

    @Query("DELETE FROM shifts")
    suspend fun deleteAll()
}
