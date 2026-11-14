package com.munchkin.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Munchkin Dungeon Edition dark color scheme.
 * Warm dark obsidian-stone surfaces paired with Crimson Torch, Dungeon Gold, and Torch Ember.
 */
private val DungeonDarkColorScheme = darkColorScheme(
    primary = NeonPrimary,
    onPrimary = NeonGray100,
    primaryContainer = NeonPrimaryDark,
    onPrimaryContainer = NeonGray100,

    secondary = NeonSecondary,
    onSecondary = NeonSurface,
    secondaryContainer = NeonSecondaryDark,
    onSecondaryContainer = NeonGray100,

    tertiary = NeonCyan,
    onTertiary = NeonSurface,
    tertiaryContainer = NeonCyanDark,
    onTertiaryContainer = NeonGray100,

    error = NeonError,
    onError = NeonSurface,
    errorContainer = Color(0xFF5C1414),
    onErrorContainer = NeonGray100,

    background = NeonBackground,
    onBackground = NeonGray100,

    surface = NeonSurface,
    onSurface = NeonGray100,
    surfaceVariant = NeonSurfaceVariant,
    onSurfaceVariant = NeonGray300,

    outline = NeonGray400,
    outlineVariant = DungeonBorder,

    inverseSurface = NeonGray100,
    inverseOnSurface = NeonBackground,
    inversePrimary = NeonPrimaryDark,

    scrim = Color.Black.copy(alpha = 0.6f)
)

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

val LocalLargeNumbersMode = staticCompositionLocalOf { false }

/**
 * Munchkin theme (Dungeon Edition).
 * Enforces dark theme and custom brand tokens across all API levels.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MunchkinTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    largeNumbersMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DungeonDarkColorScheme
    val combatColors = CombatColors(HeroGreen, MonsterRed)
    val typography = munchkinTypography(largeNumbersMode)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object MunchkinTheme {
    val combatColors: CombatColors
        @Composable
        get() = LocalCombatColors.current

    val isLargeNumbersMode: Boolean
        @Composable
        get() = LocalLargeNumbersMode.current
}
