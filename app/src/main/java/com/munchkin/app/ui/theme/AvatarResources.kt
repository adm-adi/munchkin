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
            0 -> com.munchkin.app.R.drawable.avatar_0
            1 -> com.munchkin.app.R.drawable.avatar_1
            2 -> com.munchkin.app.R.drawable.avatar_2
            3 -> com.munchkin.app.R.drawable.avatar_3
            4 -> com.munchkin.app.R.drawable.avatar_4
            5 -> com.munchkin.app.R.drawable.avatar_5
            6 -> com.munchkin.app.R.drawable.avatar_6
            7 -> com.munchkin.app.R.drawable.avatar_7
            8 -> com.munchkin.app.R.drawable.avatar_8
            9 -> com.munchkin.app.R.drawable.avatar_9
            10 -> com.munchkin.app.R.drawable.avatar_10
            11 -> com.munchkin.app.R.drawable.avatar_11
            else -> com.munchkin.app.R.drawable.avatar_0
        }
    }

    /**
     * Total number of available avatars (matches AvatarColors list).
     */
    const val AVATAR_COUNT = 12
}
