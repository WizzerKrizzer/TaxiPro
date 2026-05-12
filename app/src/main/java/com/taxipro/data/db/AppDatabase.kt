package com.taxipro.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Ride::class, Tariff::class, Shift::class, TariffExpense::class, Zone::class, ZoneWaitSession::class], version = 13, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun tariffDao(): TariffDao
    abstract fun shiftDao(): ShiftDao
    abstract fun tariffExpenseDao(): TariffExpenseDao
    abstract fun zoneDao(): ZoneDao
    abstract fun zoneWaitDao(): ZoneWaitDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN totalKm REAL NOT NULL DEFAULT 0")
            }
        }

        // Fixes DEFAULT 0.0 → DEFAULT 0 by recreating the shifts table
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE shifts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        shiftNumber INTEGER NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        totalKm REAL NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO shifts_new SELECT id, shiftNumber, startTime, endTime, isActive, totalKm FROM shifts")
                db.execSQL("DROP TABLE shifts")
                db.execSQL("ALTER TABLE shifts_new RENAME TO shifts")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS zones (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        pointsJson TEXT NOT NULL,
                        color INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN adjustmentKm REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN isInternal INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'CASH'")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS zone_wait_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        shiftId INTEGER NOT NULL,
                        zoneId INTEGER NOT NULL,
                        zoneName TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "taxipro.db")
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
