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

// Dark color scheme
private val DarkColorScheme = darkColorScheme(
    primary = Gold60,
    onPrimary = Color.Black,
    primaryContainer = GoldDark,
    onPrimaryContainer = Color.White,
    secondary = Purple60,
    onSecondary = Color.Black,
    secondaryContainer = PurpleDark,
    onSecondaryContainer = Color.White,
    tertiary = HeroGreen,
    onTertiary = Color.Black,
    tertiaryContainer = HeroGreenDark,
    onTertiaryContainer = Color.White,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorDark,
    onErrorContainer = Color.White,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

// Light color scheme
private val LightColorScheme = lightColorScheme(
    primary = Gold40,
    onPrimary = Color.Black,
    primaryContainer = Gold80,
    onPrimaryContainer = Color.Black,
    secondary = Purple40,
    onSecondary = Color.White,
    secondaryContainer = Purple80,
    onSecondaryContainer = Color.Black,
    tertiary = HeroGreen,
    onTertiary = Color.White,
    tertiaryContainer = HeroGreenDark,
    onTertiaryContainer = Color.White,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorDark,
    onErrorContainer = Color.White,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
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

@Composable
fun MunchkinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,  // Disabled for consistent branding
    largeNumbersMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val combatColors = if (darkTheme) {
        CombatColors(HeroGreen, MonsterRed)
    } else {
        CombatColors(HeroGreenDark, MonsterRedDark)
    }
    
    val typography = if (largeNumbersMode) LargeNumbersTypography else Typography
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
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

// Extension property to access combat colors
object MunchkinTheme {
    val combatColors: CombatColors
        @Composable
        get() = LocalCombatColors.current
    
    val isLargeNumbersMode: Boolean
        @Composable
        get() = LocalLargeNumbersMode.current
}
