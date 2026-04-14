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
            11 -> "Explorador"
            else -> "Aventurero"
        }
    }
    
    fun getAvatarDrawable(avatarId: Int): Int {
        return when (avatarId % 12) {
            0 -> com.munchkin.app.R.drawable.avatar_m_0
            1 -> com.munchkin.app.R.drawable.avatar_m_2
            2 -> com.munchkin.app.R.drawable.avatar_m_3
            3 -> com.munchkin.app.R.drawable.avatar_m_1
            4 -> com.munchkin.app.R.drawable.avatar_m_4
            5 -> com.munchkin.app.R.drawable.avatar_m_5
            6 -> com.munchkin.app.R.drawable.avatar_m_6
            7 -> com.munchkin.app.R.drawable.avatar_m_7
            8 -> com.munchkin.app.R.drawable.avatar_m_8
            9 -> com.munchkin.app.R.drawable.avatar_m_9
            10 -> com.munchkin.app.R.drawable.avatar_m_10
            11 -> com.munchkin.app.R.drawable.avatar_11  // no avatar_m_11 yet
            else -> com.munchkin.app.R.drawable.avatar_m_0
        }
    }

    /**
     * Total number of available avatars (matches AvatarColors list).
     */
    const val AVATAR_COUNT = 12
}
