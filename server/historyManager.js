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

        Promise.all([db.getUserHistory(ws.userId), db.getUserStats(ws.userId)])
            .then(([games, stats]) => {
                ws.send(JSON.stringify({
                    type: 'HISTORY_RESULT',
                    // Real totals, not derived from the returned rows: the history is
                    // capped at 50, so counting those understated anyone past 50 games
                    // and disagreed with the ranking.
                    stats: {
                        wins: stats.wins || 0,
                        gamesPlayed: stats.games_played || 0
                    },
                    games: games.map(game => ({
                        id: game.id,
                        endedAt: game.ended_at || 0,
                        winnerId: game.winner_id,
                        // Resolved server-side; null when the winner was a guest
                        // (no account to name) or the game ended with no winner.
                        winnerName: game.winner_name || null,
                        didIWin: game.did_i_win === 1,
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
     *
     * When the caller is signed in, their own totals are attached separately so the
     * app can show them even if they fall outside the top of the ranking.
     */
    function handleGetLeaderboard(ws) {
        const statsFor = ws.userId
            ? db.getUserStats(ws.userId).catch(err => {
                logger.error('Own-stats lookup failed:', err);
                return null;
            })
            : Promise.resolve(null);

        Promise.all([db.getLeaderboard(), statsFor])
            .then(([rows, own]) => {
                const leaderboard = rows.map(row => ({
                    id: row.id,
                    username: row.username,
                    avatarId: row.avatar_id || 0,
                    gender: row.gender || 'M',
                    wins: row.wins || 0,
                    gamesPlayed: row.games_played || 0
                }));

                // Only meaningful once they have actually played something.
                let me = null;
                if (own && (own.games_played || 0) > 0) {
                    const rank = rows.findIndex(row => row.id === ws.userId);
                    me = {
                        wins: own.wins || 0,
                        gamesPlayed: own.games_played || 0,
                        // 1-based rank within the returned page, or 0 when outside it.
                        rank: rank >= 0 ? rank + 1 : 0
                    };
                }

                ws.send(JSON.stringify({
                    type: 'LEADERBOARD_RESULT',
                    leaderboard,
                    me
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
