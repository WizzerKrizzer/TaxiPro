package com.taxipro.ui.viewmodel

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxipro.data.billing.BillingManager
import com.taxipro.data.billing.PlanType
import kotlinx.coroutines.launch

class PremiumViewModel(application: Application) : AndroidViewModel(application) {

    private val billing = BillingManager(application)

    val isPremium      = billing.isPremium
    val purchaseState  = billing.purchaseState
    val debugPlanOverride = billing.debugPlanOverride

    // Product details for each plan
    val detailsMonthly  = billing.detailsMonthly
    val detailsYearly   = billing.detailsYearly
    val detailsLifetime = billing.detailsLifetime

    // Back-compat (used in PremiumScreen for old single-product references)
    val productDetails  = billing.productDetails

    fun purchase(activity: Activity, plan: PlanType = PlanType.LIFETIME) =
        billing.launchPurchase(activity, plan)

    fun restore() = viewModelScope.launch { billing.restorePurchases() }

    fun resetState() = billing.resetPurchaseState()

    fun setDebugPlanOverride(value: String?) = billing.setDebugPlanOverride(value)

    override fun onCleared() {
        super.onCleared()
        billing.destroy()
    }
}
