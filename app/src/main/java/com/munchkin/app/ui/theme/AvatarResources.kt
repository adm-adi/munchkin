package com.munchkin.app.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R

/**
 * Avatar resource helper for the canonical 8-slot class and race portraits (0..7).
 *
 * Current resource files keep the historical image numbering so existing
 * base portraits stay mapped consistently.
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
     * String resource for the given avatar slot.
     */
    @StringRes
    fun getAvatarNameRes(avatarId: Int): Int {
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> R.string.class_warrior
            WIZARD -> R.string.class_wizard
            THIEF -> R.string.class_thief
            CLERIC -> R.string.class_cleric
            HUMAN -> R.string.race_human
            ELF -> R.string.race_elf
            DWARF -> R.string.race_dwarf
            HALFLING -> R.string.race_halfling
            else -> R.string.class_warrior
        }
    }

    /**
     * Localized avatar display name for Compose UI.
     */
    @Composable
    fun getAvatarName(avatarId: Int): String = stringResource(getAvatarNameRes(avatarId))

    /**
     * Localized avatar display name for non-Compose callers.
     */
    fun getAvatarName(context: Context, avatarId: Int): String =
        context.getString(getAvatarNameRes(avatarId))

    /**
     * Get the drawable resource for the given avatar slot and gender.
     */
    fun getAvatarDrawable(avatarId: Int, isFemale: Boolean = false): Int {
        return if (isFemale) getFemaleDrawable(avatarId) else getMaleDrawable(avatarId)
    }

    private fun getMaleDrawable(avatarId: Int): Int {
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> R.drawable.avatar_m_0
            WIZARD -> R.drawable.avatar_m_2
            THIEF -> R.drawable.avatar_m_3
            CLERIC -> R.drawable.avatar_m_1
            HUMAN -> R.drawable.avatar_m_6
            ELF -> R.drawable.avatar_m_4
            DWARF -> R.drawable.avatar_m_5
            HALFLING -> R.drawable.avatar_m_7
            else -> R.drawable.avatar_m_0
        }
    }

    private fun getFemaleDrawable(avatarId: Int): Int {
        return when (normalizeAvatarId(avatarId)) {
            WARRIOR -> R.drawable.avatar_f_0
            WIZARD -> R.drawable.avatar_f_2
            THIEF -> R.drawable.avatar_f_3
            CLERIC -> R.drawable.avatar_f_1
            HUMAN -> R.drawable.avatar_f_6
            ELF -> R.drawable.avatar_f_4
            DWARF -> R.drawable.avatar_f_5
            HALFLING -> R.drawable.avatar_f_7
            else -> R.drawable.avatar_f_0
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
