package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============== Luma-Inspired Modern Palette ==============

// Primary - Vibrant Purple/Indigo gradient colors
val LumaPrimary = Color(0xFF7C3AED)       // Vibrant purple
val LumaPrimaryLight = Color(0xFF8B5CF6)  // Lighter purple
val LumaPrimaryDark = Color(0xFF6D28D9)   // Darker purple

// Secondary - Warm accent
val LumaAccent = Color(0xFFF97316)        // Orange accent
val LumaAccentLight = Color(0xFFFB923C)
val LumaAccentDark = Color(0xFFEA580C)

// Neutral Grays - Clean, modern look
val LumaGray50 = Color(0xFFFAFAFA)
val LumaGray100 = Color(0xFFF4F4F5)
val LumaGray200 = Color(0xFFE4E4E7)
val LumaGray300 = Color(0xFFD4D4D8)
val LumaGray400 = Color(0xFFA1A1AA)
val LumaGray500 = Color(0xFF71717A)
val LumaGray600 = Color(0xFF52525B)
val LumaGray700 = Color(0xFF3F3F46)
val LumaGray800 = Color(0xFF27272A)
val LumaGray900 = Color(0xFF18181B)
val LumaGray950 = Color(0xFF09090B)

// Glass effect colors
val GlassWhite = Color(0x1AFFFFFF)       // 10% white
val GlassBorder = Color(0x33FFFFFF)      // 20% white border
val GlassDark = Color(0x1A000000)        // 10% black

// Success/Error/Warning
val LumaSuccess = Color(0xFF22C55E)
val LumaSuccessLight = Color(0xFF4ADE80)
val LumaError = Color(0xFFEF4444)
val LumaErrorLight = Color(0xFFF87171)
val LumaWarning = Color(0xFFF59E0B)

// Combat specific
val HeroGreen = Color(0xFF10B981)        // Emerald
val HeroGreenDark = Color(0xFF059669)
val MonsterRed = Color(0xFFEF4444)       // Red
val MonsterRedDark = Color(0xFFDC2626)

// ============== Gradient Colors ==============
val GradientPurpleStart = Color(0xFF7C3AED)
val GradientPurpleEnd = Color(0xFFDB2777)
val GradientBlueStart = Color(0xFF3B82F6)
val GradientBlueEnd = Color(0xFF8B5CF6)
val GradientOrangeStart = Color(0xFFF97316)
val GradientOrangeEnd = Color(0xFFFBBF24)

// ============== Light Theme Colors ==============
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = LumaGray100
val LightOnBackground = LumaGray900
val LightOnSurface = LumaGray900
val LightOnSurfaceVariant = LumaGray600
val LightOutline = LumaGray300

// ============== Dark Theme Colors (Default for Luma-style) ==============
val DarkBackground = LumaGray950
val DarkSurface = LumaGray900
val DarkSurfaceVariant = LumaGray800
val DarkOnBackground = LumaGray50
val DarkOnSurface = LumaGray100
val DarkOnSurfaceVariant = LumaGray400
val DarkOutline = LumaGray700

// ============== Card surface with glass effect ==============
val DarkCardSurface = Color(0xFF1E1E22)
val DarkCardBorder = Color(0xFF2A2A2E)

// ============== Player Avatar Colors (Modern/Vibrant) ==============
val AvatarColors = listOf(
    Color(0xFFF43F5E), // Rose
    Color(0xFF8B5CF6), // Violet
    Color(0xFF3B82F6), // Blue
    Color(0xFF14B8A6), // Teal
    Color(0xFF22C55E), // Green
    Color(0xFFF97316), // Orange
    Color(0xFFEC4899), // Pink
    Color(0xFF6366F1), // Indigo
    Color(0xFF06B6D4), // Cyan
    Color(0xFFA855F7), // Purple
    Color(0xFF10B981), // Emerald
    Color(0xFFEAB308), // Yellow
)

// Legacy compatibility
val Gold80 = LumaAccentLight
val Gold60 = LumaAccent
val Gold40 = LumaAccentDark
val GoldDark = Color(0xFFEA580C)
val Purple80 = LumaPrimaryLight
val Purple60 = LumaPrimary
val Purple40 = LumaPrimaryDark
val PurpleDark = Color(0xFF5B21B6)

val Success = LumaSuccess
val SuccessDark = Color(0xFF16A34A)
val Error = LumaError
val ErrorDark = Color(0xFFDC2626)
val Warning = LumaWarning
val WarningDark = Color(0xFFD97706)

/**
 * Get avatar color by index.
 */
fun getAvatarColor(avatarId: Int): Color {
    return AvatarColors[avatarId % AvatarColors.size]
}
