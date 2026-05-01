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

    companion object {
        private const val PAGE_W = 595
        private const val PAGE_H = 842
        private const val ML = 40f
        private const val MR = 40f
        private const val MT = 40f
        private const val MB = 48f
    }

    // ── Paints ─────────────────────────────────────────────────────────────
    private fun pTitle()   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
    private fun pSection() = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
    private fun pColHdr()  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f;  typeface = Typeface.DEFAULT_BOLD; color = Color.DKGRAY }
    private fun pNormal()  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f;  color = Color.DKGRAY }
    private fun pBold()    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f;  typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
    private fun pMono()    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f;  typeface = Typeface.MONOSPACE; color = Color.BLACK }
    private fun pSmall()   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f;  color = Color.GRAY }
    private fun pLine()    = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
    private fun pLineD()   = Paint().apply { color = Color.GRAY;   strokeWidth = 0.8f }
    private fun pAccent()  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f;  typeface = Typeface.MONOSPACE; color = Color.rgb(0, 120, 215) }
    private fun pGreen()   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f;  typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(30, 140, 60) }

    suspend fun exportToPdf(
        rides: List<Ride>,
        shifts: List<Shift>,
        settings: AppSettings,
    ): Uri = withContext(Dispatchers.IO) {

        val pdf      = PdfDocument()
        val dateFmt  = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val timeFmt  = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dtFmt    = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
        val sdf      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // ── Page state ──────────────────────────────────────────────────────
        var pageNum = 1
        var page    = openPage(pdf, pageNum)
        var canvas  = page.canvas
        var y       = MT

        fun hRule(heavy: Boolean = false) {
            canvas.drawLine(ML, y, PAGE_W - MR, y, if (heavy) pLineD() else pLine())
            y += 3f
        }
        fun gap(pts: Float = 8f) { y += pts }

        fun finishPage() {
            // page footer: page number centred
            val fp = pSmall().apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("— $pageNum —", PAGE_W / 2f, PAGE_H - MB / 2f, fp)
            pdf.finishPage(page)
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_H - MB) {
                finishPage()
                pageNum++
                page   = openPage(pdf, pageNum)
                canvas = page.canvas
                y      = MT
            }
        }

        // ── Computed stats ──────────────────────────────────────────────────
        val completedShifts = shifts.filter { !it.isActive }.sortedByDescending { it.startTime }
        val totalRevenue    = rides.sumOf { it.price }
        val totalTips       = rides.sumOf { it.tip }
        val totalGrand      = totalRevenue + totalTips
        val totalClientKm   = rides.sumOf { it.kilometers }
        val totalShiftKm    = completedShifts.sumOf { it.totalKm }.let { if (it < 1.0) totalClientKm else it }
        val cardRides       = rides.filter { it.paymentMethod == "CARD" }
        val cashRides       = rides.filter { it.paymentMethod != "CARD" }
        val cardPct         = if (rides.isNotEmpty()) cardRides.size * 100 / rides.size else 0
        val cardRev         = cardRides.sumOf { it.price + it.tip }
        val cashRev         = cashRides.sumOf { it.price + it.tip }
        val avgPerRide      = if (rides.isNotEmpty()) totalGrand / rides.size else 0.0
        val avgFuelRate     = if (rides.isNotEmpty()) rides.sumOf { it.fuelCostPerKm } / rides.size else 0.0
        val totalFuelCost   = totalShiftKm * avgFuelRate
        val totalTax        = rides.sumOf { it.price * it.taxPercent / 100.0 }
        val netProfit       = totalGrand - totalFuelCost - totalTax

        // best day of week
        val dowRev = DoubleArray(7)
        val dowLabels = arrayOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
        rides.forEach { r ->
            val d = (Calendar.getInstance().apply { timeInMillis = r.startTime }
                .get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            dowRev[d] += r.price + r.tip
        }
        val bestDow = dowRev.indices.maxByOrNull { dowRev[it] } ?: 0

        // total shift time (hours)
        val totalShiftMs = completedShifts.sumOf { if (it.endTime > 0) it.endTime - it.startTime else 0L }
        val totalShiftH  = totalShiftMs / 3_600_000.0
        val avgPerHour   = if (totalShiftH > 0.1) totalGrand / totalShiftH else 0.0

        // date range
        val firstRideDate = rides.minOfOrNull { it.startTime }
        val lastRideDate  = rides.maxOfOrNull { it.startTime }

        // ── PAGE 1 — Header ─────────────────────────────────────────────────
        canvas.drawText("TaxiPro", ML, y + 20f, pTitle())
        val subP = pNormal().apply { textSize = 10f }
        canvas.drawText("Driver Report", ML + 90f, y + 20f, subP)
        val rightP = pSmall().apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("Generated: ${dtFmt.format(Date())}", PAGE_W - MR, y + 20f, rightP)
        y += 28f

        if (firstRideDate != null && lastRideDate != null) {
            canvas.drawText(
                "Period: ${dateFmt.format(Date(firstRideDate))} – ${dateFmt.format(Date(lastRideDate))}",
                ML, y, pSmall()
            )
            y += 5f
        }
        hRule(heavy = true)
        gap(10f)

        // ── SECTION 1: Financial Summary ────────────────────────────────────
        canvas.drawText("FINANCIAL SUMMARY", ML, y, pSection())
        y += 14f

        // Two-column summary
        val col1x = ML
        val col2x = ML + 258f

        fun kv2(l1: String, v1: String, l2: String, v2: String, p1: Paint = pMono(), p2: Paint = pMono()) {
            canvas.drawText(l1, col1x, y, pNormal())
            canvas.drawText(v1, col1x + 140f, y, p1)
            canvas.drawText(l2, col2x, y, pNormal())
            canvas.drawText(v2, col2x + 140f, y, p2)
            y += 13f
        }

        kv2("Total rides",   "${rides.size}",                        "Completed shifts", "${completedShifts.size}")
        kv2("Gross revenue", settings.formatPrice(totalRevenue),     "Tips received",    settings.formatPrice(totalTips))
        kv2("Grand total",   settings.formatPrice(totalGrand),       "Net profit",       settings.formatPrice(netProfit), pGreen(), pGreen())
        kv2("Fuel cost (est.)", settings.formatPrice(totalFuelCost), "Tax cost (est.)",  settings.formatPrice(totalTax), pNormal(), pNormal())
        kv2("Client km",     "%.1f km".format(totalClientKm),        "Total shift km",  "%.1f km".format(totalShiftKm))
        kv2("Avg per ride",  settings.formatPrice(avgPerRide),       "Avg per hour",    settings.formatPrice(avgPerHour))
        kv2("Best day",      dowLabels[bestDow],                     "Best day revenue", settings.formatPrice(dowRev[bestDow]))

        gap(4f)
        hRule()
        gap(8f)

        // ── SECTION 2: Payment Breakdown ────────────────────────────────────
        canvas.drawText("PAYMENT BREAKDOWN", ML, y, pSection())
        y += 14f

        kv2("Card rides",  "${cardRides.size}  ($cardPct%)",       "Cash rides",  "${cashRides.size}  (${100-cardPct}%)")
        kv2("Card revenue", settings.formatPrice(cardRev),          "Cash revenue", settings.formatPrice(cashRev))

        gap(4f)
        hRule()
        gap(10f)

        // ── SECTION 3: Ride History Table ───────────────────────────────────
        canvas.drawText("RIDE HISTORY  (${rides.size} rides, newest first)", ML, y, pSection())
        y += 14f

        val cDate  = ML
        val cRoute = ML + 90f
        val cDist  = ML + 292f
        val cWait  = ML + 337f
        val cPay   = ML + 375f
        val cFare  = ML + 415f
        val cTotal = ML + 462f

        fun tableHeader() {
            canvas.drawText("Date/Time",  cDate,  y, pColHdr())
            canvas.drawText("Route",      cRoute, y, pColHdr())
            canvas.drawText("km",         cDist,  y, pColHdr())
            canvas.drawText("Wait",       cWait,  y, pColHdr())
            canvas.drawText("Pay",        cPay,   y, pColHdr())
            canvas.drawText("Fare",       cFare,  y, pColHdr())
            canvas.drawText("Total",      cTotal, y, pColHdr())
            y += 3f; hRule(); y += 3f
        }
        tableHeader()

        rides.sortedByDescending { it.startTime }.forEachIndexed { idx, ride ->
            ensureSpace(14f)
            if (y == MT) tableHeader()

            val route = when {
                ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
                    ride.fromAddress.substringBefore(",").take(14) + "→" +
                    ride.toAddress.substringBefore(",").take(14)
                ride.fromAddress.isNotEmpty() -> ride.fromAddress.substringBefore(",").take(28)
                else -> "#${ride.globalId}"
            }
            val waitStr = if (ride.waitMinutes > 0) "%.0fmin".format(ride.waitMinutes) else "—"

            canvas.drawText("${dateFmt.format(Date(ride.startTime))} ${timeFmt.format(Date(ride.startTime))}", cDate, y, pSmall())
            canvas.drawText(route,                                  cRoute, y, pNormal())
            canvas.drawText("%.1f".format(ride.kilometers),         cDist,  y, pMono())
            canvas.drawText(waitStr,                                cWait,  y, pSmall())
            canvas.drawText(if (ride.paymentMethod == "CARD") "Card" else "Cash", cPay, y, pNormal())
            canvas.drawText(settings.formatPrice(ride.price),       cFare,  y, pMono())
            val totalPaint = if (ride.tip > 0) pAccent() else pMono()
            canvas.drawText(settings.formatPrice(ride.price + ride.tip), cTotal, y, totalPaint)
            y += 12f

            if (idx < rides.size - 1) {
                canvas.drawLine(ML, y - 3f, PAGE_W - MR, y - 3f, pLine().apply { alpha = 60 })
            }
        }

        gap(8f)
        hRule(heavy = true)
        gap(10f)

        // ── SECTION 4: Shift Breakdown ──────────────────────────────────────
        if (completedShifts.isNotEmpty()) {
            ensureSpace(50f)
            canvas.drawText("SHIFT BREAKDOWN  (${completedShifts.size} completed shifts)", ML, y, pSection())
            y += 14f

            completedShifts.forEach { shift ->
                ensureSpace(56f)

                val shiftRides  = rides.filter { it.shiftId == shift.id }
                val sRev        = shiftRides.sumOf { it.price }
                val sTips       = shiftRides.sumOf { it.tip }
                val sGrand      = sRev + sTips
                val sFuelCost   = shift.totalKm * avgFuelRate
                val sTax        = shiftRides.sumOf { it.price * it.taxPercent / 100.0 }
                val sNet        = sGrand - sFuelCost - sTax
                val sCardRides  = shiftRides.count { it.paymentMethod == "CARD" }
                val sCardPct    = if (shiftRides.isNotEmpty()) sCardRides * 100 / shiftRides.size else 0
                val dMs         = if (shift.endTime > 0) shift.endTime - shift.startTime else 0L
                val dH          = dMs / 3_600_000L
                val dMin        = (dMs % 3_600_000L) / 60_000L
                val dHd         = dMs / 3_600_000.0
                val sAvgHour    = if (dHd > 0.01) sGrand / dHd else 0.0
                val sAvgRide    = if (shiftRides.isNotEmpty()) sGrand / shiftRides.size else 0.0

                // Shift header bar
                val shP = pBold().apply { textSize = 10f }
                canvas.drawText(
                    "Shift #${shift.shiftNumber}   ${dateFmt.format(Date(shift.startTime))}  " +
                    "${timeFmt.format(Date(shift.startTime))} – ${timeFmt.format(Date(shift.endTime))}   " +
                    "${dH}h ${dMin}min",
                    ML, y, shP
                )
                y += 13f
                kv2("Rides",         "${shiftRides.size}",              "Total km",   "%.1f km".format(shift.totalKm))
                kv2("Revenue",       settings.formatPrice(sRev),        "Tips",       settings.formatPrice(sTips))
                kv2("Grand total",   settings.formatPrice(sGrand),      "Net profit", settings.formatPrice(sNet), pGreen(), pGreen())
                kv2("Avg/ride",      settings.formatPrice(sAvgRide),    "Avg/hour",   settings.formatPrice(sAvgHour))
                kv2("Card",         "$sCardRides rides ($sCardPct%)",   "Cash",       "${shiftRides.size - sCardRides} rides (${100-sCardPct}%)")
                y += 2f
                hRule()
                gap(6f)
            }
        }

        // ── Done ────────────────────────────────────────────────────────────
        finishPage()

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
