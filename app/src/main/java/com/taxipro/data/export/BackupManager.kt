package com.taxipro.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.taxipro.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Backup container ──────────────────────────────────────────────

private data class TaxiProBackup(
    val version        : Int                  = 2,
    val createdAt      : Long                 = System.currentTimeMillis(),
    val rides          : List<Ride>           = emptyList(),
    val shifts         : List<Shift>          = emptyList(),
    val tariffs        : List<Tariff>         = emptyList(),
    val tariffExpenses : List<TariffExpense>  = emptyList(),
    val zones          : List<Zone>           = emptyList(),
)

// ── Manager ───────────────────────────────────────────────────────

class BackupManager(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Collects all data, serialises to JSON, writes to cache, returns a shareable URI. */
    suspend fun createBackup(): Uri = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val backup = TaxiProBackup(
            rides          = db.rideDao().getAllRidesOnce(),
            shifts         = db.shiftDao().getAllShiftsOnce(),
            tariffs        = db.tariffDao().getAll().first(),
            tariffExpenses = db.tariffExpenseDao().getAll().first(),
            zones          = db.zoneDao().getAllZonesOnce(),
        )
        val sdf  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val file = File(context.cacheDir, "TaxiPro_Backup_${sdf.format(Date())}.taxipro")
        file.writeText(gson.toJson(backup))
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Reads a backup from the given content URI, wipes all existing data,
     * then re-inserts everything from the backup (preserving original IDs).
     */
    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.readText()
            ?: error("Cannot open backup file")

        val backup = gson.fromJson(json, TaxiProBackup::class.java)
            ?: error("Invalid backup format")

        val db = AppDatabase.getInstance(context)

        // Clear existing data (order matters — rides reference shifts)
        db.rideDao().deleteAll()
        db.shiftDao().deleteAll()
        db.tariffExpenseDao().deleteAll()
        db.tariffDao().deleteAll()
        db.zoneDao().deleteAll()

        // Restore (preserve original IDs so cross-references stay valid)
        backup.zones.forEach          { db.zoneDao().insertZone(it) }
        backup.tariffs.forEach        { db.tariffDao().upsert(it) }
        backup.tariffExpenses.forEach { db.tariffExpenseDao().upsert(it) }
        backup.shifts.forEach         { db.shiftDao().insertShift(it) }
        backup.rides.forEach          { db.rideDao().insertRide(it) }
    }
}
