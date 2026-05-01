function createTurnManager({ games, db, logger }) {
    function getNextTurnPlayerId(game) {
        if (!game.turnPlayerId) return game.hostId;

        const playerIds = Array.isArray(game.playerOrder) && game.playerOrder.length > 0
            ? game.playerOrder.filter(id => game.players.has(id))
            : Array.from(game.players.keys()).sort();

        let currentIndex = playerIds.indexOf(game.turnPlayerId);
        if (currentIndex === -1) currentIndex = 0;

        for (let i = 1; i <= playerIds.length; i++) {
            const nextIndex = (currentIndex + i) % playerIds.length;
            const nextId = playerIds[nextIndex];
            const player = game.players.get(nextId);

            if (player && (player.isConnected !== false)) {
                return nextId;
            }
        }

        return game.turnPlayerId;
    }

    function clearTurnTimer(game, reason = null) {
        if (!game) return;

        game.turnEndsAt = null;

        if (game.turnTimerHandle) {
            clearTimeout(game.turnTimerHandle);
            game.turnTimerHandle = null;
        }

        game.turnTimerKey = null;
        game.turnTimerNonce = (game.turnTimerNonce || 0) + 1;

        if (reason) {
            logger.info(`⏱️ Cleared turn timer for ${game.joinCode}: ${reason}`);
        }
    }

    function clearRoomLifecycleTimers(game, reason = null) {
        if (!game) return;

        clearTurnTimer(game, reason ? `${reason} (turn timer)` : null);

        if (game.cleanupTimer) {
            clearTimeout(game.cleanupTimer);
            game.cleanupTimer = null;
            if (reason) {
                logger.info(`🧹 Cleared cleanup timer for ${game.joinCode}: ${reason}`);
            }
        }

        if (game.pendingHostMigration) {
            clearTimeout(game.pendingHostMigration);
            game.pendingHostMigration = null;
            if (reason) {
                logger.info(`👑 Cleared host migration timer for ${game.joinCode}: ${reason}`);
            }
        }
    }

    function hasPendingWinnerConfirmation(game) {
        if (!game || game.winnerId) return false;
        for (const player of game.players.values()) {
            if ((player.level || 0) >= (game.maxLevel || 10)) {
                return true;
            }
        }
        return false;
    }

    function computeTurnTimerKey(game) {
        if (!game || game.ended || game.phase !== "IN_GAME") return null;

        const timerSeconds = Math.max(0, Number(game.turnTimerSeconds) || 0);
        if (timerSeconds <= 0 || !game.turnPlayerId) return null;

        const turnPlayer = game.players.get(game.turnPlayerId);
        if (!turnPlayer || turnPlayer.isConnected === false) return null;
        if (hasPendingWinnerConfirmation(game)) return null;

        return `${game.phase}|${game.turnPlayerId}|${timerSeconds}`;
    }

    function syncTurnTimer(game, reason = null) {
        const previousDeadline = game?.turnEndsAt ?? null;
        const desiredKey = computeTurnTimerKey(game);

        if (!desiredKey) {
            if (game?.turnTimerHandle || game?.turnTimerKey || game?.turnEndsAt) {
                clearTurnTimer(game, reason || 'timer no longer applicable');
                return previousDeadline !== null;
            }
            return false;
        }

        const timerSeconds = Math.max(0, Number(game.turnTimerSeconds) || 0);
        const now = Date.now();
        const canReuseDeadline =
            game.turnEndsAt &&
            game.turnEndsAt > now &&
            (game.turnTimerKey === desiredKey || !game.turnTimerKey);

        const nextDeadline = canReuseDeadline
            ? game.turnEndsAt
            : now + (timerSeconds * 1000);

        if (game.turnTimerHandle && game.turnTimerKey === desiredKey && game.turnEndsAt === nextDeadline) {
            return false;
        }

        if (game.turnTimerHandle) {
            clearTimeout(game.turnTimerHandle);
            game.turnTimerHandle = null;
        }
        game.turnTimerNonce = (game.turnTimerNonce || 0) + 1;

        const turnPlayerId = game.turnPlayerId;
        const nonce = game.turnTimerNonce;
        game.turnTimerKey = desiredKey;
        game.turnEndsAt = nextDeadline;
        const delayMs = Math.max(0, nextDeadline - now);

        logger.info(`⏱️ Scheduling ${timerSeconds}s turn timer for ${game.joinCode} (${turnPlayerId}) until ${nextDeadline}`);

        game.turnTimerHandle = setTimeout(() => {
            if (!games.has(game.id)) return;
            if (game.turnTimerNonce !== nonce) return;
            if (computeTurnTimerKey(game) !== desiredKey) return;
            if (game.turnPlayerId !== turnPlayerId) return;

            clearTurnTimer(game, 'turn timer expired');

            const nextPlayerId = getNextTurnPlayerId(game);
            if (!nextPlayerId) {
                logger.warn(`⚠️ Turn timer expired for ${game.joinCode}, but no next player was available`);
                return;
            }

            const previousPlayerId = game.turnPlayerId;
            game.turnPlayerId = nextPlayerId;
            game.combat = null;
            game.seq++;

            logger.info(`⏱️ Turn timer advanced ${game.joinCode} from ${previousPlayerId} to ${nextPlayerId}`);

            game.broadcast({
                type: "STATE_SNAPSHOT",
                gameState: game.buildGameState(),
                seq: game.seq
            });

            db.saveActiveGame(game).catch(err => logger.error('Failed to save after turn timer expiry:', err));
            syncTurnTimer(game, 'post timeout advance');
        }, delayMs);

        return previousDeadline !== nextDeadline;
    }

    function closeGame(game, winnerId) {
        if (game.ended) return;
        clearRoomLifecycleTimers(game, 'game closed');
        game.ended = true;
        game.winnerId = winnerId;
        game.phase = "FINISHED";

        const participants = [];
        for (const [pid, p] of game.players) {
            participants.push({
                playerId: pid,
                userId: p.userId,
                joinedAt: p.joinedAt
            });
        }

        let winnerUserId = null;
        if (winnerId) {
            const winner = game.players.get(winnerId);
            if (winner) winnerUserId = winner.userId || winnerId;
        }

        db.recordGame(game.id, winnerUserId || "aborted", game.createdAt, Date.now(), participants)
            .then(() => logger.info(`💾 Game ${game.id} recorded in history`))
            .catch(err => logger.error(`❌ Failed to record game ${game.id}`, err));
    }

    return {
        getNextTurnPlayerId,
        clearRoomLifecycleTimers,
        syncTurnTimer,
        closeGame
    };
}

module.exports = {
    createTurnManager
};
