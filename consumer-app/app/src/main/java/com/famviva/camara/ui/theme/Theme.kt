package com.famviva.camara.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = md_primary,
    onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer,
    onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary,
    onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer,
    onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary,
    onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer,
    onTertiaryContainer = md_onTertiaryContainer,
    error = md_error,
    onError = md_onError,
    errorContainer = md_errorContainer,
    onErrorContainer = md_onErrorContainer,
    background = md_background,
    onBackground = md_onBackground,
    surface = md_surface,
    onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant,
    onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline,
    outlineVariant = md_outlineVariant,
    surfaceContainer = md_surfaceContainer,
    surfaceContainerHigh = md_surfaceContainerHigh,
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    surfaceContainer = md_dark_surfaceContainer,
    surfaceContainerHigh = md_dark_surfaceContainerHigh,
)

/**
 * App-specific semantic colours that don't map onto a Material role: the amber "low battery"
 * warning banner and the 5-step motion-intensity ramp. Kept here (theme-aware, light/dark variants)
 * so screens stop hard-coding Color(0x...) literals inline.
 */
data class AppStatusColors(
    val warningContainer: Color,
    val onWarningContainer: Color,
    val intensity: List<Color>, // index 0 = level 1 (faint) … index 4 = level 5 (strong)
)

private val LightStatusColors = AppStatusColors(
    warningContainer = Color(0xFFFFE0B2),
    onWarningContainer = Color(0xFF6D4C00),
    intensity = listOf(
        Color(0xFF9E9E9E), // 1 very faint / noise
        Color(0xFF43A047), // 2 light
        Color(0xFFC0CA33), // 3 moderate
        Color(0xFFF59E0B), // 4 notable
        Color(0xFFE53935), // 5 strong
    ),
)

private val DarkStatusColors = AppStatusColors(
    warningContainer = Color(0xFF4A3510),
    onWarningContainer = Color(0xFFFFDDB0),
    intensity = listOf(
        Color(0xFFB0B0B0),
        Color(0xFF66BB6A),
        Color(0xFFD4E157),
        Color(0xFFFFB74D),
        Color(0xFFFF6E6B),
    ),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** `MaterialTheme.status` — accessor for the app's semantic status colours inside composables. */
val MaterialTheme.status: AppStatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

@Composable
fun CamaraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val statusColors = if (dark) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
