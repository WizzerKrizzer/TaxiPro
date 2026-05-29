package com.taxipro.data.ads

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.adCreditsDataStore by preferencesDataStore(name = "ad_credits_prefs")

private val KEY_CREDITS = intPreferencesKey("credits")
private val KEY_DAY_KEY = longPreferencesKey("day_key")
private val KEY_DAILY_RIDES = intPreferencesKey("daily_rides")
private val KEY_DAILY_CALCULATOR = intPreferencesKey("daily_calculator")
private val KEY_EXTRA_ZONE_SLOTS = intPreferencesKey("extra_zone_slots")
private val KEY_INTERSTITIAL_RIDE_COUNT = intPreferencesKey("interstitial_ride_count")
private val KEY_LAST_INTERSTITIAL_MS = longPreferencesKey("last_interstitial_ms")

const val REWARDED_CREDITS = DEFAULT_REWARDED_CREDITS
const val RIDE_CREDIT_COST = DEFAULT_RIDE_CREDIT_COST
const val CALCULATOR_CREDIT_COST = DEFAULT_CALCULATOR_CREDIT_COST
const val ZONE_SLOT_CREDIT_COST = DEFAULT_ZONE_SLOT_CREDIT_COST
const val DAILY_FREE_RIDES = DEFAULT_DAILY_FREE_RIDES
const val DAILY_FREE_CALCULATOR = DEFAULT_DAILY_FREE_CALCULATOR
const val FREE_ZONE_LIMIT = DEFAULT_FREE_ZONE_LIMIT
const val MAX_CREDIT_ZONE_SLOTS = DEFAULT_MAX_CREDIT_ZONE_SLOTS
const val INTERSTITIAL_RIDE_INTERVAL = DEFAULT_INTERSTITIAL_RIDE_INTERVAL
const val INTERSTITIAL_COOLDOWN_MS = DEFAULT_INTERSTITIAL_COOLDOWN_MS

data class AdCreditsState(
    val credits: Int = 0,
    val dailyRides: Int = 0,
    val dailyCalculatorUses: Int = 0,
    val extraZoneSlots: Int = 0,
    val interstitialRideCount: Int = 0,
    val lastInterstitialMs: Long = 0L,
    val config: AdsConfig = AdsConfig(),
) {
    val zoneLimit: Int get() = config.freeZoneLimit + extraZoneSlots
}

enum class CreditFeature(val cost: Int) {
    Ride(DEFAULT_RIDE_CREDIT_COST),
    Calculator(DEFAULT_CALCULATOR_CREDIT_COST),
    ZoneSlot(DEFAULT_ZONE_SLOT_CREDIT_COST),
}

class AdCreditsRepository(
    private val context: Context,
    private val configProvider: () -> AdsConfig = { AdsConfig() },
) {

    val state: Flow<AdCreditsState> = context.adCreditsDataStore.data.map { prefs ->
        val normalized = normalizeForToday(prefs[KEY_DAY_KEY] ?: 0L)
        AdCreditsState(
            credits = prefs[KEY_CREDITS] ?: 0,
            dailyRides = if (normalized) prefs[KEY_DAILY_RIDES] ?: 0 else 0,
            dailyCalculatorUses = if (normalized) prefs[KEY_DAILY_CALCULATOR] ?: 0 else 0,
            extraZoneSlots = (prefs[KEY_EXTRA_ZONE_SLOTS] ?: 0).coerceIn(0, configProvider().maxCreditZoneSlots),
            interstitialRideCount = prefs[KEY_INTERSTITIAL_RIDE_COUNT] ?: 0,
            lastInterstitialMs = prefs[KEY_LAST_INTERSTITIAL_MS] ?: 0L,
            config = configProvider(),
        )
    }

    suspend fun canUseRide(isPremium: Boolean): Boolean {
        if (isPremium) return true
        val s = normalizedSnapshot()
        return s.dailyRides < s.config.dailyFreeRides || s.credits >= s.config.rideCreditCost
    }

    suspend fun consumeRideAccess(isPremium: Boolean): Boolean {
        if (isPremium) return true
        normalizeDailyCounters()
        var allowed = false
        context.adCreditsDataStore.edit { prefs ->
            val rides = prefs[KEY_DAILY_RIDES] ?: 0
            val credits = prefs[KEY_CREDITS] ?: 0
            val config = configProvider()
            when {
                rides < config.dailyFreeRides -> {
                    prefs[KEY_DAILY_RIDES] = rides + 1
                    allowed = true
                }
                credits >= config.rideCreditCost -> {
                    prefs[KEY_CREDITS] = credits - config.rideCreditCost
                    prefs[KEY_DAILY_RIDES] = rides + 1
                    allowed = true
                }
            }
        }
        return allowed
    }

    suspend fun canUseCalculator(isPremium: Boolean): Boolean {
        if (isPremium) return true
        val s = normalizedSnapshot()
        return s.dailyCalculatorUses < s.config.dailyFreeCalculator || s.credits >= s.config.calculatorCreditCost
    }

    suspend fun consumeCalculatorAccess(isPremium: Boolean): Boolean {
        if (isPremium) return true
        normalizeDailyCounters()
        var allowed = false
        context.adCreditsDataStore.edit { prefs ->
            val uses = prefs[KEY_DAILY_CALCULATOR] ?: 0
            val credits = prefs[KEY_CREDITS] ?: 0
            val config = configProvider()
            when {
                uses < config.dailyFreeCalculator -> {
                    prefs[KEY_DAILY_CALCULATOR] = uses + 1
                    allowed = true
                }
                credits >= config.calculatorCreditCost -> {
                    prefs[KEY_CREDITS] = credits - config.calculatorCreditCost
                    prefs[KEY_DAILY_CALCULATOR] = uses + 1
                    allowed = true
                }
            }
        }
        return allowed
    }

    suspend fun canAddZone(currentZoneCount: Int, isPremium: Boolean): Boolean {
        if (isPremium) return true
        val s = normalizedSnapshot()
        return currentZoneCount < s.zoneLimit
    }

    suspend fun canBuyZoneSlot(isPremium: Boolean): Boolean {
        if (isPremium) return false
        val s = normalizedSnapshot()
        return s.extraZoneSlots < s.config.maxCreditZoneSlots && s.credits >= s.config.zoneSlotCreditCost
    }

    suspend fun buyZoneSlot(isPremium: Boolean): Boolean {
        if (isPremium) return false
        var bought = false
        context.adCreditsDataStore.edit { prefs ->
            val config = configProvider()
            val slots = (prefs[KEY_EXTRA_ZONE_SLOTS] ?: 0).coerceIn(0, config.maxCreditZoneSlots)
            val credits = prefs[KEY_CREDITS] ?: 0
            if (slots < config.maxCreditZoneSlots && credits >= config.zoneSlotCreditCost) {
                prefs[KEY_EXTRA_ZONE_SLOTS] = slots + 1
                prefs[KEY_CREDITS] = credits - config.zoneSlotCreditCost
                bought = true
            }
        }
        return bought
    }

    suspend fun addRewardedCredits(amount: Int = REWARDED_CREDITS) {
        context.adCreditsDataStore.edit { prefs ->
            prefs[KEY_CREDITS] = (prefs[KEY_CREDITS] ?: 0) + amount
        }
    }

    suspend fun recordCompletedRideForInterstitial(isPremium: Boolean): Boolean {
        if (isPremium) return false
        var shouldShow = false
        val now = System.currentTimeMillis()
        context.adCreditsDataStore.edit { prefs ->
            val count = (prefs[KEY_INTERSTITIAL_RIDE_COUNT] ?: 0) + 1
            val last = prefs[KEY_LAST_INTERSTITIAL_MS] ?: 0L
            val config = configProvider()
            if (config.interstitialAdsEnabled && count >= config.interstitialRideInterval && now - last >= config.interstitialCooldownMs) {
                prefs[KEY_INTERSTITIAL_RIDE_COUNT] = 0
                prefs[KEY_LAST_INTERSTITIAL_MS] = now
                shouldShow = true
            } else {
                prefs[KEY_INTERSTITIAL_RIDE_COUNT] = count
            }
        }
        return shouldShow
    }

    private suspend fun normalizedSnapshot(): AdCreditsState {
        normalizeDailyCounters()
        return state.first()
    }

    private suspend fun normalizeDailyCounters() {
        val today = todayKey()
        context.adCreditsDataStore.edit { prefs ->
            if (prefs[KEY_DAY_KEY] != today) {
                prefs[KEY_DAY_KEY] = today
                prefs[KEY_DAILY_RIDES] = 0
                prefs[KEY_DAILY_CALCULATOR] = 0
            }
        }
    }

    private fun normalizeForToday(storedDay: Long): Boolean = storedDay == todayKey()

    private fun todayKey(): Long =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toLong()
}
