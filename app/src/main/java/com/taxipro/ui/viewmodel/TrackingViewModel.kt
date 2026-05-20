package com.taxipro.ui.viewmodel

import android.app.Application
import android.content.*
import android.location.Geocoder
import androidx.lifecycle.*
import com.taxipro.data.db.*
import com.taxipro.data.db.Tariff
import com.taxipro.service.GpsTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

data class TrackingState(
    val isTracking: Boolean         = false,
    val isPaused: Boolean           = false,
    val isShiftPaused: Boolean      = false,
    val shiftPausedMs: Long         = 0L,
    val currentShiftPauseStartedAt: Long = 0L,
    val currentLat: Double          = 0.0,
    val currentLng: Double          = 0.0,
    val speedKmh: Double            = 0.0,
    val totalKm: Double             = 0.0,
    val shiftTotalKm: Double        = 0.0,      // Total km driven during entire shift
    val waitMinutes: Double         = 0.0,
    val waitSeconds: Double         = 0.0,
    val elapsedSeconds: Long        = 0L,
    val currentPrice: Double        = 0.0,
    val fareAdjustment: Double      = 0.0,
    val isWaiting: Boolean          = false,
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val startTime: Long             = 0L,
    val activeTaxPercent: Double    = 0.0,
    val activeFuelCostPerKm: Double = 0.0,
    // Active pricing rates — set at ride start so UI can show/diagnose them
    val activePricePerKm: Double    = 0.0,
    val activePricePerMin: Double   = 0.0,
    val activeStartFee: Double      = 0.0,
    val activeTariffId: Int         = 0,
)



class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val db           = AppDatabase.getInstance(app)
    private val shiftPauseDao = db.shiftPauseDao()
    private val settingsRepo = SettingsRepository(app)
    private val _state       = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    val tariffs: StateFlow<List<Tariff>> = db.tariffDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allExpenses: StateFlow<List<TariffExpense>> = db.tariffExpenseDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveTariffWithExpenses(tariff: Tariff, expenses: List<TariffExpense>) =
        viewModelScope.launch {
            val savedId = db.tariffDao().upsert(tariff).toInt()
            val actualId = if (tariff.id == 0) savedId else tariff.id
            db.tariffExpenseDao().deleteByTariff(actualId)
            expenses.forEach { db.tariffExpenseDao().upsert(it.copy(tariffId = actualId)) }
        }

    fun deleteTariff(t: Tariff) = viewModelScope.launch {
        db.tariffExpenseDao().deleteByTariff(t.id)
        db.tariffDao().delete(t)
    }

    fun clearAllData() = viewModelScope.launch {
        db.rideDao().deleteAll()
        db.zoneWaitDao().deleteAll()
        db.shiftDao().deleteAll()
        db.tariffDao().deleteAll()
        db.tariffExpenseDao().deleteAll()
    }

    fun convertAllRides(factor: Double, onComplete: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val rides = db.rideDao().getAllRidesOnce()
            rides.forEach { ride ->
                db.rideDao().updateRide(ride.copy(price = ride.price * factor, tip = ride.tip * factor))
            }
        }
        onComplete()
    }

    /** Convert distance fields in all rides and shifts (km↔miles). factor = new_per_old. */
    fun convertAllDistances(factor: Double, onComplete: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val rides = db.rideDao().getAllRidesOnce()
            rides.forEach { ride ->
                db.rideDao().updateRide(ride.copy(
                    kilometers    = ride.kilometers   * factor,
                    adjustmentKm  = ride.adjustmentKm * factor,
                    // cost-per-unit-distance inverts: if factor=0.621 (km→mi), cost-per-mi = cost-per-km / 0.621
                    fuelCostPerKm = if (ride.fuelCostPerKm > 0) ride.fuelCostPerKm / factor else 0.0,
                    avgSpeed      = ride.avgSpeed     * factor,
                    maxSpeed      = ride.maxSpeed     * factor,
                ))
            }
            val shifts = db.shiftDao().getAllShiftsOnce()
            shifts.forEach { shift ->
                db.shiftDao().updateShift(shift.copy(totalKm = shift.totalKm * factor))
            }
        }
        onComplete()
    }

    // ── Shift management ─────────────────────────────────────
    val activeShift: StateFlow<Shift?> = db.shiftDao().getActiveShift()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _lastEndedShift = MutableStateFlow<Shift?>(null)
    val lastEndedShift: StateFlow<Shift?> = _lastEndedShift.asStateFlow()

    fun startShift() = viewModelScope.launch {
        val dao   = db.shiftDao()
        val count = dao.getTotalCount()
        val shiftId = dao.insertShift(Shift(shiftNumber = count + 1))
        // Reset shift km counter and pause state, start shift-level GPS tracking
        _state.update {
            it.copy(
                shiftTotalKm = 0.0,
                isShiftPaused = false,
                shiftPausedMs = 0L,
                currentShiftPauseStartedAt = 0L,
            )
        }
        shiftPauseStartMs = 0L
        sendAction(GpsTrackingService.ACTION_SHIFT_START, shiftId)
    }

    fun endShift() = viewModelScope.launch {
        val dao    = db.shiftDao()
        val active = activeShift.value ?: return@launch
        if (_state.value.isShiftPaused && shiftPauseStartMs > 0L) {
            val pauseEnd = System.currentTimeMillis()
            val dur = pauseEnd - shiftPauseStartMs
            persistShiftPauseSession(active.id, shiftPauseStartMs, pauseEnd)
            _state.update {
                it.copy(
                    isShiftPaused = false,
                    shiftPausedMs = it.shiftPausedMs + dur,
                    currentShiftPauseStartedAt = 0L,
                )
            }
            shiftPauseStartMs = 0L
        }
        sendAction(GpsTrackingService.ACTION_SHIFT_STOP, active.id)
        delay(250)
        val ended  = active.copy(
            endTime  = System.currentTimeMillis(),
            isActive = false,
            totalKm  = _state.value.shiftTotalKm,
        )
        dao.updateShift(ended)
        _lastEndedShift.value = ended
    }

    fun clearLastEndedShift() { _lastEndedShift.value = null }

    // ── Shift pause / resume ──────────────────────────────────

    private var shiftPauseStartMs = 0L

    fun pauseShift() {
        shiftPauseStartMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                isShiftPaused = true,
                currentShiftPauseStartedAt = shiftPauseStartMs,
            )
        }
        sendAction(GpsTrackingService.ACTION_SHIFT_PAUSE)
    }

    fun resumeShift() {
        val activeShiftId = activeShift.value?.id ?: 0L
        val dur = if (shiftPauseStartMs > 0L) System.currentTimeMillis() - shiftPauseStartMs else 0L
        if (activeShiftId > 0L && shiftPauseStartMs > 0L && dur > 0L) {
            viewModelScope.launch {
                persistShiftPauseSession(activeShiftId, shiftPauseStartMs, shiftPauseStartMs + dur)
            }
        }
        shiftPauseStartMs = 0L
        _state.update {
            it.copy(
                isShiftPaused = false,
                shiftPausedMs = it.shiftPausedMs + dur,
                currentShiftPauseStartedAt = 0L,
            )
        }
        sendAction(GpsTrackingService.ACTION_SHIFT_RESUME)
    }

    private suspend fun persistShiftPauseSession(shiftId: Long, startMs: Long, endMs: Long) {
        if (shiftId <= 0L || endMs <= startMs) return
        shiftPauseDao.insertSession(
            ShiftPauseSession(
                shiftId = shiftId,
                startTime = startMs,
                endTime = endMs,
                durationMs = endMs - startMs,
            )
        )
    }

    // BroadcastReceiver — receives data from GpsTrackingService
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val flatRoute = intent.getDoubleArrayExtra("route_points") ?: doubleArrayOf()
            val points    = (flatRoute.indices step 2).map { i ->
                Pair(flatRoute[i], flatRoute[i + 1])
            }
            _state.update { s -> s.copy(
                currentLat   = intent.getDoubleExtra(GpsTrackingService.EXTRA_LAT,      s.currentLat),
                currentLng   = intent.getDoubleExtra(GpsTrackingService.EXTRA_LNG,      s.currentLng),
                speedKmh     = intent.getDoubleExtra(GpsTrackingService.EXTRA_SPEED,    s.speedKmh),
                totalKm      = intent.getDoubleExtra(GpsTrackingService.EXTRA_KM,       s.totalKm),
                shiftTotalKm = intent.getDoubleExtra(GpsTrackingService.EXTRA_SHIFT_KM, s.shiftTotalKm),
                waitMinutes  = intent.getDoubleExtra(GpsTrackingService.EXTRA_WAIT_MIN, s.waitMinutes),
                currentPrice = intent.getDoubleExtra(GpsTrackingService.EXTRA_PRICE,    s.currentPrice),
                isWaiting    = intent.getBooleanExtra(GpsTrackingService.EXTRA_IS_WAITING, s.isWaiting),
                routePoints  = if (points.isNotEmpty()) points else s.routePoints,
                waitSeconds    = intent.getDoubleExtra("wait_seconds",    s.waitSeconds),
                elapsedSeconds = intent.getLongExtra("elapsed_seconds",   s.elapsedSeconds),
            )}
        }
    }

    init {
        val filter = IntentFilter(GpsTrackingService.BROADCAST_UPDATE)
        getApplication<Application>().registerReceiver(receiver, filter,
            Context.RECEIVER_NOT_EXPORTED)
    }

    fun startRide(tariff: Tariff? = null) {
        val s = settings.value
        // Helper: use tariff value when it's explicitly > 0; otherwise fall back to global setting.
        // The plain `?:` operator only triggers on null, not on 0.0 — so a tariff field left at
        // its default 0.0 would silently suppress the global setting without this guard.
        fun tariffOrGlobal(tariffVal: Double?, globalVal: Double) =
            if (tariffVal != null && tariffVal > 0.0) tariffVal else globalVal

        val rStartFee = tariffOrGlobal(tariff?.startFee,       s.startFee)
        val rPerKm    = tariffOrGlobal(tariff?.pricePerKm,     s.pricePerKm)
        val rPerMin   = tariffOrGlobal(tariff?.pricePerMinute, s.pricePerMinute)

        val intent = Intent(getApplication(), GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra("startFee",      rStartFee)
            putExtra("pricePerKm",    rPerKm)
            putExtra("pricePerMin",   rPerMin)
            putExtra("waitThreshold", tariff?.waitThresholdKmh ?: 0.0)
            putExtra("gpsInterval",   s.gpsIntervalMs)
            putExtra("gpsDistance",   s.gpsMinDistanceM)
        }
        getApplication<Application>().startForegroundService(intent)
        _state.update { it.copy(
            isTracking          = true,
            isPaused            = false,
            startTime           = System.currentTimeMillis(),
            activeTaxPercent    = tariffOrGlobal(tariff?.taxPercent,    s.taxPercent),
            activeFuelCostPerKm = tariffOrGlobal(tariff?.fuelCostPerKm, s.fuelCostPerKm),
            activePricePerKm    = rPerKm,
            activePricePerMin   = rPerMin,
            activeStartFee      = rStartFee,
            activeTariffId      = tariff?.id ?: 0,
        )}
    }

    fun pauseRide() {
        sendAction(GpsTrackingService.ACTION_PAUSE)
        _state.update { it.copy(isPaused = true) }
    }

    fun resumeRide() {
        sendAction(GpsTrackingService.ACTION_RESUME)
        _state.update { it.copy(isPaused = false) }
    }

    fun adjustFare(amount: Double) {
        _state.update { it.copy(fareAdjustment = it.fareAdjustment + amount) }
    }

    fun resetFareAdjustment() {
        _state.update { it.copy(fareAdjustment = 0.0) }
    }

    fun stopAndSaveRide(tip: Double = 0.0, currency: String = "BGN", paymentMethod: String = "CASH") {
        val s = _state.value
        sendAction(GpsTrackingService.ACTION_STOP)

        viewModelScope.launch {
            val dao      = db.rideDao()
            val now      = System.currentTimeMillis()
            val dayStart = startOfDay(now)
            val dayEnd   = dayStart + 86_400_000L
            val wkStart  = startOfWeek(now)
            val moStart  = startOfMonth(now)
            val yrStart  = startOfYear(now)

            // If fare was manually adjusted and the preference is enabled, proportionally
            // infer km and wait minutes from historical averages so stats remain consistent.
            var extraKm      = 0.0
            var extraWaitMin = 0.0
            if (s.fareAdjustment > 0.0 && settings.value.inferKmFromAdjustment) {
                val histRevenue = dao.getTotalRevenue()
                if (histRevenue > 0.0) {
                    val histKm   = dao.getTotalKm()
                    val histWait = dao.getTotalWaitMin()
                    extraKm      = s.fareAdjustment * (histKm   / histRevenue)
                    extraWaitMin = s.fareAdjustment * (histWait / histRevenue)
                }
            }

            val routeJson = s.routePoints.joinToString(",", "[", "]") {
                    (lat, lng) -> "[${lat},${lng}]" }

            val finalKm      = s.totalKm      + extraKm
            val finalWaitMin = s.waitMinutes  + extraWaitMin

            // Extract start/end coordinates from GPS route
            val fromLat = s.routePoints.firstOrNull()?.first  ?: 0.0
            val fromLng = s.routePoints.firstOrNull()?.second ?: 0.0
            val toLat   = s.routePoints.lastOrNull()?.first   ?: 0.0
            val toLng   = s.routePoints.lastOrNull()?.second  ?: 0.0

            // Detect internal ride (start and end in the same zone)
            val zones      = db.zoneDao().getAllZonesOnce()
            val fromZone   = findZone(fromLat, fromLng, zones)
            val toZone     = findZone(toLat,   toLng,   zones)
            val isInternal = fromZone != null && fromZone.id == toZone?.id

            // Reverse-geocode start and end points on IO thread
            val fromAddress = reverseGeocode(fromLat, fromLng)
            val toAddress   = reverseGeocode(toLat,   toLng)

            val ride = Ride(
                globalId        = dao.getTotalCount() + 1,
                dailyId         = dao.getDailyCount(dayStart, dayEnd) + 1,
                weeklyId        = dao.getWeeklyCount(wkStart, wkStart + 604_800_000L) + 1,
                monthlyId       = dao.getMonthlyCount(moStart, nextMonth(moStart)) + 1,
                yearlyId        = dao.getYearlyCount(yrStart, nextYear(yrStart)) + 1,
                date            = dayStart,
                startTime       = s.startTime,
                endTime         = now,
                fromLat         = fromLat,
                fromLng         = fromLng,
                toLat           = toLat,
                toLng           = toLng,
                fromAddress     = fromAddress,
                toAddress       = toAddress,
                kilometers      = finalKm,
                waitMinutes     = finalWaitMin,
                tip             = tip,
                price           = s.currentPrice + s.fareAdjustment,
                currency        = currency,
                routePointsJson = routeJson,
                avgSpeed        = if (finalKm > 0) finalKm / ((now - s.startTime) / 3_600_000.0) else 0.0,
                shiftId         = activeShift.value?.id ?: 0L,
                tariffId        = s.activeTariffId,
                taxPercent      = s.activeTaxPercent,
                fuelCostPerKm   = s.activeFuelCostPerKm,
                adjustmentKm    = extraKm,
                isInternal      = isInternal,
                paymentMethod   = paymentMethod,
            )
            dao.insertRide(ride)
        }

        _state.update { it.copy(
            isTracking = false, isPaused = false, totalKm = 0.0,
            waitMinutes = 0.0, waitSeconds = 0.0, currentPrice = 0.0,
            fareAdjustment = 0.0, isWaiting = false, routePoints = emptyList(),
            startTime = 0L, activeTaxPercent = 0.0, activeFuelCostPerKm = 0.0,
            activePricePerKm = 0.0, activePricePerMin = 0.0, activeStartFee = 0.0,
            activeTariffId = 0,
            elapsedSeconds = 0L,
            // shiftTotalKm intentionally preserved — still accumulating
        )}
    }

    private fun sendAction(action: String, shiftId: Long = activeShift.value?.id ?: 0L) {
        val intent = Intent(getApplication(), GpsTrackingService::class.java).apply {
            this.action = action
            putExtra("shiftId", shiftId)
        }
        getApplication<Application>().startService(intent)
    }

    // ── Date helpers ─────────────────────────────────────────
    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0);       c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    private fun startOfWeek(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
        return startOfDay(c.timeInMillis)
    }
    private fun startOfMonth(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.DAY_OF_MONTH, 1) }
        return startOfDay(c.timeInMillis)
    }
    private fun startOfYear(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.DAY_OF_YEAR, 1) }
        return startOfDay(c.timeInMillis)
    }
    private fun nextMonth(start: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.MONTH, 1) }
        return c.timeInMillis
    }
    private fun nextYear(start: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.YEAR, 1) }
        return c.timeInMillis
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }

    fun saveManualRide(km: Double, waitMin: Double, price: Double,
                       tip: Double, startMs: Long) {
        viewModelScope.launch {
            val dao   = db.rideDao()
            val now   = System.currentTimeMillis()
            val day   = startOfDay(now)
            val ride  = Ride(
                globalId        = dao.getTotalCount() + 1,
                dailyId         = dao.getDailyCount(day, day + 86_400_000L) + 1,
                weeklyId        = dao.getWeeklyCount(startOfWeek(now), startOfWeek(now) + 604_800_000L) + 1,
                monthlyId       = dao.getMonthlyCount(startOfMonth(now), nextMonth(startOfMonth(now))) + 1,
                yearlyId        = dao.getYearlyCount(startOfYear(now),  nextYear(startOfYear(now)))  + 1,
                date            = day,
                startTime       = startMs,
                endTime         = now,
                kilometers      = km,
                waitMinutes     = waitMin,
                tip             = tip,
                price           = price,
                currency        = "BGN",
                routePointsJson = "[]",
                shiftId         = activeShift.value?.id ?: 0L,
            )
            dao.insertRide(ride)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String {
        if (lat == 0.0 && lng == 0.0) return ""
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(getApplication(), Locale.getDefault())
                val results  = geocoder.getFromLocation(lat, lng, 1)
                if (!results.isNullOrEmpty()) {
                    val a = results[0]
                    buildString {
                        if (!a.thoroughfare.isNullOrEmpty())    append(a.thoroughfare)
                        if (!a.subThoroughfare.isNullOrEmpty()) append(" ${a.subThoroughfare}")
                    }.ifEmpty { a.getAddressLine(0) ?: "" }
                } else ""
            } catch (_: Exception) { "" }
        }
    }

    fun saveCalculatedRide(
        km: Double, waitMin: Double, price: Double,
        fromAddress: String, toAddress: String,
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double,
        routePointsJson: String = "[]",
        tip: Double = 0.0,
        fareAdjustment: Double = 0.0,
    ) {
        viewModelScope.launch {
            val dao = db.rideDao()
            val now = System.currentTimeMillis()
            val day = startOfDay(now)

            // Detect internal ride (start and end in the same zone)
            val zones      = db.zoneDao().getAllZonesOnce()
            val fromZone   = findZone(fromLat, fromLng, zones)
            val toZone     = findZone(toLat,   toLng,   zones)
            val isInternal = fromZone != null && fromZone.id == toZone?.id

            val currentSettings = settings.value
            val ride = Ride(
                globalId        = dao.getTotalCount() + 1,
                dailyId         = dao.getDailyCount(day, day + 86_400_000L) + 1,
                weeklyId        = dao.getWeeklyCount(startOfWeek(now), startOfWeek(now) + 604_800_000L) + 1,
                monthlyId       = dao.getMonthlyCount(startOfMonth(now), nextMonth(startOfMonth(now))) + 1,
                yearlyId        = dao.getYearlyCount(startOfYear(now),  nextYear(startOfYear(now)))  + 1,
                date            = day,
                startTime       = now,
                endTime         = now,
                fromAddress     = fromAddress,
                toAddress       = toAddress,
                fromLat         = fromLat,
                fromLng         = fromLng,
                toLat           = toLat,
                toLng           = toLng,
                kilometers      = km,
                waitMinutes     = waitMin,
                tip             = tip,
                price           = price + fareAdjustment,
                currency        = "BGN",
                routePointsJson = routePointsJson,
                shiftId         = activeShift.value?.id ?: 0L,
                isInternal      = isInternal,
                // Calculator rides carry the same tax & fuel rates as GPS rides so that
                // statistics (net profit, fuel cost) treat them consistently.
                taxPercent      = currentSettings.taxPercent,
                fuelCostPerKm   = currentSettings.fuelCostPerKm,
            )
            dao.insertRide(ride)
        }
    }
}
