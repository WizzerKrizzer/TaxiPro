package com.taxipro.data.db

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.dataStore by preferencesDataStore(name = "taxi_settings")

enum class Currency(val symbol: String, val code: String) {
    EUR("€", "EUR"),
    USD("$", "USD"),
    GBP("£", "GBP"),
}

enum class DistanceUnit(val label: String, val shortLabel: String) {
    KM("Kilometres", "km"),
    MILES("Miles", "mi"),
}

enum class AppLanguage(val code: String, val displayName: String) {
    EN("en", "English"),
    ES("es", "Español"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    RU("ru", "Русский"),
    PT("pt", "Português"),
    ZH("zh", "中文（简体）"),
    JA("ja", "日本語"),
    AR("ar", "العربية"),
    BG("bg", "Български"),
}

data class AppSettings(
    // ── Тарифа (не се използва директно — вижте Tariff entity) ──
    val startFee: Double              = 0.0,
    val pricePerKm: Double            = 0.0,
    val pricePerMinute: Double        = 0.0,
    val hourlyRate: Double            = 0.0,

    // ── Разходи ──
    val taxPercent: Double            = 0.0,
    val fuelCostPerKm: Double         = 0.0,

    // ── Валута ──
    val currency: Currency            = Currency.EUR,
    val usdRate: Double               = 1.08,
    val gbpRate: Double               = 0.86,

    // ── GPS ──
    val waitSpeedThresholdKmh: Double = 0.0,
    val gpsIntervalMs: Long           = 1000L,
    val gpsMinDistanceM: Float        = 5f,

    // ── UI ──
    val language: AppLanguage         = AppLanguage.EN,
    val distanceUnit: DistanceUnit    = DistanceUnit.KM,
)

fun AppSettings.formatPrice(amount: Double): String {
    val converted = when (currency) {
        Currency.EUR -> amount
        Currency.USD -> amount * usdRate
        Currency.GBP -> amount * gbpRate
    }
    return "${currency.symbol}%.2f".format(converted)
}

fun AppSettings.formatDistance(km: Double): String {
    return if (distanceUnit == DistanceUnit.MILES)
        "%.2f mi".format(km * 0.621371)
    else
        "%.2f km".format(km)
}


class SettingsRepository(private val context: Context) {

    private object Keys {
        val START_FEE      = doublePreferencesKey("start_fee")
        val PRICE_PER_KM   = doublePreferencesKey("price_per_km")
        val PRICE_PER_MIN  = doublePreferencesKey("price_per_min")
        val HOURLY_RATE    = doublePreferencesKey("hourly_rate")
        val TAX_PERCENT    = doublePreferencesKey("tax_percent")
        val FUEL_COST      = doublePreferencesKey("fuel_cost")
        val CURRENCY       = stringPreferencesKey("currency")
        val USD_RATE       = doublePreferencesKey("usd_rate")
        val GBP_RATE       = doublePreferencesKey("gbp_rate")
        val WAIT_THRESHOLD = doublePreferencesKey("wait_threshold")
        val GPS_INTERVAL   = longPreferencesKey("gps_interval")
        val GPS_DISTANCE   = floatPreferencesKey("gps_distance")
        val LANGUAGE       = stringPreferencesKey("language")
        val DISTANCE_UNIT  = stringPreferencesKey("distance_unit")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            startFee              = p[Keys.START_FEE]      ?: 0.0,
            pricePerKm            = p[Keys.PRICE_PER_KM]   ?: 0.0,
            pricePerMinute        = p[Keys.PRICE_PER_MIN]  ?: 0.0,
            hourlyRate            = p[Keys.HOURLY_RATE]    ?: 0.0,
            taxPercent            = p[Keys.TAX_PERCENT]    ?: 0.0,
            fuelCostPerKm         = p[Keys.FUEL_COST]      ?: 0.0,
            currency              = Currency.entries.firstOrNull {
                it.code == p[Keys.CURRENCY] } ?: Currency.EUR,
            usdRate               = p[Keys.USD_RATE]       ?: 1.08,
            gbpRate               = p[Keys.GBP_RATE]       ?: 0.86,
            waitSpeedThresholdKmh = p[Keys.WAIT_THRESHOLD] ?: 0.0,
            gpsIntervalMs         = p[Keys.GPS_INTERVAL]   ?: 1000L,
            gpsMinDistanceM       = p[Keys.GPS_DISTANCE]   ?: 5f,
            language              = AppLanguage.entries.firstOrNull {
                it.code == p[Keys.LANGUAGE] } ?: AppLanguage.EN,
            distanceUnit          = if (p[Keys.DISTANCE_UNIT] == "MILES")
                DistanceUnit.MILES else DistanceUnit.KM,
        )
    }

    suspend fun update(block: suspend (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setStartFee(v: Double)           = update { it[Keys.START_FEE]      = v }
    suspend fun setPricePerKm(v: Double)         = update { it[Keys.PRICE_PER_KM]   = v }
    suspend fun setPricePerMin(v: Double)        = update { it[Keys.PRICE_PER_MIN]  = v }
    suspend fun setHourlyRate(v: Double)         = update { it[Keys.HOURLY_RATE]    = v }
    suspend fun setTaxPercent(v: Double)         = update { it[Keys.TAX_PERCENT]    = v }
    suspend fun setFuelCost(v: Double)           = update { it[Keys.FUEL_COST]      = v }
    suspend fun setCurrency(v: Currency)         = update { it[Keys.CURRENCY]       = v.code }
    suspend fun setUsdRate(v: Double)            = update { it[Keys.USD_RATE]       = v }
    suspend fun setGbpRate(v: Double)            = update { it[Keys.GBP_RATE]       = v }
    suspend fun setWaitThreshold(v: Double)      = update { it[Keys.WAIT_THRESHOLD] = v }
    suspend fun setGpsInterval(v: Long)          = update { it[Keys.GPS_INTERVAL]   = v }
    suspend fun setGpsDistance(v: Float)         = update { it[Keys.GPS_DISTANCE]   = v }
    suspend fun setLanguage(v: AppLanguage)      = update { it[Keys.LANGUAGE]       = v.code }
    suspend fun setDistanceUnit(v: DistanceUnit) = update { it[Keys.DISTANCE_UNIT]  = v.name }

    /** Fetches USD and GBP rates vs EUR from api.frankfurter.app (no API key needed).
     *  Returns Pair(usdRate, gbpRate) or null on failure. */
    suspend fun fetchLiveRates(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url  = java.net.URL("https://api.frankfurter.app/latest?base=EUR&symbols=USD,GBP")
            val json = url.readText()
            val obj  = org.json.JSONObject(json).getJSONObject("rates")
            Pair(obj.getDouble("USD"), obj.getDouble("GBP"))
        } catch (_: Exception) { null }
    }

    suspend fun saveAll(s: AppSettings) = update { p ->
        p[Keys.START_FEE]      = s.startFee
        p[Keys.PRICE_PER_KM]   = s.pricePerKm
        p[Keys.PRICE_PER_MIN]  = s.pricePerMinute
        p[Keys.HOURLY_RATE]    = s.hourlyRate
        p[Keys.TAX_PERCENT]    = s.taxPercent
        p[Keys.FUEL_COST]      = s.fuelCostPerKm
        p[Keys.CURRENCY]       = s.currency.code
        p[Keys.USD_RATE]       = s.usdRate
        p[Keys.GBP_RATE]       = s.gbpRate
        p[Keys.WAIT_THRESHOLD] = s.waitSpeedThresholdKmh
        p[Keys.GPS_INTERVAL]   = s.gpsIntervalMs
        p[Keys.GPS_DISTANCE]   = s.gpsMinDistanceM
        p[Keys.LANGUAGE]       = s.language.code
        p[Keys.DISTANCE_UNIT]  = s.distanceUnit.name
    }
}
