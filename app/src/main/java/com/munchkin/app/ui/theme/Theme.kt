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
 * Luma-inspired modern dark color scheme.
 * Focus on elegance, minimal design, and vibrant accents.
 */
private val LumaDarkColorScheme = darkColorScheme(
    // Primary - Vibrant purple
    primary = LumaPrimary,
    onPrimary = Color.White,
    primaryContainer = LumaPrimaryDark,
    onPrimaryContainer = Color.White,
    
    // Secondary - Purple lighter
    secondary = LumaPrimaryLight,
    onSecondary = Color.White,
    secondaryContainer = LumaGray800,
    onSecondaryContainer = LumaGray100,
    
    // Tertiary - Accent orange
    tertiary = LumaAccent,
    onTertiary = Color.White,
    tertiaryContainer = LumaAccentDark,
    onTertiaryContainer = Color.White,
    
    // Error
    error = LumaError,
    onError = Color.White,
    errorContainer = LumaErrorLight,
    onErrorContainer = Color.White,
    
    // Backgrounds - True black for OLED
    background = LumaGray950,
    onBackground = LumaGray50,
    
    // Surfaces - Subtle elevation with gray
    surface = LumaGray900,
    onSurface = LumaGray100,
    surfaceVariant = LumaGray800,
    onSurfaceVariant = LumaGray400,
    
    // Outline
    outline = LumaGray700,
    outlineVariant = LumaGray800,
    
    // Inverse
    inverseSurface = LumaGray100,
    inverseOnSurface = LumaGray900,
    inversePrimary = LumaPrimaryDark,
    
    // Scrim
    scrim = Color.Black.copy(alpha = 0.5f)
)

/**
 * Light color scheme (optional, dark is default)
 */
private val LumaLightColorScheme = lightColorScheme(
    primary = LumaPrimary,
    onPrimary = Color.White,
    primaryContainer = LumaPrimaryLight.copy(alpha = 0.2f),
    onPrimaryContainer = LumaPrimaryDark,
    secondary = LumaPrimaryLight,
    onSecondary = Color.White,
    secondaryContainer = LumaGray200,
    onSecondaryContainer = LumaGray800,
    tertiary = LumaAccent,
    onTertiary = Color.White,
    tertiaryContainer = LumaAccentLight.copy(alpha = 0.2f),
    onTertiaryContainer = LumaAccentDark,
    error = LumaError,
    onError = Color.White,
    errorContainer = LumaErrorLight.copy(alpha = 0.2f),
    onErrorContainer = LumaError,
    background = LumaGray50,
    onBackground = LumaGray900,
    surface = Color.White,
    onSurface = LumaGray900,
    surfaceVariant = LumaGray100,
    onSurfaceVariant = LumaGray600,
    outline = LumaGray300,
    outlineVariant = LumaGray200
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
 * Munchkin theme with Luma-inspired design.
 * Dark mode by default for modern, premium feel.
 */
@Composable
fun MunchkinTheme(
    darkTheme: Boolean = true,  // Default to dark theme for Luma-style
    dynamicColor: Boolean = false,
    largeNumbersMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LumaDarkColorScheme
        else -> LumaLightColorScheme
    }
    
    val combatColors = CombatColors(HeroGreen, MonsterRed)
    val typography = if (largeNumbersMode) LargeNumbersTypography else Typography
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge with transparent bars
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
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
