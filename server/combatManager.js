const { VALID_CLASSES, VALID_RACES, VALID_GENDERS } = require('./validation');

// Must mirror the Kotlin DiceRollPurpose enum in core/Events.kt.
const VALID_DICE_PURPOSES = new Set(['COMBAT', 'RUN_AWAY', 'CURSE', 'RANDOM', 'TIE_BREAKER']);

/**
 * Active class slots for ability checks (Super Munchkin grants a second class,
 * excluding 'NONE' and duplicate primary/secondary entries).
 */
function activeClasses(player) {
    if (!player) return [];
    const classes = [];
    if (VALID_CLASSES.has(player.characterClass) && player.characterClass !== 'NONE') {
        classes.push(player.characterClass);
    }
    if (
        player.hasSuperMunchkin === true &&
        VALID_CLASSES.has(player.secondaryClass) &&
        player.secondaryClass !== 'NONE' &&
        player.secondaryClass !== player.characterClass
    ) {
        classes.push(player.secondaryClass);
    }
    return classes;
}

/**
 * Active race slots for ability checks (Half-Breed grants a second race,
 * excluding 'HUMAN' and duplicate primary/secondary entries).
 */
function activeRaces(player) {
    if (!player) return [];
    const races = [];
    if (VALID_RACES.has(player.characterRace) && player.characterRace !== 'HUMAN') {
        races.push(player.characterRace);
    }
    if (
        player.hasHalfBreed === true &&
        VALID_RACES.has(player.secondaryRace) &&
        player.secondaryRace !== 'HUMAN' &&
        player.secondaryRace !== player.characterRace
    ) {
        races.push(player.secondaryRace);
    }
    return races;
}

function playerHasClass(player, target) {
    return Boolean(player && activeClasses(player).includes(target));
}

function playerHasRace(player, target) {
    return Boolean(player && activeRaces(player).includes(target));
}

/**
 * Effective combat strength of a player: level + gear + temporary combat bonus.
 */
function combatPower(player) {
    if (!player) return 0;
    return (player.level || 1) + (player.gear || 0) + (player.tempCombatBonus || 0);
}

/**
 * Race & active curse modifiers affecting Run Away rolls (Elf +1, Halfling -1;
 * Half-Breed holding both nets 0; plus any active curse runAwayPenalty).
 * Matches CombatCalculator / combat.mjs.
 */
function getRunAwayBonus(player) {
    if (!player) return 0;
    let bonus = 0;
    if (playerHasRace(player, 'ELF')) bonus += 1;
    if (playerHasRace(player, 'HALFLING')) bonus -= 1;
    for (const curse of (player.activeCurses || [])) {
        bonus += Number.isFinite(curse?.runAwayPenalty) ? curse.runAwayPenalty : 0;
    }
    return bonus;
}

/**
 * Sum of combat penalties/bonuses from a player's active curses.
 */
function getCurseCombatPenalty(player) {
    if (!player || !Array.isArray(player.activeCurses)) return 0;
    let total = 0;
    for (const curse of player.activeCurses) {
        total += Number.isFinite(curse?.combatPenalty) ? curse.combatPenalty : 0;
    }
    return total;
}

/**
 * Decrements combatsRemaining on temporary curses (> 0) and removes expired ones.
 * Persistent curses (combatsRemaining === 0) remain until removed or death.
 */
function consumeCombatCurses(player) {
    if (!player || !Array.isArray(player.activeCurses) || player.activeCurses.length === 0) {
        return;
    }
    const next = [];
    for (const c of player.activeCurses) {
        if (!c) continue;
        const remaining = Number.isFinite(c.combatsRemaining) ? c.combatsRemaining : 0;
        if (remaining > 0) {
            if (remaining - 1 > 0) {
                next.push({ ...c, combatsRemaining: remaining - 1 });
            }
        } else {
            next.push(c);
        }
    }
    player.activeCurses = next;
}

/**
 * Whether level-10 (maxLevel) victory requires winning combat in this room.
 */
function requiresCombatToWin(game) {
    return Boolean(game?.levelTenOnlyCombat) && !game?.allowLevelTenOverride;
}

/**
 * Per-monster conditional modifiers ("+4 vs Elfos"), mirrored from the client's
 * CombatCalculator.matchesCondition/countMatches.
 */
function matchesCondition(modifier, player) {
    if (!player) return false;
    switch (modifier.conditionType) {
        case 'RACE_ID': {
            const name = String(modifier.conditionValue || '').toUpperCase();
            return VALID_RACES.has(name) && (playerHasRace(player, name) || player.characterRace === name);
        }
        case 'CLASS_ID': {
            const name = String(modifier.conditionValue || '').toUpperCase();
            return VALID_CLASSES.has(name) && (playerHasClass(player, name) || player.characterClass === name);
        }
        case 'GENDER':
            return VALID_GENDERS.has(modifier.conditionValue) &&
                player.gender === modifier.conditionValue;
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
    for (const monster of (monsters || [])) {
        for (const modifier of (monster.conditionalModifiers || [])) {
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

function calculateCombatResult(game) {
    const combat = game?.combat;
    if (!combat) return null;

    const mainPlayer = game.players.get(combat.mainPlayerId);
    if (!mainPlayer) return null;
    const helperPlayer = combat.helperPlayerId ? game.players.get(combat.helperPlayerId) : null;

    let heroesPower = combatPower(mainPlayer) + (helperPlayer ? combatPower(helperPlayer) : 0);
    heroesPower += getCurseCombatPenalty(mainPlayer) + (helperPlayer ? getCurseCombatPenalty(helperPlayer) : 0);
    heroesPower += (combat.heroModifier || 0);

    for (const bonus of (combat.tempBonuses || [])) {
        if (bonus.appliesTo === 'HEROES') heroesPower += (bonus.amount || 0);
    }

    const monsters = combat.monsters || [];
    const hasUndead = monsters.some(m => m.isUndead);
    if (hasUndead) {
        if (playerHasClass(mainPlayer, 'CLERIC')) heroesPower += 3;
        if (helperPlayer && playerHasClass(helperPlayer, 'CLERIC')) heroesPower += 3;
    }

    heroesPower += conditionalBonus(monsters, mainPlayer, helperPlayer, 'HEROES');

    let monstersPower = 0;
    for (const monster of monsters) {
        monstersPower += (monster.baseLevel || 0) + (monster.flatModifier || 0);
    }
    monstersPower += (combat.monsterModifier || 0);

    for (const bonus of (combat.tempBonuses || [])) {
        if (bonus.appliesTo === 'MONSTER') monstersPower += (bonus.amount || 0);
    }

    monstersPower += conditionalBonus(monsters, mainPlayer, helperPlayer, 'MONSTER');

    const isWarrior = playerHasClass(mainPlayer, 'WARRIOR')
        || playerHasClass(helperPlayer, 'WARRIOR');
    const heroesWinTies = isWarrior || game.tiesGoToMonsters === false;
    const playerWins = heroesPower > monstersPower || (heroesPower === monstersPower && heroesWinTies);
    const outcome = playerWins ? 'WIN' : 'LOSE';

    let totalLevels = 0;
    let totalTreasures = 0;
    let helperLevelsGained = 0;
    let orcOverkillBonus = false;
    if (playerWins) {
        for (const monster of monsters) {
            totalLevels += Number.isFinite(monster.levels) ? monster.levels : 1;
            totalTreasures += Number.isFinite(monster.treasures) ? monster.treasures : 1;
        }
        if (helperPlayer && monsters.length > 0 && playerHasRace(helperPlayer, 'ELF')) {
            helperLevelsGained = monsters.length;
        }
        // Orc Overkill: defeating monster(s) alone by more than 10 grants +1 extra level.
        if (!helperPlayer && monsters.length > 0 && playerHasRace(mainPlayer, 'ORC') && (heroesPower - monstersPower) > 10) {
            totalLevels += 1;
            orcOverkillBonus = true;
        }
    }

    const gap = monstersPower - heroesPower;
    const tiesGoToMonsters = !heroesWinTies;
    const marginToWin = playerWins ? 0 : (tiesGoToMonsters ? gap + 1 : Math.max(gap, 0));

    return {
        outcome,
        heroesPower,
        monstersPower,
        totalLevels,
        totalTreasures,
        helperLevelsGained,
        marginToWin,
        warriorTieBreak: heroesPower === monstersPower && isWarrior,
        isWarriorInvolved: isWarrior,
        orcOverkillBonus
    };
}

function createCombatManager() {
    return {
        calculateCombatResult
    };
}

module.exports = {
    createCombatManager,
    calculateCombatResult,
    VALID_DICE_PURPOSES,
    activeClasses,
    activeRaces,
    playerHasClass,
    playerHasRace,
    combatPower,
    getRunAwayBonus,
    getCurseCombatPenalty,
    consumeCombatCurses,
    requiresCombatToWin,
    matchesCondition,
    countMatches,
    conditionalBonus
};
