function createHistoryManager({ db, logger, sendError }) {
    /**
     * Game history is private to the account that played it. The requested
     * userId is ignored unless it matches the authenticated session, otherwise
     * any client could enumerate another player's history by user id.
     */
    function handleGetHistory(ws, message) {
        if (!ws.userId) {
            sendError(ws, 'UNAUTHORIZED', 'Debes iniciar sesión para ver tu historial');
            return;
        }

        const { userId } = message;
        if (userId && userId !== ws.userId) {
            logger.warn(`Rejected cross-account history read. Session: ${ws.userId}, requested: ${userId}`);
            sendError(ws, 'FORBIDDEN', 'No tienes permiso para ver este historial');
            return;
        }

        db.getUserHistory(ws.userId)
            .then(games => {
                ws.send(JSON.stringify({
                    type: 'HISTORY_RESULT',
                    games: games.map(game => ({
                        id: game.id,
                        endedAt: game.ended_at || 0,
                        winnerId: game.winner_id,
                        playerCount: game.player_count || 0
                    }))
                }));
            })
            .catch(err => {
                logger.error('History error:', err);
                sendError(ws, 'SERVER_ERROR', 'Error al cargar el historial');
            });
    }

    /**
     * Rows come back in SQLite snake_case; the Android client's LeaderboardEntry
     * requires camelCase `avatarId` with no default, so an unmapped row makes
     * kotlinx.serialization throw and the leaderboard never renders.
     */
    function handleGetLeaderboard(ws) {
        db.getLeaderboard()
            .then(rows => {
                ws.send(JSON.stringify({
                    type: 'LEADERBOARD_RESULT',
                    leaderboard: rows.map(row => ({
                        id: row.id,
                        username: row.username,
                        avatarId: row.avatar_id || 0,
                        wins: row.wins || 0
                    }))
                }));
            })
            .catch(err => {
                logger.error('Leaderboard error:', err);
                sendError(ws, 'SERVER_ERROR', 'Error al cargar la clasificación');
            });
    }

    return {
        handleGetHistory,
        handleGetLeaderboard
    };
}

module.exports = { createHistoryManager };
