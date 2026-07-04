package com.famviva.camara.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tuned on top of the Material 3 baseline: titles a touch bolder and tighter for a more
 * confident, app-like feel; labels slightly more spaced for legibility on overlays and chips.
 */
private val base = Typography()

val AppTypography = Typography(
    titleLarge = base.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    ),
    bodySmall = base.bodySmall.copy(letterSpacing = 0.15.sp),
)

/** A compact monospace-ish style for numeric readouts (sizes, latency) — falls back to default. */
val NumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium,
)
