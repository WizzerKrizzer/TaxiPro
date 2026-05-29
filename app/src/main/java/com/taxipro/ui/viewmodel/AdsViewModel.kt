package com.taxipro.ui.viewmodel

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxipro.data.ads.AdsAnalytics
import com.taxipro.data.ads.AdsConfigRepository
import com.taxipro.data.ads.AdCreditsRepository
import com.taxipro.data.ads.AdCreditsState
import com.taxipro.data.ads.AdManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdsViewModel(application: Application) : AndroidViewModel(application) {
    private val configRepository = AdsConfigRepository(application)
    private val analytics = AdsAnalytics(application)
    private val creditsRepository = AdCreditsRepository(application) { configRepository.state.value }
    private val adManager = AdManager(application, { configRepository.state.value }, analytics)

    val creditsState: StateFlow<AdCreditsState> = combine(
        creditsRepository.state,
        configRepository.state,
    ) { credits, config -> credits.copy(config = config) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AdCreditsState(),
    )
    val adsState = adManager.state
    val adsConfig = configRepository.state

    fun initialize(activity: Activity) {
        adManager.initialize(activity)
    }

    fun preloadAds() {
        adManager.preloadInterstitial()
        adManager.preloadRewarded()
    }

    fun refreshConfig() {
        configRepository.refresh()
    }

    fun watchRewardedForCredits(activity: Activity, onUnavailable: () -> Unit = {}) {
        adManager.showRewarded(
            activity = activity,
            onEarned = {
                viewModelScope.launch {
                    creditsRepository.addRewardedCredits(configRepository.state.value.rewardedCredits)
                }
            },
            onUnavailable = onUnavailable,
        )
    }

    fun consumeRideAccess(isPremium: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val allowed = creditsRepository.consumeRideAccess(isPremium)
            if (!allowed) analytics.log("ad_limit_hit", "feature" to "ride")
            onResult(allowed)
        }
    }

    fun consumeCalculatorAccess(isPremium: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val allowed = creditsRepository.consumeCalculatorAccess(isPremium)
            if (!allowed) analytics.log("ad_limit_hit", "feature" to "calculator")
            onResult(allowed)
        }
    }

    fun buyZoneSlot(isPremium: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val bought = creditsRepository.buyZoneSlot(isPremium)
            if (bought) analytics.log("ad_credits_spent", "feature" to "zone_slot")
            onResult(bought)
        }
    }

    fun recordCompletedRide(activity: Activity, isPremium: Boolean) {
        viewModelScope.launch {
            if (creditsRepository.recordCompletedRideForInterstitial(isPremium)) {
                adManager.showInterstitialIfReady(activity)
            }
        }
    }

    fun openAdInspector(activity: Activity, onClosed: (String?) -> Unit = {}) {
        adManager.openAdInspector(activity, onClosed)
    }
}
