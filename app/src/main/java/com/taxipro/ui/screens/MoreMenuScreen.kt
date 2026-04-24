package com.taxipro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.PremiumViewModel

private val Gold = Color(0xFFFFBF00)

@Composable
fun MoreMenuScreen(
    onNavigate: (String) -> Unit,
    premiumVm: PremiumViewModel,
) {
    val tc        = LocalThemeColors.current
    val st        = LocalStrings.current
    val isPremium by premiumVm.isPremium.collectAsState(initial = false)

    val items = listOf(
        MoreItem("история",       Icons.Default.History,        st.rideHistoryTitle,     st.rideHistorySub,     tc.accent),
        MoreItem("история_смени", Icons.Default.WorkHistory,    st.shiftHistoryTitle,    st.shiftHistorySub,    tc.green),
        MoreItem("calc",          Icons.Default.Calculate,      st.calculatorLabel,      st.calculatorSub,      tc.blue),
        MoreItem("zones",         Icons.Default.LocationOn,     st.zones.zonesTitle,     st.zones.zonesSub,     Color(0xFFFF8F00)),
        MoreItem("export",        Icons.Default.FileDownload,   st.export.menuTitle,     st.export.menuSub,     Color(0xFF8E24AA)),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(st.moreTitle, color = tc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(st.moreSub, color = tc.muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        // ── Premium card (always at top) ─────────────────────────────────────
        PremiumMenuCard(
            isPremium = isPremium,
            title     = st.premium.menuTitle,
            subtitle  = if (isPremium) st.premium.menuSubActive else st.premium.menuSub,
            onClick   = { onNavigate("premium") },
        )
        Spacer(Modifier.height(10.dp))

        items.forEach { item ->
            MoreMenuCard(item, onClick = { onNavigate(item.route) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PremiumMenuCard(
    isPremium: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tc = LocalThemeColors.current
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(1.5.dp, if (isPremium) Gold.copy(alpha = 0.6f) else Gold.copy(alpha = 0.3f)),
                RoundedCornerShape(14.dp),
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isPremium) Gold.copy(alpha = 0.08f) else tc.card,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Gold.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("👑", fontSize = 22.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = tc.muted, fontSize = 12.sp)
            }
            if (isPremium) {
                Icon(Icons.Default.CheckCircle, null, tint = Gold, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = tc.muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private data class MoreItem(
    val route: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val color: Color,
)

@Composable
private fun MoreMenuCard(item: MoreItem, onClick: () -> Unit) {
    val tc = LocalThemeColors.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(item.color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, null, tint = item.color, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, color = tc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(item.subtitle, color = tc.muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = tc.muted, modifier = Modifier.size(20.dp))
        }
    }
}
