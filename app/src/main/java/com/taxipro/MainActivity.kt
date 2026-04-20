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
import com.taxipro.data.db.AppTheme
import com.taxipro.data.db.SettingsRepository
import com.taxipro.ui.screens.DarkColors
import com.taxipro.ui.screens.ForestColors
import com.taxipro.ui.screens.LightColors
import com.taxipro.ui.screens.LocalThemeColors
import com.taxipro.ui.screens.MidnightColors
import com.taxipro.ui.screens.SunsetColors
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
import com.taxipro.ui.viewmodel.PremiumViewModel


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
            LaunchedEffect(permissionGranted) {
                if (permissionGranted) {
                    settingsRepo.detectAndSaveCurrency()
                }
            }

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
                AppLanguage.TR -> TrStrings
                AppLanguage.HI -> HiStrings
                AppLanguage.VI -> ViStrings
                AppLanguage.ID -> EnStrings  // Indonesian (coming soon)
                AppLanguage.IT -> EnStrings  // Italian (coming soon)
                AppLanguage.KO -> EnStrings  // Korean (coming soon)
                else           -> EnStrings
            }

            val themeColors = when (settings.theme) {
                AppTheme.LIGHT    -> LightColors
                AppTheme.MIDNIGHT -> MidnightColors
                AppTheme.SUNSET   -> SunsetColors
                AppTheme.FOREST   -> ForestColors
                else              -> DarkColors
            }

            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalSettings provides settings,
                LocalThemeColors provides themeColors,
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
    val premiumVm: PremiumViewModel = viewModel()
    val st = LocalStrings.current
    val tc = LocalThemeColors.current

    val navItems = listOf(
        NavItem(st.navRide,     Icons.Default.Navigation, "ride"),
        NavItem(st.navStats,    Icons.Default.BarChart,   "stats"),
        NavItem(st.navSettings, Icons.Default.Settings,   "settings"),
        NavItem(st.navMore,     Icons.Default.MoreHoriz,  "more"),
    )

    var selected by remember { mutableStateOf("ride") }

    val activeTab = when (selected) {
        "история", "история_смени", "calc", "zones", "zone_creator", "premium" -> "more"
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
        containerColor = tc.background,
        bottomBar = {
            NavigationBar(containerColor = tc.navBar, tonalElevation = 0.dp) {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = activeTab == item.route,
                        onClick  = { selected = item.route },
                        icon     = { Icon(item.icon, item.label) },
                        label    = { Text(item.label) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = tc.accent,
                            selectedTextColor   = tc.accent,
                            unselectedIconColor = tc.muted,
                            unselectedTextColor = tc.muted,
                            indicatorColor      = tc.accent.copy(alpha = 0.2f),
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selected) {
                "ride"          -> ActiveRideScreen(vm, rideVm)
                "stats"         -> StatsScreen(rideVm, settingsRepo)
                "settings"      -> SettingsScreen(settingsRepo, vm, onNavigate = { selected = it })
                "advanced_settings" -> AdvancedSettingsScreen(settingsRepo, vm, onBack = { selected = "settings" })
                "more"          -> MoreMenuScreen(onNavigate = { selected = it }, premiumVm = premiumVm)
                "premium"       -> PremiumScreen(premiumVm)
                "история"       -> RideHistoryScreen(rideVm)
                "история_смени" -> ShiftHistoryScreen(rideVm)
                "calc"          -> RouteCalculatorScreen(vm, settingsRepo)
                "zones"         -> {
                    val zones by rideVm.allZones.collectAsState(initial = emptyList())
                    ZoneScreen(rideVm = rideVm, onNavigate = { selected = it })
                }
                "zone_creator"  -> {
                    ZoneCreatorScreen(
                        rideVm = rideVm,
                        onBack = { selected = "zones" },
                    )
                }
                else            -> ActiveRideScreen(vm, rideVm)
            }
        }
    }
}
