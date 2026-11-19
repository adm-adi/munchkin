package com.munchkin.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R
import com.munchkin.app.core.AbilityDescriptionKey
import com.munchkin.app.core.AbilitySourceKey
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
    CharacterClass.BARD -> R.string.class_bard
    CharacterClass.RANGER -> R.string.class_ranger
}

@StringRes
fun CharacterRace.labelRes(): Int = when (this) {
    CharacterRace.HUMAN -> R.string.race_human
    CharacterRace.ELF -> R.string.race_elf
    CharacterRace.DWARF -> R.string.race_dwarf
    CharacterRace.HALFLING -> R.string.race_halfling
    CharacterRace.ORC -> R.string.race_orc
    CharacterRace.GNOME -> R.string.race_gnome
}

@Composable
fun CharacterClass.label(): String = stringResource(labelRes())

@Composable
fun CharacterRace.label(): String = stringResource(labelRes())

@StringRes
fun AbilitySourceKey.labelRes(): Int = when (this) {
    AbilitySourceKey.WARRIOR -> R.string.class_warrior
    AbilitySourceKey.WIZARD -> R.string.class_wizard
    AbilitySourceKey.THIEF -> R.string.class_thief
    AbilitySourceKey.CLERIC -> R.string.class_cleric
    AbilitySourceKey.BARD -> R.string.class_bard
    AbilitySourceKey.RANGER -> R.string.class_ranger
    AbilitySourceKey.ELF -> R.string.race_elf
    AbilitySourceKey.DWARF -> R.string.race_dwarf
    AbilitySourceKey.HALFLING -> R.string.race_halfling
    AbilitySourceKey.ORC -> R.string.race_orc
    AbilitySourceKey.GNOME -> R.string.race_gnome
}

@StringRes
fun AbilityDescriptionKey.labelRes(): Int = when (this) {
    AbilityDescriptionKey.WARRIOR_TIES -> R.string.ability_warrior_ties
    AbilityDescriptionKey.WARRIOR_BERSERK -> R.string.ability_warrior_berserk
    AbilityDescriptionKey.WIZARD_CHARM -> R.string.ability_wizard_charm
    AbilityDescriptionKey.WIZARD_FLIGHT -> R.string.ability_wizard_flight
    AbilityDescriptionKey.THIEF_BACKSTAB -> R.string.ability_thief_backstab
    AbilityDescriptionKey.THIEF_STEAL -> R.string.ability_thief_steal
    AbilityDescriptionKey.CLERIC_UNDEAD -> R.string.ability_cleric_undead
    AbilityDescriptionKey.CLERIC_RESURRECT -> R.string.ability_cleric_resurrect
    AbilityDescriptionKey.BARD_ENTHRALL -> R.string.ability_bard_enthrall
    AbilityDescriptionKey.BARD_LUCK -> R.string.ability_bard_luck
    AbilityDescriptionKey.RANGER_TAME -> R.string.ability_ranger_tame
    AbilityDescriptionKey.RANGER_HELP -> R.string.ability_ranger_help
    AbilityDescriptionKey.ELF_HELP_LEVEL -> R.string.ability_elf_help_level
    AbilityDescriptionKey.ELF_RUN_AWAY -> R.string.ability_elf_run_away
    AbilityDescriptionKey.DWARF_BIG_ITEMS -> R.string.ability_dwarf_big_items
    AbilityDescriptionKey.DWARF_HAND_SIZE -> R.string.ability_dwarf_hand_size
    AbilityDescriptionKey.HALFLING_SELL -> R.string.ability_halfling_sell
    AbilityDescriptionKey.HALFLING_RUN_AWAY -> R.string.ability_halfling_run_away
    AbilityDescriptionKey.ORC_OVERKILL -> R.string.ability_orc_overkill
    AbilityDescriptionKey.ORC_IGNORE_CURSE -> R.string.ability_orc_ignore_curse
    AbilityDescriptionKey.GNOME_ILLUSION -> R.string.ability_gnome_illusion
    AbilityDescriptionKey.GNOME_NOSE -> R.string.ability_gnome_nose
}

@Composable
fun AbilitySourceKey.label(): String = stringResource(labelRes())

@Composable
fun AbilityDescriptionKey.label(): String = stringResource(labelRes())
