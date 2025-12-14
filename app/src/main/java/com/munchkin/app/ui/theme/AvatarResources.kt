package com.munchkin.app.ui.theme

import com.munchkin.app.R

/**
 * Avatar resource helper.
 * Maps avatar IDs to drawable resources.
 */
object AvatarResources {
    
    /**
     * Get the drawable resource ID for an avatar.
     */
    fun getAvatarDrawable(avatarId: Int): Int {
        return when (avatarId % 6) {
            0 -> R.drawable.avatar_warrior
            1 -> R.drawable.avatar_wizard
            2 -> R.drawable.avatar_thief
            3 -> R.drawable.avatar_cleric
            4 -> R.drawable.avatar_elf
            5 -> R.drawable.avatar_dwarf
            else -> R.drawable.avatar_warrior
        }
    }
    
    /**
     * Get avatar display name.
     */
    fun getAvatarName(avatarId: Int): String {
        return when (avatarId % 6) {
            0 -> "Guerrero"
            1 -> "Mago"
            2 -> "Ladrón"
            3 -> "Clérigo"
            4 -> "Elfo"
            5 -> "Enano"
            else -> "Guerrero"
        }
    }
    
    /**
     * Total number of available avatars.
     */
    const val AVATAR_COUNT = 6
}
