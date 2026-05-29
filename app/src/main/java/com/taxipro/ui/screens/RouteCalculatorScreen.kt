package com.taxipro.ui.screens

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.ads.CreditFeature
import com.taxipro.data.db.*
import com.taxipro.data.network.DirectionsApi
import com.taxipro.data.network.DirectionRoute
import com.taxipro.data.network.GoogleMapsRequestCache
import com.taxipro.data.network.decodePolyline
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.TrackingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

// ── Route colors (one per alternative) ──────────────────────
private val ROUTE_COLORS = listOf(
    Color(0xFF4A9EFF),
    Color(0xFF2ECC8A),
    Color(0xFFFF9A3C),
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
    val decodedPoints: List<LatLng>,
    val usedTrafficDuration: Boolean,
)

@Composable
fun RouteCalculatorScreen(vm: TrackingViewModel, settingsRepo: SettingsRepository) {
    val tc       = LocalThemeColors.current
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val tariffs  by vm.tariffs.collectAsState()
    val onUpgrade = LocalOnUpgrade.current
    val isPremium = LocalIsPremium.current
    val adActions = LocalAdActions.current
    val creditsState = LocalAdCreditsState.current
    val activity = context.findActivity()

    var fromText  by remember { mutableStateOf("") }
    var toText    by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }
    var routes    by remember { mutableStateOf<List<RouteResult>>(emptyList()) }
    var savedIdx  by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val initialRideTime = remember { Calendar.getInstance() }
    var rideHourText by remember {
        mutableStateOf("%02d".format(initialRideTime.get(Calendar.HOUR_OF_DAY)))
    }
    var rideMinuteText by remember {
        mutableStateOf("%02d".format(initialRideTime.get(Calendar.MINUTE)))
    }
    var showCalculatorLimitDialog by remember { mutableStateOf(false) }
    var showRideLimitDialog by remember { mutableStateOf(false) }

    // Map pin picker: "from", "to", or null
    var showMapPicker by remember { mutableStateOf<String?>(null) }

    // Tariff selection
    var selectedTariff by remember { mutableStateOf<Tariff?>(null) }
    LaunchedEffect(tariffs) {
        if (selectedTariff == null && tariffs.isNotEmpty()) {
            val minuteOfDay = currentMinuteOfDay()
            selectedTariff = tariffs.firstOrNull { t ->
                t.autoEnabled && tariffInMinuteRange(minuteOfDay, t.autoStartMinuteOfDay(), t.autoEndMinuteOfDay())
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

    fun activeStartFee()    = selectedTariff?.startFee        ?: settings.startFee
    fun activePricePerKm()  = selectedTariff?.pricePerKm      ?: settings.pricePerKm
    fun activePricePerMin() = selectedTariff?.pricePerMinute  ?: settings.pricePerMinute

    fun selectedRideStartMs(): Long? {
        val hour = rideHourText.toIntOrNull()
        val minute = rideMinuteText.toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun estimateWait(distKm: Double, durMin: Double): Double {
        // Estimate the time the vehicle is stopped/slow (taxi wait meter ticking).
        // Free-flow baseline at 60 km/h; anything above that is congestion time.
        // We take 20% of congestion as actual stopped wait, capped at 15% of total trip.
        val freeFlowMin   = distKm / 60.0 * 60.0
        val congestionMin = (durMin - freeFlowMin).coerceAtLeast(0.0)
        return (congestionMin * 0.20).coerceAtMost(durMin * 0.15)
    }

    fun buildRouteResult(route: DirectionRoute): RouteResult {
        val leg      = route.legs.first()
        val distKm   = leg.distance.value / 1000.0
        val trafficDuration = leg.duration_in_traffic?.value
        val durMin   = (trafficDuration ?: leg.duration.value) / 60.0
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
            usedTrafficDuration = trafficDuration != null,
        )
    }

    fun doCalculateCore() {
        val rideStartMs = selectedRideStartMs() ?: return
        isLoading = true; errorMsg = null
        routes = emptyList(); savedIdx = emptySet()
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val departureTimeSec = if (rideStartMs >= now - 60_000L) rideStartMs / 1000L else null
                val resp = GoogleMapsRequestCache.cachedDirections(
                    context = context,
                    api = api,
                    origin = fromText.trim(),
                    destination = toText.trim(),
                    alternatives = isPremium,
                    apiKey = apiKey,
                    language = apiLangForSettings(settings),
                    departureTime = departureTimeSec,
                    trafficModel = if (departureTimeSec != null) "best_guess" else null,
                )
                if (resp.status != "OK" || resp.routes.isEmpty()) {
                    errorMsg = when (resp.status) {
                        "ZERO_RESULTS"   -> st.calc.noRouteFound
                        "NOT_FOUND"      -> st.calc.addressNotFound
                        "REQUEST_DENIED" -> st.calc.directionsApiError
                        else             -> "Error: ${resp.status}"
                    }
                } else {
                    routes = resp.routes.take(if (isPremium) 3 else 1).map { buildRouteResult(it) }
                }
            } catch (e: Exception) {
                errorMsg = "${st.calc.networkError} ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    fun doCalculate() {
        if (fromText.isBlank() || toText.isBlank()) {
            errorMsg = st.calc.enterFromTo
            return
        }
        val rideStartMs = selectedRideStartMs()
        if (rideStartMs == null) {
            errorMsg = "Invalid start time"
            return
        }
        adActions.consumeCalculator { allowed ->
            if (allowed) doCalculateCore()
            else showCalculatorLimitDialog = true
        }
    }

    // ── UI ───────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(st.calculatorLabel, color = tc.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(st.calculatorSub, color = tc.muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Wifi, null, tint = tc.muted, modifier = Modifier.size(13.dp))
            Text(st.calc.requiresInternetNote, color = tc.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))

        // Tariff selector
        if (tariffs.isNotEmpty()) {
            Text(st.tariffLabel, color = tc.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tariffs.forEach { t ->
                    FilterChip(
                        selected = selectedTariff?.id == t.id,
                        onClick  = { selectedTariff = t },
                        label    = { Text(t.name, fontSize = 13.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tc.accent.copy(alpha = 0.2f),
                            selectedLabelColor     = tc.accent,
                            containerColor         = tc.card,
                            labelColor             = tc.muted,
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
            label         = st.calc.fromPointLabel,
            accentColor   = tc.green,
            api           = api,
            apiKey        = apiKey,
            onMyLocation  = { addr -> fromText = addr },
            onPickFromMap = { showMapPicker = "from" },
        )
        Spacer(Modifier.height(10.dp))

        // Swap
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { val t = fromText; fromText = toText; toText = t }) {
                Icon(Icons.Default.SwapVert, st.calc.swapLabel, tint = tc.accent)
            }
        }
        Spacer(Modifier.height(4.dp))

        // TO field
        AddressInputField(
            value         = toText,
            onValueChange = { toText = it },
            label         = st.calc.toPointLabel,
            accentColor   = tc.red,
            api           = api,
            apiKey        = apiKey,
            onMyLocation  = { addr -> toText = addr },
            onPickFromMap = { showMapPicker = "to" },
        )
        Spacer(Modifier.height(14.dp))

        RideStartTimeCard(
            hourText = rideHourText,
            minuteText = rideMinuteText,
            onHourChange = { rideHourText = it.filter(Char::isDigit).take(2) },
            onMinuteChange = { rideMinuteText = it.filter(Char::isDigit).take(2) },
        )
        Spacer(Modifier.height(14.dp))

        // Calculate
        Button(
            onClick  = { doCalculate() },
            enabled  = !isLoading,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
            shape    = RoundedCornerShape(12.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = tc.background, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.calc.calculating, color = tc.background, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Calculate, null, tint = tc.background)
                Spacer(Modifier.width(8.dp))
                Text(st.calc.calculateRoutes, color = tc.background, fontWeight = FontWeight.Bold)
            }
        }

        // Error
        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = tc.red.copy(alpha = 0.15f)),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = tc.red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, color = tc.red, fontSize = 13.sp)
                }
            }
        }

        // Results
        if (routes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "${routes.size} ${if (routes.size > 1) st.calc.routesPlural else st.calc.routesSingle}",
                color = tc.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = tc.card),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PricingChip(st.startFeeLabel, settings.formatPrice(activeStartFee()))
                    PricingChip(st.calc.perKmLabel,    settings.formatPrice(activePricePerKm()))
                    PricingChip(st.calc.perMinLabel,   settings.formatPrice(activePricePerMin()))
                }
            }
            Spacer(Modifier.height(10.dp))

            RoutesMapView(routes = routes)
            Spacer(Modifier.height(10.dp))

            routes.forEachIndexed { idx, route ->
                RouteCard(
                    index    = idx + 1,
                    route    = route,
                    settings = settings,
                    isSaved  = idx in savedIdx,
                    startTimeMs = selectedRideStartMs() ?: System.currentTimeMillis(),
                    onSave   = { fareAdj, tip ->
                        adActions.consumeRide { allowed ->
                            if (!allowed) {
                                showRideLimitDialog = true
                                return@consumeRide
                            }
                            scope.launch {
                                val startMs = selectedRideStartMs() ?: System.currentTimeMillis()
                                val endMs = startMs + (route.durationMin * 60_000).toLong()
                                vm.saveCalculatedRide(
                                    km = route.distanceKm, waitMin = route.estimatedWaitMin,
                                    price = route.fare, fromAddress = route.fromAddress,
                                    toAddress = route.toAddress, fromLat = route.fromLat,
                                    fromLng = route.fromLng, toLat = route.toLat,
                                    toLng = route.toLng, routePointsJson = route.routePointsJson,
                                    tip = tip, fareAdjustment = fareAdj,
                                    startTimeMs = startMs,
                                    endTimeMs = endMs,
                                    onSaved = { activity?.let { adActions.recordCompletedRide(it) } },
                                )
                                savedIdx = savedIdx + idx
                            }
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    // ── Map pin picker dialog ────────────────────────────────
    showMapPicker?.let { field ->
        MapPinPickerDialog(
            title     = if (field == "from") st.calc.fromPointLabel else st.calc.toPointLabel,
            api       = api,
            apiKey    = apiKey,
            onDismiss = { showMapPicker = null },
            onConfirm = { address ->
                if (field == "from") fromText = address else toText = address
                showMapPicker = null
            }
        )
    }

    if (showCalculatorLimitDialog) {
        CreditLimitDialog(
            feature = CreditFeature.Calculator,
            title = "Route calculator limit reached",
            message = "The free plan includes ${creditsState.config.dailyFreeCalculator} route calculation per day. Use ${creditsState.config.calculatorCreditCost} credits, watch an ad, or upgrade to Premium.",
            onUseCredits = {
                adActions.consumeCalculator { allowed ->
                    if (allowed) {
                        showCalculatorLimitDialog = false
                        doCalculateCore()
                    }
                }
            },
            onWatchAd = { activity?.let { adActions.watchRewarded(it) } },
            onUpgrade = {
                showCalculatorLimitDialog = false
                onUpgrade()
            },
            onDismiss = { showCalculatorLimitDialog = false },
        )
    }

    if (showRideLimitDialog) {
        CreditLimitDialog(
            feature = CreditFeature.Ride,
            title = "Daily ride limit reached",
            message = "The free plan includes ${creditsState.config.dailyFreeRides} rides per day. Use credits, watch an ad, or upgrade to Premium before saving another ride.",
            onUseCredits = null,
            onWatchAd = { activity?.let { adActions.watchRewarded(it) } },
            onUpgrade = {
                showRideLimitDialog = false
                onUpgrade()
            },
            onDismiss = { showRideLimitDialog = false },
        )
    }
}

internal fun apiLangForSettings(settings: AppSettings): String =
    if (settings.language.code == "bg") "bg" else "en"

// ── Map pin picker dialog ────────────────────────────────────
@SuppressLint("MissingPermission")
@Composable
internal fun MapPinPickerDialog(
    title: String,
    api: DirectionsApi,
    apiKey: String,
    onDismiss: () -> Unit,
    onConfirm: (address: String) -> Unit,
    originalStart: LatLng? = null,
    originalEnd: LatLng? = null,
) {
    val tc      = LocalThemeColors.current
    val st      = LocalStrings.current
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val settings = LocalSettings.current
    val apiLang = if (settings.language.code == "bg") "bg" else "en"

    var selectedLatLng  by remember { mutableStateOf<LatLng?>(null) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var isGeocoding     by remember { mutableStateOf(false) }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(42.698, 23.322), 12f)
    }
    val originalPoints = remember(originalStart, originalEnd) {
        listOfNotNull(originalStart, originalEnd)
    }

    // Move camera to real current location when dialog opens
    LaunchedEffect(Unit) {
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)

            // 1. Try last known location (instant)
            var loc: android.location.Location? = suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }

            // 2. If null, request a fresh fix (takes a few seconds)
            if (loc == null) {
                val cts = com.google.android.gms.tasks.CancellationTokenSource()
                loc = suspendCancellableCoroutine { cont ->
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cts.token
                    )
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }

            loc?.let {
                cameraState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15f)
                )
            }
            if (originalPoints.size >= 2) {
                val bounds = LatLngBounds.Builder().also { b ->
                    originalPoints.forEach { b.include(it) }
                }.build()
                cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 90))
            } else {
                originalPoints.firstOrNull()?.let {
                    cameraState.animate(CameraUpdateFactory.newLatLngZoom(it, 15f))
                }
            }
        } catch (_: Exception) { }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Box(Modifier.fillMaxSize().background(tc.background)) {

            GoogleMap(
                modifier            = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                uiSettings          = MapUiSettings(
                    zoomControlsEnabled     = true,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled       = false,
                ),
                properties = MapProperties(isMyLocationEnabled = true),
                onMapClick = { latLng ->
                    selectedLatLng  = latLng
                    selectedAddress = null
                    isGeocoding     = true
                    scope.launch {
                        if (selectedAddress == null) {
                            try {
                                @Suppress("DEPRECATION")
                                val addrs = android.location.Geocoder(context, java.util.Locale.getDefault())
                                    .getFromLocation(latLng.latitude, latLng.longitude, 1)
                                selectedAddress = addrs?.firstOrNull()?.getAddressLine(0)
                            } catch (_: Exception) { }
                        }
                        if (selectedAddress == null && apiKey.isNotBlank()) {
                            try {
                                val resp = GoogleMapsRequestCache.cachedReverseGeocode(
                                    context = context,
                                    api = api,
                                    lat = latLng.latitude,
                                    lng = latLng.longitude,
                                    apiKey = apiKey,
                                    language = apiLang,
                                )
                                selectedAddress = resp.results.firstOrNull()?.formatted_address
                            } catch (_: Exception) { }
                        }
                        isGeocoding = false
                    }
                }
            ) {
                originalStart?.let { pt ->
                    Marker(
                        state = MarkerState(position = pt),
                        title = st.calc.fromPointLabel,
                        icon = createLabeledMarker(android.graphics.Color.parseColor("#9E9E9E"), "S"),
                        zIndex = 1f,
                    )
                }
                originalEnd?.let { pt ->
                    Marker(
                        state = MarkerState(position = pt),
                        title = st.calc.toPointLabel,
                        icon = createLabeledMarker(android.graphics.Color.parseColor("#BDBDBD"), "E"),
                        zIndex = 1f,
                    )
                }
                selectedLatLng?.let { pt ->
                    Marker(
                        state  = MarkerState(position = pt),
                        title  = selectedAddress ?: "",
                        icon   = createLabeledMarker(
                            if (title.contains("Start") || title.contains("Начал")) 0xFF4CAF50.toInt()
                            else 0xFFF44336.toInt(),
                            "P"
                        ),
                        zIndex = 2f,
                    )
                }
            }

            // Back button
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(tc.card.copy(alpha = 0.92f), RoundedCornerShape(50))
            ) {
                Icon(Icons.Default.ArrowBack, null, tint = tc.textPrimary)
            }

            // Title chip
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(tc.card.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(title, color = tc.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // Tap instruction when nothing selected
            if (selectedLatLng == null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .background(tc.card.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(st.calc.tapMapInstruction, color = tc.textPrimary, fontSize = 14.sp)
                }
            }

            // Bottom confirm panel
            if (selectedLatLng != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(tc.card.copy(alpha = 0.96f))
                        .padding(16.dp)
                ) {
                    if (isGeocoding) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = tc.accent, strokeWidth = 2.dp)
                            Text(st.calc.geocodingInProgress, color = tc.muted, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            selectedAddress ?: st.calc.geocodeError,
                            color    = if (selectedAddress != null) tc.textPrimary else tc.red,
                            fontSize = 13.sp,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick  = { selectedAddress?.let { onConfirm(it) } },
                        enabled  = !isGeocoding && selectedAddress != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
                        shape    = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Check, null, tint = tc.background)
                        Spacer(Modifier.width(8.dp))
                        Text(st.calc.useThisLocation, color = tc.background, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Address input with autocomplete + my-location + map pin ─
@SuppressLint("MissingPermission")
@Composable
internal fun AddressInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
    api: DirectionsApi,
    apiKey: String,
    onMyLocation: (String) -> Unit,
    onPickFromMap: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val settings = LocalSettings.current
    val apiLang  = if (settings.language.code == "bg") "bg" else "en"

    var suggestions      by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDropdown     by remember { mutableStateOf(false) }
    var locLoading       by remember { mutableStateOf(false) }
    // Set to true before any programmatic address assignment (suggestion/GPS/map pin)
    // so the LaunchedEffect skips fetching for that value change.
    var skipAutocomplete by remember { mutableStateOf(false) }
    // Current device location used to bias autocomplete toward nearby results.
    var userLatLng       by remember { mutableStateOf<String?>(null) }

    // Fetch user location once — used only for autocomplete biasing, no permission request needed
    // (the app already holds ACCESS_FINE_LOCATION for GPS tracking).
    LaunchedEffect(Unit) {
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc: android.location.Location? = suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            if (loc != null) userLatLng = "${loc.latitude},${loc.longitude}"
        } catch (_: Exception) { }
    }

    // Debounced autocomplete
    LaunchedEffect(value) {
        if (skipAutocomplete) {
            skipAutocomplete = false
            return@LaunchedEffect
        }
        if (value.length < 4) {
            suggestions = emptyList()
            showDropdown = false
            return@LaunchedEffect
        }
        delay(700)

        var fetched = false

        // Try Google Places Autocomplete
        if (apiKey.isNotBlank()) {
            try {
                val resp = GoogleMapsRequestCache.cachedAutocomplete(
                    context = context,
                    api = api,
                    input = value,
                    apiKey = apiKey,
                    language = apiLang,
                    location = userLatLng,
                    radius = if (userLatLng != null) 50_000 else null,
                )
                if (resp.predictions.isNotEmpty()) {
                    suggestions  = resp.predictions.map { it.description }
                    showDropdown = true
                    fetched = true
                }
            } catch (_: Exception) { }
        }

        // Fallback: Android Geocoder
        if (!fetched) {
            try {
                @Suppress("DEPRECATION")
                val addrs = android.location.Geocoder(context, java.util.Locale.getDefault())
                    .getFromLocationName(value, 5)
                val results = addrs?.mapNotNull { it.getAddressLine(0) } ?: emptyList()
                suggestions  = results
                showDropdown = results.isNotEmpty()
            } catch (_: Exception) {
                suggestions = emptyList()
            }
        }
    }

    Column {
        OutlinedTextField(
            value         = value,
            onValueChange = {
                skipAutocomplete = false   // user is typing manually — re-enable autocomplete
                onValueChange(it)
                if (it.length < 4) { suggestions = emptyList(); showDropdown = false }
            },
            label        = { Text(label) },
            leadingIcon  = { Icon(Icons.Default.Place, null, tint = accentColor) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Map pin button
                    IconButton(onClick = onPickFromMap, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Map, null,
                            tint     = tc.muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // My location button
                    if (locLoading) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = tc.accent, strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(
                            onClick  = {
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
                                            var addr: String? = null
                                            // Try Android Geocoder first; Google is the paid fallback.
                                            if (addr == null) {
                                                try {
                                                    @Suppress("DEPRECATION")
                                                    val addrs = android.location.Geocoder(context, java.util.Locale.getDefault())
                                                        .getFromLocation(loc.latitude, loc.longitude, 1)
                                                    addr = addrs?.firstOrNull()?.getAddressLine(0)
                                                } catch (_: Exception) { }
                                            }
                                            if (addr == null && apiKey.isNotBlank()) {
                                                try {
                                                    val resp = GoogleMapsRequestCache.cachedReverseGeocode(
                                                        context = context,
                                                        api = api,
                                                        lat = loc.latitude,
                                                        lng = loc.longitude,
                                                        apiKey = apiKey,
                                                        language = apiLang,
                                                    )
                                                    addr = resp.results.firstOrNull()?.formatted_address
                                                } catch (_: Exception) { }
                                            }
                                            if (addr != null) {
                                                skipAutocomplete = true
                                                onValueChange(addr)
                                                onMyLocation(addr)
                                                suggestions  = emptyList()
                                                showDropdown = false
                                            }
                                        }
                                    } catch (_: Exception) { }
                                    finally { locLoading = false }
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                LocalStrings.current.calc.myLocationLabel,
                                tint     = tc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            singleLine = true,
            modifier   = Modifier.fillMaxWidth(),
            colors     = OutlinedTextFieldDefaults.colors(
                focusedTextColor          = tc.textPrimary,
                unfocusedTextColor        = tc.textPrimary,
                cursorColor               = tc.accent,
                focusedBorderColor        = tc.accent,
                unfocusedBorderColor      = tc.muted,
                focusedLabelColor         = tc.accent,
                unfocusedLabelColor       = tc.muted,
                focusedLeadingIconColor   = accentColor,
                unfocusedLeadingIconColor = tc.muted,
            )
        )

        // Autocomplete dropdown
        if (showDropdown && suggestions.isNotEmpty()) {
            Card(
                modifier  = Modifier.fillMaxWidth().padding(top = 2.dp),
                colors    = CardDefaults.cardColors(containerColor = tc.surface),
                shape     = RoundedCornerShape(10.dp),
                elevation = CardDefaults.cardElevation(4.dp),
            ) {
                Column {
                    suggestions.forEachIndexed { idx, suggestion ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    skipAutocomplete = true
                                    onValueChange(suggestion)
                                    onMyLocation(suggestion)
                                    suggestions  = emptyList()
                                    showDropdown = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, null, tint = tc.muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(suggestion, color = tc.textPrimary, fontSize = 13.sp)
                        }
                        if (idx < suggestions.lastIndex) {
                            HorizontalDivider(color = tc.muted.copy(alpha = 0.15f))
                        }
                    }
                }
            }
        }
    }
}

// ── Route card ──────────────────────────────────────────────
@Composable
private fun RideStartTimeCard(
    hourText: String,
    minuteText: String,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
) {
    val tc = LocalThemeColors.current
    val st = LocalStrings.current

    Card(
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = tc.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${st.fromLabel} ${st.calc.timeLabel.lowercase(Locale.getDefault())}",
                    color = tc.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = onHourChange,
                    label = { Text("HH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tc.textPrimary,
                        unfocusedTextColor = tc.textPrimary,
                        cursorColor = tc.accent,
                        focusedBorderColor = tc.accent,
                        unfocusedBorderColor = tc.muted,
                        focusedLabelColor = tc.accent,
                        unfocusedLabelColor = tc.muted,
                    )
                )
                Text(":", color = tc.muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = onMinuteChange,
                    label = { Text("MM") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tc.textPrimary,
                        unfocusedTextColor = tc.textPrimary,
                        cursorColor = tc.accent,
                        focusedBorderColor = tc.accent,
                        unfocusedBorderColor = tc.muted,
                        focusedLabelColor = tc.accent,
                        unfocusedLabelColor = tc.muted,
                    )
                )
            }
        }
    }
}

@Composable
private fun RouteCard(
    index: Int,
    route: RouteResult,
    settings: AppSettings,
    isSaved: Boolean,
    startTimeMs: Long,
    onSave: (fareAdj: Double, tip: Double) -> Unit,
) {
    val tc  = LocalThemeColors.current
    val st  = LocalStrings.current
    val sym = settings.currency.symbol
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val endTimeMs = startTimeMs + (route.durationMin * 60_000).toLong()

    var fareAdj by remember { mutableStateOf(0.0) }
    var tipText by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = tc.card),
        shape  = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(tc.accent.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$index", color = tc.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    route.summary,
                    color = tc.textPrimary, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol(Icons.Default.Straighten,  st.distanceField, settings.formatDistance(route.distanceKm))
                MetricCol(Icons.Default.Timer,        st.calc.timeLabel, fmtDuration(route.durationMin, st.calc.secAbbr, st.minAbbr))
                MetricCol(Icons.Default.PauseCircle, st.waitTimeField,  fmtDuration(route.estimatedWaitMin, st.calc.secAbbr, st.minAbbr))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${st.fromLabel} ${timeFmt.format(Date(startTimeMs))}", color = tc.muted, fontSize = 12.sp)
                Icon(Icons.Default.ArrowForward, null, tint = tc.muted, modifier = Modifier.size(16.dp))
                Text(
                    "${st.toLabel} ${timeFmt.format(Date(endTimeMs))}",
                    color = tc.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))

            HorizontalDivider(color = tc.muted.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(st.distanceField, color = tc.muted, fontSize = 11.sp)
                    Text(settings.formatPrice(route.distanceCost), color = tc.textPrimary, fontSize = 13.sp)
                }
                Column {
                    Text(st.waitTimeField, color = tc.muted, fontSize = 11.sp)
                    Text(settings.formatPrice(route.waitCost), color = tc.textPrimary, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(st.calc.totalLabel, color = tc.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        settings.formatPrice(route.fare + fareAdj),
                        color = tc.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Fare adjustment + tip — only visible before saving
            if (!isSaved) {
                FareAdjustCard(
                    currentAdjust = fareAdj,
                    symbol        = sym,
                    onAdjust      = { fareAdj += it },
                    onReset       = { fareAdj = 0.0 },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value           = tipText,
                    onValueChange   = { tipText = it },
                    label           = { Text("${st.tipLabel} ($sym)", color = tc.muted) },
                    leadingIcon     = { Icon(Icons.Default.CardGiftcard, null, tint = tc.muted, modifier = Modifier.size(18.dp)) },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                    colors          = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = tc.textPrimary,
                        unfocusedTextColor   = tc.textPrimary,
                        cursorColor          = tc.accent,
                        focusedBorderColor   = tc.accent,
                        unfocusedBorderColor = tc.muted,
                        focusedLabelColor    = tc.accent,
                        unfocusedLabelColor  = tc.muted,
                    )
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick  = { onSave(fareAdj, tipText.toDoubleOrNull() ?: 0.0) },
                enabled  = !isSaved,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (isSaved) tc.green.copy(alpha = 0.2f) else tc.accent,
                    disabledContainerColor = tc.green.copy(alpha = 0.2f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (isSaved) {
                    Icon(Icons.Default.Check, null, tint = tc.green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.calc.savedAsRide, color = tc.green, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.Save, null, tint = tc.background, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(st.calc.saveAsRide, color = tc.background, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Routes map ──────────────────────────────────────────────
@Composable
private fun RoutesMapView(routes: List<RouteResult>) {
    val tc = LocalThemeColors.current
    if (routes.isEmpty()) return

    val allPoints = routes.flatMap { it.decodedPoints }
    if (allPoints.isEmpty()) return

    val boundsBuilder = LatLngBounds.Builder()
    allPoints.forEach { boundsBuilder.include(it) }
    val bounds = boundsBuilder.build()

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 12f)
    }

    LaunchedEffect(routes) {
        cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 80), durationMs = 600)
    }

    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tc.card),
    ) {
        GoogleMap(
            modifier            = Modifier.fillMaxWidth().height(260.dp),
            cameraPositionState = cameraState,
            uiSettings          = MapUiSettings(
                zoomControlsEnabled     = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled       = false,
            ),
            properties = MapProperties(),
        ) {
            routes.forEachIndexed { idx, route ->
                if (route.decodedPoints.isNotEmpty()) {
                    Polyline(
                        points   = route.decodedPoints,
                        color    = ROUTE_COLORS[idx % ROUTE_COLORS.size],
                        width    = if (idx == 0) 18f else 12f,
                        zIndex   = (routes.size - idx).toFloat(),
                    )
                }
            }
            val mapSt = LocalStrings.current
            routes.first().decodedPoints.firstOrNull()?.let { pt ->
                Marker(
                    state  = MarkerState(position = pt),
                    title  = mapSt.history.startMarker,
                    icon   = createLabeledMarker(android.graphics.Color.parseColor("#4CAF50"), "S"),
                    zIndex = 2f,
                )
            }
            routes.first().decodedPoints.lastOrNull()?.let { pt ->
                Marker(
                    state  = MarkerState(position = pt),
                    title  = mapSt.history.endMarker,
                    icon   = createLabeledMarker(android.graphics.Color.parseColor("#F44336"), "E"),
                    zIndex = 2f,
                )
            }
        }

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
                        color    = tc.textPrimary,
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
    val tc = LocalThemeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tc.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tc.muted, fontSize = 10.sp)
        Text(value, color = tc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PricingChip(label: String, value: String) {
    val tc = LocalThemeColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tc.muted, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = tc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun fmtDuration(minutes: Double, secAbbr: String, minAbbr: String): String {
    val sec = (minutes * 60).toLong()
    return if (sec < 60) "$sec $secAbbr" else "%d:%02d $minAbbr".format(sec / 60, sec % 60)
}
