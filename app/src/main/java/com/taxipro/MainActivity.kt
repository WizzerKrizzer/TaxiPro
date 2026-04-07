package com.taxipro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxipro.data.db.AppLanguage
import com.taxipro.data.db.AppSettings
import com.taxipro.data.db.SettingsRepository
import com.taxipro.ui.screens.*
import com.taxipro.ui.theme.*
import com.taxipro.ui.theme.ArStrings
import com.taxipro.ui.theme.DeStrings
import com.taxipro.ui.theme.EsStrings
import com.taxipro.ui.theme.FrStrings
import com.taxipro.ui.theme.JaStrings
import com.taxipro.ui.theme.PtStrings
import com.taxipro.ui.theme.RuStrings
import com.taxipro.ui.theme.ZhStrings
import com.taxipro.ui.viewmodel.TrackingViewModel
import com.taxipro.ui.viewmodel.RideViewModel


class MainActivity : ComponentActivity() {
    private val trackingVm: TrackingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepo = SettingsRepository(applicationContext)
        setContent {
            val hasGps = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            var permissionGranted by remember { mutableStateOf(hasGps) }
            val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
            val strings = when (settings.language) {
                AppLanguage.BG -> BgStrings
                AppLanguage.ES -> EsStrings
                AppLanguage.DE -> DeStrings
                AppLanguage.FR -> FrStrings
                AppLanguage.RU -> RuStrings
                AppLanguage.PT -> PtStrings
                AppLanguage.ZH -> ZhStrings
                AppLanguage.JA -> JaStrings
                AppLanguage.AR -> ArStrings
                else           -> EnStrings
            }

            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalSettings provides settings,
            ) {
                if (!permissionGranted) {
                    LocationPermissionScreen(onGranted = { permissionGranted = true })
                } else {
                    MainApp(trackingVm, settingsRepo)
                }
            }
        }
    }
}

data class NavItem(val label: String, val icon: ImageVector, val route: String)

@Composable
fun MainApp(vm: TrackingViewModel, settingsRepo: SettingsRepository) {
    val rideVm: RideViewModel = viewModel()
    val st = LocalStrings.current

    val navItems = listOf(
        NavItem(st.navRide,     Icons.Default.Navigation, "ride"),
        NavItem(st.navStats,    Icons.Default.BarChart,   "stats"),
        NavItem(st.navSettings, Icons.Default.Settings,   "settings"),
        NavItem(st.navMore,     Icons.Default.MoreHoriz,  "more"),
    )

    var selected by remember { mutableStateOf("ride") }

    val activeTab = when (selected) {
        "история", "история_смени", "calc", "map" -> "more"
        "advanced_settings" -> "settings"
        else -> selected
    }

    val lastEndedShift by vm.lastEndedShift.collectAsState()
    if (lastEndedShift != null) {
        ShiftSummaryScreen(
            shift     = lastEndedShift!!,
            rideVm    = rideVm,
            onDismiss = { vm.clearLastEndedShift() }
        )
        return
    }

    Scaffold(
        containerColor = Dark,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111318), tonalElevation = 0.dp) {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = activeTab == item.route,
                        onClick  = { selected = item.route },
                        icon     = { Icon(item.icon, item.label) },
                        label    = { Text(item.label) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Gold,
                            selectedTextColor   = Gold,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                            indicatorColor      = Color(0x33F5C842),
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selected) {
                "ride"          -> ActiveRideScreen(vm, rideVm)
                "stats"         -> StatsScreen(rideVm)
                "settings"      -> SettingsScreen(settingsRepo, vm, onNavigate = { selected = it })
                "advanced_settings" -> AdvancedSettingsScreen(settingsRepo, onBack = { selected = "settings" })
                "more"          -> MoreMenuScreen(onNavigate = { selected = it })
                "история"       -> RideHistoryScreen(rideVm)
                "история_смени" -> ShiftHistoryScreen(rideVm)
                "calc"          -> RouteCalculatorScreen(vm, settingsRepo)
                "map"           -> MapScreen(vm)
                else            -> ActiveRideScreen(vm, rideVm)
            }
        }
    }
}
