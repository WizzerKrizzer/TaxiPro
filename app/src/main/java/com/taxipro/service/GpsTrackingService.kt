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
        const val CHANNEL_ID       = "gps_tracking"
        const val NOTIFICATION_ID  = 1001

        // Actions
        const val ACTION_START     = "START"
        const val ACTION_STOP      = "STOP"
        const val ACTION_PAUSE     = "PAUSE"
        const val ACTION_RESUME    = "RESUME"

        // Broadcast extras
        const val EXTRA_LAT        = "lat"
        const val EXTRA_LNG        = "lng"
        const val EXTRA_SPEED      = "speed_kmh"
        const val EXTRA_KM         = "total_km"
        const val EXTRA_WAIT_MIN   = "wait_minutes"
        const val EXTRA_PRICE      = "current_price"
        const val EXTRA_IS_WAITING = "is_waiting"
        const val EXTRA_WAIT_SEC   = "wait_seconds"
        const val EXTRA_ELAPSED    = "elapsed_seconds"
        const val BROADCAST_UPDATE = "com.taxipro.GPS_UPDATE"
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Tracking state
    private var isTracking   = false
    private var isPaused     = false
    private var lastLocation: Location? = null
    private var lastGoodLocation: Location? = null  // само точки с добра GPS точност
    private var totalKm      = 0.0
    private var waitSeconds  = 0.0
    private var lastUpdateMs = 0L
    private var lastWaitTickMs = 0L                 // за реално изминало време при престой
    private var startTimeMs  = 0L
    private val waitHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private var waitRunnable : Runnable? = null

    // Settings (injected via Intent extras on start)
    private var settings = AppSettings()

    // Route points for map drawing
    private val routePoints = mutableListOf<Pair<Double, Double>>()  // lat, lng

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START  -> startTracking(intent)
            ACTION_STOP   -> stopTracking()
            ACTION_PAUSE  -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
        }
        return START_NOT_STICKY
    }

    private fun startWaitTimer() {
        lastWaitTickMs = System.currentTimeMillis()
        waitRunnable = object : Runnable {
            override fun run() {
                if (isTracking && !isPaused) {
                    // Реално изминало време от последния tick (Handler може да се забави при изключен екран)
                    val nowMs   = System.currentTimeMillis()
                    val elapsed = (nowMs - lastWaitTickMs).coerceIn(500L, 4000L) / 1000.0
                    lastWaitTickMs = nowMs

                    val lastSpeed = lastLocation?.speed?.times(3.6) ?: 0.0
                    if (lastSpeed < settings.waitSpeedThresholdKmh) {
                        waitSeconds += elapsed
                        // Преизчисли цената
                        val sf   = settings.startFee
                        val pkm  = settings.pricePerKm
                        val pmin = settings.pricePerMinute
                        val waitMin = waitSeconds / 60.0
                        val price   = sf + (totalKm * pkm) + (waitMin * pmin)
                        updateNotification("⏸ Wait • %.1f km • %.2f".format(totalKm, price))

                        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000L

                        val broadcast = Intent(BROADCAST_UPDATE).apply {
                            putExtra(EXTRA_LAT,        lastLocation?.latitude  ?: 0.0)
                            putExtra(EXTRA_LNG,        lastLocation?.longitude ?: 0.0)
                            putExtra(EXTRA_SPEED,      lastLocation?.speed?.times(3.6) ?: 0.0)
                            putExtra(EXTRA_KM,         totalKm)
                            putExtra(EXTRA_WAIT_MIN,   waitSeconds / 60.0)
                            putExtra(EXTRA_PRICE,      price)
                            putExtra(EXTRA_IS_WAITING, true)
                            putExtra("wait_seconds",   waitSeconds)
                            putExtra("elapsed_seconds", elapsedSec)
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
        // Load settings from intent extras
        settings = AppSettings(
            startFee              = intent.getDoubleExtra("startFee", 1.50),
            pricePerKm            = intent.getDoubleExtra("pricePerKm", 1.20),
            pricePerMinute        = intent.getDoubleExtra("pricePerMin", 0.25),
            waitSpeedThresholdKmh = intent.getDoubleExtra("waitThreshold", 5.0),
            gpsIntervalMs         = intent.getLongExtra("gpsInterval", 1000L),
            gpsMinDistanceM       = intent.getFloatExtra("gpsDistance", 5f),
        )

        isTracking       = true
        isPaused         = false
        totalKm          = 0.0
        waitSeconds      = 0.0
        lastLocation     = null
        lastGoodLocation = null
        lastUpdateMs     = System.currentTimeMillis()
        lastWaitTickMs   = System.currentTimeMillis()
        startTimeMs      = System.currentTimeMillis()
        routePoints.clear()

        startForeground(NOTIFICATION_ID, buildNotification("GPS активен — 0.00 лв"))

        // Android минимум е ~1000ms независимо какво задаваш; coerceAtLeast го гарантира
        val intervalMs = settings.gpsIntervalMs.coerceAtLeast(1000L)
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        )
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)     // без минимално разстояние — само времеви интервал
            .setWaitForAccurateLocation(false)  // изпращай веднага, без да чака перфектен сигнал
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
        startWaitTimer()
    }

    private fun handleLocation(location: Location) {
        if (!isTracking || isPaused) return

        val speedKmh = location.speed * 3.6
        lastUpdateMs = System.currentTimeMillis()

        // Само GPS точки с добра точност → правилна линия по пътя и по-точни км
        // 50м е стандартен праг; под 20м е страхотно, 20-50м е приемливо в градски условия
        val accuracy = location.accuracy
        if (accuracy <= 50f) {
            routePoints.add(Pair(location.latitude, location.longitude))
            lastGoodLocation?.let { last ->
                val distM = last.distanceTo(location)
                if (distM < 300) {  // отхвърля нереалистични GPS скокове
                    totalKm += distM / 1000.0
                }
            }
            lastGoodLocation = location
        }
        lastLocation = location  // за скорост / престой

        val isWaiting = speedKmh < settings.waitSpeedThresholdKmh

        val sf   = settings.startFee
        val pkm  = settings.pricePerKm
        val pmin = settings.pricePerMinute

        val waitMin = waitSeconds / 60.0
        val price   = sf + (totalKm * pkm) + (waitMin * pmin)

        val statusText = if (isWaiting)
            "⏸ Wait • %.1f km • %.2f".format(totalKm, price)
        else
            "▶ %.1f km/h • %.1f km • %.2f".format(speedKmh, totalKm, price)
        updateNotification(statusText)

        val broadcast = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_LAT,        location.latitude)
            putExtra(EXTRA_LNG,        location.longitude)
            putExtra(EXTRA_SPEED,      speedKmh)
            putExtra(EXTRA_KM,         totalKm)
            putExtra(EXTRA_WAIT_MIN,   waitMin)
            putExtra(EXTRA_PRICE,      price)
            putExtra(EXTRA_IS_WAITING, isWaiting)
            putExtra("wait_seconds",    waitSeconds)
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
        updateNotification("⏸ Пауза")
    }

    private fun resumeTracking() {
        isPaused       = false
        lastUpdateMs   = System.currentTimeMillis()
        lastWaitTickMs = System.currentTimeMillis()  // нулирай за да не брои паузата като престой
        updateNotification("▶ Продължава...")
    }

    private fun stopTracking() {
        isTracking = false
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        waitRunnable?.let { waitHandler.removeCallbacks(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
