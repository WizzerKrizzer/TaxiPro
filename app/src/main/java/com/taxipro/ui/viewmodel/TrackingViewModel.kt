package com.taxipro.ui.viewmodel

import android.app.Application
import android.content.*
import androidx.lifecycle.*
import com.taxipro.data.db.*
import com.taxipro.data.db.Tariff
import com.taxipro.service.GpsTrackingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class TrackingState(
    val isTracking: Boolean       = false,
    val isPaused: Boolean         = false,
    val currentLat: Double        = 0.0,
    val currentLng: Double        = 0.0,
    val speedKmh: Double          = 0.0,
    val totalKm: Double           = 0.0,
    val waitMinutes: Double       = 0.0,
    val waitSeconds: Double       = 0.0,      // НОВО — за показване в сек/мин
    val elapsedSeconds: Long      = 0L,       // НОВО — общо времетраене
    val currentPrice: Double      = 0.0,
    val fareAdjustment: Double    = 0.0,      // НОВО — корекция +/-
    val isWaiting: Boolean        = false,
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val startTime: Long           = 0L,
)



class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val db          = AppDatabase.getInstance(app)
    private val settingsRepo = SettingsRepository(app)
    private val _state      = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()


    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    val tariffs: StateFlow<List<Tariff>> = db.tariffDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveTariff(t: Tariff)  = viewModelScope.launch { db.tariffDao().upsert(t) }
    fun deleteTariff(t: Tariff) = viewModelScope.launch { db.tariffDao().delete(t) }

    // ── Shift management ─────────────────────────────────────
    val activeShift: StateFlow<Shift?> = db.shiftDao().getActiveShift()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _lastEndedShift = MutableStateFlow<Shift?>(null)
    val lastEndedShift: StateFlow<Shift?> = _lastEndedShift.asStateFlow()

    fun startShift() = viewModelScope.launch {
        val dao   = db.shiftDao()
        val count = dao.getTotalCount()
        dao.insertShift(Shift(shiftNumber = count + 1))
    }

    fun endShift() = viewModelScope.launch {
        val dao    = db.shiftDao()
        val active = activeShift.value ?: return@launch
        val ended  = active.copy(endTime = System.currentTimeMillis(), isActive = false)
        dao.updateShift(ended)
        _lastEndedShift.value = ended
    }

    fun clearLastEndedShift() { _lastEndedShift.value = null }





    // BroadcastReceiver — получава данни от GpsTrackingService
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val flatRoute = intent.getDoubleArrayExtra("route_points") ?: doubleArrayOf()
            val points    = (flatRoute.indices step 2).map { i ->
                Pair(flatRoute[i], flatRoute[i + 1])
            }
            _state.update { s -> s.copy(
                currentLat  = intent.getDoubleExtra(GpsTrackingService.EXTRA_LAT,   0.0),
                currentLng  = intent.getDoubleExtra(GpsTrackingService.EXTRA_LNG,   0.0),
                speedKmh    = intent.getDoubleExtra(GpsTrackingService.EXTRA_SPEED, 0.0),
                totalKm     = intent.getDoubleExtra(GpsTrackingService.EXTRA_KM,    0.0),
                waitMinutes = intent.getDoubleExtra(GpsTrackingService.EXTRA_WAIT_MIN, 0.0),
                currentPrice= intent.getDoubleExtra(GpsTrackingService.EXTRA_PRICE, 0.0),
                isWaiting   = intent.getBooleanExtra(GpsTrackingService.EXTRA_IS_WAITING, false),
                routePoints = points,
                waitSeconds    = intent.getDoubleExtra("wait_seconds", 0.0),
                elapsedSeconds = intent.getLongExtra("elapsed_seconds", 0L),
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
        val intent = Intent(getApplication(), GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra("startFee",      tariff?.startFee       ?: s.startFee)
            putExtra("pricePerKm",    tariff?.pricePerKm     ?: s.pricePerKm)
            putExtra("pricePerMin",   tariff?.pricePerMinute ?: s.pricePerMinute)
            putExtra("waitThreshold", s.waitSpeedThresholdKmh)
            putExtra("gpsInterval",   s.gpsIntervalMs)
            putExtra("gpsDistance",   s.gpsMinDistanceM)
        }
        getApplication<Application>().startForegroundService(intent)
        _state.update { it.copy(isTracking = true, isPaused = false, startTime = System.currentTimeMillis()) }
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

    fun stopAndSaveRide(tip: Double = 0.0, currency: String = "BGN") {
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

            val routeJson = s.routePoints.joinToString(",", "[", "]") {
                    (lat, lng) -> "[${lat},${lng}]" }

            val ride = Ride(
                globalId       = dao.getTotalCount() + 1,
                dailyId        = dao.getDailyCount(dayStart, dayEnd) + 1,
                weeklyId       = dao.getWeeklyCount(wkStart, wkStart + 604_800_000L) + 1,
                monthlyId      = dao.getMonthlyCount(moStart, nextMonth(moStart)) + 1,
                yearlyId       = dao.getYearlyCount(yrStart, nextYear(yrStart)) + 1,
                date           = dayStart,
                startTime      = s.startTime,
                endTime        = now,
                kilometers     = s.totalKm,
                waitMinutes    = s.waitMinutes,
                tip            = tip,
                price          = s.currentPrice + s.fareAdjustment,
                currency       = currency,
                routePointsJson= routeJson,
                avgSpeed       = if (s.totalKm > 0) s.totalKm / ((now - s.startTime) / 3_600_000.0) else 0.0,
                shiftId        = activeShift.value?.id ?: 0L,
            )
            dao.insertRide(ride)
        }

        _state.value = TrackingState()
    }

    private fun sendAction(action: String) {
        val intent = Intent(getApplication(), GpsTrackingService::class.java).apply { this.action = action }
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

    fun saveCalculatedRide(
        km: Double, waitMin: Double, price: Double,
        fromAddress: String, toAddress: String,
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double,
        routePointsJson: String = "[]",
    ) {
        viewModelScope.launch {
            val dao = db.rideDao()
            val now = System.currentTimeMillis()
            val day = startOfDay(now)
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
                price           = price,
                currency        = "BGN",
                routePointsJson = routePointsJson,
                shiftId         = activeShift.value?.id ?: 0L,
            )
            dao.insertRide(ride)
        }
    }
}
