package com.taxipro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "zones")
data class Zone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val pointsJson: String = "[]",   // "[[lat,lng],[lat,lng],...]" polygon vertices
    val color: Int = 0xFF1E88E5.toInt(),
    @ColumnInfo(defaultValue = "0") val parentZoneId: Long = 0,
)

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY name ASC")
    fun getAllZones(): Flow<List<Zone>>

    @Query("SELECT * FROM zones ORDER BY name ASC")
    suspend fun getAllZonesOnce(): List<Zone>

    @Insert
    suspend fun insertZone(zone: Zone): Long

    @Update
    suspend fun updateZone(zone: Zone)

    @Delete
    suspend fun deleteZone(zone: Zone)

    @Query("DELETE FROM zones")
    suspend fun deleteAll()
}
