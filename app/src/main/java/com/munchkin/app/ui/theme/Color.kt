package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============== Neon Fantasy Palette ==============

// Backgrounds - Deep Slate/Blue
val NeonBackground = Color(0xFF0F172A) // Slate 900
val NeonSurface = Color(0xFF1E293B)    // Slate 800
val NeonSurfaceVariant = Color(0xFF334155) // Slate 700

// Primary - Electric Violet
val NeonPrimary = Color(0xFF8B5CF6)    // Violet 500
val NeonPrimaryLight = Color(0xFFA78BFA) // Violet 400
val NeonPrimaryDark = Color(0xFF7C3AED)   // Violet 600

// Secondary - Hot Pink / Fuchsia
val NeonSecondary = Color(0xFFD946EF)  // Fuchsia 500
val NeonSecondaryLight = Color(0xFFE879F9)
val NeonSecondaryDark = Color(0xFFC026D3)

// Tertiary - Cyan (Tech accents)
val NeonCyan = Color(0xFF06B6D4)       // Cyan 500
val NeonCyanLight = Color(0xFF22D3EE)
val NeonCyanDark = Color(0xFF0891B2)

// Neutrals
val NeonWhite = Color(0xFFF8FAFC)      // Slate 50
val NeonGray100 = Color(0xFFF1F5F9)
val NeonGray200 = Color(0xFFE2E8F0)
val NeonGray300 = Color(0xFFCBD5E1)
val NeonGray400 = Color(0xFF94A3B8)
val NeonGray500 = Color(0xFF64748B)

// Glass Effects
val GlassWhite = Color(0x1FFFFFFF)       // ~12% white
val GlassBorder = Color(0x33FFFFFF)      // 20% white
val GlassDark = Color(0x4D000000)        // 30% black (darker for contrast)

// Functional Colors
val NeonSuccess = Color(0xFF10B981)      // Emerald 500
val NeonError = Color(0xFFEF4444)        // Red 500
val NeonWarning = Color(0xFFF59E0B)      // Amber 500

// Combat Specific
val HeroGreen = Color(0xFF10B981)
val MonsterRed = Color(0xFFEF4444)

// Gradients
val GradientNeonPurple = listOf(NeonPrimary, NeonSecondary)
val GradientNeonBlue = listOf(NeonCyanDark, NeonPrimary)
val GradientNeonFire = listOf(NeonSecondary, Color(0xFFF43F5E)) // Fuchsia to Rose

// Legacy/Compat mappings (for smoother migration if any direct refs exist)
val LumaGray100 = NeonGray100
val LumaGray500 = NeonGray500
val LumaGray900 = NeonBackground
val LumaAccent = NeonSecondary
val GradientOrangeEnd = NeonSecondaryLight

// Avatar Colors
val NeonAvatarColors = listOf(
    NeonPrimary, NeonSecondary, NeonCyan, NeonSuccess, NeonWarning, NeonError,
    Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF6366F1), Color(0xFF3B82F6),
    Color(0xFF14B8A6), Color(0xFFF59E0B)
)

fun getAvatarColor(avatarId: Int): Color {
    return NeonAvatarColors[Math.abs(avatarId) % NeonAvatarColors.size]
}
