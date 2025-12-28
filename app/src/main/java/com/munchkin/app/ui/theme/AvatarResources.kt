package com.munchkin.app.ui.theme

/**
 * Avatar resource helper.
 * Uses color-based avatars instead of image drawables.
 */
object AvatarResources {
    
    /**
     * Get avatar display name.
     */
    fun getAvatarName(avatarId: Int): String {
        return when (avatarId % 12) {
            0 -> "Guerrero"
            1 -> "Mago"
            2 -> "Ladrón"
            3 -> "Clérigo"
            4 -> "Elfo"
            5 -> "Enano"
            6 -> "Bárbaro"
            7 -> "Bardo"
            8 -> "Druida"
            9 -> "Monje"
            10 -> "Paladín"
            11 -> "Ranger"
            else -> "Aventurero"
        }
    }
    
    /**
     * Total number of available avatars (matches AvatarColors list).
     */
    const val AVATAR_COUNT = 12
}
