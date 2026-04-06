package com.taxipro.ui.screens

import androidx.compose.foundation.background
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

@Composable
fun MoreMenuScreen(onNavigate: (String) -> Unit) {
    val items = listOf(
        MoreItem("история",       Icons.Default.History,       "История на курсове", "Преглед на всички курсове",  Gold),
        MoreItem("история_смени", Icons.Default.WorkHistory,   "История на смени",   "Преглед по работни смени",   Green),
        MoreItem("calc",          Icons.Default.Calculate,     "Калкулатор",         "Изчисли цена по маршрут",    Blue),
        MoreItem("map",           Icons.Default.Map,           "Карта",              "Текуща позиция и маршрут",   Purple),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Dark)
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Още", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Допълнителни функции", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        items.forEach { item ->
            MoreMenuCard(item, onClick = { onNavigate(item.route) })
            Spacer(Modifier.height(10.dp))
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Card),
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
                Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(item.subtitle, color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}
