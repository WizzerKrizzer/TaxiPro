package com.taxipro.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.taxipro.ui.viewmodel.TrackingViewModel
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MapScreen(vm: TrackingViewModel) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Текуща локация
    var myLat by remember { mutableStateOf(0.0) }
    var myLng by remember { mutableStateOf(0.0) }

    // Взимаме реалната локация
    DisposableEffect(Unit) {
        val fusedClient = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(context)
        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 3000L
        ).setMinUpdateDistanceMeters(5f).build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(
                result: com.google.android.gms.location.LocationResult
            ) {
                result.lastLocation?.let {
                    myLat = it.latitude
                    myLng = it.longitude
                }
            }
        }
        try {
            fusedClient.requestLocationUpdates(
                request, callback, android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
        }
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    val displayLat = if (state.currentLat != 0.0) state.currentLat else myLat
    val displayLng = if (state.currentLng != 0.0) state.currentLng else myLng

    // НОВО:
    var locationReady by remember { mutableStateOf(false) }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 15f)
    }

    LaunchedEffect(displayLat, displayLng) {
        if (displayLat != 0.0 && displayLng != 0.0 && !locationReady) {
            locationReady = true
            cameraState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(displayLat, displayLng), 15f
                )
            )
        } else if (displayLat != 0.0 && displayLng != 0.0) {
            cameraState.animate(
                CameraUpdateFactory.newLatLng(LatLng(displayLat, displayLng))
            )
        }
    }

    // Следи позицията в реално време
    LaunchedEffect(displayLat, displayLng) {
        if (displayLat != 0.0 && displayLng != 0.0) {
            cameraState.animate(
                CameraUpdateFactory.newLatLng(LatLng(displayLat, displayLng))
            )
        }
    }

// 1. Използваме Box като контейнер, за да можем да застъпваме елементи
    Box(modifier = Modifier.fillMaxSize()) {

        // 2. Картата е първият слой (най-отдолу)
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(
                isMyLocationEnabled = false,
                mapType = MapType.NORMAL,
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                myLocationButtonEnabled = false,
                compassEnabled = true,
            )
        ) {
            // Тук си остават твоите маркери и линии (Polyline, Marker и т.н.)
            if (displayLat != 0.0 && displayLng != 0.0) {
                key(displayLat, displayLng) {
                    Marker(
                        state = rememberMarkerState(position = LatLng(displayLat, displayLng)),
                        icon = createDotMarker(),
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 10f,
                        flat = true
                    )
                }
            }

            if (state.routePoints.size >= 2) {
                Polyline(
                    points    = state.routePoints.map { (lat, lng) -> LatLng(lat, lng) },
                    color     = Color(0xFF2196F3),   // Material Blue
                    width     = 14f,
                    zIndex    = 5f,
                    geodesic  = true,
                )
            }
        }

        // 3. СЛОЯ ЗА ЗАРЕЖДАНЕ (Най-отгоре)
        // Ако локацията още не е готова, рисуваме този Box върху картата
        if (!locationReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0C10)), // Тъмен фон
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                ) {
                    // Въртящото се кръгче
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFF5C842))
                    androidx.compose.material3.Text(
                        text = "Getting your location...",
                        color = Color(0xFF6B7280),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
} // Край на MapScreen

@Composable
fun createDotMarker(): BitmapDescriptor {
    val size   = 32  // пиксели — винаги еднакво
    val bitmap = android.graphics.Bitmap.createBitmap(size, size,
        android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // Черна точка
    paint.color = android.graphics.Color.RED
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}