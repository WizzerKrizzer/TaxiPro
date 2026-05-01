package com.taxipro.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Enums ────────────────────────────────────────────────────────

enum class ExpenseFrequency(val code: String) {
    PER_RIDE("PER_RIDE"),
    PER_SHIFT("PER_SHIFT"),
    PER_MONTH("PER_MONTH");
    companion object { fun from(s: String) = entries.firstOrNull { it.code == s } ?: PER_MONTH }
}

enum class ExpenseType(val code: String) {
    FIXED("FIXED"),
    PERCENT("PERCENT");
    companion object { fun from(s: String) = entries.firstOrNull { it.code == s } ?: FIXED }
}

// ── Entity ───────────────────────────────────────────────────────

@Entity(tableName = "tariff_expenses")
data class TariffExpense(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val tariffId: Int = 0,
    val name: String = "",
    val frequency: String = ExpenseFrequency.PER_MONTH.code,  // ExpenseFrequency.code
    val type: String = ExpenseType.FIXED.code,                 // ExpenseType.code
    val amount: Double = 0.0,
)

val TariffExpense.freq    get() = ExpenseFrequency.from(frequency)
val TariffExpense.expType get() = ExpenseType.from(type)

// ── DAO ──────────────────────────────────────────────────────────

@Dao
interface TariffExpenseDao {
    @Query("SELECT * FROM tariff_expenses ORDER BY tariffId ASC, id ASC")
    fun getAll(): Flow<List<TariffExpense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: TariffExpense): Long

    @Delete
    suspend fun delete(expense: TariffExpense)

    @Query("DELETE FROM tariff_expenses WHERE tariffId = :tariffId")
    suspend fun deleteByTariff(tariffId: Int)

    @Query("DELETE FROM tariff_expenses")
    suspend fun deleteAll()
}
