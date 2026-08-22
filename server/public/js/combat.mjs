/**
 * Combat calculator — port of core/CombatCalculator.kt.
 *
 * Both this module and server/combatManager.js compute the outcome; the server
 * wins. Divergence shows up as the server's `COMBAT_END mismatch` warning, so
 * any rule change must land in both places (and in the Kotlin client).
 */

import { activeClasses, activeRaces, hasClass, hasRace, combatPower } from './engine.mjs';

const CLASS_NAMES = new Set(['NONE', 'WARRIOR', 'WIZARD', 'THIEF', 'CLERIC']);
const RACE_NAMES = new Set(['HUMAN', 'ELF', 'DWARF', 'HALFLING']);
const GENDER_NAMES = new Set(['M', 'F', 'NA']);

/**
 * Mirrors CombatCalculator.matchesCondition, quirks included:
 *  - RACE_ID / CLASS_ID compare the uppercased value against the enum, and the
 *    empty values (NONE / HUMAN) match through the "characterX == enum" arm, so
 *    a condition can target classless or raceless players.
 *  - A value that is not an enum name is treated as a catalog EntryId.
 *  - GENDER compares case-sensitively.
 */
function matchesCondition(modifier, player) {
    const value = String(modifier.conditionValue ?? '');
    switch (modifier.conditionType) {
        case 'RACE_ID': {
            const name = value.toUpperCase();
            if (RACE_NAMES.has(name)) {
                return hasRace(player, name) || player.characterRace === name;
            }
            return Array.isArray(player.raceIds) && player.raceIds.includes(value);
        }
        case 'CLASS_ID': {
            const name = value.toUpperCase();
            if (CLASS_NAMES.has(name)) {
                return hasClass(player, name) || player.characterClass === name;
            }
            return Array.isArray(player.classIds) && player.classIds.includes(value);
        }
        case 'GENDER':
            return GENDER_NAMES.has(value) && player.gender === value;
        default:
            return false;
    }
}

function countMatches(modifier, mainPlayer, helperPlayer) {
    const players =
        modifier.scope === 'MAIN_ONLY' ? [mainPlayer]
        : modifier.scope === 'HELPER_ONLY' ? (helperPlayer ? [helperPlayer] : [])
        : helperPlayer ? [mainPlayer, helperPlayer] : [mainPlayer];
    return players.filter(p => matchesCondition(modifier, p)).length;
}

function conditionalBonus(monsters, mainPlayer, helperPlayer, side) {
    let total = 0;
    for (const monster of monsters || []) {
        for (const modifier of monster.conditionalModifiers || []) {
            if (modifier.side !== side) continue;
            const matches = countMatches(modifier, mainPlayer, helperPlayer);
            if (matches > 0) {
                total += modifier.applyMode === 'PER_MATCHING_PLAYER'
                    ? (modifier.amount || 0) * matches
                    : (modifier.amount || 0);
            }
        }
    }
    return total;
}

/** Cleric +3 vs Undead, for each participating Cleric. */
function intrinsicHeroBonus(monsters, mainPlayer, helperPlayer) {
    if (!(monsters || []).some(m => m.isUndead)) return 0;
    let bonus = 0;
    if (hasClass(mainPlayer, 'CLERIC')) bonus += 3;
    if (helperPlayer && hasClass(helperPlayer, 'CLERIC')) bonus += 3;
    return bonus;
}

/**
 * CombatCalculator.calculateResult. Returns the CombatResult shape plus
 * marginToWin (power still needed to flip a LOSE into a WIN).
 */
export function calculateResult(combat, state) {
    const empty = {
        heroesPower: 0, monstersPower: 0, outcome: 'LOSE',
        totalTreasures: 0, totalLevels: 0, warriorTieBreak: false,
        helperLevelsGained: 0, isWarriorInvolved: false, marginToWin: 1
    };
    const mainPlayer = state.players[combat.mainPlayerId];
    if (!mainPlayer) return empty;
    const helperPlayer = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;

    let heroesPower = combatPower(mainPlayer) + (helperPlayer ? combatPower(helperPlayer) : 0);
    heroesPower += conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'HEROES');
    heroesPower += intrinsicHeroBonus(combat.monsters, mainPlayer, helperPlayer);
    for (const bonus of combat.tempBonuses || []) {
        if (bonus.appliesTo === 'HEROES') heroesPower += bonus.amount || 0;
    }
    heroesPower += combat.heroModifier || 0;

    let monstersPower = 0;
    for (const monster of combat.monsters || []) {
        monstersPower += (monster.baseLevel || 0) + (monster.flatModifier || 0);
    }
    monstersPower += conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'MONSTER');
    for (const bonus of combat.tempBonuses || []) {
        if (bonus.appliesTo === 'MONSTER') monstersPower += bonus.amount || 0;
    }
    monstersPower += combat.monsterModifier || 0;

    const isWarriorInvolved = hasClass(mainPlayer, 'WARRIOR') ||
        (helperPlayer ? hasClass(helperPlayer, 'WARRIOR') : false);
    const heroesWinTies = isWarriorInvolved || state.settings?.tiesGoToMonsters === false;
    const outcome = (heroesPower > monstersPower || (heroesPower === monstersPower && heroesWinTies))
        ? 'WIN' : 'LOSE';

    let totalLevels = 0;
    let totalTreasures = 0;
    let helperLevelsGained = 0;
    if (outcome === 'WIN') {
        for (const monster of combat.monsters || []) {
            totalLevels += monster.levels ?? 1;
            totalTreasures += monster.treasures ?? 1;
        }
        if (helperPlayer && hasRace(helperPlayer, 'ELF')) helperLevelsGained = 1;
    }

    const gap = monstersPower - heroesPower;
    const marginToWin = outcome === 'WIN' ? 0 : (isWarriorInvolved ? gap : gap + 1);

    return {
        heroesPower,
        monstersPower,
        outcome,
        totalTreasures,
        totalLevels,
        warriorTieBreak: heroesPower === monstersPower && isWarriorInvolved,
        helperLevelsGained,
        isWarriorInvolved,
        marginToWin
    };
}

/**
 * CombatCalculator.getBreakdown — labelled power sources for the combat screen,
 * guaranteed to add up to the totals calculateResult reports.
 */
export function getBreakdown(combat, state, labels) {
    const mainPlayer = state.players[combat.mainPlayerId];
    if (!mainPlayer) {
        return { heroSources: [], monsterSources: [], result: calculateResult(combat, state) };
    }
    const helperPlayer = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;

    const heroSources = [{ label: labels.base(mainPlayer.name), amount: combatPower(mainPlayer) }];
    if (helperPlayer) {
        heroSources.push({ label: labels.helper(helperPlayer.name), amount: combatPower(helperPlayer) });
    }

    const monsterSources = (combat.monsters || []).map(monster => ({
        label: labels.monster(monster.name, monster.baseLevel),
        amount: (monster.baseLevel || 0) + (monster.flatModifier || 0)
    }));

    for (const bonus of combat.tempBonuses || []) {
        const source = { label: bonus.label, amount: bonus.amount };
        if (bonus.appliesTo === 'HEROES') heroSources.push(source);
        else monsterSources.push(source);
    }

    if (combat.heroModifier) {
        heroSources.push({ label: labels.manual, amount: combat.heroModifier });
    }
    if (combat.monsterModifier) {
        monsterSources.push({ label: labels.manual, amount: combat.monsterModifier });
    }

    if ((combat.monsters || []).some(m => m.isUndead)) {
        if (hasClass(mainPlayer, 'CLERIC')) {
            heroSources.push({ label: labels.clericVsUndead, amount: 3 });
        }
        if (helperPlayer && hasClass(helperPlayer, 'CLERIC')) {
            heroSources.push({ label: labels.helperClericVsUndead, amount: 3 });
        }
    }

    const conditionalHero = conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'HEROES');
    if (conditionalHero !== 0) {
        heroSources.push({ label: labels.cardModifiers, amount: conditionalHero });
    }
    const conditionalMonster = conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'MONSTER');
    if (conditionalMonster !== 0) {
        monsterSources.push({ label: labels.cardModifiers, amount: conditionalMonster });
    }

    return { heroSources, monsterSources, result: calculateResult(combat, state) };
}

// ============== Run away (Abilities.kt) ==============

/** A run-away attempt succeeds on 5 or more, after race and card modifiers. */
export const RUN_AWAY_THRESHOLD = 5;

/**
 * Race modifiers that apply to a run-away roll, as [race, amount] pairs so the
 * UI can show the breakdown: an Elf is +1, a Halfling -1, and a Half-Breed
 * holding both nets zero.
 */
export function runAwayModifiers(player) {
    const mods = [];
    if (hasRace(player, 'ELF')) mods.push(['ELF', 1]);
    if (hasRace(player, 'HALFLING')) mods.push(['HALFLING', -1]);
    return mods;
}

export function runAwayBonus(player) {
    return runAwayModifiers(player).reduce((sum, [, amount]) => sum + amount, 0);
}

export { activeClasses, activeRaces, hasClass, hasRace, combatPower };
