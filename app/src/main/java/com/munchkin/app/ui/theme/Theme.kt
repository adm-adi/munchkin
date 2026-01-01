package com.munchkin.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Neon Fantasy dark color scheme.
 * Deep slate backgrounds with electric violet, fuchsia and cyan accents.
 */
private val NeonDarkColorScheme = darkColorScheme(
    // Primary - Electric Violet
    primary = NeonPrimary,
    onPrimary = Color.White,
    primaryContainer = NeonPrimaryDark,
    onPrimaryContainer = NeonGray100,
    
    // Secondary - Hot Pink / Fuchsia
    secondary = NeonSecondary,
    onSecondary = Color.White,
    secondaryContainer = NeonSecondaryDark,
    onSecondaryContainer = NeonGray100,
    
    // Tertiary - Cyan (Tech/Info)
    tertiary = NeonCyan,
    onTertiary = Color.White,
    tertiaryContainer = NeonCyanDark,
    onTertiaryContainer = NeonGray100,
    
    // Error
    error = NeonError,
    onError = Color.White,
    errorContainer = Color(0xFF991B1B), // Dark Red
    onErrorContainer = NeonGray100,
    
    // Backgrounds - Deep Slate
    background = NeonBackground,
    onBackground = NeonGray100,
    
    // Surfaces
    surface = NeonSurface,
    onSurface = NeonGray100,
    surfaceVariant = NeonSurfaceVariant,
    onSurfaceVariant = NeonGray300,
    
    // Outline
    outline = NeonGray500,
    outlineVariant = NeonGray500,
    
    // Inverse
    inverseSurface = NeonGray100,
    inverseOnSurface = NeonBackground,
    inversePrimary = NeonPrimaryDark,
    
    // Scrim
    scrim = Color.Black.copy(alpha = 0.5f)
)

// Combat colors provider
data class CombatColors(
    val heroColor: Color,
    val monsterColor: Color
)

val LocalCombatColors = staticCompositionLocalOf {
    CombatColors(
        heroColor = HeroGreen,
        monsterColor = MonsterRed
    )
}

// Large numbers mode provider
val LocalLargeNumbersMode = staticCompositionLocalOf { false }

/**
 * Munchkin theme with Neon Fantasy design.
 * Always dark mode for consistency with the theme.
 */
@Composable
fun MunchkinTheme(
    darkTheme: Boolean = true, // Force dark theme for Neon Fantasy
    dynamicColor: Boolean = false, // Disable dynamic color to enforce theme
    largeNumbersMode: Boolean = false,
    content: @Composable () -> Unit
) {
    // We enforce our Neon Dark scheme
    val colorScheme = NeonDarkColorScheme
    
    val combatColors = CombatColors(HeroGreen, MonsterRed)
    val typography = if (largeNumbersMode) LargeNumbersTypography else Typography
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge transparent
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false // Always dark status bar (light icons)
                isAppearanceLightNavigationBars = false
            }
        }
    }
    
    CompositionLocalProvider(
        LocalCombatColors provides combatColors,
        LocalLargeNumbersMode provides largeNumbersMode
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

// Extension property to access custom theme values
object MunchkinTheme {
    val combatColors: CombatColors
        @Composable
        get() = LocalCombatColors.current
    
    val isLargeNumbersMode: Boolean
        @Composable
        get() = LocalLargeNumbersMode.current
}
