package com.taxipro.data.billing

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ── DEBUG ─────────────────────────────────────────────────────────────────────
/** Set to true to bypass billing and act as premium. MUST be false before publishing. */
private const val DEBUG_FORCE_PREMIUM = true

// ── DataStore ─────────────────────────────────────────────────────────────────
private val Context.billingDataStore by preferencesDataStore(name = "billing_prefs")
private val KEY_PREMIUM  = booleanPreferencesKey("is_premium")
private val KEY_LIFETIME = booleanPreferencesKey("is_lifetime")  // one-time: never revoked

/** Product IDs — must match exactly what you create in Play Console. */
const val PRODUCT_ID_PREMIUM  = "taxipro_premium"   // one-time (lifetime)
const val PRODUCT_ID_MONTHLY  = "taxipro_monthly"   // monthly subscription
const val PRODUCT_ID_YEARLY   = "taxipro_yearly"    // yearly  subscription

enum class PlanType { MONTHLY, YEARLY, LIFETIME }

class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Premium state ─────────────────────────────────────────────────────────
    val isPremium: Flow<Boolean> = if (DEBUG_FORCE_PREMIUM) {
        kotlinx.coroutines.flow.flowOf(true)
    } else {
        context.billingDataStore.data.map { it[KEY_PREMIUM] ?: false }
    }

    private suspend fun setPremium(value: Boolean) {
        context.billingDataStore.edit { it[KEY_PREMIUM] = value }
    }

    private suspend fun setLifetime(value: Boolean) {
        context.billingDataStore.edit {
            it[KEY_LIFETIME] = value
            if (value) it[KEY_PREMIUM] = true   // lifetime → always premium
        }
    }

    private val hasLifetime: Flow<Boolean> = context.billingDataStore.data
        .map { it[KEY_LIFETIME] ?: false }

    // ── Product details ───────────────────────────────────────────────────────
    private val _detailsMonthly  = MutableStateFlow<ProductDetails?>(null)
    private val _detailsYearly   = MutableStateFlow<ProductDetails?>(null)
    private val _detailsLifetime = MutableStateFlow<ProductDetails?>(null)

    val detailsMonthly : StateFlow<ProductDetails?> = _detailsMonthly.asStateFlow()
    val detailsYearly  : StateFlow<ProductDetails?> = _detailsYearly.asStateFlow()
    val detailsLifetime: StateFlow<ProductDetails?> = _detailsLifetime.asStateFlow()

    // Back-compat alias used by old PremiumViewModel
    val productDetails: StateFlow<ProductDetails?> = _detailsLifetime.asStateFlow()

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    sealed class PurchaseUiState {
        object Idle    : PurchaseUiState()
        object Loading : PurchaseUiState()
        object Success : PurchaseUiState()
        data class Error(val message: String) : PurchaseUiState()
    }

    // ── BillingClient ─────────────────────────────────────────────────────────
    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init { connect() }

    // ── Connection ────────────────────────────────────────────────────────────
    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryProductDetails()
                        restorePurchases()
                    }
                }
            }
            override fun onBillingServiceDisconnected() {
                scope.launch { delay(5_000); connect() }
            }
        })
    }

    // ── Query products ────────────────────────────────────────────────────────
    private suspend fun queryProductDetails() {
        // One-time lifetime purchase
        val inappResult = billingClient.queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PREMIUM)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                ))
                .build()
        )
        if (inappResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _detailsLifetime.value = inappResult.productDetailsList?.firstOrNull()
        }

        // Subscriptions (monthly + yearly)
        val subsResult = billingClient.queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_YEARLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ))
                .build()
        )
        if (subsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            subsResult.productDetailsList?.forEach { d ->
                when (d.productId) {
                    PRODUCT_ID_MONTHLY -> _detailsMonthly.value = d
                    PRODUCT_ID_YEARLY  -> _detailsYearly.value  = d
                }
            }
        }
    }

    // ── Launch purchase ───────────────────────────────────────────────────────
    fun launchPurchase(activity: Activity, plan: PlanType = PlanType.LIFETIME) {
        val details = when (plan) {
            PlanType.MONTHLY  -> _detailsMonthly.value
            PlanType.YEARLY   -> _detailsYearly.value
            PlanType.LIFETIME -> _detailsLifetime.value
        } ?: run {
            _purchaseState.value = PurchaseUiState.Error("Product not available yet — try again shortly.")
            return
        }
        _purchaseState.value = PurchaseUiState.Loading

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        // Subscriptions require an offerToken
        details.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let {
            paramsBuilder.setOfferToken(it)
        }

        billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(paramsBuilder.build()))
                .build()
        )
    }

    // ── PurchasesUpdatedListener ──────────────────────────────────────────────
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                scope.launch { handlePurchases(purchases.orEmpty()) }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _purchaseState.value = PurchaseUiState.Idle
            else ->
                _purchaseState.value = PurchaseUiState.Error(result.debugMessage)
        }
    }

    // ── Restore (called on startup + by user) ─────────────────────────────────
    suspend fun restorePurchases() {
        var hasActivePurchase = false
        var hasBillingSuccess = false

        // Check one-time purchases
        val inappResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        if (inappResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            hasBillingSuccess = true
            for (p in inappResult.purchasesList) {
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    acknowledgeIfNeeded(p)
                    hasActivePurchase = true
                    setLifetime(true)   // one-time: persist permanently
                }
            }
        }

        // Check subscriptions
        val subsResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        if (subsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            hasBillingSuccess = true
            for (p in subsResult.purchasesList) {
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    acknowledgeIfNeeded(p)
                    hasActivePurchase = true
                }
            }
        }

        // Update premium status:
        // - If billing succeeded, reflect actual state
        // - If billing failed (offline), don't revoke premium (DataStore keeps last value)
        if (hasBillingSuccess) {
            // Don't revoke if user has a lifetime purchase saved locally
            val isLifetime = hasLifetime.first()
            if (!isLifetime) {
                setPremium(hasActivePurchase)
            }
            if (hasActivePurchase) {
                setPremium(true)
                _purchaseState.value = PurchaseUiState.Success
            }
        }
    }

    // ── Acknowledge purchase ──────────────────────────────────────────────────
    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            )
        }
    }

    // ── Handle new purchases (from onPurchasesUpdated) ────────────────────────
    private suspend fun handlePurchases(purchases: List<Purchase>) {
        for (p in purchases) {
            if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                acknowledgeIfNeeded(p)
                // Check if it's the lifetime product
                if (p.products.contains(PRODUCT_ID_PREMIUM)) setLifetime(true)
                else setPremium(true)
                _purchaseState.value = PurchaseUiState.Success
            }
        }
    }

    fun resetPurchaseState() { _purchaseState.value = PurchaseUiState.Idle }

    fun destroy() {
        billingClient.endConnection()
        scope.cancel()
    }
}
