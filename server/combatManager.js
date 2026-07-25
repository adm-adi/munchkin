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

        let monstersPower = 0;
        let totalLevels = 0;
        let totalTreasures = 0;
        for (const monster of (combat.monsters || [])) {
            monstersPower += (monster.baseLevel || 0) + (monster.flatModifier || 0);
            totalLevels += (monster.levels || 1);
            totalTreasures += (monster.treasures || 1);
        }
        monstersPower += (combat.monsterModifier || 0);

        for (const bonus of (combat.tempBonuses || [])) {
            if (bonus.appliesTo === 'MONSTER') monstersPower += (bonus.amount || 0);
        }

        // A Warrior on either side of the party wins ties. The client's
        // CombatCalculator already checks both the main player and the helper, so
        // only checking the main player here made the server overrule a tie the
        // client had shown as a win.
        const isWarrior = playerHasClass(mainPlayer, 'WARRIOR')
            || playerHasClass(helperPlayer, 'WARRIOR');
        const outcome = (heroesPower > monstersPower || (heroesPower === monstersPower && isWarrior)) ? 'WIN' : 'LOSE';
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
            purpose: purpose || "RANDOM",
            success: success || false,
            timestamp: Date.now()
        };

        game.lastCombatDiceRoll = diceRollInfo;

        logger.info(`🎲 ${player.name} rolled ${result} for ${purpose} - ${success ? 'SUCCESS' : 'FAIL'}`);

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
    playerHasClass,
    playerHasRace
};
