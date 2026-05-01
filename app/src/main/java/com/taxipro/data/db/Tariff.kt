package com.taxipro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tariffs")
data class Tariff(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String = "",
    val startFee: Double = 0.0,
    val pricePerKm: Double = 0.0,
    val pricePerMinute: Double = 0.0,
    val hourlyRate: Double = 0.0,
    val autoEnabled: Boolean = false,   // автоматично активиране в определен час
    val autoStartHour: Int = 22,        // от кой час
    val autoEndHour: Int = 6,           // до кой час
    // Per-tariff cost settings
    val waitThresholdKmh: Double = 0.0, // speed below which wait time is counted
    val taxPercent: Double = 0.0,       // tax & insurance %
    val fuelCostPerKm: Double = 0.0,    // fuel cost per km
)

@Dao
interface TariffDao {
    @Query("SELECT * FROM tariffs ORDER BY id ASC")
    fun getAll(): Flow<List<Tariff>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tariff: Tariff): Long

    @Delete
    suspend fun delete(tariff: Tariff)

    @Query("DELETE FROM tariffs")
    suspend fun deleteAll()
}
