package com.munchkin.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R
import com.munchkin.app.core.CharacterClass
import com.munchkin.app.core.CharacterRace

/**
 * Display names for the class and race enums.
 *
 * The pickers rendered `it.name`, so a player choosing their character saw the
 * raw wire constants — WARRIOR, NONE, HALFLING — on the one screen where the
 * translated names matter most. The `class_*` / `race_*` strings these map to
 * already existed and were only being used by the ability reminders.
 */
@StringRes
fun CharacterClass.labelRes(): Int = when (this) {
    CharacterClass.NONE -> R.string.class_none
    CharacterClass.WARRIOR -> R.string.class_warrior
    CharacterClass.WIZARD -> R.string.class_wizard
    CharacterClass.THIEF -> R.string.class_thief
    CharacterClass.CLERIC -> R.string.class_cleric
}

@StringRes
fun CharacterRace.labelRes(): Int = when (this) {
    CharacterRace.HUMAN -> R.string.race_human
    CharacterRace.ELF -> R.string.race_elf
    CharacterRace.DWARF -> R.string.race_dwarf
    CharacterRace.HALFLING -> R.string.race_halfling
}

@Composable
fun CharacterClass.label(): String = stringResource(labelRes())

@Composable
fun CharacterRace.label(): String = stringResource(labelRes())
