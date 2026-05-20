package com.taxipro.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shift_pause_sessions")
data class ShiftPauseSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long = 0,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
)

@Dao
interface ShiftPauseDao {
    @Query("SELECT * FROM shift_pause_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ShiftPauseSession>>

    @Query("SELECT * FROM shift_pause_sessions WHERE shiftId = :shiftId ORDER BY startTime ASC")
    fun getSessionsByShift(shiftId: Long): Flow<List<ShiftPauseSession>>

    @Insert
    suspend fun insertSession(session: ShiftPauseSession): Long

    @Update
    suspend fun updateSession(session: ShiftPauseSession)

    @Query("SELECT * FROM shift_pause_sessions WHERE shiftId = :shiftId AND endTime = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getOpenSessionByShift(shiftId: Long): ShiftPauseSession?

    @Query("DELETE FROM shift_pause_sessions WHERE shiftId = :shiftId")
    suspend fun deleteByShiftId(shiftId: Long)

    @Query("DELETE FROM shift_pause_sessions")
    suspend fun deleteAll()
}
