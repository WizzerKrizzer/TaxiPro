package com.taxipro.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.taxipro.data.network.decodePolyline
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel
import kotlinx.coroutines.launch

// Calculated route data for ride editing
private data class EditRouteResult(
    val distanceKm: Double,
    val durationMin: Double,
    val estimatedWaitMin: Double,
    val fare: Double,
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
    val scope    = rememberCoroutineScope()
    val tariffs  by vm.allTariffs.collectAsState(initial = emptyList())

    var fromText  by remember { mutableStateOf(ride.fromAddress) }
    var toText    by remember { mutableStateOf(ride.toAddress) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    var calculatedRoute by remember { mutableStateOf<EditRouteResult?>(null) }
    var showConfirm     by remember { mutableStateOf(false) }
    var showMapPicker   by remember { mutableStateOf<String?>(null) }
    var selectedTariff  by remember { mutableStateOf<Tariff?>(null) }

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

    fun doCalculate() {
        if (fromText.isBlank() || toText.isBlank()) {
            errorMsg = st.calc.enterFromTo
            return
        }
        isLoading = true; errorMsg = null; calculatedRoute = null
        scope.launch {
            try {
                val resp = api.getDirections(fromText.trim(), toText.trim(), apiKey = apiKey)
                if (resp.status != "OK" || resp.routes.isEmpty()) {
                    errorMsg = st.calc.noRouteFound
                } else {
                    val route    = resp.routes.first()
                    val leg      = route.legs.first()
                    val distKm   = leg.distance.value / 1000.0
                    val durMin   = leg.duration.value / 60.0
                    // Estimate wait as time beyond free-flow speed
                    val waitMin  = ((durMin - distKm / 40.0 * 60.0)).coerceAtLeast(0.0)
                    val rawPts   = route.overview_polyline?.points?.let { decodePolyline(it) } ?: emptyList()
                    val latLngs  = rawPts.map { (lat, lng) -> LatLng(lat, lng) }
                    val json     = rawPts.joinToString(",", "[", "]") { (lat, lng) -> "[$lat,$lng]" }
                    val fare     = activeStartFee() +
                                   distKm * activePricePerKm() +
                                   waitMin * activePricePerMin()

                    calculatedRoute = EditRouteResult(
                        distanceKm       = distKm,
                        durationMin      = durMin,
                        estimatedWaitMin = waitMin,
                        fare             = fare,
                        fromAddress      = leg.start_address,
                        toAddress        = leg.end_address,
                        fromLat          = leg.start_location.lat,
                        fromLng          = leg.start_location.lng,
                        toLat            = leg.end_location.lat,
                        toLng            = leg.end_location.lng,
                        routePointsJson  = json,
                        decodedPoints    = latLngs,
                    )

                    if (latLngs.size >= 2) {
                        val b = LatLngBounds.Builder()
                            .also { b -> latLngs.forEach { b.include(it) } }.build()
                        cameraState.animate(CameraUpdateFactory.newLatLngBounds(b, 80))
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
        Column(Modifier.fillMaxSize().background(tc.background)) {

            // ── Top panel ────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
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
                        items(tariffs) { t ->
                            FilterChip(
                                selected = selectedTariff?.id == t.id,
                                onClick  = {
                                    selectedTariff = if (selectedTariff?.id == t.id) null else t
                                    calculatedRoute = null
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
                        onValueChange = { fromText = it; calculatedRoute = null },
                        label         = st.calc.fromPointLabel,
                        accentColor   = tc.green,
                        api           = api,
                        apiKey        = apiKey,
                        onMyLocation  = { fromText = it; calculatedRoute = null },
                        onPickFromMap = { showMapPicker = "from" },
                    )
                }

                // Swap
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = {
                        val tmp = fromText; fromText = toText; toText = tmp
                        calculatedRoute = null
                    }) {
                        Icon(Icons.Default.SwapVert, null, tint = tc.accent)
                    }
                }

                // To
                Column(Modifier.padding(horizontal = 12.dp)) {
                    AddressInputField(
                        value         = toText,
                        onValueChange = { toText = it; calculatedRoute = null },
                        label         = st.calc.toPointLabel,
                        accentColor   = tc.red,
                        api           = api,
                        apiKey        = apiKey,
                        onMyLocation  = { toText = it; calculatedRoute = null },
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

                // Result summary card
                calculatedRoute?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(tc.accent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
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
                                settings.formatPrice(r.fare),
                                color = tc.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${settings.formatPrice(r.distanceKm * activePricePerKm())} ${st.calc.perKmLabel}" +
                                " + ${settings.formatPrice(r.estimatedWaitMin * activePricePerMin())} ${st.calc.perMinLabel}",
                                color = tc.muted, fontSize = 10.sp,
                            )
                        }
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
                                alpha = if (calculatedRoute == null) 0.7f else 0.25f
                            ),
                            width  = 10f,
                        )
                        if (calculatedRoute == null) {
                            val startMarkerState = remember(existingPoints) { MarkerState(existingPoints.first()) }
                            val endMarkerState = remember(existingPoints) { MarkerState(existingPoints.last()) }
                            Marker(
                                state = startMarkerState,
                                icon  = createLabeledMarker(
                                    android.graphics.Color.parseColor("#4CAF50"), "S"
                                ),
                            )
                            Marker(
                                state = endMarkerState,
                                icon  = createLabeledMarker(
                                    android.graphics.Color.parseColor("#F44336"), "E"
                                ),
                            )
                        }
                    }

                    // New calculated route (bright, on top)
                    calculatedRoute?.let { r ->
                        if (r.decodedPoints.size >= 2) {
                            Polyline(
                                points = r.decodedPoints,
                                color  = tc.accent,
                                width  = 14f,
                            )
                            val calcStartMarkerState = remember(r) { MarkerState(r.decodedPoints.first()) }
                            val calcEndMarkerState = remember(r) { MarkerState(r.decodedPoints.last()) }
                            Marker(
                                state = calcStartMarkerState,
                                icon  = createLabeledMarker(
                                    android.graphics.Color.parseColor("#4CAF50"), "S"
                                ),
                                zIndex = 2f,
                            )
                            Marker(
                                state = calcEndMarkerState,
                                icon  = createLabeledMarker(
                                    android.graphics.Color.parseColor("#F44336"), "E"
                                ),
                                zIndex = 2f,
                            )
                        }
                    }
                }

                // Apply Changes button — visible once a route has been calculated
                if (calculatedRoute != null) {
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
                    calculatedRoute?.let { r ->
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
                                price           = r.fare,
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
                calculatedRoute = null
                showMapPicker   = null
            },
        )
    }
}
