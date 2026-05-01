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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.data.billing.BillingManager
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.PremiumViewModel

private val Gold = Color(0xFFFFBF00)
private val GoldDim = Color(0xFFFFD966)

@Composable
fun PremiumScreen(premiumVm: PremiumViewModel) {
    val tc           = LocalThemeColors.current
    val st           = LocalStrings.current
    val context      = LocalContext.current
    val activity     = context as? Activity

    val isPremium    by premiumVm.isPremium.collectAsState(initial = false)
    val details      by premiumVm.productDetails.collectAsState()
    val purchState   by premiumVm.purchaseState.collectAsState()

    val isLoading = purchState is BillingManager.PurchaseUiState.Loading

    // Clear transient error / success after navigation back
    DisposableEffect(Unit) { onDispose { premiumVm.resetState() } }

    // Price string from Play Console (null until billing client connects)
    val priceText = details?.oneTimePurchaseOfferDetails?.formattedPrice ?: "—"

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
                    Brush.verticalGradient(
                        listOf(Gold.copy(alpha = 0.22f), tc.background)
                    )
                )
                .padding(top = 48.dp, bottom = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Crown icon inside golden circle
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(Gold.copy(alpha = 0.18f), CircleShape)
                        .border(2.dp, Gold.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👑", fontSize = 42.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = st.premium.screenTitle,
                    color = Gold,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = st.premium.screenSubtitle,
                    color = tc.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }

        // ── Already premium ───────────────────────────────────────────────────
        AnimatedVisibility(visible = isPremium) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Gold, modifier = Modifier.size(28.dp))
                    Column {
                        Text(st.premium.activeTitle, color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(st.premium.activeSub, color = tc.muted, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Feature list ──────────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(containerColor = tc.card),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = st.premium.featuresTitle,
                    color = tc.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                HorizontalDivider(color = tc.surface)
                st.premium.features.forEach { (icon, text) ->
                    FeatureRow(icon = icon, text = text, checked = isPremium, tc = tc)
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // ── Error snackbar area ───────────────────────────────────────────────
        val errorMsg = (purchState as? BillingManager.PurchaseUiState.Error)?.message
        AnimatedVisibility(visible = errorMsg != null) {
            Text(
                text = errorMsg ?: "",
                color = tc.red,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Purchase button ───────────────────────────────────────────────────
        if (!isPremium) {
            Button(
                onClick = { activity?.let { premiumVm.purchase(it) } },
                enabled = !isLoading && details != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor   = Color(0xFF1A1200),
                    disabledContainerColor = Gold.copy(alpha = 0.4f),
                    disabledContentColor   = Color(0xFF1A1200).copy(alpha = 0.5f),
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color    = Color(0xFF1A1200),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "${st.premium.buyBtn}  $priceText",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Restore button
            TextButton(
                onClick = { premiumVm.restore() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Text(st.premium.restoreBtn, color = tc.muted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = st.premium.oneTimeNote,
                color = tc.muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    text: String,
    checked: Boolean,
    tc: ThemeColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
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
