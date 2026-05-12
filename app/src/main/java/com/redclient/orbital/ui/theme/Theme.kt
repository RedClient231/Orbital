package com.redclient.orbital.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Orbital's brand palette — neutral dark with a single purple accent.
// Chosen for legibility on OLED screens (the dominant Android hardware for gaming).

private val BrandAccent = Color(0xFF7C4DFF)
private val BrandAccentDark = Color(0xFF651FFF)
private val SurfaceDark = Color(0xFF101013)
private val SurfaceLight = Color(0xFFF7F6FA)

private val DarkColors = darkColorScheme(
    primary = BrandAccent,
    onPrimary = Color.White,
    primaryContainer = BrandAccentDark,
    onPrimaryContainer = Color.White,
    background = SurfaceDark,
    onBackground = Color(0xFFE6E1E5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6E1E5),
)

private val LightColors = lightColorScheme(
    primary = BrandAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    background = SurfaceLight,
    onBackground = Color(0xFF1C1B1F),
    surface = SurfaceLight,
    onSurface = Color(0xFF1C1B1F),
)

/**
 * Orbital's top-level theme wrapper. Uses dynamic Material You colors on
 * Android 12+ so the app adapts to the user's wallpaper; falls back to the
 * static brand palette on older versions.
 */
@Composable
fun OrbitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
