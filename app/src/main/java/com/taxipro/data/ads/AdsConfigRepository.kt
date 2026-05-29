package com.taxipro.data.ads

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

const val DEFAULT_REWARDED_CREDITS = 50
const val DEFAULT_RIDE_CREDIT_COST = 10
const val DEFAULT_CALCULATOR_CREDIT_COST = 25
const val DEFAULT_ZONE_SLOT_CREDIT_COST = 250
const val DEFAULT_DAILY_FREE_RIDES = 10
const val DEFAULT_DAILY_FREE_CALCULATOR = 1
const val DEFAULT_FREE_ZONE_LIMIT = 3
const val DEFAULT_MAX_CREDIT_ZONE_SLOTS = 3
const val DEFAULT_INTERSTITIAL_RIDE_INTERVAL = 5
const val DEFAULT_INTERSTITIAL_COOLDOWN_MS = 15 * 60 * 1000L

const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
const val REAL_BANNER_AD_UNIT_ID = "ca-app-pub-6621304807079356/6758705411"
const val REAL_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-6621304807079356/2981900482"
const val REAL_REWARDED_AD_UNIT_ID = "ca-app-pub-6621304807079356/2598453188"

data class AdsConfig(
    val useTestAdIds: Boolean = false,
    val bannerAdUnitId: String = REAL_BANNER_AD_UNIT_ID,
    val interstitialAdUnitId: String = REAL_INTERSTITIAL_AD_UNIT_ID,
    val rewardedAdUnitId: String = REAL_REWARDED_AD_UNIT_ID,
    val rewardedCredits: Int = DEFAULT_REWARDED_CREDITS,
    val rideCreditCost: Int = DEFAULT_RIDE_CREDIT_COST,
    val calculatorCreditCost: Int = DEFAULT_CALCULATOR_CREDIT_COST,
    val zoneSlotCreditCost: Int = DEFAULT_ZONE_SLOT_CREDIT_COST,
    val dailyFreeRides: Int = DEFAULT_DAILY_FREE_RIDES,
    val dailyFreeCalculator: Int = DEFAULT_DAILY_FREE_CALCULATOR,
    val freeZoneLimit: Int = DEFAULT_FREE_ZONE_LIMIT,
    val maxCreditZoneSlots: Int = DEFAULT_MAX_CREDIT_ZONE_SLOTS,
    val interstitialRideInterval: Int = DEFAULT_INTERSTITIAL_RIDE_INTERVAL,
    val interstitialCooldownMs: Long = DEFAULT_INTERSTITIAL_COOLDOWN_MS,
    val bannerAdsEnabled: Boolean = true,
    val interstitialAdsEnabled: Boolean = true,
    val rewardedAdsEnabled: Boolean = true,
    val mediationReady: Boolean = false,
    val nativeAdsExperimentEnabled: Boolean = false,
    val firebaseReady: Boolean = false,
)

class AdsConfigRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO)
    private val remoteConfig = createRemoteConfig()
    private val _state = MutableStateFlow(AdsConfig(firebaseReady = remoteConfig != null))
    val state: StateFlow<AdsConfig> = _state.asStateFlow()

    init {
        remoteConfig?.setDefaultsAsync(defaults())
        refresh()
    }

    fun refresh() {
        val config = remoteConfig ?: return
        scope.launch {
            runCatching {
                config.fetchAndActivate().await()
            }
            _state.value = readConfig(config).copy(firebaseReady = true)
        }
    }

    private fun createRemoteConfig(): FirebaseRemoteConfig? = runCatching {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext) ?: return null
        }
        FirebaseRemoteConfig.getInstance().apply {
            setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(60 * 60)
                    .build(),
            )
        }
    }.getOrNull()

    private fun readConfig(config: FirebaseRemoteConfig): AdsConfig {
        val useTestIds = config.getBoolean(KEY_USE_TEST_AD_IDS)
        return AdsConfig(
            useTestAdIds = useTestIds,
            bannerAdUnitId = adUnit(config, KEY_BANNER_AD_UNIT_ID, REAL_BANNER_AD_UNIT_ID, TEST_BANNER_AD_UNIT_ID, useTestIds),
            interstitialAdUnitId = adUnit(config, KEY_INTERSTITIAL_AD_UNIT_ID, REAL_INTERSTITIAL_AD_UNIT_ID, TEST_INTERSTITIAL_AD_UNIT_ID, useTestIds),
            rewardedAdUnitId = adUnit(config, KEY_REWARDED_AD_UNIT_ID, REAL_REWARDED_AD_UNIT_ID, TEST_REWARDED_AD_UNIT_ID, useTestIds),
            rewardedCredits = config.getLong(KEY_REWARDED_CREDITS).toInt().coerceAtLeast(1),
            rideCreditCost = config.getLong(KEY_RIDE_CREDIT_COST).toInt().coerceAtLeast(1),
            calculatorCreditCost = config.getLong(KEY_CALCULATOR_CREDIT_COST).toInt().coerceAtLeast(1),
            zoneSlotCreditCost = config.getLong(KEY_ZONE_SLOT_CREDIT_COST).toInt().coerceAtLeast(1),
            dailyFreeRides = config.getLong(KEY_DAILY_FREE_RIDES).toInt().coerceAtLeast(0),
            dailyFreeCalculator = config.getLong(KEY_DAILY_FREE_CALCULATOR).toInt().coerceAtLeast(0),
            freeZoneLimit = config.getLong(KEY_FREE_ZONE_LIMIT).toInt().coerceAtLeast(0),
            maxCreditZoneSlots = config.getLong(KEY_MAX_CREDIT_ZONE_SLOTS).toInt().coerceAtLeast(0),
            interstitialRideInterval = config.getLong(KEY_INTERSTITIAL_RIDE_INTERVAL).toInt().coerceAtLeast(1),
            interstitialCooldownMs = config.getLong(KEY_INTERSTITIAL_COOLDOWN_MS).coerceAtLeast(0L),
            bannerAdsEnabled = config.getBoolean(KEY_BANNER_ADS_ENABLED),
            interstitialAdsEnabled = config.getBoolean(KEY_INTERSTITIAL_ADS_ENABLED),
            rewardedAdsEnabled = config.getBoolean(KEY_REWARDED_ADS_ENABLED),
            mediationReady = config.getBoolean(KEY_MEDIATION_READY),
            nativeAdsExperimentEnabled = config.getBoolean(KEY_NATIVE_ADS_EXPERIMENT_ENABLED),
        )
    }

    private fun adUnit(
        config: FirebaseRemoteConfig,
        key: String,
        realId: String,
        testId: String,
        useTestIds: Boolean,
    ): String {
        if (useTestIds) return testId
        return config.getString(key).takeIf { it.startsWith("ca-app-pub-") } ?: realId
    }

    private fun defaults(): Map<String, Any> = mapOf(
        KEY_USE_TEST_AD_IDS to false,
        KEY_BANNER_AD_UNIT_ID to REAL_BANNER_AD_UNIT_ID,
        KEY_INTERSTITIAL_AD_UNIT_ID to REAL_INTERSTITIAL_AD_UNIT_ID,
        KEY_REWARDED_AD_UNIT_ID to REAL_REWARDED_AD_UNIT_ID,
        KEY_REWARDED_CREDITS to DEFAULT_REWARDED_CREDITS,
        KEY_RIDE_CREDIT_COST to DEFAULT_RIDE_CREDIT_COST,
        KEY_CALCULATOR_CREDIT_COST to DEFAULT_CALCULATOR_CREDIT_COST,
        KEY_ZONE_SLOT_CREDIT_COST to DEFAULT_ZONE_SLOT_CREDIT_COST,
        KEY_DAILY_FREE_RIDES to DEFAULT_DAILY_FREE_RIDES,
        KEY_DAILY_FREE_CALCULATOR to DEFAULT_DAILY_FREE_CALCULATOR,
        KEY_FREE_ZONE_LIMIT to DEFAULT_FREE_ZONE_LIMIT,
        KEY_MAX_CREDIT_ZONE_SLOTS to DEFAULT_MAX_CREDIT_ZONE_SLOTS,
        KEY_INTERSTITIAL_RIDE_INTERVAL to DEFAULT_INTERSTITIAL_RIDE_INTERVAL,
        KEY_INTERSTITIAL_COOLDOWN_MS to DEFAULT_INTERSTITIAL_COOLDOWN_MS,
        KEY_BANNER_ADS_ENABLED to true,
        KEY_INTERSTITIAL_ADS_ENABLED to true,
        KEY_REWARDED_ADS_ENABLED to true,
        KEY_MEDIATION_READY to false,
        KEY_NATIVE_ADS_EXPERIMENT_ENABLED to false,
    )

    private companion object {
        const val KEY_USE_TEST_AD_IDS = "ads_use_test_ids"
        const val KEY_BANNER_AD_UNIT_ID = "admob_banner_ad_unit_id"
        const val KEY_INTERSTITIAL_AD_UNIT_ID = "admob_interstitial_ad_unit_id"
        const val KEY_REWARDED_AD_UNIT_ID = "admob_rewarded_ad_unit_id"
        const val KEY_REWARDED_CREDITS = "ad_rewarded_credits"
        const val KEY_RIDE_CREDIT_COST = "ad_ride_credit_cost"
        const val KEY_CALCULATOR_CREDIT_COST = "ad_calculator_credit_cost"
        const val KEY_ZONE_SLOT_CREDIT_COST = "ad_zone_slot_credit_cost"
        const val KEY_DAILY_FREE_RIDES = "free_daily_rides"
        const val KEY_DAILY_FREE_CALCULATOR = "free_daily_calculator"
        const val KEY_FREE_ZONE_LIMIT = "free_zone_limit"
        const val KEY_MAX_CREDIT_ZONE_SLOTS = "credit_max_extra_zone_slots"
        const val KEY_INTERSTITIAL_RIDE_INTERVAL = "ad_interstitial_ride_interval"
        const val KEY_INTERSTITIAL_COOLDOWN_MS = "ad_interstitial_cooldown_ms"
        const val KEY_BANNER_ADS_ENABLED = "banner_ads_enabled"
        const val KEY_INTERSTITIAL_ADS_ENABLED = "interstitial_ads_enabled"
        const val KEY_REWARDED_ADS_ENABLED = "rewarded_ads_enabled"
        const val KEY_MEDIATION_READY = "admob_mediation_ready"
        const val KEY_NATIVE_ADS_EXPERIMENT_ENABLED = "native_ads_experiment_enabled"
    }
}
