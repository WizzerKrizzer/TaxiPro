package com.taxipro.ui.screens

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.*
import com.taxipro.data.network.DirectionsApi
import com.taxipro.data.network.DirectionRoute
import com.taxipro.data.network.decodePolyline
import com.taxipro.ui.viewmodel.TrackingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// ── Route colors (one per alternative) ──────────────────────
private val ROUTE_COLORS = listOf(
    Color(0xFF4A9EFF),   // Blue
    Color(0xFF2ECC8A),   // Green
    Color(0xFFFF9A3C),   // Orange
)

// ── Calculated route result ─────────────────────────────────
private data class RouteResult(
    val summary: String,
    val distanceKm: Double,
    val durationMin: Double,
    val estimatedWaitMin: Double,
    val fare: Double,
    val distanceCost: Double,
    val waitCost: Double,
    val startFeeCost: Double,
    val fromAddress: String,
    val toAddress: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val routePointsJson: String,
    val decodedPoints: List<LatLng>,   // for map display
)

@Composable
fun RouteCalculatorScreen(vm: TrackingViewModel, settingsRepo: SettingsRepository) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val tariffs  by vm.tariffs.collectAsState()

    var fromText by remember { mutableStateOf("") }
    var toText   by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }
    var routes    by remember { mutableStateOf<List<RouteResult>>(emptyList()) }
    var savedIdx  by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Tariff selection
    var selectedTariff by remember { mutableStateOf<Tariff?>(null) }
    LaunchedEffect(tariffs) {
        if (selectedTariff == null && tariffs.isNotEmpty()) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            selectedTariff = tariffs.firstOrNull { t ->
                t.autoEnabled && tariffInHourRange(hour, t.autoStartHour, t.autoEndHour)
            }
        }
    }

    val apiKey = remember {
        try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            ai.metaData?.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (_: Exception) { "" }
    }

    val api = remember { DirectionsApi.create() }

    fun activeStartFee()    = selectedTariff?.startFee       ?: settings.startFee
    fun activePricePerKm()  = selectedTariff?.pricePerKm     ?: settings.pricePerKm
    fun activePricePerMin() = selectedTariff?.pricePerMinute  ?: settings.pricePerMinute

    fun estimateWait(distKm: Double, durMin: Double): Double {
        val freeFlowMin = distKm / 40.0 * 60.0
        return (durMin - freeFlowMin).coerceAtLeast(0.0)
    }

    fun buildRouteResult(route: DirectionRoute): RouteResult {
        val leg      = route.legs.first()
        val distKm   = leg.distance.value / 1000.0
        val durMin   = leg.duration.value / 60.0
        val waitMin  = estimateWait(distKm, durMin)
        val sf       = activeStartFee()
        val distCost = distKm * activePricePerKm()
        val waitCost = waitMin * activePricePerMin()
        val rawPts   = route.overview_polyline?.points?.let { decodePolyline(it) } ?: emptyList()
        val latLngs  = rawPts.map { (lat, lng) -> LatLng(lat, lng) }
        val json     = rawPts.joinToString(",", "[", "]") { (lat, lng) -> "[$lat,$lng]" }
        return RouteResult(
            summary          = route.summary.ifBlank {
                leg.start_address.take(22) + " → " + leg.end_address.take(22)
            },
            distanceKm       = distKm,
            durationMin      = durMin,
            estimatedWaitMin = waitMin,
            fare             = sf + distCost + waitCost,
            distanceCost     = distCost,
            waitCost         = waitCost,
            startFeeCost     = sf,
            fromAddress      = leg.start_address,
            toAddress        = leg.end_address,
            fromLat          = leg.start_location.lat,
            fromLng          = leg.start_location.lng,
            toLat            = leg.end_location.lat,
            toLng            = leg.end_location.lng,
            routePointsJson  = json,
            decodedPoints    = latLngs,
        )
    }

    fun doCalculate() {
        if (fromText.isBlank() || toText.isBlank()) {
            errorMsg = "Въведи начална и крайна точка"
            return
        }
        isLoading = true; errorMsg = null
        routes = emptyList(); savedIdx = emptySet()
        scope.launch {
            try {
                val resp = api.getDirections(fromText.trim(), toText.trim(), apiKey = apiKey)
                if (resp.status != "OK" || resp.routes.isEmpty()) {
                    errorMsg = when (resp.status) {
                        "ZERO_RESULTS"   -> "Не е намерен маршрут"
                        "NOT_FOUND"      -> "Адресът не е намерен"
                        "REQUEST_DENIED" -> "Directions API не е активиран за този ключ"
                        else             -> "Грешка: ${resp.status}"
                    }
                } else {
                    routes = resp.routes.take(3).map { buildRouteResult(it) }
                }
            } catch (e: Exception) {
                errorMsg = "Мрежова грешка: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    // ── UI ───────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Калкулатор", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Изчисли цена по маршрут", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        // Tariff selector
        if (tariffs.isNotEmpty()) {
            Text("Тарифа", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTariff == null,
                    onClick  = { selectedTariff = null },
                    label    = { Text("По подразб.", fontSize = 13.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Gold.copy(alpha = 0.2f),
                        selectedLabelColor     = Gold,
                        containerColor         = Card,
                        labelColor             = Muted,
                    )
                )
                tariffs.forEach { t ->
                    FilterChip(
                        selected = selectedTariff?.id == t.id,
                        onClick  = { selectedTariff = t },
                        label    = { Text(t.name, fontSize = 13.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold.copy(alpha = 0.2f),
                            selectedLabelColor     = Gold,
                            containerColor         = Card,
                            labelColor             = Muted,
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // FROM field
        AddressInputField(
            value         = fromText,
            onValueChange = { fromText = it },
            label         = "Начална точка",
            accentColor   = Green,
            api           = api,
            apiKey        = apiKey,
            onMyLocation  = { addr -> fromText = addr },
        )
        Spacer(Modifier.height(10.dp))

        // Swap
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { val t = fromText; fromText = toText; toText = t }) {
                Icon(Icons.Default.SwapVert, "Размени", tint = Gold)
            }
        }
        Spacer(Modifier.height(4.dp))

        // TO field
        AddressInputField(
            value         = toText,
            onValueChange = { toText = it },
            label         = "Крайна точка",
            accentColor   = Red,
            api           = api,
            apiKey        = apiKey,
            onMyLocation  = { addr -> toText = addr },
        )
        Spacer(Modifier.height(14.dp))

        // Calculate
        Button(
            onClick  = { doCalculate() },
            enabled  = !isLoading,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Gold),
            shape    = RoundedCornerShape(12.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Dark, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Изчисляване...", color = Dark, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Calculate, null, tint = Dark)
                Spacer(Modifier.width(8.dp))
                Text("Изчисли маршрути", color = Dark, fontWeight = FontWeight.Bold)
            }
        }

        // Error
        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.15f)),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, color = Red, fontSize = 13.sp)
                }
            }
        }

        // Results
        if (routes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "${routes.size} маршрут${if (routes.size > 1) "а" else ""}",
                color = Gold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Card),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PricingChip("Старт", settings.formatPrice(activeStartFee()))
                    PricingChip("/km", settings.formatPrice(activePricePerKm()))
                    PricingChip("/мин", settings.formatPrice(activePricePerMin()))
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── Route map ──
            RoutesMapView(routes = routes)
            Spacer(Modifier.height(10.dp))

            routes.forEachIndexed { idx, route ->
                RouteCard(
                    index    = idx + 1,
                    route    = route,
                    settings = settings,
                    isSaved  = idx in savedIdx,
                    onSave   = {
                        scope.launch {
                            vm.saveCalculatedRide(
                                km = route.distanceKm, waitMin = route.estimatedWaitMin,
                                price = route.fare, fromAddress = route.fromAddress,
                                toAddress = route.toAddress, fromLat = route.fromLat,
                                fromLng = route.fromLng, toLat = route.toLat,
                                toLng = route.toLng, routePointsJson = route.routePointsJson,
                            )
                            savedIdx = savedIdx + idx
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── Address input with autocomplete + my-location ───────────
@SuppressLint("MissingPermission")
@Composable
private fun AddressInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
    api: DirectionsApi,
    apiKey: String,
    onMyLocation: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var suggestions    by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDropdown   by remember { mutableStateOf(false) }
    var locLoading     by remember { mutableStateOf(false) }

    // Debounced autocomplete: fetch after 350 ms of no typing
    LaunchedEffect(value) {
        if (value.length < 3) {
            suggestions = emptyList()
            showDropdown = false
            return@LaunchedEffect
        }
        delay(350)
        try {
            val resp = api.autocomplete(input = value, apiKey = apiKey)
            suggestions  = resp.predictions.map { it.description }
            showDropdown = suggestions.isNotEmpty()
        } catch (_: Exception) {
            suggestions = emptyList()
        }
    }

    Column {
        OutlinedTextField(
            value         = value,
            onValueChange = {
                onValueChange(it)
                if (it.length < 3) { suggestions = emptyList(); showDropdown = false }
            },
            label         = { Text(label) },
            leadingIcon   = { Icon(Icons.Default.Place, null, tint = accentColor) },
            trailingIcon  = {
                if (locLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = {
                        locLoading = true
                        scope.launch {
                            try {
                                val client = LocationServices.getFusedLocationProviderClient(context)
                                val loc: android.location.Location? = suspendCancellableCoroutine { cont ->
                                    client.lastLocation
                                        .addOnSuccessListener { cont.resume(it) }
                                        .addOnFailureListener { cont.resume(null) }
                                }
                                if (loc != null) {
                                    val resp = api.reverseGeocode(
                                        latLng = "${loc.latitude},${loc.longitude}",
                                        apiKey = apiKey
                                    )
                                    val addr = resp.results.firstOrNull()?.formatted_address
                                    if (addr != null) {
                                        onValueChange(addr)
                                        onMyLocation(addr)
                                        suggestions = emptyList()
                                        showDropdown = false
                                    }
                                }
                            } catch (_: Exception) { }
                            finally { locLoading = false }
                        }
                    }) {
                        Icon(Icons.Default.MyLocation, "Моето местоположение", tint = Gold)
                    }
                }
            },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedTextColor          = Color.White,
                unfocusedTextColor        = Color.White,
                cursorColor               = Gold,
                focusedBorderColor        = Gold,
                unfocusedBorderColor      = Muted,
                focusedLabelColor         = Gold,
                unfocusedLabelColor       = Muted,
                focusedLeadingIconColor   = accentColor,
                unfocusedLeadingIconColor = Muted,
            )
        )

        // Autocomplete dropdown
        if (showDropdown && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E2330)),
                shape    = RoundedCornerShape(10.dp),
                elevation = CardDefaults.cardElevation(4.dp),
            ) {
                Column {
                    suggestions.forEachIndexed { idx, suggestion ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(suggestion)
                                    onMyLocation(suggestion)
                                    suggestions  = emptyList()
                                    showDropdown = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.LocationOn, null,
                                tint     = Muted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(suggestion, color = Color.White, fontSize = 13.sp)
                        }
                        if (idx < suggestions.lastIndex) {
                            HorizontalDivider(color = Muted.copy(alpha = 0.15f))
                        }
                    }
                }
            }
        }
    }
}

// ── Route card ──────────────────────────────────────────────
@Composable
private fun RouteCard(
    index: Int,
    route: RouteResult,
    settings: AppSettings,
    isSaved: Boolean,
    onSave: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Card),
        shape  = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Gold.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$index", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    route.summary,
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol(Icons.Default.Straighten, "Разстояние", settings.formatDistance(route.distanceKm))
                MetricCol(Icons.Default.Timer, "Време", fmtDuration(route.durationMin))
                MetricCol(Icons.Default.PauseCircle, "Престой", fmtDuration(route.estimatedWaitMin))
            }
            Spacer(Modifier.height(12.dp))

            HorizontalDivider(color = Muted.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Разстояние", color = Muted, fontSize = 11.sp)
                    Text(settings.formatPrice(route.distanceCost), color = Color.White, fontSize = 13.sp)
                }
                Column {
                    Text("Престой", color = Muted, fontSize = 11.sp)
                    Text(settings.formatPrice(route.waitCost), color = Color.White, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Общо", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(settings.formatPrice(route.fare), color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = onSave,
                enabled  = !isSaved,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (isSaved) Green.copy(alpha = 0.2f) else Gold,
                    disabledContainerColor = Green.copy(alpha = 0.2f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (isSaved) {
                    Icon(Icons.Default.Check, null, tint = Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Запазен като курс", color = Green, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.Save, null, tint = Dark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Запази като курс", color = Dark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Routes map ──────────────────────────────────────────────
@Composable
private fun RoutesMapView(routes: List<RouteResult>) {
    if (routes.isEmpty()) return

    // Build bounds from all route points across all alternatives
    val allPoints = routes.flatMap { it.decodedPoints }
    if (allPoints.isEmpty()) return

    val boundsBuilder = LatLngBounds.Builder()
    allPoints.forEach { boundsBuilder.include(it) }
    val bounds = boundsBuilder.build()

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 12f)
    }

    // Fit camera to bounds once
    LaunchedEffect(routes) {
        cameraState.animate(
            CameraUpdateFactory.newLatLngBounds(bounds, 80),
            durationMs = 600
        )
    }

    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Card),
    ) {
        GoogleMap(
            modifier           = Modifier
                .fillMaxWidth()
                .height(260.dp),
            cameraPositionState = cameraState,
            uiSettings          = MapUiSettings(
                zoomControlsEnabled  = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled    = false,
            ),
            properties = MapProperties(),
        ) {
            routes.forEachIndexed { idx, route ->
                if (route.decodedPoints.isNotEmpty()) {
                    Polyline(
                        points   = route.decodedPoints,
                        color    = ROUTE_COLORS[idx % ROUTE_COLORS.size],
                        width    = if (idx == 0) 18f else 12f,   // fastest route thicker
                        zIndex   = (routes.size - idx).toFloat(), // fastest on top
                    )
                }
            }
            // Start marker
            routes.first().decodedPoints.firstOrNull()?.let { pt ->
                Marker(
                    state   = MarkerState(position = pt),
                    title   = "Начало",
                    snippet = routes.first().fromAddress.substringBefore(","),
                )
            }
            // End marker
            routes.first().decodedPoints.lastOrNull()?.let { pt ->
                Marker(
                    state   = MarkerState(position = pt),
                    title   = "Край",
                    snippet = routes.first().toAddress.substringBefore(","),
                )
            }
        }

        // Color legend
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            routes.forEachIndexed { idx, route ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(ROUTE_COLORS[idx % ROUTE_COLORS.size], RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${idx + 1}. ${route.summary.take(20)}",
                        color    = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────
@Composable
private fun MetricCol(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PricingChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun fmtDuration(minutes: Double): String {
    val sec = (minutes * 60).toLong()
    return if (sec < 60) "${sec} сек" else "%d:%02d мин".format(sec / 60, sec % 60)
}
