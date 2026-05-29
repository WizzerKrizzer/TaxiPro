package com.taxipro.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.Ride
import com.taxipro.data.db.Tariff
import com.taxipro.data.db.formatDistance
import com.taxipro.data.db.formatPrice
import com.taxipro.data.network.DirectionsApi
import com.taxipro.data.network.GoogleMapsRequestCache
import com.taxipro.data.network.decodePolyline
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import kotlinx.coroutines.launch

// Calculated route data for ride editing
private data class EditRouteResult(
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
)

@Composable
fun EditRouteDialog(
    ride: Ride,
    vm: RideViewModel,
    onDismiss: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val context  = LocalContext.current
    val settings = LocalSettings.current
    val isPremium = LocalIsPremium.current
    val scope    = rememberCoroutineScope()
    val tariffs  by vm.allTariffs.collectAsState(initial = emptyList())

    var fromText  by remember { mutableStateOf(ride.fromAddress) }
    var toText    by remember { mutableStateOf(ride.toAddress) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    var calculatedRoutes by remember { mutableStateOf<List<EditRouteResult>>(emptyList()) }
    var selectedRouteIndex by remember { mutableIntStateOf(0) }
    var showConfirm     by remember { mutableStateOf(false) }
    var showMapPicker   by remember { mutableStateOf<String?>(null) }
    var selectedTariff  by remember { mutableStateOf<Tariff?>(null) }
    var fareAdj by remember { mutableDoubleStateOf(0.0) }
    var tipText by remember {
        mutableStateOf(
            if (ride.tip == ride.tip.toLong().toDouble()) ride.tip.toLong().toString()
            else "%.2f".format(ride.tip)
        )
    }

    // Camera
    val cameraState = rememberCameraPositionState {
        val lat = if (ride.fromLat != 0.0) ride.fromLat else 42.1500
        val lng = if (ride.fromLng != 0.0) ride.fromLng else 24.7500
        position = CameraPosition.fromLatLngZoom(LatLng(lat, lng), 13f)
    }

    // Parse and show existing route when dialog opens
    val existingPoints = remember(ride.id) { parseRoutePoints(ride.routePointsJson) }
    LaunchedEffect(Unit) {
        if (existingPoints.size >= 2) {
            val b = LatLngBounds.Builder()
                .also { b -> existingPoints.forEach { b.include(it) } }.build()
            cameraState.animate(CameraUpdateFactory.newLatLngBounds(b, 60))
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

    // Use tariff value only when explicitly > 0; fall back to global settings otherwise.
    // The plain `?:` operator only triggers on null, not on 0.0, so a tariff field left at
    // its default 0.0 would silently suppress the global setting without this guard.
    fun tariffOr(tariffVal: Double?, globalVal: Double) =
        if (tariffVal != null && tariffVal > 0.0) tariffVal else globalVal
    fun activeStartFee()    = tariffOr(selectedTariff?.startFee,       settings.startFee)
    fun activePricePerKm()  = tariffOr(selectedTariff?.pricePerKm,     settings.pricePerKm)
    fun activePricePerMin() = tariffOr(selectedTariff?.pricePerMinute, settings.pricePerMinute)
    fun routePointInput(text: String, lat: Double, lng: Double): String =
        text.trim().ifBlank {
            if (lat != 0.0 || lng != 0.0) "$lat,$lng" else ""
        }

    fun selectedRoute(): EditRouteResult? = calculatedRoutes.getOrNull(selectedRouteIndex)

    fun doCalculate() {
        val origin = routePointInput(fromText, ride.fromLat, ride.fromLng)
        val destination = routePointInput(toText, ride.toLat, ride.toLng)
        if (origin.isBlank() || destination.isBlank()) {
            errorMsg = st.calc.enterFromTo
            return
        }
        isLoading = true; errorMsg = null; calculatedRoutes = emptyList(); selectedRouteIndex = 0
        scope.launch {
            try {
                val resp = GoogleMapsRequestCache.cachedDirections(
                    context = context,
                    api = api,
                    origin = origin,
                    destination = destination,
                    alternatives = isPremium,
                    apiKey = apiKey,
                    language = apiLangForSettings(settings),
                    departureTime = null,
                    trafficModel = null,
                )
                if (resp.status != "OK" || resp.routes.isEmpty()) {
                    errorMsg = st.calc.noRouteFound
                } else {
                    calculatedRoutes = resp.routes.take(if (isPremium) 3 else 1).map { route ->
                        val leg      = route.legs.first()
                        val distKm   = leg.distance.value / 1000.0
                        val durMin   = (leg.duration_in_traffic?.value ?: leg.duration.value) / 60.0
                        val waitMin  = ((durMin - distKm / 40.0 * 60.0)).coerceAtLeast(0.0)
                        val rawPts   = route.overview_polyline?.points?.let { decodePolyline(it) } ?: emptyList()
                        val latLngs  = rawPts.map { (lat, lng) -> LatLng(lat, lng) }
                        val json     = rawPts.joinToString(",", "[", "]") { (lat, lng) -> "[$lat,$lng]" }
                        val distCost = distKm * activePricePerKm()
                        val waitCost = waitMin * activePricePerMin()
                        val startFee = activeStartFee()
                        EditRouteResult(
                            summary          = route.summary.ifBlank {
                                leg.start_address.take(22) + " -> " + leg.end_address.take(22)
                            },
                            distanceKm       = distKm,
                            durationMin      = durMin,
                            estimatedWaitMin = waitMin,
                            fare             = startFee + distCost + waitCost,
                            distanceCost     = distCost,
                            waitCost         = waitCost,
                            startFeeCost     = startFee,
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

                    calculatedRoutes.firstOrNull()?.decodedPoints?.let { latLngs ->
                        if (latLngs.size >= 2) {
                        val b = LatLngBounds.Builder()
                            .also { b -> latLngs.forEach { b.include(it) } }.build()
                        cameraState.animate(CameraUpdateFactory.newLatLngBounds(b, 80))
                        }
                    }
                }
            } catch (e: Exception) {
                errorMsg = "${st.calc.networkError} ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    // ── Full-screen dialog ───────────────────────────────────────
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(tc.background)) {
            val topPanelMaxHeight = (maxHeight * 0.58f).coerceAtLeast(220.dp)
            Column(Modifier.fillMaxSize()) {

            // ── Top panel ────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = topPanelMaxHeight)
                    .background(tc.card)
                    .verticalScroll(rememberScrollState())
            ) {
                // Back + title
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            st.history.editRouteLabel,
                            color = tc.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "#${ride.globalId}",
                            color = tc.muted, fontSize = 12.sp,
                        )
                    }
                }

                // Tariff chips
                if (tariffs.isNotEmpty()) {
                    LazyRow(
                        contentPadding        = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = tariffs, key = { it.id }) { t ->
                            FilterChip(
                                selected = selectedTariff?.id == t.id,
                                onClick  = {
                                    selectedTariff = if (selectedTariff?.id == t.id) null else t
                                    calculatedRoutes = emptyList()
                                    selectedRouteIndex = 0
                                },
                                label = { Text(t.name, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = tc.accent.copy(alpha = 0.2f),
                                    selectedLabelColor     = tc.accent,
                                    containerColor         = tc.surface,
                                    labelColor             = tc.muted,
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // From
                Column(Modifier.padding(horizontal = 12.dp)) {
                    AddressInputField(
                        value         = fromText,
                        onValueChange = { fromText = it; calculatedRoutes = emptyList(); selectedRouteIndex = 0 },
                        label         = st.calc.fromPointLabel,
                        accentColor   = tc.green,
                        api           = api,
                        apiKey        = apiKey,
                        onMyLocation  = { fromText = it; calculatedRoutes = emptyList(); selectedRouteIndex = 0 },
                        onPickFromMap = { showMapPicker = "from" },
                    )
                }

                // Swap
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = {
                        val tmp = fromText; fromText = toText; toText = tmp
                        calculatedRoutes = emptyList()
                        selectedRouteIndex = 0
                    }) {
                        Icon(Icons.Default.SwapVert, null, tint = tc.accent)
                    }
                }

                // To
                Column(Modifier.padding(horizontal = 12.dp)) {
                    AddressInputField(
                        value         = toText,
                        onValueChange = { toText = it; calculatedRoutes = emptyList(); selectedRouteIndex = 0 },
                        label         = st.calc.toPointLabel,
                        accentColor   = tc.red,
                        api           = api,
                        apiKey        = apiKey,
                        onMyLocation  = { toText = it; calculatedRoutes = emptyList(); selectedRouteIndex = 0 },
                        onPickFromMap = { showMapPicker = "to" },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Calculate button
                Button(
                    onClick  = { doCalculate() },
                    enabled  = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tc.accent),
                    shape  = RoundedCornerShape(12.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp), color = tc.background, strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(st.calc.calculating, color = tc.background)
                    } else {
                        Icon(Icons.Default.Directions, null, tint = tc.background)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            st.calc.calculateRoutes,
                            color = tc.background, fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Error
                errorMsg?.let { msg ->
                    Text(
                        msg,
                        color    = tc.red,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                if (calculatedRoutes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${calculatedRoutes.size} ${if (calculatedRoutes.size > 1) st.calc.routesPlural else st.calc.routesSingle}",
                        color = tc.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = calculatedRoutes,
                            key = { route -> route.routePointsJson.ifBlank { route.summary } },
                        ) { r ->
                            val idx = calculatedRoutes.indexOf(r)
                            val selected = idx == selectedRouteIndex
                            Card(
                                modifier = Modifier
                                    .width(220.dp)
                                    .clickable { selectedRouteIndex = idx },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) tc.accent.copy(alpha = 0.16f) else tc.surface
                                ),
                                shape = RoundedCornerShape(10.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        "${idx + 1}. ${r.summary}",
                                        color = if (selected) tc.accent else tc.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(settings.formatDistance(r.distanceKm), color = tc.muted, fontSize = 11.sp)
                                        Text(settings.formatPrice(r.fare), color = tc.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                selectedRoute()?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(tc.accent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    settings.formatDistance(r.distanceKm),
                                    color = tc.textPrimary, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "%.0f ${st.history.waitSuffix}".format(r.estimatedWaitMin),
                                    color = tc.muted, fontSize = 11.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    settings.formatPrice(r.fare + fareAdj),
                                    color = tc.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${settings.formatPrice(r.distanceCost)} ${st.calc.perKmLabel}" +
                                        " + ${settings.formatPrice(r.waitCost)} ${st.calc.perMinLabel}",
                                    color = tc.muted, fontSize = 10.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FareAdjustCard(
                            currentAdjust = fareAdj,
                            symbol = settings.currency.symbol,
                            onAdjust = { fareAdj += it },
                            onReset = { fareAdj = 0.0 },
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tipText,
                            onValueChange = { tipText = it },
                            label = { Text("${st.tipLabel} (${settings.currency.symbol})", color = tc.muted) },
                            leadingIcon = { Icon(Icons.Default.CardGiftcard, null, tint = tc.muted, modifier = Modifier.size(18.dp)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
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

                Spacer(Modifier.height(8.dp))
            }

            // ── Map + Apply button ───────────────────────────────
            Box(Modifier.weight(1f)) {
                GoogleMap(
                    modifier            = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    uiSettings          = MapUiSettings(
                        zoomControlsEnabled     = true,
                        myLocationButtonEnabled = false,
                        mapToolbarEnabled       = false,
                    ),
                ) {
                    // Existing route (always shown as dim reference)
                    if (existingPoints.size >= 2) {
                        Polyline(
                            points = existingPoints,
                            color  = tc.muted.copy(
                                alpha = if (calculatedRoutes.isEmpty()) 0.7f else 0.25f
                            ),
                            width  = 10f,
                        )
                        val startMarkerState = remember(existingPoints) { MarkerState(existingPoints.first()) }
                        val endMarkerState = remember(existingPoints) { MarkerState(existingPoints.last()) }
                        Marker(
                            state = startMarkerState,
                            title = st.calc.fromPointLabel,
                            icon  = createLabeledMarker(android.graphics.Color.parseColor("#9E9E9E"), "S"),
                            zIndex = 1f,
                        )
                        Marker(
                            state = endMarkerState,
                            title = st.calc.toPointLabel,
                            icon  = createLabeledMarker(android.graphics.Color.parseColor("#BDBDBD"), "E"),
                            zIndex = 1f,
                        )
                    }

                    // New calculated routes. Selected one is bright; alternatives stay lighter.
                    calculatedRoutes.forEachIndexed { idx, r ->
                        if (r.decodedPoints.size >= 2) {
                            Polyline(
                                points = r.decodedPoints,
                                color  = if (idx == selectedRouteIndex) tc.accent else tc.accent.copy(alpha = 0.34f),
                                width  = if (idx == selectedRouteIndex) 14f else 9f,
                                zIndex = if (idx == selectedRouteIndex) 3f else 2f,
                            )
                            if (idx == selectedRouteIndex) {
                                val calcStartMarkerState = remember(r) { MarkerState(r.decodedPoints.first()) }
                                val calcEndMarkerState = remember(r) { MarkerState(r.decodedPoints.last()) }
                                Marker(
                                    state = calcStartMarkerState,
                                    icon  = createLabeledMarker(android.graphics.Color.parseColor("#4CAF50"), "S"),
                                    zIndex = 4f,
                                )
                                Marker(
                                    state = calcEndMarkerState,
                                    icon  = createLabeledMarker(android.graphics.Color.parseColor("#F44336"), "E"),
                                    zIndex = 4f,
                                )
                            }
                        }
                    }
                }

                // Apply Changes button — visible once a route has been calculated
                if (selectedRoute() != null) {
                    Button(
                        onClick  = { showConfirm = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tc.green),
                        shape  = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Check, null, tint = tc.background)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            st.history.applyChangesBtn,
                            color = tc.background, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        )
                    }
                }
            }
            }
        }
    }

    // ── Confirmation alert ───────────────────────────────────────
    if (showConfirm) {
        AlertDialog(
            onDismissRequest  = { showConfirm = false },
            containerColor    = tc.cardAlt,
            titleContentColor = tc.textPrimary,
            textContentColor  = tc.muted,
            title = {
                Text(st.history.editRouteConfirmTitle, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(st.history.editRouteConfirmMsg, fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedRoute()?.let { r ->
                        val newTip = tipText.replace(",", ".").toDoubleOrNull() ?: ride.tip
                        vm.updateRide(
                            ride.copy(
                                fromAddress     = r.fromAddress,
                                toAddress       = r.toAddress,
                                fromLat         = r.fromLat,
                                fromLng         = r.fromLng,
                                toLat           = r.toLat,
                                toLng           = r.toLng,
                                kilometers      = r.distanceKm,
                                waitMinutes     = r.estimatedWaitMin,
                                price           = r.fare + fareAdj,
                                tip             = newTip,
                                routePointsJson = r.routePointsJson,
                                avgSpeed        = if (r.distanceKm > 0 && r.durationMin > 0)
                                    r.distanceKm / (r.durationMin / 60.0) else ride.avgSpeed,
                            )
                        )
                    }
                    showConfirm = false
                    onDismiss()
                }) {
                    Text(
                        st.history.applyChangesBtn,
                        color = tc.green, fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            },
        )
    }

    // ── Map pin picker ───────────────────────────────────────────
    showMapPicker?.let { field ->
        MapPinPickerDialog(
            title     = if (field == "from") st.calc.fromPointLabel else st.calc.toPointLabel,
            api       = api,
            apiKey    = apiKey,
            onDismiss = { showMapPicker = null },
            onConfirm = { address ->
                if (field == "from") fromText = address else toText = address
                calculatedRoutes = emptyList()
                selectedRouteIndex = 0
                showMapPicker   = null
            },
            originalStart = if (ride.fromLat != 0.0 || ride.fromLng != 0.0) LatLng(ride.fromLat, ride.fromLng) else null,
            originalEnd = if (ride.toLat != 0.0 || ride.toLng != 0.0) LatLng(ride.toLat, ride.toLng) else null,
        )
    }
}
