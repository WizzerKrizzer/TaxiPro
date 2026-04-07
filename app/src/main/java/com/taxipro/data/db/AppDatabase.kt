package com.taxipro.data.db

import android.content.Context
import androidx.room.*

@Database(entities = [Ride::class, Tariff::class, Shift::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun tariffDao(): TariffDao
    abstract fun shiftDao(): ShiftDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "taxipro.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
