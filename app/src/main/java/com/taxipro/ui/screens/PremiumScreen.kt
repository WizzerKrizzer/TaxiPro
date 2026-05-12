package com.taxipro.ui.screens

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.data.billing.BillingManager
import com.taxipro.data.billing.PlanType
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.PremiumViewModel

private val Gold    = Color(0xFFFFBF00)
private val GoldDim = Color(0xFFFFD966)

@Composable
fun PremiumScreen(premiumVm: PremiumViewModel) {
    val tc        = LocalThemeColors.current
    val st        = LocalStrings.current
    val context   = LocalContext.current
    val activity  = context as? Activity

    val isPremium    by premiumVm.isPremium.collectAsState(initial = false)
    val purchState   by premiumVm.purchaseState.collectAsState()
    val detMonthly   by premiumVm.detailsMonthly.collectAsState()
    val detYearly    by premiumVm.detailsYearly.collectAsState()
    val detLifetime  by premiumVm.detailsLifetime.collectAsState()

    val isLoading = purchState is BillingManager.PurchaseUiState.Loading

    // Default selected plan — yearly is pre-selected as "best value"
    var selectedPlan by remember { mutableStateOf(PlanType.YEARLY) }

    DisposableEffect(Unit) { onDispose { premiumVm.resetState() } }

    // Helper: extract price string from ProductDetails
    fun monthlyPrice()  = detMonthly?.subscriptionOfferDetails
        ?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "—"
    fun yearlyPrice()   = detYearly?.subscriptionOfferDetails
        ?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "—"
    fun lifetimePrice() = detLifetime?.oneTimePurchaseOfferDetails?.formattedPrice ?: "—"

    // Whether the currently selected plan's details have loaded
    val canPurchase = !isLoading && when (selectedPlan) {
        PlanType.MONTHLY  -> detMonthly  != null
        PlanType.YEARLY   -> detYearly   != null
        PlanType.LIFETIME -> detLifetime != null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Hero banner ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Gold.copy(alpha = 0.22f), tc.background))
                )
                .padding(top = 48.dp, bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(Gold.copy(alpha = 0.18f), CircleShape)
                        .border(2.dp, Gold.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Stars,
                        contentDescription = null,
                        tint     = Gold,
                        modifier = Modifier.size(46.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(st.premium.screenTitle,
                    color = Gold, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(st.premium.screenSubtitle,
                    color = tc.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp))
            }
        }

        // ── Already premium ───────────────────────────────────────────────────
        AnimatedVisibility(visible = isPremium) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors   = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.12f)),
                shape    = RoundedCornerShape(14.dp),
                border   = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null,
                        tint = Gold, modifier = Modifier.size(28.dp))
                    Column {
                        Text(st.premium.activeTitle,
                            color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(st.premium.activeSub, color = tc.muted, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Plan selector ─────────────────────────────────────────────────────
        if (!isPremium) {
            Text(
                st.premium.choosePlan,
                color = tc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ── Monthly ──────────────────────────────────────────────────
                PlanCard(
                    selected  = selectedPlan == PlanType.MONTHLY,
                    badge     = null,
                    planName  = st.premium.planMonthlyName,
                    priceMain = monthlyPrice(),
                    priceSub  = st.premium.planMonthlyPer,
                    extraNote = null,
                    onClick   = { selectedPlan = PlanType.MONTHLY },
                    tc        = tc,
                )

                // ── Yearly — pre-selected, "Best Value" badge ─────────────────
                PlanCard(
                    selected  = selectedPlan == PlanType.YEARLY,
                    badge     = st.premium.planBestValue,
                    planName  = st.premium.planYearlyName,
                    priceMain = yearlyPrice(),
                    priceSub  = st.premium.planYearlyPer,
                    extraNote = buildYearlyNote(detYearly, st.premium.planPerMonth),
                    onClick   = { selectedPlan = PlanType.YEARLY },
                    tc        = tc,
                )

                // ── Lifetime ──────────────────────────────────────────────────
                PlanCard(
                    selected  = selectedPlan == PlanType.LIFETIME,
                    badge     = null,
                    planName  = st.premium.planLifetimeName,
                    priceMain = lifetimePrice(),
                    priceSub  = st.premium.planLifetimePer,
                    extraNote = st.premium.planLifetimeNote,
                    onClick   = { selectedPlan = PlanType.LIFETIME },
                    tc        = tc,
                )
            }

            Spacer(Modifier.height(20.dp))
        }

        // ── Feature list ──────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            colors   = CardDefaults.cardColors(containerColor = tc.card),
            shape    = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(st.premium.featuresTitle,
                    color = tc.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                HorizontalDivider(color = tc.surface)
                st.premium.features.forEach { (icon, text) ->
                    FeatureRow(icon = icon, text = text, checked = isPremium, tc = tc)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Error ─────────────────────────────────────────────────────────────
        val errorMsg = (purchState as? BillingManager.PurchaseUiState.Error)?.message
        AnimatedVisibility(visible = errorMsg != null) {
            Text(errorMsg ?: "", color = tc.red, fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(8.dp))
        }

        // ── Purchase / loading button ─────────────────────────────────────────
        if (!isPremium) {
            val btnLabel = when {
                isLoading -> ""
                else -> when (selectedPlan) {
                    PlanType.MONTHLY  -> st.premium.btnSubscribeMonthly.format(monthlyPrice())
                    PlanType.YEARLY   -> st.premium.btnSubscribeYearly.format(yearlyPrice())
                    PlanType.LIFETIME -> st.premium.btnBuyLifetime.format(lifetimePrice())
                }
            }

            Button(
                onClick  = { activity?.let { premiumVm.purchase(it, selectedPlan) } },
                enabled  = canPurchase,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(54.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor         = Gold,
                    contentColor           = Color(0xFF1A1200),
                    disabledContainerColor = Gold.copy(alpha = 0.4f),
                    disabledContentColor   = Color(0xFF1A1200).copy(alpha = 0.5f),
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(22.dp),
                        color       = Color(0xFF1A1200),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(btnLabel, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick  = { premiumVm.restore() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Text(st.premium.restoreBtn, color = tc.muted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Fine-print note depending on selected plan
            val note = when (selectedPlan) {
                PlanType.MONTHLY, PlanType.YEARLY -> st.premium.subRenewNote
                PlanType.LIFETIME                 -> st.premium.oneTimeNote
            }
            Text(note, color = tc.muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Plan card ─────────────────────────────────────────────────────────────────
@Composable
private fun PlanCard(
    selected   : Boolean,
    badge      : String?,
    planName   : String,
    priceMain  : String,
    priceSub   : String,
    extraNote  : String?,
    onClick    : () -> Unit,
    tc         : ThemeColors,
) {
    val borderColor = if (selected) Gold else tc.muted.copy(alpha = 0.2f)
    val bgColor     = if (selected) Gold.copy(alpha = 0.08f) else tc.card

    Card(
        onClick  = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width  = if (selected) 2.dp else 1.dp,
                color  = borderColor,
                shape  = RoundedCornerShape(14.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape  = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: radio dot + name + extra note
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier              = Modifier.weight(1f),
            ) {
                // Radio indicator
                Box(
                    Modifier
                        .size(20.dp)
                        .border(
                            width  = if (selected) 6.dp else 1.5.dp,
                            color  = if (selected) Gold else tc.muted.copy(alpha = 0.4f),
                            shape  = CircleShape,
                        ),
                )
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(planName,
                            color      = if (selected) tc.textPrimary else tc.muted,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        // "НАЙ-ИЗГОДЕН" badge
                        if (badge != null) {
                            Box(
                                Modifier
                                    .background(Gold, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(badge,
                                    color      = Color(0xFF1A1200),
                                    fontSize   = 9.sp,
                                    fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    if (extraNote != null) {
                        Text(extraNote,
                            color    = tc.muted,
                            fontSize = 11.sp)
                    }
                }
            }

            // Right: price
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    priceMain,
                    color      = if (selected) Gold else tc.textPrimary,
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(priceSub, color = tc.muted, fontSize = 10.sp)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Tries to compute a per-month note from the yearly price, e.g. "≈ 3.33 BGN / mo" */
@Composable
private fun buildYearlyNote(
    details       : com.android.billingclient.api.ProductDetails?,
    perMonthLabel : String,
): String? {
    val phase = details?.subscriptionOfferDetails
        ?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()
        ?: return null
    // micros → currency units  (1_000_000 micros = 1 unit)
    val yearlyMicros   = phase.priceAmountMicros
    val perMonthMicros = yearlyMicros / 12
    val perMonth       = perMonthMicros / 1_000_000.0
    val currency       = phase.priceCurrencyCode
    return "≈ %.2f %s %s".format(perMonth, currency, perMonthLabel)
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String, checked: Boolean, tc: ThemeColors) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Gold, modifier = Modifier.size(18.dp))
        }
        Text(text, color = tc.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (checked) {
            Icon(Icons.Default.Check, null, tint = Gold, modifier = Modifier.size(16.dp))
        }
    }
}
