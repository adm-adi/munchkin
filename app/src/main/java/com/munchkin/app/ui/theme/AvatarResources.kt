package com.munchkin.app.ui.theme

import com.munchkin.app.R

/**
 * Maps avatar slots and gender to the matching drawable resource.
 */
object AvatarResources {
    private enum class AvatarSlot(
        val label: String,
        val labelRes: Int,
        val maleDrawable: Int,
        val femaleDrawable: Int
    ) {
        WARRIOR(
            "Guerrero",
            R.string.avatar_warrior,
            R.drawable.avatar_warrior_m,
            R.drawable.avatar_warrior_f
        ),
        WIZARD(
            "Mago",
            R.string.avatar_wizard,
            R.drawable.avatar_wizard_m,
            R.drawable.avatar_wizard_f
        ),
        THIEF(
            "Ladron",
            R.string.avatar_thief,
            R.drawable.avatar_thief_m,
            R.drawable.avatar_thief_f
        ),
        CLERIC(
            "Clerigo",
            R.string.avatar_cleric,
            R.drawable.avatar_cleric_m,
            R.drawable.avatar_cleric_f
        ),
        HUMAN(
            "Humano",
            R.string.avatar_human,
            R.drawable.avatar_human_m,
            R.drawable.avatar_human_f
        ),
        ELF(
            "Elfo",
            R.string.avatar_elf,
            R.drawable.avatar_elf_m,
            R.drawable.avatar_elf_f
        ),
        DWARF(
            "Enano",
            R.string.avatar_dwarf,
            R.drawable.avatar_dwarf_m,
            R.drawable.avatar_dwarf_f
        ),
        HALFLING(
            "Mediano",
            R.string.avatar_halfling,
            R.drawable.avatar_halfling_m,
            R.drawable.avatar_halfling_f
        )
    }

    /**
     * Get avatar display name for non-Compose contexts.
     */
    fun getAvatarName(avatarId: Int): String = slot(avatarId).label

    /**
     * Get localized avatar display-name resource for UI text.
     */
    fun getAvatarNameRes(avatarId: Int): Int = slot(avatarId).labelRes

    /**
     * Get the drawable resource for the given avatar slot and gender.
     */
    fun getAvatarDrawable(avatarId: Int, isFemale: Boolean = false): Int {
        val avatarSlot = slot(avatarId)
        return if (isFemale) avatarSlot.femaleDrawable else avatarSlot.maleDrawable
    }

    private fun slot(avatarId: Int): AvatarSlot {
        return AvatarSlot.entries[Math.floorMod(avatarId, AvatarSlot.entries.size)]
    }

    /**
     * Total number of available avatars.
     */
    val AVATAR_COUNT: Int = AvatarSlot.entries.size
}
