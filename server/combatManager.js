const { VALID_CLASSES, VALID_RACES, VALID_GENDERS } = require('./validation');

// Must mirror the Kotlin DiceRollPurpose enum in core/Events.kt.
const VALID_DICE_PURPOSES = new Set(['COMBAT', 'RUN_AWAY', 'CURSE', 'RANDOM', 'TIE_BREAKER']);

/**
 * Super Munchkin grants the abilities of two classes, Half-Breed of two races, so
 * every ability check has to consider both slots. These mirror
 * PlayerState.activeClasses / activeRaces on the client — the two implementations
 * must agree or the server overrules an outcome the client already displayed.
 */
function playerHasClass(player, target) {
    if (!player) return false;
    if (player.characterClass === target) return true;
    return player.hasSuperMunchkin === true && player.secondaryClass === target;
}

function playerHasRace(player, target) {
    if (!player) return false;
    if (player.characterRace === target) return true;
    return player.hasHalfBreed === true && player.secondaryRace === target;
}

/**
 * Per-monster conditional modifiers ("+4 vs Elfos"), mirrored from the client's
 * CombatCalculator.matchesCondition/countMatches. The server previously ignored
 * them entirely, so any combat carrying one was overruled with the wrong total.
 *
 * The matching mirrors the Kotlin source exactly, quirks included:
 *  - RACE_ID/CLASS_ID compare the uppercased value against the enum, and NONE /
 *    HUMAN match via the "characterX == enum" arm, so a condition can target
 *    classless or raceless players.
 *  - The secondary slot only counts with its card, and never when it duplicates
 *    the primary or holds the empty value (NONE / HUMAN) — that is activeClasses
 *    / activeRaces on the client, which is narrower than playerHasClass above.
 *  - GENDER is compared case-sensitively, as Gender.entries.find does.
 */
function matchesCondition(modifier, player) {
    switch (modifier.conditionType) {
        case 'RACE_ID': {
            const name = String(modifier.conditionValue || '').toUpperCase();
            if (!VALID_RACES.has(name)) return false;
            const secondaryCounts =
                player.hasHalfBreed === true &&
                player.secondaryRace === name &&
                name !== 'HUMAN' &&
                player.secondaryRace !== player.characterRace;
            return player.characterRace === name || secondaryCounts;
        }
        case 'CLASS_ID': {
            const name = String(modifier.conditionValue || '').toUpperCase();
            if (!VALID_CLASSES.has(name)) return false;
            const secondaryCounts =
                player.hasSuperMunchkin === true &&
                player.secondaryClass === name &&
                name !== 'NONE' &&
                player.secondaryClass !== player.characterClass;
            return player.characterClass === name || secondaryCounts;
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

function createCombatManager({ games, clientGames, sendError, logger }) {
    function calculateCombatResult(game) {
        const combat = game.combat;
        if (!combat) return null;

        const mainPlayer = game.players.get(combat.mainPlayerId);
        if (!mainPlayer) return null;
        const helperPlayer = combat.helperPlayerId ? game.players.get(combat.helperPlayerId) : null;

        let heroesPower = (mainPlayer.level || 1) + (mainPlayer.gear || 0);
        if (helperPlayer) heroesPower += (helperPlayer.level || 1) + (helperPlayer.gear || 0);
        heroesPower += (combat.heroModifier || 0);

        for (const bonus of (combat.tempBonuses || [])) {
            if (bonus.appliesTo === 'HEROES') heroesPower += (bonus.amount || 0);
        }

        const hasUndead = (combat.monsters || []).some(m => m.isUndead);
        if (hasUndead) {
            if (playerHasClass(mainPlayer, 'CLERIC')) heroesPower += 3;
            if (helperPlayer && playerHasClass(helperPlayer, 'CLERIC')) heroesPower += 3;
        }

        heroesPower += conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'HEROES');

        let monstersPower = 0;
        let totalLevels = 0;
        let totalTreasures = 0;
        for (const monster of (combat.monsters || [])) {
            monstersPower += (monster.baseLevel || 0) + (monster.flatModifier || 0);
            // `|| 1` treated a legal 0 as absent, so a deliberately treasure-less
            // monster still paid out one treasure — while the client's
            // `sumOf { it.treasures }` showed 0. sanitizeMonster already bounds
            // both fields, so only a genuinely missing value needs a default.
            totalLevels += Number.isFinite(monster.levels) ? monster.levels : 1;
            totalTreasures += Number.isFinite(monster.treasures) ? monster.treasures : 1;
        }
        monstersPower += (combat.monsterModifier || 0);

        for (const bonus of (combat.tempBonuses || [])) {
            if (bonus.appliesTo === 'MONSTER') monstersPower += (bonus.amount || 0);
        }

        monstersPower += conditionalBonus(combat.monsters, mainPlayer, helperPlayer, 'MONSTER');

        // A Warrior on either side of the party wins ties. The client's
        // CombatCalculator already checks both the main player and the helper, so
        // only checking the main player here made the server overrule a tie the
        // client had shown as a win.
        const isWarrior = playerHasClass(mainPlayer, 'WARRIOR')
            || playerHasClass(helperPlayer, 'WARRIOR');
        // Mirrors CombatCalculator.kt: the room's tiesGoToMonsters setting can
        // hand ties to the heroes, and a Warrior always does.
        const heroesWinTies = isWarrior || game.tiesGoToMonsters === false;
        const outcome = (heroesPower > monstersPower || (heroesPower === monstersPower && heroesWinTies)) ? 'WIN' : 'LOSE';
        const helperLevelsGained =
            (outcome === 'WIN' && playerHasRace(helperPlayer, 'ELF')) ? 1 : 0;

        return { outcome, heroesPower, monstersPower, totalLevels, totalTreasures, helperLevelsGained };
    }

    function handleCombatDiceRoll(ws, message) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return sendError(ws, "GENERAL_ERROR", "No estás en ninguna partida");
        }

        const game = games.get(clientInfo.gameId);
        if (!game) {
            return sendError(ws, "GENERAL_ERROR", "Partida no encontrada");
        }

        const player = game.players.get(clientInfo.playerId);
        if (!player) {
            return sendError(ws, "GENERAL_ERROR", "Jugador no encontrado");
        }

        const { result, purpose, success } = message;
        const validResult = Math.max(1, Math.min(6, Math.round(Number(result) || 1)));

        const diceRollInfo = {
            playerId: clientInfo.playerId,
            playerName: player.name,
            result: validResult,
            // `purpose` is a Kotlin enum on the client and this value is
            // rebroadcast to the whole room, so an arbitrary string here breaks
            // decoding for everyone. `success` must be a real boolean for the
            // same reason.
            purpose: VALID_DICE_PURPOSES.has(purpose) ? purpose : 'RANDOM',
            success: success === true,
            timestamp: Date.now()
        };

        game.lastCombatDiceRoll = diceRollInfo;

        logger.info(`🎲 ${player.name} rolled ${diceRollInfo.result} for ${diceRollInfo.purpose} - ${diceRollInfo.success ? 'SUCCESS' : 'FAIL'}`);

        game.broadcast({
            type: "COMBAT_DICE_ROLL_RESULT",
            diceRoll: diceRollInfo
        });
    }

    return {
        calculateCombatResult,
        handleCombatDiceRoll
    };
}

module.exports = {
    createCombatManager,
    VALID_DICE_PURPOSES,
    playerHasClass,
    playerHasRace,
    matchesCondition,
    countMatches,
    conditionalBonus
};
