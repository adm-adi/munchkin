package com.munchkin.app.ui.theme

/**
 * Avatar resource helper for the base-game class and race portraits.
 *
 * Current resource files keep the historical image numbering so existing
 * base portraits do not get shuffled before new art is generated.
 */
object AvatarResources {

    private const val WARRIOR = 0
    private const val WIZARD = 1
    private const val THIEF = 2
    private const val CLERIC = 3
    private const val HUMAN = 4
    private const val ELF = 5
    private const val DWARF = 6
    private const val HALFLING = 7

    /**
     * Get avatar display name for the given slot.
     */
    fun getAvatarName(avatarId: Int): String {
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> "Guerrero"
            WIZARD -> "Mago"
            THIEF -> "Ladrón"
            CLERIC -> "Clérigo"
            HUMAN -> "Humano"
            ELF -> "Elfo"
            DWARF -> "Enano"
            HALFLING -> "Mediano"
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
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> com.munchkin.app.R.drawable.avatar_m_0
            WIZARD -> com.munchkin.app.R.drawable.avatar_m_2
            THIEF -> com.munchkin.app.R.drawable.avatar_m_3
            CLERIC -> com.munchkin.app.R.drawable.avatar_m_1
            HUMAN -> com.munchkin.app.R.drawable.avatar_m_6
            ELF -> com.munchkin.app.R.drawable.avatar_m_4
            DWARF -> com.munchkin.app.R.drawable.avatar_m_5
            HALFLING -> com.munchkin.app.R.drawable.avatar_m_7
            else -> com.munchkin.app.R.drawable.avatar_m_0
        }
    }

    private fun getFemaleDrawable(avatarId: Int): Int {
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> com.munchkin.app.R.drawable.avatar_f_0
            WIZARD -> com.munchkin.app.R.drawable.avatar_f_2
            THIEF -> com.munchkin.app.R.drawable.avatar_f_3
            CLERIC -> com.munchkin.app.R.drawable.avatar_f_1
            HUMAN -> com.munchkin.app.R.drawable.avatar_f_6
            ELF -> com.munchkin.app.R.drawable.avatar_f_4
            DWARF -> com.munchkin.app.R.drawable.avatar_f_5
            HALFLING -> com.munchkin.app.R.drawable.avatar_f_7
            else -> com.munchkin.app.R.drawable.avatar_f_0
        }
    }

    fun normalizeAvatarId(avatarId: Int): Int {
        return Math.floorMod(avatarId, AVATAR_COUNT)
    }

    /**
     * Total number of available avatars.
     */
    const val AVATAR_COUNT = 8
}
