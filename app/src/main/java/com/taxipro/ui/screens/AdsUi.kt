package com.taxipro.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.taxipro.data.ads.AdCreditsState
import com.taxipro.data.ads.CreditFeature
import com.taxipro.data.db.AppLanguage
import com.taxipro.ui.theme.LocalSettings

val LocalAdCreditsState = compositionLocalOf { AdCreditsState() }
val LocalAdActions = compositionLocalOf { AdActions() }

data class AdActions(
    val watchRewarded: (Activity) -> Unit = {},
    val consumeRide: ((Boolean) -> Unit) -> Unit = { it(false) },
    val consumeCalculator: ((Boolean) -> Unit) -> Unit = { it(false) },
    val buyZoneSlot: ((Boolean) -> Unit) -> Unit = { it(false) },
    val recordCompletedRide: (Activity) -> Unit = {},
    val openAdInspector: (Activity, (String?) -> Unit) -> Unit = { _, cb -> cb("Ad Inspector is not available.") },
    val refreshAdsConfig: () -> Unit = {},
)

@Composable
fun AdMobBanner(adUnitId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        factory = {
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

@Composable
fun CreditLimitDialog(
    feature: CreditFeature,
    title: String,
    message: String,
    onUseCredits: (() -> Unit)?,
    onWatchAd: () -> Unit,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val creditsState = LocalAdCreditsState.current
    val credits = creditsState.credits
    val cost = feature.cost(creditsState)
    val isBg = LocalSettings.current.language == AppLanguage.BG
    val canUseCredits = credits >= cost && onUseCredits != null
    var showSpendConfirmation by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tc.card),
            border = BorderStroke(1.dp, tc.accent.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Stars, null, tint = tc.accent, modifier = Modifier.size(40.dp))
                Text(title, color = tc.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    message,
                    color = tc.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
                Text(
                    if (isBg) "Баланс: $credits кредита" else "Balance: $credits credits",
                    color = tc.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                if (canUseCredits) {
                    Button(
                        onClick = { showSpendConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = tc.green),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            if (isBg) "Използвай $cost кредита" else "Use $cost credits",
                            color = tc.background,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onWatchAd,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tc.accent),
                ) {
                    Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isBg) "Гледай реклама за ${creditsState.config.rewardedCredits} кредита" else "Watch ad for ${creditsState.config.rewardedCredits} credits",
                        fontWeight = FontWeight.Bold,
                    )
                }

                Button(
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFBF00)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (isBg) "Отключи Premium" else "Unlock Premium",
                        color = Color(0xFF1A1200),
                        fontWeight = FontWeight.Bold,
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text(if (isBg) "Не сега" else "Not now", color = tc.muted)
                }
            }
        }
    }

    if (showSpendConfirmation) {
        CreditSpendConfirmationDialog(
            feature = feature,
            cost = cost,
            balanceAfter = credits - cost,
            onConfirm = {
                showSpendConfirmation = false
                onUseCredits?.invoke()
            },
            onDismiss = { showSpendConfirmation = false },
        )
    }
}

@Composable
private fun CreditSpendConfirmationDialog(
    feature: CreditFeature,
    cost: Int,
    balanceAfter: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = LocalThemeColors.current
    val isBg = LocalSettings.current.language == AppLanguage.BG
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tc.card,
        title = {
            Text(
                if (isBg) "Потвърди използване на кредити" else "Confirm credit use",
                color = tc.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                if (isBg) {
                    "Ще бъдат използвани $cost кредита за ${feature.label(isBg)}. След това ще останеш с $balanceAfter кредита."
                } else {
                    "$cost credits will be used for ${feature.label(isBg)}. You will have $balanceAfter credits left."
                },
                color = tc.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = tc.green),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (isBg) "Използвай $cost кредита" else "Use $cost credits",
                    color = tc.background,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBg) "Отказ" else "Cancel", color = tc.muted)
            }
        },
    )
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun CreditFeature.cost(state: AdCreditsState): Int = when (this) {
    CreditFeature.Ride -> state.config.rideCreditCost
    CreditFeature.Calculator -> state.config.calculatorCreditCost
    CreditFeature.ZoneSlot -> state.config.zoneSlotCreditCost
}

private fun CreditFeature.label(isBg: Boolean): String = when (this) {
    CreditFeature.Ride -> if (isBg) "допълнителен курс" else "an extra ride"
    CreditFeature.Calculator -> if (isBg) "изчисляване на маршрут" else "a route calculation"
    CreditFeature.ZoneSlot -> if (isBg) "допълнителна зона" else "an extra zone"
}
