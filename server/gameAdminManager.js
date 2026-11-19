const { requiresCombatToWin } = require('./combatManager');

function createGameAdminManager({
    games,
    clientGames,
    db,
    logger,
    WebSocket,
    sendError,
    safeSend,
    getNextTurnPlayerId,
    syncTurnTimer,
    clearRoomLifecycleTimers,
    cancelPendingSave,
    removeGame
}) {
    const sendJson = typeof safeSend === 'function'
        ? safeSend
        : (ws, payload) => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(payload));
            }
        };

    function broadcastSnapshot(game) {
        game.broadcast({
            type: 'STATE_SNAPSHOT',
            gameState: game.buildGameState(),
            seq: game.seq
        });
    }

    function persistGame(game, context) {
        if (!game || game.ended || game.phase === 'FINISHED') return;
        db.saveActiveGame(game).catch(err => logger.error(`Failed to save after ${context}:`, err));
    }

    function deleteGameRecord(game, reason) {
        clearRoomLifecycleTimers(game, reason);
        cancelPendingSave(game.id);
        if (typeof removeGame === 'function') {
            removeGame(game.id);
        } else {
            games.delete(game.id);
        }
        db.deleteActiveGame(game.id).catch(err => logger.error('Failed to delete game from DB:', err));
    }

    function removeGameClients(gameId) {
        for (const [clientWs, info] of clientGames.entries()) {
            if (info.gameId === gameId) {
                clientGames.delete(clientWs);
            }
        }
    }

    /**
     * Validates whether `winnerId` is legally eligible to claim victory in `game`.
     * When `winnerId == null`, early termination without a winner is always allowed.
     */
    function validateGameOverWinner(game, winnerId) {
        if (winnerId === undefined || winnerId === null) return null;
        if (typeof winnerId !== 'string' || !game.players.has(winnerId)) {
            return { code: 'PLAYER_NOT_FOUND', message: 'Ganador no encontrado' };
        }
        if (game.phase !== 'IN_GAME') {
            return { code: 'INVALID_DATA', message: 'La partida debe estar en curso para declarar un ganador' };
        }
        if (game.players.size < 2) {
            return { code: 'INVALID_DATA', message: 'Se requieren al menos 2 jugadores para registrar un ganador' };
        }
        const winner = game.players.get(winnerId);
        const maxLevel = game.maxLevel || 10;
        if ((winner.level || 0) < maxLevel) {
            return { code: 'INVALID_DATA', message: `El ganador debe alcanzar el nivel ${maxLevel}` };
        }
        if (requiresCombatToWin(game) && winner.reachedMaxLevelViaCombat !== true) {
            return { code: 'INVALID_DATA', message: 'El nivel final debe alcanzarse ganando un combate' };
        }
        return null;
    }

    function finalizeGameOver(game, winnerId) {
        clearRoomLifecycleTimers(game, 'game over received');
        cancelPendingSave(game.id);
        game.combat = null;
        game.ended = true;
        game.phase = 'FINISHED';
        game.winnerId = winnerId ?? null;
        game.seq += 1;

        logger.info(`Game over: ${game.joinCode}, winner: ${game.winnerId}`);
        broadcastSnapshot(game);

        const participants = [];
        const playerStats = [];
        for (const [playerId, player] of game.players) {
            participants.push({
                userId: player.userId,
                playerId,
                joinedAt: player.joinedAt
            });
            playerStats.push({
                playerId,
                userId: player.userId,
                playerName: player.name,
                finalLevel: player.level || 1,
                monstersKilled: player.monstersKilled || 0,
                combatsHelped: player.combatsHelped || 0,
                biggestCombat: player.biggestCombat || 0,
                deaths: player.deaths || 0,
                escapes: player.escapes || 0
            });
        }

        const winnerUserId = game.winnerId
            ? (game.players.get(game.winnerId)?.userId || null)
            : null;

        db.recordGame(
            game.id,
            winnerUserId,
            game.createdAt,
            Date.now(),
            participants,
            game.joinCode,
            game.originalHostId || game.hostId,
            playerStats
        )
            .then(() => logger.info('Game recorded successfully'))
            .catch(err => logger.error('Error recording game:', err))
            .finally(() => {
                cancelPendingSave(game.id);
                db.deleteActiveGame(game.id).catch(err =>
                    logger.error('Failed to drop finished game from active_games:', err));
            });

        // Reclaim finished room memory after a short grace period for clients viewing victory screen.
        game.cleanupTimer = setTimeout(() => {
            if (games.has(game.id)) {
                removeGameClients(game.id);
                deleteGameRecord(game, 'finished game grace cleanup');
            }
        }, 5 * 60 * 1000);
        if (typeof game.cleanupTimer.unref === 'function') {
            game.cleanupTimer.unref();
        }
    }

    function handleGameOver(ws, message) {
        const { gameId, winnerId } = message;
        const clientData = clientGames.get(ws);
        if (!clientData) {
            return;
        }

        if (gameId && gameId !== clientData.gameId) {
            logger.warn(`Cross-room GAME_OVER rejected. Session game: ${clientData.gameId}, requested: ${gameId}`);
            sendError(ws, 'UNAUTHORIZED', 'No tienes permiso para cerrar esta partida');
            return;
        }

        const game = games.get(clientData.gameId);
        if (!game || game.hostId !== clientData.playerId) {
            sendError(ws, 'UNAUTHORIZED', 'Solo el anfitrion puede finalizar la partida');
            return;
        }

        if (game.ended) {
            logger.info(`Game ${game.joinCode} already ended; ignoring duplicate GAME_OVER`);
            return;
        }

        const normalizedWinnerId = winnerId === '' ? null : (winnerId ?? null);
        const validationErr = validateGameOverWinner(game, normalizedWinnerId);
        if (validationErr) {
            sendError(ws, validationErr.code, validationErr.message);
            return;
        }

        finalizeGameOver(game, normalizedWinnerId);
    }

    function handleEndTurn(ws) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            sendError(ws, 'GENERAL_ERROR', 'No estas en ninguna partida');
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game) {
            sendError(ws, 'GENERAL_ERROR', 'Partida no encontrada');
            return;
        }

        if (game.phase !== 'IN_GAME') {
            sendError(ws, 'INVALID_DATA', 'La partida no esta en curso');
            return;
        }

        const turnPlayer = game.turnPlayerId ? game.players.get(game.turnPlayerId) : null;
        const isTurnOwner = !game.turnPlayerId || game.turnPlayerId === clientInfo.playerId;
        const isHostAdvancingDisconnected =
            game.hostId === clientInfo.playerId &&
            turnPlayer != null &&
            turnPlayer.isConnected === false;

        if (!isTurnOwner && !isHostAdvancingDisconnected) {
            sendError(ws, 'GENERAL_ERROR', 'No es tu turno');
            return;
        }

        const nextPlayerId = getNextTurnPlayerId(game);
        if (!nextPlayerId) {
            sendError(ws, 'GENERAL_ERROR', 'No hay jugadores disponibles');
            return;
        }

        if (typeof game.pushUndoSnapshot === 'function') {
            game.pushUndoSnapshot();
        }
        game.turnPlayerId = nextPlayerId;
        game.turnTimerPausedRemainingMs = null;
        game.combat = null;
        game.seq += 1;
        syncTurnTimer(game, 'direct end turn');

        const nextPlayer = game.players.get(game.turnPlayerId);
        logger.info(`Turn advanced to: ${nextPlayer?.name || game.turnPlayerId}`);

        broadcastSnapshot(game);
        persistGame(game, 'direct end turn');
    }

    function handleDisconnect(ws) {
        const clientData = clientGames.get(ws);
        if (!clientData) {
            return;
        }

        const game = games.get(clientData.gameId);
        if (game) {
            logger.info(`Player ${clientData.playerId} disconnected from ${game.joinCode}`);

            const player = game.players.get(clientData.playerId);
            if (player && player.ws !== ws) {
                clientGames.delete(ws);
                logger.info(`Ignoring stale socket close for ${clientData.playerId}`);
                return;
            }

            if (player) {
                player.ws = null;
                player.isConnected = false;
            }

            if (game.ended || game.phase === 'FINISHED') {
                let anyConnected = false;
                for (const activePlayer of game.players.values()) {
                    if (activePlayer.isConnected !== false && activePlayer.ws) {
                        anyConnected = true;
                        break;
                    }
                }
                clientGames.delete(ws);
                if (!anyConnected && games.has(clientData.gameId)) {
                    deleteGameRecord(game, 'all players left finished game');
                }
                return;
            }

            let combatHelperCleared = false;
            if (game.combat && game.combat.helperPlayerId === clientData.playerId) {
                game.combat.helperPlayerId = null;
                combatHelperCleared = true;
            }

            game.broadcast({
                type: 'PLAYER_STATUS',
                playerId: clientData.playerId,
                isConnected: false
            });

            let connectedCount = 0;
            for (const activePlayer of game.players.values()) {
                if (activePlayer.isConnected !== false && activePlayer.ws) {
                    connectedCount += 1;
                }
            }

            if (connectedCount === 0) {
                syncTurnTimer(game, 'all players disconnected');
                if (combatHelperCleared) {
                    game.seq += 1;
                    broadcastSnapshot(game);
                }
                if (game.pendingHostMigration) {
                    clearTimeout(game.pendingHostMigration);
                    game.pendingHostMigration = null;
                }
                if (game.cleanupTimer) {
                    clearTimeout(game.cleanupTimer);
                }

                logger.info(`All players disconnected from ${game.joinCode}, starting 5-minute cleanup timer`);
                game.cleanupTimer = setTimeout(() => {
                    let stillEmpty = true;
                    for (const activePlayer of game.players.values()) {
                        if (activePlayer.isConnected !== false && activePlayer.ws) {
                            stillEmpty = false;
                            break;
                        }
                    }

                    if (stillEmpty && games.has(clientData.gameId)) {
                        deleteGameRecord(game, 'deleting disconnected game');
                        logger.info(`Game ${game.joinCode} deleted (all players disconnected for 5 min)`);
                    }
                }, 5 * 60 * 1000);
            } else {
                if (game.hostId === clientData.playerId) {
                    if (game.pendingHostMigration) {
                        clearTimeout(game.pendingHostMigration);
                    }

                    game.pendingHostMigration = setTimeout(() => {
                        game.pendingHostMigration = null;
                        if (game.ended || game.phase === 'FINISHED' || !games.has(game.id)) return;
                        const hostPlayer = game.players.get(clientData.playerId);
                        if (hostPlayer && !hostPlayer.isConnected) {
                            for (const [playerId, activePlayer] of game.players.entries()) {
                                if (
                                    activePlayer.isConnected &&
                                    activePlayer.ws &&
                                    activePlayer.ws.readyState === WebSocket.OPEN
                                ) {
                                    game.hostId = playerId;
                                    game.hostName = activePlayer.name;
                                    game.seq += 1;
                                    logger.info(`Host migrated to ${activePlayer.name} after 90s timeout`);
                                    broadcastSnapshot(game);
                                    persistGame(game, 'migration');
                                    break;
                                }
                            }
                        }
                    }, 90 * 1000);
                    logger.info(`Host ${game.hostName} disconnected - migration pending for 90s`);
                }

                const timerStateChanged = syncTurnTimer(game, 'player disconnected');
                if (combatHelperCleared) {
                    game.seq += 1;
                }
                if (combatHelperCleared || timerStateChanged) {
                    broadcastSnapshot(game);
                }
                persistGame(game, 'disconnect');
            }
        }

        clientGames.delete(ws);
    }

    function handleDeleteGame(ws) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game) {
            return;
        }

        if (game.hostId !== clientInfo.playerId) {
            sendError(ws, 'PERMISSION_DENIED', 'Solo el anfitrion puede borrar la partida');
            return;
        }

        logger.info(`Game ${game.joinCode} deleted by host ${clientInfo.playerId}`);
        game.broadcast({
            type: 'GAME_DELETED',
            reason: 'El anfitrion ha borrado la partida'
        });

        removeGameClients(game.id);
        deleteGameRecord(game, 'game deleted by host');
    }

    function handleKickPlayer(ws, message) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game || game.ended) {
            return;
        }

        if (game.hostId !== clientInfo.playerId) {
            sendError(ws, 'PERMISSION_DENIED', 'Solo el anfitrion puede expulsar jugadores');
            return;
        }

        const { targetPlayerId } = message;
        if (!targetPlayerId || !game.players.has(targetPlayerId)) {
            sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado');
            return;
        }

        // Kicking yourself as host left a room with a hostId matching no seat, so
        // nobody could ever end, delete or administer it again.
        if (targetPlayerId === clientInfo.playerId) {
            sendError(ws, 'INVALID_DATA', 'No puedes expulsarte a ti mismo');
            return;
        }

        const kickedPlayer = game.players.get(targetPlayerId);
        if (!game.kickedUserIds) game.kickedUserIds = new Set();
        if (!game.kickedIps) game.kickedIps = new Set();
        if (kickedPlayer?.userId) {
            game.kickedUserIds.add(kickedPlayer.userId);
        }

        let kickedClientIp = kickedPlayer?.lastKnownIp || null;
        for (const [clientWs, info] of clientGames.entries()) {
            if (info.gameId === clientInfo.gameId && info.playerId === targetPlayerId) {
                if (!kickedClientIp && clientWs.clientIp) {
                    kickedClientIp = clientWs.clientIp;
                }
                clientWs.close(1000, 'Kicked by host');
                clientGames.delete(clientWs);
                break;
            }
        }
        if (kickedClientIp && kickedClientIp !== 'unknown') {
            let sharedWithOtherSeat = false;
            for (const [otherPid, otherPlayer] of game.players.entries()) {
                if (otherPid !== targetPlayerId && otherPlayer.lastKnownIp === kickedClientIp) {
                    sharedWithOtherSeat = true;
                    break;
                }
            }
            if (!sharedWithOtherSeat) {
                game.kickedIps.add(kickedClientIp);
            }
        }

        if (game.turnPlayerId === targetPlayerId) {
            const nextPlayerId = getNextTurnPlayerId(game);
            const fallbackOrderId = (Array.isArray(game.playerOrder) ? game.playerOrder : [])
                .find(id => id !== targetPlayerId && game.players.has(id));
            game.turnPlayerId = (nextPlayerId && nextPlayerId !== targetPlayerId)
                ? nextPlayerId
                : (fallbackOrderId || (game.hostId !== targetPlayerId ? game.hostId : null));
            game.combat = null;
        }

        if (game.combat) {
            if (game.combat.mainPlayerId === targetPlayerId) {
                game.combat = null;
            } else if (game.combat.helperPlayerId === targetPlayerId) {
                game.combat.helperPlayerId = null;
            }
        }

        game.players.delete(targetPlayerId);
        if (Array.isArray(game.playerOrder)) {
            game.playerOrder = game.playerOrder.filter(playerId => playerId !== targetPlayerId);
        }

        logger.info(`Player ${kickedPlayer?.name} kicked from ${game.joinCode} by host`);
        game.seq += 1;
        syncTurnTimer(game, 'player kicked');
        broadcastSnapshot(game);
        persistGame(game, 'kick');
    }

    function handleTransferHost(ws, message) {
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game || game.ended) {
            return;
        }

        if (game.hostId !== clientInfo.playerId) {
            sendError(ws, 'PERMISSION_DENIED', 'Solo el anfitrion puede transferir el liderazgo');
            return;
        }

        const { targetPlayerId } = message;
        if (!targetPlayerId || typeof targetPlayerId !== 'string' || !game.players.has(targetPlayerId)) {
            sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado');
            return;
        }

        if (targetPlayerId === clientInfo.playerId) {
            sendError(ws, 'INVALID_DATA', 'Ya eres el anfitrion');
            return;
        }

        const targetPlayer = game.players.get(targetPlayerId);
        if (game.pendingHostMigration) {
            clearTimeout(game.pendingHostMigration);
            game.pendingHostMigration = null;
        }

        game.hostId = targetPlayerId;
        game.hostName = targetPlayer.name;
        game.hostUserId = targetPlayer.userId || null;
        game.originalHostId = targetPlayerId;
        game.hostAvatarId = targetPlayer.avatarId ?? 0;
        game.hostGender = targetPlayer.gender || 'M';
        game.seq += 1;

        logger.info(`Host transferred in ${game.joinCode} from ${clientInfo.playerId} to ${targetPlayerId}`);
        broadcastSnapshot(game);
        persistGame(game, 'transfer host');
    }

    function handleSwapPlayers(ws, message) {
        const { player1, player2 } = message;
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game || game.ended) {
            return;
        }

        if (game.hostId !== clientInfo.playerId) {
            logger.warn(`Non-host ${clientInfo.playerId} tried to swap players`);
            return;
        }

        if (!game.players.has(player1) || !game.players.has(player2)) {
            logger.warn('Swap failed: player not found');
            return;
        }

        const order = Array.isArray(game.playerOrder) && game.playerOrder.length > 0
            ? [...game.playerOrder]
            : Array.from(game.players.keys());
        const index1 = order.indexOf(player1);
        const index2 = order.indexOf(player2);
        if (index1 === -1 || index2 === -1) {
            return;
        }

        [order[index1], order[index2]] = [order[index2], order[index1]];
        game.playerOrder = order;

        logger.info(`Swapped players ${player1} and ${player2}`);
        game.seq += 1;
        broadcastSnapshot(game);
        persistGame(game, 'swap');
    }

    function handleGetHostedGames(ws) {
        if (!ws.userId) {
            sendJson(ws, {
                type: 'HOSTED_GAMES_RESULT',
                games: []
            });
            return;
        }

        const hostedGames = [];
        for (const game of games.values()) {
            if (game.hostUserId === ws.userId) {
                hostedGames.push({
                    gameId: game.id,
                    joinCode: game.joinCode,
                    playerCount: game.players.size,
                    phase: game.phase,
                    createdAt: game.createdAt
                });
            }
        }

        sendJson(ws, {
            type: 'HOSTED_GAMES_RESULT',
            games: hostedGames
        });
    }

    function handleDeleteHostedGame(ws, message) {
        const { gameId } = message;
        if (!ws.userId) {
            sendError(ws, 'UNAUTHORIZED', 'Debes iniciar sesion');
            return;
        }

        const game = games.get(gameId);
        if (!game) {
            sendError(ws, 'GAME_NOT_FOUND', 'Partida no encontrada');
            return;
        }

        if (game.hostUserId !== ws.userId) {
            logger.warn(`Unauthorized delete attempt by ${ws.userId} on game ${gameId}`);
            sendError(ws, 'PERMISSION_DENIED', 'No eres el anfitrion de esta partida');
            return;
        }

        logger.info(`Host ${ws.userId} deleting game ${game.joinCode} from menu`);
        game.broadcast({
            type: 'GAME_DELETED',
            reason: 'La partida ha sido borrada por el anfitrion'
        });

        removeGameClients(gameId);
        deleteGameRecord(game, 'host deleted game from menu');

        sendJson(ws, {
            type: 'HOSTED_GAME_DELETED',
            gameId
        });
    }

    return {
        validateGameOverWinner,
        finalizeGameOver,
        handleGameOver,
        handleEndTurn,
        handleDisconnect,
        handleDeleteGame,
        handleKickPlayer,
        handleTransferHost,
        handleSwapPlayers,
        handleGetHostedGames,
        handleDeleteHostedGame
    };
}

module.exports = { createGameAdminManager };
