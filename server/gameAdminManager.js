function createGameAdminManager({
    games,
    clientGames,
    db,
    logger,
    WebSocket,
    sendError,
    getNextTurnPlayerId,
    syncTurnTimer,
    clearRoomLifecycleTimers,
    cancelPendingSave
}) {
    function broadcastSnapshot(game) {
        game.broadcast({
            type: 'STATE_SNAPSHOT',
            gameState: game.buildGameState(),
            seq: game.seq
        });
    }

    function persistGame(game, context) {
        db.saveActiveGame(game).catch(err => logger.error(`Failed to save after ${context}:`, err));
    }

    function deleteGameRecord(game, reason) {
        clearRoomLifecycleTimers(game, reason);
        cancelPendingSave(game.id);
        games.delete(game.id);
        db.deleteActiveGame(game.id).catch(err => logger.error('Failed to delete game from DB:', err));
    }

    function removeGameClients(gameId) {
        for (const [clientWs, info] of clientGames.entries()) {
            if (info.gameId === gameId) {
                clientGames.delete(clientWs);
            }
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

        if (winnerId && !game.players.has(winnerId)) {
            sendError(ws, 'PLAYER_NOT_FOUND', 'Ganador no encontrado');
            return;
        }

        clearRoomLifecycleTimers(game, 'game over received');
        game.ended = true;
        game.phase = 'FINISHED';
        game.winnerId = winnerId;
        game.seq += 1;

        logger.info(`Game over: ${game.joinCode}, winner: ${winnerId}`);
        broadcastSnapshot(game);

        const participants = [];
        for (const [playerId, player] of game.players) {
            participants.push({
                userId: player.userId,
                playerId
            });
        }

        const winnerUserId = winnerId
            ? (game.players.get(winnerId)?.userId || winnerId)
            : null;

        db.recordGame(game.id, winnerUserId, game.createdAt, Date.now(), participants)
            .then(() => logger.info('Game recorded successfully'))
            .catch(err => logger.error('Error recording game:', err));
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

        if (game.turnPlayerId && game.turnPlayerId !== clientInfo.playerId) {
            sendError(ws, 'GENERAL_ERROR', 'No es tu turno');
            return;
        }

        const nextPlayerId = getNextTurnPlayerId(game);
        if (!nextPlayerId) {
            sendError(ws, 'GENERAL_ERROR', 'No hay jugadores disponibles');
            return;
        }

        game.turnPlayerId = nextPlayerId;
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
                if (timerStateChanged) {
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
        if (!game) {
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

        const kickedPlayer = game.players.get(targetPlayerId);

        if (game.turnPlayerId === targetPlayerId) {
            const nextPlayerId = getNextTurnPlayerId(game);
            game.turnPlayerId = nextPlayerId !== targetPlayerId ? nextPlayerId : null;
            game.combat = null;
        }

        game.players.delete(targetPlayerId);
        if (Array.isArray(game.playerOrder)) {
            game.playerOrder = game.playerOrder.filter(playerId => playerId !== targetPlayerId);
        }

        for (const [clientWs, info] of clientGames.entries()) {
            if (info.gameId === clientInfo.gameId && info.playerId === targetPlayerId) {
                clientWs.close(1000, 'Kicked by host');
                clientGames.delete(clientWs);
                break;
            }
        }

        logger.info(`Player ${kickedPlayer?.name} kicked from ${game.joinCode} by host`);
        game.seq += 1;
        syncTurnTimer(game, 'player kicked');
        broadcastSnapshot(game);
        persistGame(game, 'kick');
    }

    function handleSwapPlayers(ws, message) {
        const { player1, player2 } = message;
        const clientInfo = clientGames.get(ws);
        if (!clientInfo) {
            return;
        }

        const game = games.get(clientInfo.gameId);
        if (!game) {
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
            ws.send(JSON.stringify({
                type: 'HOSTED_GAMES_RESULT',
                games: []
            }));
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

        ws.send(JSON.stringify({
            type: 'HOSTED_GAMES_RESULT',
            games: hostedGames
        }));
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

        ws.send(JSON.stringify({
            type: 'HOSTED_GAME_DELETED',
            gameId
        }));
    }

    return {
        handleGameOver,
        handleEndTurn,
        handleDisconnect,
        handleDeleteGame,
        handleKickPlayer,
        handleSwapPlayers,
        handleGetHostedGames,
        handleDeleteHostedGame
    };
}

module.exports = { createGameAdminManager };
