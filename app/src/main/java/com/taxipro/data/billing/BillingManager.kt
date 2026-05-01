package com.taxipro.data.billing

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ── DataStore for persisting purchase status offline ──────────────────────────
private val Context.billingDataStore by preferencesDataStore(name = "billing_prefs")
private val KEY_PREMIUM = booleanPreferencesKey("is_premium")

/** One-time purchase product ID — must match exactly what you create in Play Console. */
const val PRODUCT_ID_PREMIUM = "taxipro_premium"

class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Premium state (persisted so it works offline) ─────────────────────────
    val isPremium: Flow<Boolean> = context.billingDataStore.data
        .map { prefs -> prefs[KEY_PREMIUM] ?: false }

    private suspend fun setPremium(value: Boolean) {
        context.billingDataStore.edit { it[KEY_PREMIUM] = value }
    }

    // ── UI-facing state flows ─────────────────────────────────────────────────
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    sealed class PurchaseUiState {
        object Idle    : PurchaseUiState()
        object Loading : PurchaseUiState()
        object Success : PurchaseUiState()
        data class Error(val message: String) : PurchaseUiState()
    }

    // ── BillingClient setup ───────────────────────────────────────────────────
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
                // Retry after short delay; Play Store may restart
                scope.launch {
                    delay(5_000)
                    connect()
                }
            }
        })
    }

    // ── Query product from Play Console ───────────────────────────────────────
    private suspend fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PREMIUM)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _productDetails.value = result.productDetailsList?.firstOrNull()
        }
    }

    // ── Restore existing purchases (called on startup + by user) ─────────────
    suspend fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            handlePurchases(result.purchasesList)
        }
    }

    // ── Launch the Play Store purchase sheet ─────────────────────────────────
    fun launchPurchase(activity: Activity) {
        val details = _productDetails.value ?: run {
            _purchaseState.value = PurchaseUiState.Error("Product not available yet — try again shortly.")
            return
        }
        _purchaseState.value = PurchaseUiState.Loading

        val productDetailsParams = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParams)
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    // ── PurchasesUpdatedListener ─────────────────────────────────────────────
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

    // ── Acknowledge + persist ─────────────────────────────────────────────────
    private suspend fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(ackParams)
                }
                setPremium(true)
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
