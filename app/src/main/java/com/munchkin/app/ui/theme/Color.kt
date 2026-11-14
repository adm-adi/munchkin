package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// ============== Munchkin Official Palette - Dungeon Edition ==============

// Backgrounds - Warm dungeon obsidian & stone
val NeonBackground = Color(0xFF0F0A08)          // Base canvas
val NeonSurface = Color(0xFF1A110D)             // Elevated surface (cards, dialogs, sheets)
val NeonSurfaceVariant = Color(0xFF271A14)      // Surface variant (inset rows, input fills)
val DungeonBorder = Color(0xFF3E2A20)           // 1dp hairline border & structural divider

// Primary - Crimson Torch
val NeonPrimary = Color(0xFFCC2020)             // Primary CTA, menace, level badge
val NeonPrimaryLight = Color(0xFFE05050)        // Highlight accent & AA-legible red ink on dark
val NeonPrimaryDark = Color(0xFF8B0000)         // Deep blood red container

// Secondary - Dungeon Gold
val NeonSecondary = Color(0xFFD4960A)           // Treasure gold, active host, join code
val NeonSecondaryDark = Color(0xFF8C5C00)       // Deep antique gold container

// Tertiary - Torch Ember
val NeonCyan = Color(0xFFE85D00)                // Torch ember accent
val NeonCyanDark = Color(0xFFA03A00)            // Deep burnt orange container

// Accent
val NeonGold = Color(0xFFFFD600)                // Bright gold (warnings / celebrations)

// Neutrals - Warm parchment scale (WCAG AA compliant on dark stone)
val NeonGray100 = Color(0xFFFAF7F0)             // Primary warm parchment ink
val NeonGray200 = Color(0xFFD8CFC0)             // Secondary body copy
val NeonGray300 = Color(0xFFB8A898)             // Muted labels, timestamps, helper copy
val NeonGray400 = Color(0xFF8A7A6A)             // Hint / inactive icon minimum legible gray
val NeonGray500 = Color(0xFF8A7A6A)             // Promoted from #5A4A3A to meet >=4.5:1 on #0F0A08

// ============== Glass Effect System ==============
val GlassBorder = Color(0x3DFFFFFF)             // 24% white - main border
val GlassBorderDim = Color(0x14FFFFFF)          // 8% white - subtle border
val GlassBase = Color(0x1A200A05)               // Warm amber-brown tinted glass (10%)

// ============== Functional Colors ==============
val NeonSuccess = Color(0xFF00E676)             // Dark-surface emerald ink
val DungeonSuccessFill = Color(0xFF1E7E34)      // Filled success banner/button (#FAF7F0 text)
val NeonError = Color(0xFFE5534B)               // Dark-surface danger ink (4.57:1+ on #271A14)
val DungeonDangerFill = Color(0xFFB91C1C)       // Filled danger banner/button (#FAF7F0 text)
val NeonWarning = Color(0xFFFFD600)             // Turn countdown low time warning

// Combat Specific
val HeroGreen = NeonSuccess
val MonsterRed = NeonError

// ============== Gradients (WCAG AA with #FAF7F0 foreground) ==============
val GradientNeonPurple = listOf(NeonPrimary, NeonSecondaryDark)       // Crimson -> Antique Gold
val GradientNeonFire = listOf(Color(0xFFC2410C), NeonPrimary)         // Ember -> Crimson
val GradientViridian = listOf(NeonCyanDark, NeonPrimary)              // Burnt Orange -> Crimson

// ============== Legacy Compatibility (pending screen cleanup) ==============
val NeonWhite = NeonGray100
val LumaAccent = NeonSecondary

// ============== Canonical 8-Avatar Colors (0..7) ==============
private val DungeonAvatarColors = listOf(
    NeonPrimary,              // 0 Warrior
    NeonSecondary,            // 1 Wizard
    NeonCyan,                 // 2 Thief
    NeonSuccess,              // 3 Cleric
    Color(0xFF8B6914),        // 4 Human
    NeonWarning,              // 5 Elf
    NeonError,                // 6 Dwarf
    Color(0xFF8D4025)         // 7 Halfling
)

fun getAvatarColor(avatarId: Int): Color {
    return DungeonAvatarColors[abs(avatarId) % DungeonAvatarColors.size]
}
