package com.munchkin.app.ui.theme

/**
 * Avatar resource helper — maps avatarId + gender to the correct drawable.
 *
 * File numbering does not match slot order (the first 4 characters were
 * generated in a different order). The correct indirection is:
 *   slot 0 (Guerrero)  → file 0
 *   slot 1 (Mago)      → file 2
 *   slot 2 (Ladrón)    → file 3
 *   slot 3 (Clérigo)   → file 1
 *   slots 4-11         → file == slot (direct)
 */
object AvatarResources {

    /**
     * Get avatar display name for the given slot.
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

    /**
     * Get the drawable resource for the given avatar slot and gender.
     */
    fun getAvatarDrawable(avatarId: Int, isFemale: Boolean = false): Int {
        return if (isFemale) getFemaleDrawable(avatarId) else getMaleDrawable(avatarId)
    }

    private fun getMaleDrawable(avatarId: Int): Int {
        return when (avatarId % 12) {
            0  -> com.munchkin.app.R.drawable.avatar_m_0   // Guerrero
            1  -> com.munchkin.app.R.drawable.avatar_m_2   // Mago
            2  -> com.munchkin.app.R.drawable.avatar_m_3   // Ladrón
            3  -> com.munchkin.app.R.drawable.avatar_m_1   // Clérigo
            4  -> com.munchkin.app.R.drawable.avatar_m_4   // Elfo
            5  -> com.munchkin.app.R.drawable.avatar_m_5   // Enano
            6  -> com.munchkin.app.R.drawable.avatar_m_6   // Bárbaro
            7  -> com.munchkin.app.R.drawable.avatar_m_7   // Bardo
            8  -> com.munchkin.app.R.drawable.avatar_m_8   // Druida
            9  -> com.munchkin.app.R.drawable.avatar_m_9   // Monje
            10 -> com.munchkin.app.R.drawable.avatar_m_10  // Paladín
            11 -> com.munchkin.app.R.drawable.avatar_m_11  // Explorador
            else -> com.munchkin.app.R.drawable.avatar_m_0
        }
    }

    private fun getFemaleDrawable(avatarId: Int): Int {
        return when (avatarId % 12) {
            0  -> com.munchkin.app.R.drawable.avatar_f_0   // Guerrero
            1  -> com.munchkin.app.R.drawable.avatar_f_2   // Mago
            2  -> com.munchkin.app.R.drawable.avatar_f_3   // Ladrón
            3  -> com.munchkin.app.R.drawable.avatar_f_1   // Clérigo
            4  -> com.munchkin.app.R.drawable.avatar_f_4   // Elfo
            5  -> com.munchkin.app.R.drawable.avatar_f_5   // Enano
            6  -> com.munchkin.app.R.drawable.avatar_f_6   // Bárbaro
            7  -> com.munchkin.app.R.drawable.avatar_f_7   // Bardo
            8  -> com.munchkin.app.R.drawable.avatar_f_8   // Druida
            9  -> com.munchkin.app.R.drawable.avatar_f_9   // Monje
            10 -> com.munchkin.app.R.drawable.avatar_f_10  // Paladín
            11 -> com.munchkin.app.R.drawable.avatar_f_11  // Explorador
            else -> com.munchkin.app.R.drawable.avatar_f_0
        }
    }

    /**
     * Total number of available avatars.
     */
    const val AVATAR_COUNT = 12
}
