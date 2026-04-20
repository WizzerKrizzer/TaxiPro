package com.taxipro.service

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.taxipro.R
import com.taxipro.data.db.AppSettings
import kotlinx.coroutines.flow.*

class GpsTrackingService : Service() {

    companion object {
        const val CHANNEL_ID         = "gps_tracking"
        const val NOTIFICATION_ID    = 1001

        // Actions
        const val ACTION_START       = "START"
        const val ACTION_STOP        = "STOP"
        const val ACTION_PAUSE       = "PAUSE"
        const val ACTION_RESUME      = "RESUME"
        const val ACTION_SHIFT_START  = "SHIFT_START"
        const val ACTION_SHIFT_STOP   = "SHIFT_STOP"
        const val ACTION_SHIFT_PAUSE  = "SHIFT_PAUSE"
        const val ACTION_SHIFT_RESUME = "SHIFT_RESUME"

        // Broadcast extras
        const val EXTRA_LAT          = "lat"
        const val EXTRA_LNG          = "lng"
        const val EXTRA_SPEED        = "speed_kmh"
        const val EXTRA_KM           = "total_km"
        const val EXTRA_SHIFT_KM     = "shift_km"
        const val EXTRA_WAIT_MIN     = "wait_minutes"
        const val EXTRA_PRICE        = "current_price"
        const val EXTRA_IS_WAITING   = "is_waiting"
        const val EXTRA_WAIT_SEC     = "wait_seconds"
        const val EXTRA_ELAPSED      = "elapsed_seconds"
        const val BROADCAST_UPDATE   = "com.taxipro.GPS_UPDATE"
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Ride tracking state
    private var isTracking        = false
    private var isPaused          = false
    private var lastLocation: Location? = null
    private var lastGoodLocation: Location? = null
    private var totalKm           = 0.0
    private var lastUpdateMs      = 0L
    private var startTimeMs       = 0L
    private val waitHandler       = android.os.Handler(android.os.Looper.getMainLooper())
    private var waitRunnable      : Runnable? = null

    // Wall-clock wait tracking
    private var waitingStartMs    = 0L
    private var completedWaitMs   = 0L

    // Shift-level km tracking (runs independently of ride tracking)
    private var isShiftTracking   = false
    private var isShiftPaused     = false
    private var shiftTotalKm      = 0.0
    private var shiftLastGoodLoc  : Location? = null

    private fun getTotalWaitSeconds(): Double {
        val activeMs = if (waitingStartMs > 0L) System.currentTimeMillis() - waitingStartMs else 0L
        return (completedWaitMs + activeMs) / 1000.0
    }

    // Settings (injected via Intent extras on start)
    private var settings = AppSettings()

    // Route points for map drawing
    private val routePoints = mutableListOf<Pair<Double, Double>>()

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START       -> startTracking(intent)
            ACTION_STOP        -> stopTracking()
            ACTION_PAUSE       -> pauseTracking()
            ACTION_RESUME      -> resumeTracking()
            ACTION_SHIFT_START  -> startShiftTracking()
            ACTION_SHIFT_STOP   -> stopShiftTracking()
            ACTION_SHIFT_PAUSE  -> { isShiftPaused = true;  shiftLastGoodLoc = null }
            ACTION_SHIFT_RESUME -> { isShiftPaused = false; shiftLastGoodLoc = null }
        }
        return START_NOT_STICKY
    }

    // ── Shift-level tracking ──────────────────────────────────

    private fun startShiftTracking() {
        isShiftTracking  = true
        shiftTotalKm     = 0.0
        shiftLastGoodLoc = null

        // If no ride is active, start a lightweight location stream for shift km only
        if (!isTracking) {
            startForeground(NOTIFICATION_ID, buildNotification("🚕 Смяна активна"))
            startLocationUpdates(intervalMs = 3000L)
        }
    }

    private fun stopShiftTracking() {
        isShiftTracking = false
        // Broadcast final shift km so ViewModel can read it before stopping
        val broadcast = Intent(BROADCAST_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SHIFT_KM, shiftTotalKm)
        }
        sendBroadcast(broadcast)

        // If no ride is running, stop the service entirely
        if (!isTracking) {
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Ride tracking ─────────────────────────────────────────

    private fun startWaitTimer() {
        waitRunnable = object : Runnable {
            override fun run() {
                if (isTracking && !isPaused) {
                    val waitSec = getTotalWaitSeconds()
                    val isWaiting = waitingStartMs > 0L
                    if (isWaiting) {
                        val sf      = settings.startFee
                        val pkm     = settings.pricePerKm
                        val pmin    = settings.pricePerMinute
                        val waitMin = waitSec / 60.0
                        val price   = sf + (totalKm * pkm) + (waitMin * pmin)
                        updateNotification("⏸ Wait • %.1f km • %.2f".format(totalKm, price))

                        val broadcast = Intent(BROADCAST_UPDATE).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_LAT,         lastLocation?.latitude  ?: 0.0)
                            putExtra(EXTRA_LNG,         lastLocation?.longitude ?: 0.0)
                            putExtra(EXTRA_SPEED,       lastLocation?.speed?.times(3.6) ?: 0.0)
                            putExtra(EXTRA_KM,          totalKm)
                            putExtra(EXTRA_SHIFT_KM,    shiftTotalKm)
                            putExtra(EXTRA_WAIT_MIN,    waitMin)
                            putExtra(EXTRA_PRICE,       price)
                            putExtra(EXTRA_IS_WAITING,  true)
                            putExtra("wait_seconds",    waitSec)
                            putExtra("elapsed_seconds", (System.currentTimeMillis() - startTimeMs) / 1000L)
                            val flat = DoubleArray(routePoints.size * 2)
                            routePoints.forEachIndexed { i, (lat, lng) ->
                                flat[i * 2] = lat; flat[i * 2 + 1] = lng
                            }
                            putExtra("route_points", flat)
                        }
                        sendBroadcast(broadcast)
                    }
                }
                waitHandler.postDelayed(this, 1000L)
            }
        }
        waitHandler.post(waitRunnable!!)
    }

    private fun startTracking(intent: Intent) {
        settings = AppSettings(
            startFee              = intent.getDoubleExtra("startFee", 1.50),
            pricePerKm            = intent.getDoubleExtra("pricePerKm", 1.20),
            pricePerMinute        = intent.getDoubleExtra("pricePerMin", 0.25),
            waitSpeedThresholdKmh = intent.getDoubleExtra("waitThreshold", 0.0),
            gpsIntervalMs         = intent.getLongExtra("gpsInterval", 1000L),
            gpsMinDistanceM       = intent.getFloatExtra("gpsDistance", 5f),
        )

        isTracking       = true
        isPaused         = false
        totalKm          = 0.0
        waitingStartMs   = 0L
        completedWaitMs  = 0L
        lastLocation     = null
        lastGoodLocation = null
        lastUpdateMs     = System.currentTimeMillis()
        startTimeMs      = System.currentTimeMillis()
        routePoints.clear()

        startForeground(NOTIFICATION_ID, buildNotification("GPS активен — 0.00 лв"))

        val intervalMs = settings.gpsIntervalMs.coerceAtLeast(1000L)
        startLocationUpdates(intervalMs)
        startWaitTimer()
    }

    private fun handleLocation(location: Location) {
        val accuracy = location.accuracy

        // ── Shift-level km (always, regardless of ride state) ──
        // Accuracy threshold 150m: urban canyons often report 50–120m accuracy even with a working
        // fix. Only the 300m max-jump guard protects against teleportation; no minimum distance,
        // because in city driving (frequent slow / stop-and-go), consecutive fixes are often only
        // 2–6 m apart — a minimum cutoff would silently discard most real movement and leave
        // totalKm at zero despite the speedometer working (Doppler-based, unaffected by this).
        val accuracyCap = 150f

        if (isShiftTracking && !isShiftPaused && accuracy <= accuracyCap) {
            shiftLastGoodLoc?.let { last ->
                val distM = last.distanceTo(location)
                if (distM < 300f) shiftTotalKm += distM / 1000.0
            }
            shiftLastGoodLoc = location
        }

        // ── Ride-level tracking ────────────────────────────────
        if (!isTracking || isPaused) return

        val speedKmh = location.speed * 3.6
        lastUpdateMs = System.currentTimeMillis()

        if (accuracy <= accuracyCap) {
            routePoints.add(Pair(location.latitude, location.longitude))
            lastGoodLocation?.let { last ->
                val distM = last.distanceTo(location)
                if (distM < 300f) {
                    totalKm += distM / 1000.0
                }
            }
            lastGoodLocation = location
        }
        lastLocation = location

        val isWaiting = speedKmh < settings.waitSpeedThresholdKmh

        // Use location.time (when the GPS chipset took the fix) rather than
        // System.currentTimeMillis() (when the callback fired). On screen-off/Doze,
        // callbacks can be delivered minutes late — using fix time keeps wait
        // transitions accurate regardless of delivery delay.
        val fixMs = location.time
        when {
            isWaiting && waitingStartMs == 0L -> waitingStartMs = fixMs
            !isWaiting && waitingStartMs > 0L -> {
                completedWaitMs += fixMs - waitingStartMs
                waitingStartMs = 0L
            }
        }

        val sf      = settings.startFee
        val pkm     = settings.pricePerKm
        val pmin    = settings.pricePerMinute
        val waitMin = getTotalWaitSeconds() / 60.0
        val price   = sf + (totalKm * pkm) + (waitMin * pmin)

        val statusText = if (isWaiting)
            "⏸ Wait • %.1f km • %.2f".format(totalKm, price)
        else
            "▶ %.1f km/h • %.1f km • %.2f".format(speedKmh, totalKm, price)
        updateNotification(statusText)

        val broadcast = Intent(BROADCAST_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LAT,        location.latitude)
            putExtra(EXTRA_LNG,        location.longitude)
            putExtra(EXTRA_SPEED,      speedKmh)
            putExtra(EXTRA_KM,         totalKm)
            putExtra(EXTRA_SHIFT_KM,   shiftTotalKm)
            putExtra(EXTRA_WAIT_MIN,    waitMin)
            putExtra(EXTRA_PRICE,       price)
            putExtra(EXTRA_IS_WAITING,  isWaiting)
            putExtra("wait_seconds",    getTotalWaitSeconds())
            putExtra("elapsed_seconds", (System.currentTimeMillis() - startTimeMs) / 1000L)
            val flat = DoubleArray(routePoints.size * 2)
            routePoints.forEachIndexed { i, (lat, lng) ->
                flat[i * 2] = lat; flat[i * 2 + 1] = lng
            }
            putExtra("route_points", flat)
        }
        sendBroadcast(broadcast)
    }

    private fun pauseTracking() {
        isPaused = true
        if (waitingStartMs > 0L) {
            completedWaitMs += System.currentTimeMillis() - waitingStartMs
            waitingStartMs = 0L
        }
        updateNotification("⏸ Пауза")
    }

    private fun resumeTracking() {
        isPaused     = false
        lastUpdateMs = System.currentTimeMillis()
        updateNotification("▶ Продължава...")
    }

    private fun stopTracking() {
        isTracking = false
        waitRunnable?.let { waitHandler.removeCallbacks(it) }

        // If shift is still active keep GPS running (lighter interval), else stop
        if (isShiftTracking) {
            startLocationUpdates(intervalMs = 3000L)
            updateNotification("🚕 Смяна активна")
        } else {
            stopLocationUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Location updates helpers ──────────────────────────────

    private fun startLocationUpdates(intervalMs: Long) {
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        )
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateDelayMillis(intervalMs)   // no batching — deliver each fix immediately
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleLocation(it) }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    // ── Notification ──────────────────────────────────────────
    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "GPS Проследяване", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Активен запис на маршрут" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GpsTrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GpsTrackingService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚕 Таксиметър Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Пауза", pauseIntent)
            .addAction(android.R.drawable.ic_delete, "Стоп", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
