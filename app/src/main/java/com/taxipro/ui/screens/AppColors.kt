package com.taxipro.ui.screens

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

data class ThemeColors(
    val background: Color,   // main screen bg (was: Dark)
    val card: Color,         // card surface (was: Card)
    val cardAlt: Color,      // darker card for dialogs/overlays
    val surface: Color,      // dividers, inner surfaces (was: Color(0xFF1E2430))
    val navBar: Color,       // bottom nav bar
    val accent: Color,       // primary accent (was: Gold)
    val green: Color,        // success/positive (was: Green)
    val red: Color,          // error/expense (was: Red)
    val muted: Color,        // secondary text (was: Muted)
    val blue: Color,         // info (was: Blue)
    val purple: Color,       // tips/tertiary (was: Purple)
    val textPrimary: Color,  // primary text (was: Color.White)
)

val DarkColors = ThemeColors(
    background  = Color(0xFF0A0C10),
    card        = Color(0xFF161A22),
    cardAlt     = Color(0xFF0D1017),
    surface     = Color(0xFF1E2430),
    navBar      = Color(0xFF111318),
    accent      = Color(0xFFF5C842),
    green       = Color(0xFF2ECC8A),
    red         = Color(0xFFE85555),
    muted       = Color(0xFF6B7280),
    blue        = Color(0xFF4A9EFF),
    purple      = Color(0xFFA78BFA),
    textPrimary = Color.White,
)

val LightColors = ThemeColors(
    background  = Color(0xFFF2F4F8),
    card        = Color(0xFFFFFFFF),
    cardAlt     = Color(0xFFE8EBF0),
    surface     = Color(0xFFD8DCE6),
    navBar      = Color(0xFFFFFFFF),
    accent      = Color(0xFFB8900F),
    green       = Color(0xFF1A9E6A),
    red         = Color(0xFFCC3333),
    muted       = Color(0xFF8A95A3),
    blue        = Color(0xFF2B7FD4),
    purple      = Color(0xFF7C5FC8),
    textPrimary = Color(0xFF0A0C10),
)

val MidnightColors = ThemeColors(
    background  = Color(0xFF05080F),
    card        = Color(0xFF0C1220),
    cardAlt     = Color(0xFF080E1A),
    surface     = Color(0xFF162035),
    navBar      = Color(0xFF070B16),
    accent      = Color(0xFF7B9EFF),
    green       = Color(0xFF3DDBA8),
    red         = Color(0xFFFF6B7A),
    muted       = Color(0xFF4A5A7A),
    blue        = Color(0xFF60B8FF),
    purple      = Color(0xFFBC9FFF),
    textPrimary = Color.White,
)

val SunsetColors = ThemeColors(
    background  = Color(0xFF110A03),
    card        = Color(0xFF1E1108),
    cardAlt     = Color(0xFF160C04),
    surface     = Color(0xFF2A1A0A),
    navBar      = Color(0xFF0D0702),
    accent      = Color(0xFFFF8C42),
    green       = Color(0xFF44D4A0),
    red         = Color(0xFFFF5252),
    muted       = Color(0xFF7A6050),
    blue        = Color(0xFF5CC8FF),
    purple      = Color(0xFFD48FFF),
    textPrimary = Color.White,
)

val ForestColors = ThemeColors(
    background  = Color(0xFF050F07),
    card        = Color(0xFF0C1E0F),
    cardAlt     = Color(0xFF081508),
    surface     = Color(0xFF132A16),
    navBar      = Color(0xFF040C05),
    accent      = Color(0xFF7ED321),
    green       = Color(0xFF4CD964),
    red         = Color(0xFFFF6B6B),
    muted       = Color(0xFF4A6A4F),
    blue        = Color(0xFF52C8E0),
    purple      = Color(0xFFB8A4D8),
    textPrimary = Color.White,
)

val LocalThemeColors = compositionLocalOf { DarkColors }

// ── Shared map marker ────────────────────────────────────────────
fun createLabeledMarker(bgColor: Int, letter: String): BitmapDescriptor {
    val size   = 72
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // Shadow
    paint.color      = android.graphics.Color.argb(60, 0, 0, 0)
    paint.maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    canvas.drawCircle(size / 2f, size / 2f + 3f, size / 2f - 4f, paint)
    paint.maskFilter = null

    // Fill
    paint.color = bgColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

    // White border
    paint.color       = android.graphics.Color.WHITE
    paint.style       = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 4f
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

    // Letter
    paint.style     = android.graphics.Paint.Style.FILL
    paint.color     = android.graphics.Color.WHITE
    paint.textSize  = 30f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.typeface  = android.graphics.Typeface.DEFAULT_BOLD
    val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(letter, size / 2f, textY, paint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
