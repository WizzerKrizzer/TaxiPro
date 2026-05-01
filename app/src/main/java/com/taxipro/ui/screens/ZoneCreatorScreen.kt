package com.taxipro.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.taxipro.data.db.Zone
import com.taxipro.data.db.ZONE_PALETTE
import com.taxipro.data.db.parseZonePoints
import com.taxipro.data.db.pointInPolygon
import com.taxipro.data.db.serializeZonePoints
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.RideViewModel

@Composable
fun ZoneCreatorScreen(
    rideVm: RideViewModel,
    existingZone: Zone? = null,
    onBack: () -> Unit,
) {
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current

    val allZones by rideVm.allZones.collectAsState(initial = emptyList())
    val otherZones = remember(allZones, existingZone) {
        if (existingZone != null) allZones.filter { it.id != existingZone.id } else allZones
    }

    var zoneName by remember { mutableStateOf(existingZone?.name ?: "") }

    // ── MarkerState list — each entry is one draggable corner ───────
    val markerStates = remember {
        mutableStateListOf<MarkerState>().also { list ->
            existingZone?.let {
                parseZonePoints(it.pointsJson).forEach { pt -> list.add(MarkerState(pt)) }
            }
        }
    }

    // ── Color ────────────────────────────────────────────────────────
    var colorArgb    by remember { mutableIntStateOf(existingZone?.color ?: ZONE_PALETTE[0]) }
    var colorAutoSet by remember { mutableStateOf(existingZone != null) }
    var showColorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(allZones.size) {
        if (!colorAutoSet && existingZone == null) {
            colorArgb    = ZONE_PALETTE[allZones.size % ZONE_PALETTE.size]
            colorAutoSet = true
        }
    }

    val polyColor = Color(colorArgb)

    // ── Drag indicator animation ─────────────────────────────────────
    var draggingIdx by remember { mutableIntStateOf(-1) }
    val sweepAngle  = remember { Animatable(0f) }

    LaunchedEffect(draggingIdx) {
        if (draggingIdx >= 0) {
            sweepAngle.snapTo(0f)
            sweepAngle.animateTo(
                targetValue   = 360f,
                animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
            )
        } else {
            sweepAngle.snapTo(0f)
        }
    }

    // ── Blocked-point toast (auto-clears after 3 s) ──────────────────
    var blockedByZone by remember { mutableStateOf("") }
    LaunchedEffect(blockedByZone) {
        if (blockedByZone.isNotEmpty()) {
            kotlinx.coroutines.delay(3000)
            blockedByZone = ""
        }
    }

    // ── Validation ───────────────────────────────────────────────────
    val isDuplicateName = settings.blockDuplicateZoneName &&
        zoneName.isNotBlank() &&
        otherZones.any { it.name.trim().equals(zoneName.trim(), ignoreCase = true) }

    val canSave = markerStates.size >= 3 && zoneName.isNotBlank() && !isDuplicateName

    // Overlap check: inline so drag position changes are reflected immediately
    val pts = markerStates.map { it.position }
    val overlappingNames = if (pts.size < 3) emptyList()
    else otherZones.filter { other ->
        val otherPts = parseZonePoints(other.pointsJson)
        if (otherPts.size < 3) return@filter false
        pts.any { pt -> pointInPolygon(pt.latitude, pt.longitude, otherPts) } ||
        otherPts.any { pt -> pointInPolygon(pt.latitude, pt.longitude, pts) }
    }.map { it.name }

    // ── Camera ───────────────────────────────────────────────────────
    val cameraState = rememberCameraPositionState {
        val center = existingZone?.let {
            val p = parseZonePoints(it.pointsJson)
            if (p.isNotEmpty()) LatLng(p.map { v -> v.latitude }.average(),
                                       p.map { v -> v.longitude }.average())
            else null
        }
        position = CameraPosition.fromLatLngZoom(center ?: LatLng(42.1500, 24.7500), 12f)
    }
    LaunchedEffect(existingZone) {
        if (existingZone != null) {
            val p = parseZonePoints(existingZone.pointsJson)
            if (p.size >= 2) {
                val b = LatLngBounds.Builder().also { b -> p.forEach { b.include(it) } }.build()
                cameraState.animate(CameraUpdateFactory.newLatLngBounds(b, 80))
            }
        }
    }

    // ── UI ───────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize()) {

        GoogleMap(
            modifier            = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            uiSettings          = MapUiSettings(
                zoomControlsEnabled     = true,
                myLocationButtonEnabled = false,
            ),
            onMapClick = { latLng ->
                if (settings.blockZoneOverlapPoints) {
                    val blocking = otherZones.firstOrNull { zone ->
                        pointInPolygon(latLng.latitude, latLng.longitude,
                            parseZonePoints(zone.pointsJson))
                    }
                    if (blocking != null) blockedByZone = blocking.name
                    else markerStates.add(MarkerState(latLng))
                } else {
                    markerStates.add(MarkerState(latLng))
                }
            },
        ) {
            // Reference zones (faded)
            otherZones.forEach { zone ->
                val rPts = remember(zone.pointsJson) { parseZonePoints(zone.pointsJson) }
                if (rPts.size >= 3) {
                    Polygon(
                        points      = rPts,
                        fillColor   = Color(zone.color).copy(alpha = 0.15f),
                        strokeColor = Color(zone.color).copy(alpha = 0.55f),
                        strokeWidth = 3f,
                        clickable   = false,
                    )
                }
            }

            // Current zone polygon / polyline
            val livePts = markerStates.map { it.position }
            when {
                livePts.size >= 3 -> Polygon(
                    points      = livePts,
                    fillColor   = polyColor.copy(alpha = 0.25f),
                    strokeColor = polyColor,
                    strokeWidth = 5f,
                    clickable   = false,
                )
                livePts.size == 2 -> Polyline(
                    points    = livePts,
                    color     = polyColor,
                    width     = 5f,
                    clickable = false,
                )
            }

            // Draggable numbered markers
            // DragState is observed via MarkerState.dragState (Maps Compose 6.x API)
            markerStates.forEachIndexed { idx, ms ->
                key(ms) {
                    LaunchedEffect(ms.dragState) {
                        when (ms.dragState) {
                            DragState.START -> draggingIdx = idx
                            DragState.END   -> if (draggingIdx == idx) draggingIdx = -1
                            else            -> {}
                        }
                    }
                    Marker(
                        state     = ms,
                        draggable = true,
                        icon      = createLabeledMarker(colorArgb, "${idx + 1}"),
                        title     = "${idx + 1}",
                    )
                }
            }
        }

        // ── Drag arc overlay ─────────────────────────────────────────
        val sweep = sweepAngle.value  // read in composable scope → per-frame recomposition
        if (draggingIdx >= 0 && draggingIdx < markerStates.size) {
            val dragLatLng  = markerStates[draggingIdx].position
            val screenPoint = cameraState.projection?.toScreenLocation(dragLatLng)
            if (screenPoint != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx        = screenPoint.x.toFloat()
                    val cy        = screenPoint.y.toFloat()
                    val arcRadius = 36.dp.toPx()
                    val stroke    = 5.dp.toPx()

                    // Track (faint ring)
                    drawCircle(
                        color  = polyColor.copy(alpha = 0.20f),
                        radius = arcRadius,
                        center = Offset(cx, cy),
                        style  = Stroke(width = stroke),
                    )
                    // Sweeping arc — top center (-90°), clockwise
                    if (sweep > 0f) {
                        drawArc(
                            color      = polyColor.copy(alpha = 0.90f),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter  = false,
                            topLeft    = Offset(cx - arcRadius, cy - arcRadius),
                            size       = Size(arcRadius * 2, arcRadius * 2),
                            style      = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    // Center dot
                    drawCircle(
                        color  = polyColor.copy(alpha = 0.70f),
                        radius = 5.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                }
            }
        }

        // ── Top overlay ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(tc.background.copy(alpha = 0.93f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(pass = PointerEventPass.Final)
                                .changes.forEach { if (!it.isConsumed) it.consume() }
                        }
                    }
                }
        ) {
            // Back + title + colour swatch
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = tc.accent)
                }
                Text(
                    if (existingZone != null) st.zones.zoneEditBtn else st.zones.createZoneBtn,
                    color = tc.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .background(polyColor, RoundedCornerShape(8.dp))
                        .clickable { showColorPicker = true }
                )
                Spacer(Modifier.width(12.dp))
            }

            // Zone name
            OutlinedTextField(
                value         = zoneName,
                onValueChange = { zoneName = it },
                label         = { Text(st.zones.zoneNameHint, fontSize = 12.sp) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = tc.accent,
                    unfocusedBorderColor = tc.muted,
                    focusedLabelColor    = tc.accent,
                    unfocusedLabelColor  = tc.muted,
                    focusedTextColor     = tc.textPrimary,
                    unfocusedTextColor   = tc.textPrimary,
                    cursorColor          = tc.accent,
                ),
            )

            Spacer(Modifier.height(4.dp))

            // Instruction / corner count
            Text(
                if (markerStates.size < 3)
                    st.zones.tapCornerInstruction.format(markerStates.size + 1, 3)
                else
                    "✓ ${markerStates.size} ${st.zones.cornerCountLabel}",
                color    = if (markerStates.size >= 3) tc.green else tc.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )

            // Drag hint
            if (markerStates.isNotEmpty()) {
                Text(
                    st.zones.dragMarkerHint,
                    color    = tc.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp)
                )
            }

            // Blocked-point toast
            if (blockedByZone.isNotEmpty()) {
                val msg = "🚫 ${st.prefs.blockOverlapSub.substringBefore('.')} \"$blockedByZone\""
                Text(msg, color = tc.red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            // Polygon overlap warning
            if (overlappingNames.isNotEmpty()) {
                Text(
                    "⚠ ${st.zones.zoneOverlapWarning}: ${overlappingNames.joinToString(", ")}",
                    color = Color(0xFFFF9A3C), fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            // Duplicate name warning
            if (isDuplicateName) {
                Text(
                    "🚫 ${st.prefs.blockDupNameSub}",
                    color = tc.red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            // Undo + Save
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick  = { if (markerStates.isNotEmpty()) markerStates.removeAt(markerStates.size - 1) },
                    enabled  = markerStates.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = tc.muted),
                    shape    = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Undo, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }

                Button(
                    onClick = {
                        if (!canSave) return@Button
                        val savePoints = markerStates.map { it.position }
                        if (existingZone != null) {
                            rideVm.updateZone(existingZone.copy(
                                name       = zoneName.trim(),
                                pointsJson = serializeZonePoints(savePoints),
                                color      = colorArgb,
                            ))
                        } else {
                            rideVm.addZone(zoneName.trim(), savePoints, colorArgb)
                        }
                        onBack()
                    },
                    enabled  = canSave,
                    modifier = Modifier.weight(2f).height(48.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = tc.accent),
                    shape    = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Save, null, tint = tc.background,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (existingZone != null) st.zones.zoneEditBtn else st.zones.createZoneBtn,
                        color = tc.background, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── Colour picker dialog ──────────────────────────────────────
        if (showColorPicker) {
            Dialog(onDismissRequest = { showColorPicker = false }) {
                val tc2 = LocalThemeColors.current
                Card(
                    shape    = RoundedCornerShape(20.dp),
                    colors   = CardDefaults.cardColors(containerColor = tc2.card),
                    modifier = Modifier.padding(8.dp),
                ) {
                    Column(
                        modifier            = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // ── Copy from existing zone ───────────────
                        if (allZones.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    st.zones.copyColorFromZone,
                                    color    = tc2.muted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(allZones) { zone ->
                                        val zoneColor = Color(zone.color)
                                        val isSelected = zone.color == colorArgb
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier
                                                .clickable { colorArgb = zone.color }
                                                .padding(4.dp),
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .background(zoneColor, CircleShape)
                                                    .then(
                                                        if (isSelected)
                                                            Modifier.border(2.5.dp, tc2.textPrimary, CircleShape)
                                                        else
                                                            Modifier.border(1.dp, tc2.muted.copy(alpha = 0.4f), CircleShape)
                                                    )
                                            )
                                            Text(
                                                zone.name,
                                                color    = if (isSelected) tc2.textPrimary else tc2.muted,
                                                fontSize = 9.sp,
                                                textAlign = TextAlign.Center,
                                                maxLines  = 1,
                                                modifier  = Modifier.widthIn(max = 44.dp),
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = tc2.surface, thickness = 0.5.dp)
                            }
                        }

                        // ── Colour wheel ──────────────────────────
                        ColorWheelPicker(
                            initialColor    = Color(colorArgb),
                            onColorSelected = { colorArgb = it.toArgb() },
                            modifier        = Modifier.fillMaxWidth().aspectRatio(1f),
                        )

                        // ── Preview swatch ────────────────────────
                        Box(
                            Modifier.fillMaxWidth().height(36.dp)
                                .background(Color(colorArgb), RoundedCornerShape(10.dp))
                        )

                        // ── OK ────────────────────────────────────
                        Button(
                            onClick  = { showColorPicker = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = tc2.accent),
                            shape    = RoundedCornerShape(12.dp),
                        ) {
                            Text("OK", color = tc2.background, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
