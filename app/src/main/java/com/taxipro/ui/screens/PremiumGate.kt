package com.taxipro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.taxipro.ui.theme.LocalStrings

// ── CompositionLocals ─────────────────────────────────────────────────────────
// Lets every screen read isPremium / trigger upgrade without prop-drilling.

val LocalIsPremium = compositionLocalOf { false }
val LocalOnUpgrade = compositionLocalOf<() -> Unit> { {} }

// Reused gold tint
private val GoldGate = Color(0xFFFFBF00)

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Wraps [content] with a blur + upgrade-card overlay when the user is not premium.
 * When premium, renders [content] normally.
 *
 * @param featureHint  Optional hint shown below the gate title.
 *                     Defaults to `st.premium.gateSub` when blank.
 */
@Composable
fun PremiumGate(
    modifier    : Modifier = Modifier,
    featureHint : String   = "",
    content     : @Composable () -> Unit,
) {
    val isPremium = LocalIsPremium.current
    val onUpgrade = LocalOnUpgrade.current
    val st        = LocalStrings.current
    val tc        = LocalThemeColors.current

    if (isPremium) {
        Box(modifier) { content() }
        return
    }

    Box(modifier) {
        // ── Blurred content behind the overlay ──────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .blur(20.dp),
        ) {
            content()
        }

        // ── Semi-transparent frost overlay ───────────────────────────────────
        Box(
            modifier         = Modifier
                .matchParentSize()
                .background(tc.background.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier  = Modifier
                    .fillMaxWidth(0.85f)
                    .border(1.dp, GoldGate.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                colors    = CardDefaults.cardColors(containerColor = tc.card),
                shape     = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            ) {
                Column(
                    modifier            = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Stars,
                        contentDescription = null,
                        tint               = GoldGate,
                        modifier           = Modifier.size(40.dp),
                    )

                    Text(
                        text       = st.premium.gateTitle,
                        color      = GoldGate,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize   = 17.sp,
                        textAlign  = TextAlign.Center,
                    )

                    Text(
                        text      = featureHint.ifBlank { st.premium.gateSub },
                        color     = tc.muted,
                        fontSize  = 13.sp,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick  = onUpgrade,
                        shape    = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = GoldGate,
                            contentColor   = Color(0xFF1A1200),
                        ),
                    ) {
                        Text(st.premium.gateBtn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Compact inline banner — used when premium unlocks extra *data* rather than
 * replacing visible content (e.g. "stats beyond 1 week").
 * Shows a lock icon, a short message, and an Upgrade button.
 */
@Composable
fun PremiumBanner(
    message  : String,
    modifier : Modifier = Modifier,
) {
    val onUpgrade = LocalOnUpgrade.current
    val st        = LocalStrings.current
    val tc        = LocalThemeColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = GoldGate.copy(alpha = 0.10f)),
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(1.dp, GoldGate.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Lock,
                contentDescription = null,
                tint               = GoldGate,
                modifier           = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text       = st.premium.gateTitle,
                    color      = GoldGate,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp,
                )
                Text(
                    text     = message,
                    color    = tc.muted,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onUpgrade) {
                Text(
                    text       = st.premium.gateBtn,
                    color      = GoldGate,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
/**
 * Standalone upgrade dialog — shown when a locked *action* is tapped
 * (e.g. "More info" / map button in shift history) without blurred content.
 */
@Composable
fun PremiumUpgradeDialog(
    hint      : String = "",
    onUpgrade : () -> Unit,
    onDismiss : () -> Unit,
) {
    val st = LocalStrings.current
    val tc = LocalThemeColors.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape     = RoundedCornerShape(20.dp),
            colors    = CardDefaults.cardColors(containerColor = tc.card),
            border    = BorderStroke(1.dp, GoldGate.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(
                modifier            = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Stars,
                    contentDescription = null,
                    tint               = GoldGate,
                    modifier           = Modifier.size(44.dp),
                )
                Text(
                    text       = st.premium.gateTitle,
                    color      = GoldGate,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 18.sp,
                    textAlign  = TextAlign.Center,
                )
                Text(
                    text      = hint.ifBlank { st.premium.gateSub },
                    color     = tc.muted,
                    fontSize  = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = onUpgrade,
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = GoldGate,
                        contentColor   = Color(0xFF1A1200),
                    ),
                ) {
                    Text(st.premium.gateBtn, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = st.premium.gateMaybeLater,
                        color    = tc.muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
