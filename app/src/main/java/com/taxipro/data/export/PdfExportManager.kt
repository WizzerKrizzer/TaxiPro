package com.taxipro.data.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.taxipro.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PdfExportManager(private val context: Context) {

    // ── Page geometry ─────────────────────────────────────────────
    companion object {
        private const val PAGE_W  = 595   // A4 portrait width  (pt)
        private const val PAGE_H  = 842   // A4 portrait height (pt)
        private const val ML      = 40f   // left  margin
        private const val MR      = 40f   // right margin
        private const val MT      = 40f   // top   margin
        private const val MB      = 40f   // bottom margin
        private val BODY_W        = PAGE_W - ML - MR   // 515f
    }

    // ── Paints ────────────────────────────────────────────────────
    private fun titlePaint()  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 18f
        typeface  = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        color     = Color.BLACK
    }
    private fun sectionPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 11f
        typeface  = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        color     = Color.BLACK
    }
    private fun normalPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 9f
        color     = Color.DKGRAY
    }
    private fun monoPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 9f
        typeface  = Typeface.MONOSPACE
        color     = Color.BLACK
    }
    private fun smallPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 8f
        color     = Color.GRAY
    }
    private fun linePaint()  = Paint().apply {
        color       = Color.LTGRAY
        strokeWidth = 0.5f
    }
    private fun accentPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 9f
        typeface  = Typeface.MONOSPACE
        color     = Color.rgb(0, 120, 215)
    }

    // ── Public API ────────────────────────────────────────────────

    suspend fun exportToPdf(
        rides   : List<Ride>,
        shifts  : List<Shift>,
        settings: AppSettings,
    ): Uri = withContext(Dispatchers.IO) {

        val pdf        = PdfDocument()
        val dateFmt    = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val timeFmt    = SimpleDateFormat("HH:mm",       Locale.getDefault())
        val datetimeFmt = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

        // ── Mutable page state ────────────────────────────────────
        var pageNum = 1
        var page    = openPage(pdf, pageNum)
        var canvas  = page.canvas
        var y       = MT

        fun finishCurrentPage() { pdf.finishPage(page) }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_H - MB) {
                finishCurrentPage()
                pageNum++
                page   = openPage(pdf, pageNum)
                canvas = page.canvas
                y      = MT
            }
        }

        fun hRule() {
            canvas.drawLine(ML, y, PAGE_W - MR, y, linePaint())
            y += 2f
        }

        fun gap(pts: Float = 8f) { y += pts }

        // ── Title ─────────────────────────────────────────────────
        canvas.drawText("TaxiPro — Ride Report", ML, y + 18f, titlePaint())
        y += 24f
        canvas.drawText(
            "Generated on ${datetimeFmt.format(Date())}",
            ML, y, smallPaint()
        )
        y += 6f
        hRule()
        gap(10f)

        // ── Summary ───────────────────────────────────────────────
        canvas.drawText("SUMMARY", ML, y, sectionPaint())
        y += 14f

        val totalRev  = rides.sumOf { it.price }
        val totalTips = rides.sumOf { it.tip }
        val totalKm   = rides.sumOf { it.kilometers }
        val cardCount = rides.count { it.paymentMethod == "CARD" }
        val completedShifts = shifts.filter { !it.isActive }

        val summaryRows = listOf(
            "Total rides"          to "${rides.size}",
            "Completed shifts"     to "${completedShifts.size}",
            "Total revenue"        to settings.formatPrice(totalRev),
            "Total tips"           to settings.formatPrice(totalTips),
            "Grand total"          to settings.formatPrice(totalRev + totalTips),
            "Total distance"       to "%.1f ${settings.distanceUnit.shortLabel}".format(totalKm),
            "Card payments"        to "$cardCount rides",
        )
        val labelX = ML
        val valueX = ML + 160f
        summaryRows.forEach { (label, value) ->
            canvas.drawText(label, labelX, y, normalPaint())
            canvas.drawText(value, valueX, y, monoPaint())
            y += 13f
        }
        gap(6f)
        hRule()
        gap(10f)

        // ── Ride table ────────────────────────────────────────────
        canvas.drawText(
            "RIDE HISTORY  (${rides.size} rides, most recent first)",
            ML, y, sectionPaint()
        )
        y += 14f

        // Column X positions
        val cDate  = ML
        val cRoute = ML  + 92f
        val cDist  = ML  + 297f
        val cPay   = ML  + 347f
        val cFare  = ML  + 397f
        val cTotal = ML  + 452f

        fun tableHeader() {
            val hp = sectionPaint().apply { textSize = 8f }
            canvas.drawText("Date / Time", cDate,  y, hp)
            canvas.drawText("Route",       cRoute, y, hp)
            canvas.drawText("km",          cDist,  y, hp)
            canvas.drawText("Pay",         cPay,   y, hp)
            canvas.drawText("Fare",        cFare,  y, hp)
            canvas.drawText("Total",       cTotal, y, hp)
            y += 3f
            hRule()
            y += 4f
        }
        tableHeader()

        val sorted = rides.sortedByDescending { it.startTime }
        sorted.forEachIndexed { idx, ride ->
            ensureSpace(16f)
            // New page → reprint header
            if (y == MT) tableHeader()

            val dateStr  = dateFmt.format(Date(ride.startTime))
            val timeStr  = timeFmt.format(Date(ride.startTime))
            val route = when {
                ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                    ride.fromAddress.substringBefore(",").take(16) + "→" +
                    ride.toAddress.substringBefore(",").take(16)
                ride.fromAddress.isNotEmpty() ->
                    ride.fromAddress.substringBefore(",").take(32)
                else -> "Ride #${ride.globalId}"
            }
            val dist  = "%.1f".format(ride.kilometers)
            val pay   = if (ride.paymentMethod == "CARD") "Card" else "Cash"
            val fare  = settings.formatPrice(ride.price)
            val total = settings.formatPrice(ride.price + ride.tip)

            canvas.drawText("$dateStr $timeStr", cDate,  y, smallPaint())
            canvas.drawText(route,               cRoute, y, normalPaint())
            canvas.drawText(dist,                cDist,  y, monoPaint())
            canvas.drawText(pay,                 cPay,   y, normalPaint())
            canvas.drawText(fare,                cFare,  y, monoPaint())
            canvas.drawText(total,               cTotal, y, if (ride.tip > 0) accentPaint() else monoPaint())
            y += 12f

            if (idx < sorted.lastIndex) {
                canvas.drawLine(ML, y - 3f, PAGE_W - MR, y - 3f, linePaint().apply { alpha = 80 })
            }
        }

        gap(6f)
        hRule()
        gap(10f)

        // ── Shift summary section ─────────────────────────────────
        if (completedShifts.isNotEmpty()) {
            ensureSpace(40f)
            canvas.drawText(
                "SHIFT SUMMARIES  (${completedShifts.size} shifts)",
                ML, y, sectionPaint()
            )
            y += 14f

            completedShifts.sortedByDescending { it.startTime }.forEach { shift ->
                ensureSpace(36f)
                val shiftRides  = rides.filter { it.shiftId == shift.id }
                val shiftRev    = shiftRides.sumOf { it.price + it.tip }
                val dMs         = if (shift.endTime > 0) shift.endTime - shift.startTime else 0L
                val dH          = dMs / 3_600_000L
                val dMin        = (dMs % 3_600_000L) / 60_000L
                val startStr    = datetimeFmt.format(Date(shift.startTime))

                canvas.drawText(
                    "Shift #${shift.shiftNumber}  |  $startStr  |  ${dH}h ${dMin}min",
                    ML, y, normalPaint().apply { typeface = Typeface.DEFAULT_BOLD; color = Color.DKGRAY }
                )
                y += 13f
                canvas.drawText(
                    "  Rides: ${shiftRides.size}   Revenue: ${settings.formatPrice(shiftRev)}" +
                    "   Total km: %.1f".format(shift.totalKm),
                    ML, y, smallPaint()
                )
                y += 10f
                canvas.drawLine(ML, y - 2f, PAGE_W - MR, y - 2f, linePaint().apply { alpha = 60 })
                y += 6f
            }
        }

        // ── Footer on last page ───────────────────────────────────
        finishCurrentPage()

        // ── Write to file ─────────────────────────────────────────
        val sdf  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val file = File(context.cacheDir, "TaxiPro_Report_${sdf.format(Date())}.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun openPage(pdf: PdfDocument, number: Int): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()
        return pdf.startPage(info)
    }
}
