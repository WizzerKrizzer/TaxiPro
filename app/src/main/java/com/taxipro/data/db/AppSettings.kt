package com.taxipro.data.db

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

val Context.dataStore by preferencesDataStore(name = "taxi_settings")

// ── Currency ─────────────────────────────────────────────────────

enum class Currency(val symbol: String, val code: String) {
    // Europe – Eurozone
    EUR("€",   "EUR"),
    // Europe – non-euro
    GBP("£",   "GBP"),
    CHF("Fr",  "CHF"),
    SEK("kr",  "SEK"),
    NOK("kr",  "NOK"),
    DKK("kr",  "DKK"),
    PLN("zł",  "PLN"),
    CZK("Kč",  "CZK"),
    HUF("Ft",  "HUF"),
    RON("lei", "RON"),
    BGN("лв",  "BGN"),
    RSD("din", "RSD"),
    BAM("KM",  "BAM"),
    ALL("L",   "ALL"),
    MKD("ден", "MKD"),
    ISK("kr",  "ISK"),
    UAH("₴",   "UAH"),
    RUB("₽",   "RUB"),
    BYN("Br",  "BYN"),
    GEL("₾",   "GEL"),
    AMD("֏",   "AMD"),
    AZN("₼",   "AZN"),
    MDL("L",   "MDL"),
    // Americas
    USD("$",   "USD"),
    CAD("CA$", "CAD"),
    MXN("$",   "MXN"),
    BRL("R$",  "BRL"),
    ARS("$",   "ARS"),
    CLP("$",   "CLP"),
    COP("$",   "COP"),
    PEN("S/",  "PEN"),
    BOB("Bs.", "BOB"),
    PYG("₲",   "PYG"),
    UYU("U",  "UYU"),
    CRC("₡",   "CRC"),
    GTQ("Q",   "GTQ"),
    // Asia / Pacific
    JPY("¥",    "JPY"),
    CNY("¥",    "CNY"),
    KRW("₩",    "KRW"),
    HKD("HK$",  "HKD"),
    SGD("S$",   "SGD"),
    AUD("A$",   "AUD"),
    NZD("NZ$",  "NZD"),
    INR("₹",    "INR"),
    IDR("Rp",   "IDR"),
    PHP("₱",    "PHP"),
    THB("฿",    "THB"),
    MYR("RM",   "MYR"),
    TWD("NT$",  "TWD"),
    VND("₫",    "VND"),
    BDT("৳",    "BDT"),
    PKR("₨",    "PKR"),
    // Middle East / Africa
    AED("د.إ",  "AED"),
    SAR("﷼",    "SAR"),
    ILS("₪",    "ILS"),
    TRY("₺",    "TRY"),
    ZAR("R",    "ZAR"),
    EGP("E£",   "EGP"),
    MAD("د.م.", "MAD"),
    NGN("₦",    "NGN"),
    KES("KSh",  "KES"),
    QAR("﷼",    "QAR"),
    KWD("KD",   "KWD"),
    BHD("BD",   "BHD"),
    OMR("﷼",    "OMR"),
    JOD("JD",   "JOD"),
    ;

    companion object {
        fun from(code: String) = entries.firstOrNull { it.code == code } ?: EUR
    }
}

// ── Country → Currency mapping ───────────────────────────────────

private val COUNTRY_CURRENCY = mapOf(
    // Eurozone
    "AT" to Currency.EUR, "BE" to Currency.EUR, "CY" to Currency.EUR,
    "EE" to Currency.EUR, "FI" to Currency.EUR, "FR" to Currency.EUR,
    "DE" to Currency.EUR, "GR" to Currency.EUR, "IE" to Currency.EUR,
    "IT" to Currency.EUR, "LV" to Currency.EUR, "LT" to Currency.EUR,
    "LU" to Currency.EUR, "MT" to Currency.EUR, "NL" to Currency.EUR,
    "PT" to Currency.EUR, "SK" to Currency.EUR, "SI" to Currency.EUR,
    "ES" to Currency.EUR, "HR" to Currency.EUR, "ME" to Currency.EUR,
    "XK" to Currency.EUR, "AD" to Currency.EUR, "MC" to Currency.EUR,
    "SM" to Currency.EUR, "VA" to Currency.EUR,
    // Europe non-euro
    "GB" to Currency.GBP, "IM" to Currency.GBP, "JE" to Currency.GBP, "GG" to Currency.GBP,
    "CH" to Currency.CHF, "LI" to Currency.CHF,
    "SE" to Currency.SEK,
    "NO" to Currency.NOK, "SJ" to Currency.NOK,
    "DK" to Currency.DKK, "FO" to Currency.DKK, "GL" to Currency.DKK,
    "PL" to Currency.PLN,
    "CZ" to Currency.CZK,
    "HU" to Currency.HUF,
    "RO" to Currency.RON,
    "BG" to Currency.EUR,
    "RS" to Currency.RSD,
    "BA" to Currency.BAM,
    "AL" to Currency.ALL,
    "MK" to Currency.MKD,
    "IS" to Currency.ISK,
    "UA" to Currency.UAH,
    "RU" to Currency.RUB,
    "BY" to Currency.BYN,
    "GE" to Currency.GEL,
    "AM" to Currency.AMD,
    "AZ" to Currency.AZN,
    "MD" to Currency.MDL,
    // Americas
    "US" to Currency.USD, "EC" to Currency.USD, "PA" to Currency.USD,
    "PR" to Currency.USD, "GU" to Currency.USD, "VI" to Currency.USD,
    "CA" to Currency.CAD,
    "MX" to Currency.MXN,
    "BR" to Currency.BRL,
    "AR" to Currency.ARS,
    "CL" to Currency.CLP,
    "CO" to Currency.COP,
    "PE" to Currency.PEN,
    "BO" to Currency.BOB,
    "PY" to Currency.PYG,
    "UY" to Currency.UYU,
    "CR" to Currency.CRC,
    "GT" to Currency.GTQ,
    // Asia / Pacific
    "JP" to Currency.JPY,
    "CN" to Currency.CNY,
    "KR" to Currency.KRW,
    "HK" to Currency.HKD,
    "MO" to Currency.HKD,
    "SG" to Currency.SGD,
    "AU" to Currency.AUD, "CC" to Currency.AUD, "CX" to Currency.AUD,
    "NZ" to Currency.NZD,
    "IN" to Currency.INR,
    "ID" to Currency.IDR,
    "PH" to Currency.PHP,
    "TH" to Currency.THB,
    "MY" to Currency.MYR,
    "TW" to Currency.TWD,
    "VN" to Currency.VND,
    "BD" to Currency.BDT,
    "PK" to Currency.PKR,
    // Middle East / Africa
    "AE" to Currency.AED,
    "SA" to Currency.SAR,
    "IL" to Currency.ILS,
    "TR" to Currency.TRY,
    "ZA" to Currency.ZAR,
    "EG" to Currency.EGP,
    "MA" to Currency.MAD,
    "NG" to Currency.NGN,
    "KE" to Currency.KES,
    "QA" to Currency.QAR,
    "KW" to Currency.KWD,
    "BH" to Currency.BHD,
    "OM" to Currency.OMR,
    "JO" to Currency.JOD,
)

fun countryToCurrency(countryCode: String): Currency =
    COUNTRY_CURRENCY[countryCode.uppercase(Locale.ROOT)] ?: Currency.EUR

// ── Other enums ──────────────────────────────────────────────────

enum class DistanceUnit(val label: String, val shortLabel: String) {
    KM("Kilometres", "km"),
    MILES("Miles", "mi"),
}

enum class AppTheme(val code: String) {
    DARK("DARK"),
    LIGHT("LIGHT"),
    MIDNIGHT("MIDNIGHT"),
    SUNSET("SUNSET"),
    FOREST("FOREST");
    companion object { fun from(code: String) = entries.firstOrNull { it.code == code } ?: DARK }
}

enum class AppLanguage(val code: String, val displayName: String) {
    EN("en", "English"),
    ES("es", "Español"),
    PT("pt", "Português"),
    RU("ru", "Русский"),
    FR("fr", "Français"),
    HI("hi", "हिन्दी"),
    ZH("zh", "中文（简体）"),
    AR("ar", "العربية"),
    DE("de", "Deutsch"),
    JA("ja", "日本語"),
    TR("tr", "Türkçe"),
    KO("ko", "한국어"),
    VI("vi", "Tiếng Việt"),
    ID("id", "Bahasa Indonesia"),
    IT("it", "Italiano"),
    BG("bg", "Български"),
}

// ── AppSettings data class ───────────────────────────────────────

data class AppSettings(
    val startFee: Double              = 0.0,
    val pricePerKm: Double            = 0.0,
    val pricePerMinute: Double        = 0.0,
    val hourlyRate: Double            = 0.0,
    val taxPercent: Double            = 0.0,
    val fuelCostPerKm: Double         = 0.0,
    val currency: Currency            = Currency.EUR,
    val waitSpeedThresholdKmh: Double = 0.0,
    val gpsIntervalMs: Long           = 1000L,
    val gpsMinDistanceM: Float        = 5f,
    val language: AppLanguage         = AppLanguage.EN,
    val distanceUnit: DistanceUnit    = DistanceUnit.KM,
    val theme: AppTheme               = AppTheme.DARK,
    val inferKmFromAdjustment: Boolean  = true,
    val blockZoneOverlapPoints: Boolean = true,
    val blockDuplicateZoneName: Boolean = true,
    /** When ON, rides between 00:00–03:59 that belong to a shift started on the
     *  previous calendar day are counted as that previous day in statistics. */
    val countMidnightRidesToShiftDay: Boolean = true,
    /** When ON, tips are added to the revenue total everywhere in the app. */
    val includeTipsInTotal: Boolean = true,
    val weeklyGoal: Double = 0.0,
)

// Prices are always stored in local currency — no conversion needed
fun AppSettings.formatPrice(amount: Double): String =
    "${currency.symbol}%.2f".format(amount)

fun AppSettings.formatDistance(km: Double): String =
    if (distanceUnit == DistanceUnit.MILES) "%.2f mi".format(km * 0.621371)
    else "%.2f km".format(km)

// ── SettingsRepository ───────────────────────────────────────────

class SettingsRepository(private val context: Context) {

    private object Keys {
        val START_FEE      = doublePreferencesKey("start_fee")
        val PRICE_PER_KM   = doublePreferencesKey("price_per_km")
        val PRICE_PER_MIN  = doublePreferencesKey("price_per_min")
        val HOURLY_RATE    = doublePreferencesKey("hourly_rate")
        val TAX_PERCENT    = doublePreferencesKey("tax_percent")
        val FUEL_COST      = doublePreferencesKey("fuel_cost")
        val CURRENCY       = stringPreferencesKey("currency")
        val WAIT_THRESHOLD = doublePreferencesKey("wait_threshold")
        val GPS_INTERVAL   = longPreferencesKey("gps_interval")
        val GPS_DISTANCE   = floatPreferencesKey("gps_distance")
        val LANGUAGE          = stringPreferencesKey("language")
        val DISTANCE_UNIT     = stringPreferencesKey("distance_unit")
        val THEME             = stringPreferencesKey("theme")
        val INFER_KM                = booleanPreferencesKey("infer_km_from_adjustment")
        val BLOCK_ZONE_OVERLAP      = booleanPreferencesKey("block_zone_overlap")
        val BLOCK_DUPLICATE_NAME    = booleanPreferencesKey("block_duplicate_zone_name")
        val MIDNIGHT_TO_SHIFT_DAY   = booleanPreferencesKey("midnight_to_shift_day")
        val INCLUDE_TIPS            = booleanPreferencesKey("include_tips_in_total")
        val WEEKLY_GOAL             = doublePreferencesKey("weekly_goal")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            startFee              = p[Keys.START_FEE]      ?: 0.0,
            pricePerKm            = p[Keys.PRICE_PER_KM]   ?: 0.0,
            pricePerMinute        = p[Keys.PRICE_PER_MIN]  ?: 0.0,
            hourlyRate            = p[Keys.HOURLY_RATE]    ?: 0.0,
            taxPercent            = p[Keys.TAX_PERCENT]    ?: 0.0,
            fuelCostPerKm         = p[Keys.FUEL_COST]      ?: 0.0,
            currency              = Currency.from(p[Keys.CURRENCY] ?: "EUR"),
            waitSpeedThresholdKmh = p[Keys.WAIT_THRESHOLD] ?: 0.0,
            gpsIntervalMs         = p[Keys.GPS_INTERVAL]   ?: 1000L,
            gpsMinDistanceM       = p[Keys.GPS_DISTANCE]   ?: 5f,
            language              = AppLanguage.entries.firstOrNull {
                it.code == p[Keys.LANGUAGE] } ?: AppLanguage.EN,
            distanceUnit          = if (p[Keys.DISTANCE_UNIT] == "MILES")
                DistanceUnit.MILES else DistanceUnit.KM,
            theme                 = AppTheme.from(p[Keys.THEME] ?: "DARK"),
            inferKmFromAdjustment = p[Keys.INFER_KM]             ?: true,
            blockZoneOverlapPoints = p[Keys.BLOCK_ZONE_OVERLAP]   ?: true,
            blockDuplicateZoneName = p[Keys.BLOCK_DUPLICATE_NAME] ?: true,
            countMidnightRidesToShiftDay = p[Keys.MIDNIGHT_TO_SHIFT_DAY] ?: true,
            includeTipsInTotal           = p[Keys.INCLUDE_TIPS]           ?: true,
            weeklyGoal                   = p[Keys.WEEKLY_GOAL]            ?: 0.0,
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
    suspend fun setWaitThreshold(v: Double)      = update { it[Keys.WAIT_THRESHOLD] = v }
    suspend fun setGpsInterval(v: Long)          = update { it[Keys.GPS_INTERVAL]   = v }
    suspend fun setGpsDistance(v: Float)         = update { it[Keys.GPS_DISTANCE]   = v }
    suspend fun setLanguage(v: AppLanguage)      = update { it[Keys.LANGUAGE]       = v.code }
    suspend fun setDistanceUnit(v: DistanceUnit)    = update { it[Keys.DISTANCE_UNIT]  = v.name }
    suspend fun setTheme(v: AppTheme)               = update { it[Keys.THEME]          = v.code }
    suspend fun setInferKmFromAdjustment(v: Boolean)  = update { it[Keys.INFER_KM]             = v }
    suspend fun setBlockZoneOverlap(v: Boolean)       = update { it[Keys.BLOCK_ZONE_OVERLAP]   = v }
    suspend fun setBlockDuplicateName(v: Boolean)     = update { it[Keys.BLOCK_DUPLICATE_NAME] = v }
    suspend fun setMidnightToShiftDay(v: Boolean)     = update { it[Keys.MIDNIGHT_TO_SHIFT_DAY] = v }
    suspend fun setIncludeTips(v: Boolean)            = update { it[Keys.INCLUDE_TIPS]           = v }
    suspend fun setWeeklyGoal(v: Double)              = update { it[Keys.WEEKLY_GOAL]            = v }

    /**
     * Reads the last known device location, reverse-geocodes it to a country,
     * maps it to the local currency and saves it. Silent no-op if location
     * is unavailable or geocoding fails.
     */
    @Suppress("MissingPermission")
    suspend fun detectAndSaveCurrency() = withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location =
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: return@withContext

            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)

            val countryCode = addresses?.firstOrNull()?.countryCode ?: return@withContext
            val detected    = countryToCurrency(countryCode)
            setCurrency(detected)
        } catch (_: Exception) { }
    }

    suspend fun saveAll(s: AppSettings) = update { p ->
        p[Keys.START_FEE]      = s.startFee
        p[Keys.PRICE_PER_KM]   = s.pricePerKm
        p[Keys.PRICE_PER_MIN]  = s.pricePerMinute
        p[Keys.HOURLY_RATE]    = s.hourlyRate
        p[Keys.TAX_PERCENT]    = s.taxPercent
        p[Keys.FUEL_COST]      = s.fuelCostPerKm
        p[Keys.CURRENCY]       = s.currency.code
        p[Keys.WAIT_THRESHOLD] = s.waitSpeedThresholdKmh
        p[Keys.GPS_INTERVAL]   = s.gpsIntervalMs
        p[Keys.GPS_DISTANCE]   = s.gpsMinDistanceM
        p[Keys.LANGUAGE]       = s.language.code
        p[Keys.DISTANCE_UNIT]  = s.distanceUnit.name
        p[Keys.THEME]          = s.theme.code
        p[Keys.INFER_KM]             = s.inferKmFromAdjustment
        p[Keys.BLOCK_ZONE_OVERLAP]   = s.blockZoneOverlapPoints
        p[Keys.BLOCK_DUPLICATE_NAME] = s.blockDuplicateZoneName
        p[Keys.MIDNIGHT_TO_SHIFT_DAY] = s.countMidnightRidesToShiftDay
        p[Keys.INCLUDE_TIPS]          = s.includeTipsInTotal
        p[Keys.WEEKLY_GOAL]           = s.weeklyGoal
    }
}
