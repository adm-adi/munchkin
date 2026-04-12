package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============== Neon Fantasy Palette — Vibrant Edition ==============

// Backgrounds — Deep space blue-black
val NeonBackground = Color(0xFF06091A)      // Near-black with blue tint
val NeonSurface = Color(0xFF0D1225)         // Deep navy
val NeonSurfaceVariant = Color(0xFF182038)  // Lighter navy

// Primary — Electric Violet (more vivid)
val NeonPrimary = Color(0xFFA855F7)         // Vibrant violet
val NeonPrimaryLight = Color(0xFFC084FC)    // Bright lavender
val NeonPrimaryDark = Color(0xFF7C3AED)     // Deep violet

// Secondary — Electric Magenta
val NeonSecondary = Color(0xFFE040FB)       // Vivid magenta/fuchsia
val NeonSecondaryLight = Color(0xFFEA80FC)  // Light pink-purple
val NeonSecondaryDark = Color(0xFFAA00FF)   // Deep purple-magenta

// Tertiary — Electric Cyan
val NeonCyan = Color(0xFF00E5FF)            // Vivid electric cyan
val NeonCyanLight = Color(0xFF40FFFF)       // Bright turquoise
val NeonCyanDark = Color(0xFF00B8D4)        // Deep teal-cyan

// Accent — Electric Gold & Lime
val NeonGold = Color(0xFFFFD600)            // Pure gold (victories/warnings)
val NeonLime = Color(0xFF76FF03)            // Neon lime (success bonus)
val NeonOrange = Color(0xFFFF6D00)          // Electric orange

// Neutrals
val NeonWhite = Color(0xFFF8FAFC)
val NeonGray100 = Color(0xFFEEF2FF)         // Slightly blue-tinted white
val NeonGray200 = Color(0xFFD0D7F0)
val NeonGray300 = Color(0xFFB4BCDB)
val NeonGray400 = Color(0xFF8892B0)
val NeonGray500 = Color(0xFF5A6285)

// ============== Glass Effect System ==============
// Layered glass simulation: no real blur, depth through alpha
val GlassWhite = Color(0x0FFFFFFF)          // 6% white — inner highlight
val GlassBorder = Color(0x3DFFFFFF)         // 24% white — main border
val GlassBorderBright = Color(0x66FFFFFF)   // 40% white — active/focus border
val GlassBorderDim = Color(0x14FFFFFF)      // 8% white — subtle border
val GlassDark = Color(0x26000000)           // 15% black — lighter glass base
val GlassBase = Color(0x1A0D1530)           // Tinted dark glass
val GlassDeep = Color(0x33060918)           // Deeper glass layer

// ============== Functional Colors ==============
val NeonSuccess = Color(0xFF00E676)         // Vivid emerald green
val NeonError = Color(0xFFFF1744)           // Vivid red
val NeonWarning = Color(0xFFFFD600)         // Gold (same as NeonGold)

// Combat Specific
val HeroGreen = Color(0xFF00E676)
val MonsterRed = Color(0xFFFF1744)

// ============== Gradients ==============
val GradientNeonPurple = listOf(NeonPrimary, NeonSecondary)
val GradientNeonBlue = listOf(NeonCyanDark, NeonPrimary)
val GradientNeonFire = listOf(NeonSecondary, Color(0xFFFF1744))
val GradientNeonGold = listOf(NeonGold, NeonOrange)
val GradientViridian = listOf(NeonCyan, NeonPrimary)
val GradientSunrise = listOf(NeonOrange, NeonSecondary, NeonPrimary)

// ============== Legacy/Compat ==============
val LumaGray100 = NeonGray100
val LumaGray500 = NeonGray500
val LumaGray900 = NeonBackground
val LumaAccent = NeonSecondary
val GradientOrangeEnd = NeonSecondaryLight

// ============== Avatar Colors ==============
val NeonAvatarColors = listOf(
    NeonPrimary, NeonSecondary, NeonCyan, NeonSuccess, NeonWarning, NeonError,
    Color(0xFFEC4899), Color(0xFF818CF8), Color(0xFF6366F1), Color(0xFF38BDF8),
    Color(0xFF34D399), Color(0xFFFBBF24)
)

fun getAvatarColor(avatarId: Int): Color {
    return NeonAvatarColors[Math.abs(avatarId) % NeonAvatarColors.size]
}
