package com.taxipro.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "zone_wait_sessions")
data class ZoneWaitSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long = 0,
    val zoneId: Long = 0,
    val zoneName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
)

@Dao
interface ZoneWaitDao {
    @Query("SELECT * FROM zone_wait_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ZoneWaitSession>>

    @Query("SELECT * FROM zone_wait_sessions")
    suspend fun getAllSessionsOnce(): List<ZoneWaitSession>

    @Query("SELECT * FROM zone_wait_sessions WHERE shiftId = :shiftId ORDER BY startTime ASC")
    fun getSessionsByShift(shiftId: Long): Flow<List<ZoneWaitSession>>

    @Insert
    suspend fun insertSession(session: ZoneWaitSession): Long

    @Delete
    suspend fun deleteSession(session: ZoneWaitSession)

    @Query("DELETE FROM zone_wait_sessions WHERE shiftId = :shiftId")
    suspend fun deleteByShiftId(shiftId: Long)

    @Query("DELETE FROM zone_wait_sessions")
    suspend fun deleteAll()
}

data class ZoneWaitSummary(
    val zoneId: Long,
    val zoneName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
)

fun List<ZoneWaitSession>.longestZoneWait(): ZoneWaitSummary? =
    maxByOrNull { it.durationMs }
        ?.takeIf { it.durationMs > 0L }
        ?.let {
            ZoneWaitSummary(
                zoneId = it.zoneId,
                zoneName = it.zoneName,
                startTime = it.startTime,
                endTime = it.endTime,
                durationMs = it.durationMs,
            )
        }

fun computeAverageZoneWaitMs(
    sessions: List<ZoneWaitSession>,
    zoneId: Long,
): Long {
    val zoneSessions = sessions.filter { it.zoneId == zoneId && it.durationMs > 0L }
    if (zoneSessions.isEmpty()) return 0L
    return zoneSessions.sumOf { it.durationMs } / zoneSessions.size
}
