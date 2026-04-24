package com.taxipro.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxipro.data.db.AppDatabase
import com.taxipro.data.db.AppSettings
import com.taxipro.data.export.BackupManager
import com.taxipro.data.export.PdfExportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── State ─────────────────────────────────────────────────────────

sealed class ExportState {
    object Idle           : ExportState()
    object Working        : ExportState()
    object RestoreSuccess : ExportState()
    data class ShareReady(
        val uri      : Uri,
        val mimeType : String,
        val fileName : String,
    ) : ExportState()
    data class Failure(val message: String) : ExportState()
}

// ── ViewModel ─────────────────────────────────────────────────────

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val pdfManager    = PdfExportManager(app)
    private val backupManager = BackupManager(app)

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    fun resetState() { _state.value = ExportState.Idle }

    /** Generate a PDF report of all rides & shifts and emit a shareable URI. */
    fun exportPdf(settings: AppSettings) = viewModelScope.launch {
        _state.value = ExportState.Working
        try {
            val db     = AppDatabase.getInstance(getApplication())
            val rides  = db.rideDao().getAllRidesOnce()
            val shifts = db.shiftDao().getAllShiftsOnce()
            val uri    = pdfManager.exportToPdf(rides, shifts, settings)
            _state.value = ExportState.ShareReady(uri, "application/pdf", "TaxiPro_Report.pdf")
        } catch (e: Exception) {
            _state.value = ExportState.Failure(e.message ?: "Unknown error")
        }
    }

    /** Serialize all DB data to a .taxipro backup file and emit a shareable URI. */
    fun createBackup() = viewModelScope.launch {
        _state.value = ExportState.Working
        try {
            val uri = backupManager.createBackup()
            _state.value = ExportState.ShareReady(uri, "application/octet-stream", "TaxiPro_Backup.taxipro")
        } catch (e: Exception) {
            _state.value = ExportState.Failure(e.message ?: "Unknown error")
        }
    }

    /** Read a .taxipro file URI, wipe existing DB, and restore from backup. */
    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        _state.value = ExportState.Working
        try {
            backupManager.restoreBackup(uri)
            _state.value = ExportState.RestoreSuccess
        } catch (e: Exception) {
            _state.value = ExportState.Failure(e.message ?: "Unknown error")
        }
    }
}
