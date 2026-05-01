package com.taxipro.ui.viewmodel

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxipro.data.billing.BillingManager
import kotlinx.coroutines.launch

class PremiumViewModel(application: Application) : AndroidViewModel(application) {

    private val billing = BillingManager(application)

    /** True once the user has purchased — persisted in DataStore (offline-safe). */
    val isPremium      = billing.isPremium
    val productDetails = billing.productDetails
    val purchaseState  = billing.purchaseState

    fun purchase(activity: Activity) = billing.launchPurchase(activity)

    fun restore() = viewModelScope.launch { billing.restorePurchases() }

    fun resetState() = billing.resetPurchaseState()

    override fun onCleared() {
        super.onCleared()
        billing.destroy()
    }
}
