package com.taxipro.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * A full-colour RGB picker drawn entirely on Canvas:
 *   – outer ring  → hue  (0–360°, sweep gradient)
 *   – inner square → saturation (x) × brightness (y)
 *
 * Touch / drag on either region updates the selected colour live.
 * [onColorSelected] is called every time the colour changes.
 */
@Composable
fun ColorWheelPicker(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Decompose initial colour into HSV
    val initHsv = FloatArray(3).also {
        android.graphics.Color.colorToHSV(initialColor.toArgb(), it)
    }

    var hue by remember { mutableFloatStateOf(initHsv[0]) }   // 0..360
    var sat by remember { mutableFloatStateOf(initHsv[1]) }   // 0..1
    var bri by remember { mutableFloatStateOf(initHsv[2]) }   // 0..1

    // 0 = none, 1 = hue ring, 2 = SV square
    var touchTarget by remember { mutableIntStateOf(0) }
    var canvasSize  by remember { mutableStateOf(IntSize.Zero) }

    // Notify parent whenever the derived colour changes
    val currentColor by remember(hue, sat, bri) {
        derivedStateOf {
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))
        }
    }
    LaunchedEffect(currentColor) { onColorSelected(currentColor) }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(canvasSize) {
                if (canvasSize.width == 0) return@pointerInput

                val cx      = canvasSize.width  / 2f
                val cy      = canvasSize.height / 2f
                val outerR  = minOf(cx, cy) * 0.95f
                val ringW   = minOf(cx, cy) * 0.15f
                val innerR  = outerR - ringW
                val sqHalf  = innerR / sqrt(2f) * 0.85f

                fun applyPosition(x: Float, y: Float) {
                    val dx = x - cx
                    val dy = y - cy
                    when (touchTarget) {
                        1 -> {
                            var angle = atan2(dy, dx) * 180f / PI.toFloat()
                            if (angle < 0) angle += 360f
                            hue = angle
                        }
                        2 -> {
                            sat = ((dx + sqHalf) / (sqHalf * 2)).coerceIn(0f, 1f)
                            bri = (1f - (dy + sqHalf) / (sqHalf * 2)).coerceIn(0f, 1f)
                        }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dx   = down.position.x - cx
                    val dy   = down.position.y - cy
                    val dist = sqrt(dx * dx + dy * dy)

                    touchTarget = when {
                        dist in innerR..outerR              -> 1  // hue ring
                        abs(dx) <= sqHalf && abs(dy) <= sqHalf -> 2  // SV square
                        else                                -> 0
                    }
                    applyPosition(down.position.x, down.position.y)

                    do {
                        val event  = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        applyPosition(change.position.x, change.position.y)
                    } while (true)

                    touchTarget = 0
                }
            }
    ) {
        val cx     = size.width  / 2f
        val cy     = size.height / 2f
        val outerR = minOf(cx, cy) * 0.95f
        val ringW  = minOf(cx, cy) * 0.15f
        val innerR = outerR - ringW
        val sqHalf = innerR / sqrt(2f) * 0.85f
        val center = Offset(cx, cy)

        // ── Hue ring (sweep gradient 0→360) ─────────────────────
        val hueColors = List(361) { h ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf((h % 360).toFloat(), 1f, 1f)))
        }
        drawCircle(
            brush  = Brush.sweepGradient(colors = hueColors, center = center),
            radius = (outerR + innerR) / 2f,
            style  = Stroke(width = ringW),
        )

        // ── SV square (saturation × brightness) ─────────────────
        val sqLeft  = cx - sqHalf
        val sqTop   = cy - sqHalf
        val sqSize  = Size(sqHalf * 2, sqHalf * 2)
        val pureHue = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

        // Layer 1 – pure hue
        drawRect(color = pureHue, topLeft = Offset(sqLeft, sqTop), size = sqSize)
        // Layer 2 – white → transparent (left→right = S 0→1)
        drawRect(
            brush   = Brush.horizontalGradient(
                colors = listOf(Color.White, Color.Transparent),
                startX = sqLeft, endX = sqLeft + sqHalf * 2,
            ),
            topLeft = Offset(sqLeft, sqTop), size = sqSize,
        )
        // Layer 3 – transparent → black (top→bottom = V 1→0)
        drawRect(
            brush   = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = sqTop, endY = sqTop + sqHalf * 2,
            ),
            topLeft = Offset(sqLeft, sqTop), size = sqSize,
        )

        // ── Hue ring indicator ───────────────────────────────────
        val hRad       = hue * PI.toFloat() / 180f
        val ringMid    = (outerR + innerR) / 2f
        val ringInd    = Offset(cx + cos(hRad) * ringMid, cy + sin(hRad) * ringMid)
        val indR       = ringW / 2.4f
        drawCircle(Color.White,               indR,              ringInd)
        drawCircle(pureHue,                   indR - 3.dp.toPx(), ringInd)
        drawCircle(Color.Black.copy(0.25f),   indR,              ringInd,
            style = Stroke(1.5f.dp.toPx()))

        // ── SV indicator ─────────────────────────────────────────
        val svX    = sqLeft + sat * sqHalf * 2
        val svY    = sqTop  + (1f - bri) * sqHalf * 2
        val svOff  = Offset(svX, svY)
        val svR    = 8.dp.toPx()
        drawCircle(Color.White,              svR,          svOff, style = Stroke(2.5f.dp.toPx()))
        drawCircle(Color.Black.copy(0.20f),  svR + 1.5f,   svOff, style = Stroke(1.dp.toPx()))
    }
}
