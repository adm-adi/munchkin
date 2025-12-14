package com.munchkin.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============== Primary Colors ==============
// Gold - Main brand color inspired by Munchkin treasure
val Gold80 = Color(0xFFFFD54F)
val Gold60 = Color(0xFFFFCA28)
val Gold40 = Color(0xFFFFC107)
val GoldDark = Color(0xFFFF8F00)

// Purple - Secondary color for accents
val Purple80 = Color(0xFFD0BCFF)
val Purple60 = Color(0xFFB794F6)
val Purple40 = Color(0xFF9C27B0)
val PurpleDark = Color(0xFF7B1FA2)

// ============== Status Colors ==============
val Success = Color(0xFF4CAF50)
val SuccessDark = Color(0xFF388E3C)
val Error = Color(0xFFF44336)
val ErrorDark = Color(0xFFD32F2F)
val Warning = Color(0xFFFF9800)
val WarningDark = Color(0xFFF57C00)

// ============== Combat Colors ==============
val HeroGreen = Color(0xFF66BB6A)
val HeroGreenDark = Color(0xFF43A047)
val MonsterRed = Color(0xFFEF5350)
val MonsterRedDark = Color(0xFFE53935)

// ============== Light Theme Colors ==============
val LightBackground = Color(0xFFFFFBFE)
val LightSurface = Color(0xFFFFFBFE)
val LightSurfaceVariant = Color(0xFFF3EDF7)
val LightOnBackground = Color(0xFF1C1B1F)
val LightOnSurface = Color(0xFF1C1B1F)
val LightOnSurfaceVariant = Color(0xFF49454F)
val LightOutline = Color(0xFF79747E)

// ============== Dark Theme Colors ==============
val DarkBackground = Color(0xFF1C1B1F)
val DarkSurface = Color(0xFF1C1B1F)
val DarkSurfaceVariant = Color(0xFF49454F)
val DarkOnBackground = Color(0xFFE6E1E5)
val DarkOnSurface = Color(0xFFE6E1E5)
val DarkOnSurfaceVariant = Color(0xFFCAC4D0)
val DarkOutline = Color(0xFF938F99)

// ============== Player Avatar Colors ==============
val AvatarColors = listOf(
    Color(0xFFE57373), // Red
    Color(0xFF81C784), // Green
    Color(0xFF64B5F6), // Blue
    Color(0xFFFFD54F), // Yellow
    Color(0xFFBA68C8), // Purple
    Color(0xFF4DB6AC), // Teal
    Color(0xFFFF8A65), // Orange
    Color(0xFFA1887F), // Brown
    Color(0xFF90A4AE), // Blue Grey
    Color(0xFFF06292), // Pink
    Color(0xFF9575CD), // Deep Purple
    Color(0xFF4FC3F7)  // Light Blue
)

/**
 * Get avatar color by index.
 */
fun getAvatarColor(avatarId: Int): Color {
    return AvatarColors[avatarId % AvatarColors.size]
}
