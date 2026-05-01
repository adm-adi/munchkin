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
            if (mainPlayer.characterClass === 'CLERIC') heroesPower += 3;
            if (helperPlayer && helperPlayer.characterClass === 'CLERIC') heroesPower += 3;
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

        const isWarrior = mainPlayer.characterClass === 'WARRIOR';
        const outcome = (heroesPower > monstersPower || (heroesPower === monstersPower && isWarrior)) ? 'WIN' : 'LOSE';
        const helperLevelsGained = (outcome === 'WIN' && helperPlayer && helperPlayer.characterRace === 'ELF') ? 1 : 0;

        return { outcome, heroesPower, monstersPower, totalLevels, totalTreasures, helperLevelsGained };
    }

    function handleCombatDiceRoll(ws, message) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return sendError(ws, "GENERAL_ERROR", "No estÃ¡s en ninguna partida");
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

        logger.info(`ðŸŽ² ${player.name} rolled ${result} for ${purpose} - ${success ? 'SUCCESS' : 'FAIL'}`);

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
    createCombatManager
};
