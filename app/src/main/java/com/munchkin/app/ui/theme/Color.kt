package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============== Munchkin Official Palette - Dungeon Edition ==============

// Backgrounds - Warm dungeon dark-black (replaces cold blue-black)
val NeonBackground = Color(0xFF0F0A08)         // Dungeon black, warm red undertone
val NeonSurface = Color(0xFF1A110D)             // Dark warm stone brown
val NeonSurfaceVariant = Color(0xFF271A14)      // Lighter warm stone

// Primary - Munchkin Crimson Red (replaces electric violet)
val NeonPrimary = Color(0xFFCC2020)             // Munchkin brand red
val NeonPrimaryLight = Color(0xFFE05050)        // Lighter vivid red
val NeonPrimaryDark = Color(0xFF8B0000)         // Deep blood red

// Secondary - Munchkin Amber Gold (replaces electric magenta)
val NeonSecondary = Color(0xFFD4960A)           // Rich treasure-chest amber-gold
val NeonSecondaryLight = Color(0xFFEDB84A)      // Light honey gold
val NeonSecondaryDark = Color(0xFF8C5C00)       // Deep antique gold

// Tertiary - Torch Orange (replaces electric cyan)
val NeonCyan = Color(0xFFE85D00)                // Torch-flame orange
val NeonCyanLight = Color(0xFFFF8C40)           // Light warm ember
val NeonCyanDark = Color(0xFFA03A00)            // Deep burnt orange

// Accent - Gold, Lime, Orange
val NeonGold = Color(0xFFFFD600)                // Pure bright gold (victories/warnings)
val NeonLime = Color(0xFF8BC34A)                // Earthy moss green
val NeonOrange = Color(0xFFFF6D00)              // Deep orange

// Neutrals - Warm parchment scale (replaces cold blue-tinted grays)
val NeonWhite = Color(0xFFFAF7F0)
val NeonGray100 = Color(0xFFF5F0E8)             // Warm cream (primary text)
val NeonGray200 = Color(0xFFD8CFC0)
val NeonGray300 = Color(0xFFB8A898)
val NeonGray400 = Color(0xFF8A7A6A)
val NeonGray500 = Color(0xFF5A4A3A)

// ============== Glass Effect System ==============
// Layered glass simulation: no real blur, depth through alpha
val GlassWhite = Color(0x0FFFFFFF)              // 6% white - inner highlight
val GlassBorder = Color(0x3DFFFFFF)             // 24% white - main border
val GlassBorderBright = Color(0x66FFFFFF)       // 40% white - active/focus border
val GlassBorderDim = Color(0x14FFFFFF)          // 8% white - subtle border
val GlassDark = Color(0x26000000)               // 15% black - lighter glass base
val GlassBase = Color(0x1A200A05)               // Warm amber-brown tinted glass (10%)
val GlassDeep = Color(0x33150804)               // Deeper warm glass layer (20%)

// ============== Functional Colors ==============
val NeonSuccess = Color(0xFF00E676)             // Vivid emerald green (level-up/win)
val NeonError = Color(0xFFFF1744)               // Vivid red (errors/danger)
val NeonWarning = Color(0xFFFFD600)             // Gold (turn warnings - same as NeonGold)

// Combat Specific
val HeroGreen = Color(0xFF00E676)
val MonsterRed = Color(0xFFFF1744)

// ============== Gradients ==============
// Variable names kept for compatibility; values updated to Munchkin palette
val GradientNeonPurple = listOf(NeonPrimary, NeonSecondary)
// Red (#CC2020) -> Gold (#D4960A) - main CTA buttons

val GradientNeonBlue = listOf(NeonCyanDark, NeonPrimary)
// Burnt Orange (#A03A00) -> Red (#CC2020) - dungeon ember accent

val GradientNeonFire = listOf(NeonOrange, Color(0xFFCC2020))
// Orange (#FF6D00) -> Red (#CC2020) - authentic fire (register/danger actions)

val GradientNeonGold = listOf(NeonGold, NeonOrange)
// Gold (#FFD600) -> Orange (#FF6D00) - treasure gradient (unchanged)

val GradientViridian = listOf(NeonCyan, NeonPrimary)
// Torch Orange (#E85D00) -> Red (#CC2020) - End Turn button

val GradientSunrise = listOf(NeonOrange, NeonSecondary, NeonPrimary)
// Orange (#FF6D00) -> Gold (#D4960A) -> Red (#CC2020) - dungeon torchlight sweep

// ============== Legacy/Compat ==============
val LumaGray100 = NeonGray100
val LumaGray500 = NeonGray500
val LumaGray900 = NeonBackground
val LumaAccent = NeonSecondary
val GradientOrangeEnd = NeonSecondaryLight

// ============== Avatar Colors - Base Classes And Races ==============
val NeonAvatarColors = listOf(
    NeonPrimary,              // 0 Guerrero
    NeonSecondary,            // 1 Mago
    NeonCyan,                 // 2 Ladron
    NeonSuccess,              // 3 Clerigo
    Color(0xFF8B6914),        // 4 Humano
    NeonWarning,              // 5 Elfo
    NeonError,                // 6 Enano
    Color(0xFF8D4025),        // 7 Mediano
)

fun getAvatarColor(avatarId: Int): Color {
    return NeonAvatarColors[Math.abs(avatarId) % NeonAvatarColors.size]
}
