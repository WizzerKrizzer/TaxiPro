package com.taxipro.data.ads

import android.app.Activity
import android.app.Application
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdsState(
    val canRequestAds: Boolean = false,
    val initialized: Boolean = false,
    val rewardedLoading: Boolean = false,
)

class AdManager(
    private val application: Application,
    private val configProvider: () -> AdsConfig = { AdsConfig() },
    private val analytics: AdsAnalytics = AdsAnalytics(application),
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(application)

    private val _state = MutableStateFlow(AdsState())
    val state: StateFlow<AdsState> = _state.asStateFlow()

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null

    fun initialize(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    refreshCanRequestAds()
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAds()
                    }
                }
            },
            {
                refreshCanRequestAds()
                if (consentInformation.canRequestAds()) {
                    initializeMobileAds()
                }
            },
        )
        refreshCanRequestAds()
        if (consentInformation.canRequestAds()) {
            initializeMobileAds()
        }
    }

    fun preloadInterstitial() {
        val config = configProvider()
        if (!config.interstitialAdsEnabled || !_state.value.canRequestAds || interstitialAd != null) return
        InterstitialAd.load(
            application,
            config.interstitialAdUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    analytics.log("ad_interstitial_loaded", "unit_type" to unitType(config))
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    analytics.log(
                        "ad_interstitial_load_failed",
                        "code" to error.code,
                        "message" to error.message,
                    )
                }
            },
        )
    }

    fun showInterstitialIfReady(activity: Activity) {
        if (!configProvider().interstitialAdsEnabled || !_state.value.canRequestAds) return
        val ad = interstitialAd ?: run {
            preloadInterstitial()
            return
        }
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadInterstitial()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                preloadInterstitial()
            }
        }
        ad.show(activity)
        analytics.log("ad_interstitial_shown")
    }

    fun showRewarded(activity: Activity, onEarned: () -> Unit, onUnavailable: () -> Unit) {
        if (!configProvider().rewardedAdsEnabled || !_state.value.canRequestAds) {
            onUnavailable()
            initialize(activity)
            return
        }
        val readyAd = rewardedAd
        if (readyAd != null) {
            rewardedAd = null
            readyAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    preloadRewarded()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    preloadRewarded()
                }
            }
            readyAd.show(activity) {
                analytics.log("ad_rewarded_earned", "amount" to configProvider().rewardedCredits)
                onEarned()
            }
            return
        }
        preloadRewarded(onLoaded = {
            showRewarded(activity, onEarned, onUnavailable)
        }, onFailed = onUnavailable)
    }

    fun preloadRewarded(onLoaded: (() -> Unit)? = null, onFailed: (() -> Unit)? = null) {
        val config = configProvider()
        if (!config.rewardedAdsEnabled || !_state.value.canRequestAds || _state.value.rewardedLoading || rewardedAd != null) return
        _state.value = _state.value.copy(rewardedLoading = true)
        RewardedAd.load(
            application,
            config.rewardedAdUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    _state.value = _state.value.copy(rewardedLoading = false)
                    analytics.log("ad_rewarded_loaded", "unit_type" to unitType(config))
                    onLoaded?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    _state.value = _state.value.copy(rewardedLoading = false)
                    analytics.log(
                        "ad_rewarded_load_failed",
                        "code" to error.code,
                        "message" to error.message,
                    )
                    onFailed?.invoke()
                }
            },
        )
    }

    fun openAdInspector(activity: Activity, onClosed: (String?) -> Unit = {}) {
        if (!_state.value.initialized) {
            initialize(activity)
        }
        MobileAds.openAdInspector(activity) { error ->
            analytics.log(
                "ad_inspector_closed",
                "success" to (error == null),
                "message" to (error?.message ?: ""),
            )
            onClosed(error?.message)
        }
    }

    private fun initializeMobileAds() {
        if (_state.value.initialized) return
        scope.launch(Dispatchers.IO) {
            MobileAds.initialize(application) {}
            scope.launch {
                _state.value = _state.value.copy(initialized = true)
                preloadInterstitial()
                preloadRewarded()
            }
        }
    }

    private fun refreshCanRequestAds() {
        _state.value = _state.value.copy(canRequestAds = consentInformation.canRequestAds())
    }

    private fun unitType(config: AdsConfig): String = if (config.useTestAdIds) "test" else "real"
}
