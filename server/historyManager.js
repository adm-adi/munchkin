function createHistoryManager({ db, logger }) {
    function handleGetHistory(ws, message) {
        const { userId } = message;
        if (!userId) {
            return;
        }

        db.getUserHistory(userId)
            .then(games => {
                ws.send(JSON.stringify({
                    type: 'HISTORY_RESULT',
                    games: games.map(game => ({
                        id: game.id,
                        endedAt: game.ended_at,
                        winnerId: game.winner_id,
                        playerCount: game.player_count
                    }))
                }));
            })
            .catch(err => {
                logger.error('History error:', err);
            });
    }

    function handleGetLeaderboard(ws) {
        db.getLeaderboard()
            .then(leaderboard => {
                ws.send(JSON.stringify({
                    type: 'LEADERBOARD_RESULT',
                    leaderboard
                }));
            })
            .catch(err => logger.error('Leaderboard error:', err));
    }

    return {
        handleGetHistory,
        handleGetLeaderboard
    };
}

module.exports = { createHistoryManager };
