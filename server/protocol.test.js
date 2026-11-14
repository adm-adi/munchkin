/**
 * Integration tests over a real WebSocket against a real server process.
 *
 * These target the handlers, which is where every bug in this round actually
 * lived — the pure helpers in validation.test.js were never the risky part.
 * Each test below corresponds to a defect that shipped.
 */

const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const WebSocket = require('ws');

const {
    startServer,
    TestClient,
    registerUser,
    createGame,
    joinGame,
    pollUntil
} = require('./testServer');

async function winCombatToLevel10(client, playerId, prefix) {
    client.drain();
    client.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL', eventId: `${prefix}-9`, actorId: playerId,
            targetPlayerId: playerId, timestamp: Date.now(), level: 9
        }
    });
    await client.waitFor(['EVENT_BROADCAST', 'ERROR']);

    await client.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: `${prefix}-cs`, actorId: playerId,
            timestamp: Date.now(), mainPlayerId: playerId
        }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    await client.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_ADD_MONSTER', eventId: `${prefix}-am`, actorId: playerId,
            timestamp: Date.now(),
            monster: { id: `${prefix}-m`, name: 'Rata', baseLevel: 1, levels: 1, treasures: 1 }
        }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    const won = await client.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_END', eventId: `${prefix}-ce`, actorId: playerId,
            timestamp: Date.now(), outcome: 'WIN'
        }
    }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(won.type, 'STATE_SNAPSHOT', JSON.stringify(won));
    assert.strictEqual(won.gameState.players[playerId].level, 10);
    assert.strictEqual(won.gameState.players[playerId].reachedMaxLevelViaCombat, true);
    return won;
}

let server;

test.before(async () => { server = await startServer(); });
test.after(async () => { if (server) await server.stop(); });

// ─────────────────────────── auth ───────────────────────────

test('registration rejects a password under the minimum length', async () => {
    const { reply, client } = await registerUser(server.url, 'shortpw_user', 'abc');
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'INVALID_DATA');
    await client.close();
});

test('registration succeeds and returns a camelCase user with a token', async () => {
    const { reply, client } = await registerUser(server.url, 'good_user');
    assert.strictEqual(reply.type, 'AUTH_SUCCESS', JSON.stringify(reply));
    assert.ok(reply.token, 'a JWT must be issued');
    // The client's UserProfile requires avatarId; a snake_case avatar_id would
    // make kotlinx.serialization throw.
    assert.ok('avatarId' in reply.user, 'user must expose avatarId, not avatar_id');
    assert.strictEqual(reply.user.username, 'good_user');
    await client.close();
});

test('login with the wrong password fails, with the right password succeeds', async () => {
    const { client: reg } = await registerUser(server.url, 'login_user', 'correct-horse-1');
    await reg.close();

    const c1 = await TestClient.connect(server.url);
    const bad = await c1.request(
        { type: 'LOGIN', email: 'login_user@example.com', password: 'wrong-password' },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(bad.type, 'ERROR');
    assert.strictEqual(bad.code, 'AUTH_FAILED');
    await c1.close();

    const c2 = await TestClient.connect(server.url);
    const good = await c2.request(
        { type: 'LOGIN', email: 'login_user@example.com', password: 'correct-horse-1' },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(good.type, 'AUTH_SUCCESS');
    await c2.close();
});

test('LOGIN_WITH_TOKEN issues a refreshed token rather than echoing the old one', async () => {
    // Echoing it back meant a session could never be extended, so an active player
    // was logged out the moment the original 48h expiry passed.
    const { reply, client } = await registerUser(server.url, 'refresh_user');
    await client.close();

    const c = await TestClient.connect(server.url);
    const relogin = await c.request(
        { type: 'LOGIN_WITH_TOKEN', token: reply.token },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(relogin.type, 'AUTH_SUCCESS');
    assert.ok(relogin.token, 'a token must come back');
    await c.close();
});

test('an invalid token is rejected with AUTH_FAILED', async () => {
    // The client relies on this exact code to decide to sign the user out rather
    // than keeping a stale profile.
    const c = await TestClient.connect(server.url);
    const reply = await c.request(
        { type: 'LOGIN_WITH_TOKEN', token: 'not.a.jwt' },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'AUTH_FAILED');
    await c.close();
});

// ─────────────────── username uniqueness ───────────────────
//
// Only email was UNIQUE in the schema, so nothing stopped a second account
// taking an existing name. Login accepts either identifier and returns the first
// matching row, so the original owner could then be handed someone else's row
// and fail their own password check. Production had one name held three times.

test('a second account cannot take an existing username', async () => {
    const first = await registerUser(server.url, 'unique_name');
    assert.strictEqual(first.reply.type, 'AUTH_SUCCESS', JSON.stringify(first.reply));
    await first.client.close();

    const clash = await TestClient.connect(server.url);
    const reply = await clash.request({
        type: 'REGISTER',
        username: 'unique_name',
        email: 'someone_else@example.com',
        password: 'test-password-123'
    }, ['AUTH_SUCCESS', 'ERROR']);
    assert.strictEqual(reply.type, 'ERROR', 'a duplicate username must be refused');
    assert.strictEqual(
        reply.code, 'USERNAME_EXISTS',
        'and say so — it used to surface as a generic REGISTER_FAILED'
    );
    await clash.close();
});

test('username uniqueness ignores case, so it cannot be sidestepped', async () => {
    const first = await registerUser(server.url, 'CaseHolder');
    assert.strictEqual(first.reply.type, 'AUTH_SUCCESS');
    await first.client.close();

    const clash = await TestClient.connect(server.url);
    const reply = await clash.request({
        type: 'REGISTER',
        username: 'caseholder',
        email: 'case_clash@example.com',
        password: 'test-password-123'
    }, ['AUTH_SUCCESS', 'ERROR']);
    assert.strictEqual(reply.type, 'ERROR', 'a case variant is the same name to a reader');
    assert.strictEqual(reply.code, 'USERNAME_EXISTS');
    await clash.close();
});

test('login by username is case-insensitive, matching the index', async () => {
    // Otherwise a name could be blocked from registration yet unusable to log in.
    const reg = await registerUser(server.url, 'MixedCase', 'test-password-123');
    assert.strictEqual(reg.reply.type, 'AUTH_SUCCESS');
    await reg.client.close();

    const c = await TestClient.connect(server.url);
    const reply = await c.request(
        { type: 'LOGIN', email: 'mixedcase', password: 'test-password-123' },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(reply.type, 'AUTH_SUCCESS', JSON.stringify(reply));
    assert.strictEqual(reply.user.username, 'MixedCase', 'the stored casing is preserved');
    await c.close();
});

test('renaming a profile onto a taken username is refused', async () => {
    const taken = await registerUser(server.url, 'taken_name');
    await taken.client.close();

    const mover = await registerUser(server.url, 'mover_name');
    const reply = await mover.client.request(
        { type: 'UPDATE_PROFILE', userId: mover.reply.user.id, username: 'taken_name' },
        ['PROFILE_UPDATED', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(
        reply.code, 'USERNAME_EXISTS',
        'the profile path surfaced this as a generic UPDATE_FAILED'
    );
    await mover.client.close();
});

// ─────────────────── access control (the IDOR) ───────────────────

test('GET_HISTORY refuses to read another account\'s history', async () => {
    const a = await registerUser(server.url, 'victim_user');
    const victimId = a.reply.user.id;
    await a.client.close();

    const b = await registerUser(server.url, 'attacker_user');
    // b.client is authenticated as attacker_user; ask for the victim's history.
    const reply = await b.client.request(
        { type: 'GET_HISTORY', userId: victimId },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR', 'cross-account history read must be refused');
    assert.strictEqual(reply.code, 'FORBIDDEN');
    await b.client.close();
});

test('GET_HISTORY over a fresh unauthenticated connection is refused', async () => {
    // Mirrors how the real client used to call this: a one-off socket with no
    // LOGIN_WITH_TOKEN first. Every other history test authenticates on the same
    // connection, which hid the fact that the app's own request path was broken by
    // the authorization check.
    const registered = await registerUser(server.url, 'freshconn_user');
    const userId = registered.reply.user.id;
    await registered.client.close();

    const fresh = await TestClient.connect(server.url);
    const reply = await fresh.request(
        { type: 'GET_HISTORY', userId },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR', 'an unauthenticated socket must be refused');
    assert.strictEqual(reply.code, 'UNAUTHORIZED');
    await fresh.close();
});

test('history carries real lifetime totals, not a count of the returned page', async () => {
    const { reply: auth, client } = await registerUser(server.url, 'totals_user');
    const reply = await client.request(
        { type: 'GET_HISTORY', userId: auth.user.id },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'HISTORY_RESULT', JSON.stringify(reply));
    assert.ok(reply.stats, 'totals must be attached');
    assert.strictEqual(typeof reply.stats.wins, 'number');
    assert.strictEqual(typeof reply.stats.gamesPlayed, 'number');
    await client.close();
});

test('GET_HISTORY without a session is refused', async () => {
    const c = await TestClient.connect(server.url);
    const reply = await c.request(
        { type: 'GET_HISTORY', userId: 'whatever' },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'UNAUTHORIZED');
    await c.close();
});

test('GET_HISTORY serves the caller\'s own history', async () => {
    const { reply: auth, client } = await registerUser(server.url, 'ownhistory_user');
    const reply = await client.request(
        { type: 'GET_HISTORY', userId: auth.user.id },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'HISTORY_RESULT', JSON.stringify(reply));
    assert.ok(Array.isArray(reply.games));
    await client.close();
});

// ─────────────────── leaderboard shape ───────────────────

test('LEADERBOARD_RESULT uses camelCase avatarId', async () => {
    // The server sent raw snake_case rows, and LeaderboardEntry.avatarId has no
    // default, so decoding threw and the leaderboard screen never rendered.
    const c = await TestClient.connect(server.url);
    const reply = await c.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT', 'ERROR']);
    assert.strictEqual(reply.type, 'LEADERBOARD_RESULT');
    assert.ok(Array.isArray(reply.leaderboard));
    for (const entry of reply.leaderboard) {
        assert.ok('avatarId' in entry, 'entry must expose avatarId');
        assert.ok(!('avatar_id' in entry), 'entry must not leak snake_case avatar_id');
        assert.strictEqual(typeof entry.wins, 'number');
    }
    await c.close();
});

// ─────────────────── ranking end to end ───────────────────

/**
 * Plays a game to a confirmed finish between two registered accounts and checks
 * the ranking reflects it. This is the path the whole feature depends on: the
 * account must be attached to the seat at join time, GAME_OVER must record both
 * the game and every participant, and the query must then count one win for the
 * winner and one game played for both.
 */
test('a finished game credits the winner and counts a game for both players', async () => {
    // Authenticate first: createPlayerState() copies ws.userId onto the seat, so a
    // game created on an anonymous socket is never linked to an account.
    const winner = await registerUser(server.url, 'rank_winner');
    assert.strictEqual(winner.reply.type, 'AUTH_SUCCESS', JSON.stringify(winner.reply));
    const winnerUserId = winner.reply.user.id;

    const loser = await registerUser(server.url, 'rank_loser');
    assert.strictEqual(loser.reply.type, 'AUTH_SUCCESS');
    const loserUserId = loser.reply.user.id;

    // Host the game on the winner's authenticated socket.
    const hostWelcome = await winner.client.request({
        type: 'CreateGameMessage',
        playerMeta: { name: 'RankWinner', avatarId: 2, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    assert.strictEqual(hostWelcome.type, 'WELCOME', JSON.stringify(hostWelcome));
    const gameId = hostWelcome.gameState.gameId;
    const code = hostWelcome.gameState.joinCode;
    const hostPlayerId = hostWelcome.yourPlayerId;

    // Join on the loser's authenticated socket.
    const guestWelcome = await loser.client.request({
        type: 'HELLO',
        joinCode: code,
        playerMeta: { name: 'RankLoser', avatarId: 5, gender: 'F' }
    }, ['WELCOME', 'ERROR']);
    assert.strictEqual(guestWelcome.type, 'WELCOME', JSON.stringify(guestWelcome));

    await winner.client.settle();

    // Start, win combat to Level 10, then have the host confirm the win.
    winner.client.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'GAME_START', eventId: 'r-start',
            actorId: hostPlayerId, timestamp: Date.now()
        }
    });
    await winner.client.waitFor(['STATE_SNAPSHOT']);
    await winCombatToLevel10(winner.client, hostPlayerId, 'rw');

    winner.client.drain();
    winner.client.send({ type: 'GAME_OVER', gameId, winnerId: hostPlayerId });
    const finished = await winner.client.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(finished.gameState.phase, 'FINISHED');

    // Recording happens asynchronously after the snapshot goes out.
    const board = await pollUntil(async () => {
        const res = await winner.client.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT', 'ERROR']);
        if (
            res.type === 'LEADERBOARD_RESULT' &&
            res.leaderboard.some(e => e.id === winnerUserId && e.gamesPlayed >= 1)
        ) {
            return res;
        }
        return null;
    });
    assert.strictEqual(board.type, 'LEADERBOARD_RESULT', JSON.stringify(board));

    const winnerRow = board.leaderboard.find(e => e.id === winnerUserId);
    const loserRow = board.leaderboard.find(e => e.id === loserUserId);

    assert.ok(winnerRow, 'the winner must appear in the ranking');
    assert.strictEqual(winnerRow.wins, 1, 'the winner has one win');
    assert.strictEqual(winnerRow.gamesPlayed, 1, 'the winner has played one game');
    assert.strictEqual(winnerRow.avatarId, 0, 'avatarId comes from the account, not the seat');

    // The whole point of joining through participants: a player who lost still
    // appears, with zero wins. The old winner_id join hid them entirely.
    assert.ok(loserRow, 'a player who has not won must still appear');
    assert.strictEqual(loserRow.wins, 0, 'the loser has no wins');
    assert.strictEqual(loserRow.gamesPlayed, 1, 'the loser has played one game');

    // Ordering: more wins first.
    const winnerIdx = board.leaderboard.findIndex(e => e.id === winnerUserId);
    const loserIdx = board.leaderboard.findIndex(e => e.id === loserUserId);
    assert.ok(winnerIdx < loserIdx, 'the winner must rank above the player with no wins');

    // The authenticated caller gets their own totals back.
    assert.ok(board.me, 'a signed-in caller receives their own stats');
    assert.strictEqual(board.me.wins, 1);
    assert.strictEqual(board.me.gamesPlayed, 1);
    assert.ok(board.me.rank > 0, 'the caller is inside the returned page');

    await winner.client.close();
    await loser.client.close();
});

test('history names the winner and flags whether it was you', async () => {
    // The client had no way to turn a user id into a name, so every finished game
    // read "Jugador". The winner is resolved server-side now.
    const champ = await registerUser(server.url, 'hist_champ');
    const other = await registerUser(server.url, 'hist_other');

    const welcome = await champ.client.request({
        type: 'CreateGameMessage',
        playerMeta: { name: 'Champ', avatarId: 1, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    const gameId = welcome.gameState.gameId;
    const champPlayerId = welcome.yourPlayerId;

    await other.client.request({
        type: 'HELLO',
        joinCode: welcome.gameState.joinCode,
        playerMeta: { name: 'Other', avatarId: 0, gender: 'F' }
    }, ['WELCOME', 'ERROR']);
    await champ.client.settle();

    champ.client.send({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'h-start', actorId: champPlayerId, timestamp: Date.now() }
    });
    await champ.client.waitFor(['STATE_SNAPSHOT']);
    await winCombatToLevel10(champ.client, champPlayerId, 'hc');
    champ.client.drain();
    champ.client.send({ type: 'GAME_OVER', gameId, winnerId: champPlayerId });
    await champ.client.waitFor(['STATE_SNAPSHOT']);

    // The winner's own view (polled until async DB recording commits).
    const mine = await pollUntil(async () => {
        const res = await champ.client.request(
            { type: 'GET_HISTORY', userId: champ.reply.user.id },
            ['HISTORY_RESULT', 'ERROR']
        );
        if (res.type === 'HISTORY_RESULT' && res.games.some(g => g.id === gameId)) {
            return res;
        }
        return null;
    });
    assert.strictEqual(mine.type, 'HISTORY_RESULT', JSON.stringify(mine));
    const wonGame = mine.games.find(g => g.id === gameId);
    assert.ok(wonGame, 'the finished game must appear in the history');
    assert.strictEqual(wonGame.winnerName, 'hist_champ', 'the winner is named, not an id');
    assert.strictEqual(wonGame.didIWin, true);
    assert.strictEqual(wonGame.playerCount, 2, 'both participants are counted');

    // The loser's view of the same game.
    const theirs = await other.client.request(
        { type: 'GET_HISTORY', userId: other.reply.user.id },
        ['HISTORY_RESULT', 'ERROR']
    );
    const lostGame = theirs.games.find(g => g.id === gameId);
    assert.ok(lostGame, 'the loser also sees the game');
    assert.strictEqual(lostGame.winnerName, 'hist_champ');
    assert.strictEqual(lostGame.didIWin, false, 'must not claim the loser won');

    await champ.client.close();
    await other.client.close();
});

test('registration stores the chosen avatar and gender, and the profile can change them', async () => {
    // Registration hardcoded avatar 0 client-side and UPDATE_PROFILE could not touch
    // the avatar at all, so every account displayed the same portrait forever.
    const c = await TestClient.connect(server.url);
    const reg = await c.request({
        type: 'REGISTER',
        username: 'avatar_user',
        email: 'avatar_user@example.com',
        password: 'test-password-123',
        avatarId: 5,
        gender: 'F'
    }, ['AUTH_SUCCESS', 'ERROR']);

    assert.strictEqual(reg.type, 'AUTH_SUCCESS', JSON.stringify(reg));
    assert.strictEqual(reg.user.avatarId, 5, 'the chosen avatar must be stored');
    assert.strictEqual(reg.user.gender, 'F', 'the chosen gender must be stored');

    // Change both.
    const updated = await c.request({
        type: 'UPDATE_PROFILE',
        userId: reg.user.id,
        avatarId: 2,
        gender: 'NA'
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(updated.type, 'PROFILE_UPDATED', JSON.stringify(updated));
    assert.strictEqual(updated.user.avatarId, 2);
    assert.strictEqual(updated.user.gender, 'NA');

    // Slot 0 is a real value, so it must not be mistaken for "unchanged".
    const toZero = await c.request({
        type: 'UPDATE_PROFILE', userId: reg.user.id, avatarId: 0
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(toZero.type, 'PROFILE_UPDATED');
    assert.strictEqual(toZero.user.avatarId, 0, 'avatar 0 must be settable');
    assert.strictEqual(toZero.user.gender, 'NA', 'an omitted field stays unchanged');

    await c.close();
});

test('an invalid profile gender or avatar is refused', async () => {
    const c = await TestClient.connect(server.url);
    const reg = await c.request({
        type: 'REGISTER',
        username: 'badprofile_user',
        email: 'badprofile_user@example.com',
        password: 'test-password-123'
    }, ['AUTH_SUCCESS', 'ERROR']);
    assert.strictEqual(reg.type, 'AUTH_SUCCESS');

    const badGender = await c.request({
        type: 'UPDATE_PROFILE', userId: reg.user.id, gender: 'ROBOT'
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(badGender.type, 'ERROR');
    assert.strictEqual(badGender.code, 'INVALID_DATA');

    const badAvatar = await c.request({
        type: 'UPDATE_PROFILE', userId: reg.user.id, avatarId: 9999
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(badAvatar.type, 'ERROR');
    assert.strictEqual(badAvatar.code, 'INVALID_DATA');

    await c.close();
});

test('the ranking exposes each account\'s avatar and gender', async () => {
    const c = await TestClient.connect(server.url);
    const board = await c.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT']);
    for (const entry of board.leaderboard) {
        assert.strictEqual(typeof entry.avatarId, 'number');
        assert.ok(
            ['M', 'F', 'NA'].includes(entry.gender),
            `gender must be a Kotlin Gender value, got ${entry.gender}`
        );
    }
    await c.close();
});

test('an anonymous caller gets the ranking but no personal stats', async () => {
    const c = await TestClient.connect(server.url);
    const board = await c.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT', 'ERROR']);
    assert.strictEqual(board.type, 'LEADERBOARD_RESULT');
    assert.ok(Array.isArray(board.leaderboard), 'the ranking is readable without an account');
    assert.strictEqual(board.me ?? null, null, 'no personal stats without a session');
    await c.close();
});

test('guests never appear in the ranking', async () => {
    // A guest seat has userId null, so its participant row carries a NULL user_id
    // and cannot join against users. Only accounts are ranked.
    const host = await registerUser(server.url, 'rank_host2');
    const hostUserId = host.reply.user.id;

    const welcome = await host.client.request({
        type: 'CreateGameMessage',
        playerMeta: { name: 'AccountHost', avatarId: 0, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    const gameId = welcome.gameState.gameId;
    const hostPlayerId = welcome.yourPlayerId;

    // Unauthenticated guest.
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, welcome.gameState.joinCode, 'PureGuest');
    const guestPlayerId = guestWelcome.yourPlayerId;
    await Promise.all([host.client.settle(), guest.settle()]);

    host.client.send({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'g-start', actorId: hostPlayerId, timestamp: Date.now() }
    });
    await host.client.waitFor(['STATE_SNAPSHOT']);

    // The guest wins via combat to Level 10, but has no account to credit.
    await winCombatToLevel10(guest, guestPlayerId, 'gr');
    await host.client.settle();
    host.client.drain();

    host.client.send({ type: 'GAME_OVER', gameId, winnerId: guestPlayerId });
    await host.client.waitFor(['STATE_SNAPSHOT']);

    const board = await pollUntil(async () => {
        const res = await host.client.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT', 'ERROR']);
        if (
            res.type === 'LEADERBOARD_RESULT' &&
            res.leaderboard.some(e => e.id === hostUserId && e.gamesPlayed >= 1)
        ) {
            return res;
        }
        return null;
    });
    // The host played, so appears with no win. The guest appears nowhere.
    const hostRow = board.leaderboard.find(e => e.id === hostUserId);
    assert.ok(hostRow, 'the account that played must appear');
    assert.strictEqual(hostRow.wins, 0, 'the win went to a guest, so it credits nobody');
    assert.ok(
        board.leaderboard.every(e => e.username !== 'PureGuest'),
        'a guest must never appear in the ranking'
    );

    await guest.close();
    await host.client.close();
});

// ─────────────────── game listing ───────────────────

test('LIST_GAMES advertises a lobby but hides a started game', async () => {
    // handleListGames filtered on seat count alone, so in-progress and finished
    // rooms were offered as joinable.
    const { client: host, welcome } = await createGame(server.url, 'ListHost');
    const code = welcome.gameState.joinCode;

    const probe = await TestClient.connect(server.url);
    let list = await probe.request({ type: 'LIST_GAMES' }, ['GAMES_LIST']);
    assert.ok(
        list.games.some(g => g.joinCode === code),
        'a LOBBY game should be listed'
    );

    // Start the game, then it must disappear from the joinable list.
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'GAME_START',
            eventId: 'e-start',
            actorId: welcome.yourPlayerId,
            timestamp: Date.now()
        }
    });
    await host.waitFor(['STATE_SNAPSHOT']);

    list = await probe.request({ type: 'LIST_GAMES' }, ['GAMES_LIST']);
    assert.ok(
        !list.games.some(g => g.joinCode === code),
        'an IN_GAME room must not be advertised as joinable'
    );

    await probe.close();
    await host.close();
});

// ─────────────────── join / reconnect ───────────────────

test('an unknown join code is rejected', async () => {
    const { client, welcome } = await joinGame(server.url, 'NOSUCHCD', 'Nobody');
    assert.strictEqual(welcome.type, 'ERROR');
    assert.strictEqual(welcome.code, 'INVALID_JOIN_CODE');
    await client.close();
});

test('a second player can join and both appear in the snapshot', async () => {
    const { client: host, welcome } = await createGame(server.url, 'JoinHost');
    const code = welcome.gameState.joinCode;

    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'Guest');
    assert.strictEqual(guestWelcome.type, 'WELCOME', JSON.stringify(guestWelcome));
    assert.strictEqual(Object.keys(guestWelcome.gameState.players).length, 2);
    assert.ok(guestWelcome.reconnectToken, 'a reconnect token must be issued');

    await guest.close();
    await host.close();
});

test('reconnecting with a bogus token is refused', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ReconHost');
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'Guest');
    const guestId = guestWelcome.yourPlayerId;
    await guest.close();

    const { client: impostor, welcome: reply } = await joinGame(server.url, code, 'Impostor', {
        playerMeta: { name: 'Impostor', avatarId: 0, gender: 'F', playerId: guestId },
        reconnectToken: 'clearly-not-the-right-token'
    });
    assert.strictEqual(reply.type, 'ERROR', 'seat takeover must be refused');
    assert.strictEqual(reply.code, 'UNAUTHORIZED');

    await impostor.close();
    await host.close();
});

test('reconnecting with the issued token restores the seat', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ReconHost2');
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: first } = await joinGame(server.url, code, 'Guest');
    const guestId = first.yourPlayerId;
    const token = first.reconnectToken;
    await guest.close();

    const { client: back, welcome: again } = await joinGame(server.url, code, 'Guest', {
        playerMeta: { name: 'Guest', avatarId: 0, gender: 'F', playerId: guestId },
        reconnectToken: token
    });
    assert.strictEqual(again.type, 'WELCOME', JSON.stringify(again));
    assert.strictEqual(again.yourPlayerId, guestId, 'must resume the same seat');
    assert.notStrictEqual(again.reconnectToken, token, 'the token should rotate on reconnect');

    await back.close();
    await host.close();
});

// ─────────────────── event validation ───────────────────

test('a non-numeric SET_GEAR cannot corrupt the broadcast snapshot', async () => {
    // player.gear = event.gear was applied raw. A string reached the snapshot and
    // broke strictly-typed decoding for every client in the room, not just the sender.
    const { client: host, welcome } = await createGame(server.url, 'GearHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'e-gear-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_GEAR',
            eventId: 'e-gear',
            actorId: me,
            targetPlayerId: me,
            timestamp: Date.now(),
            gear: 'not-a-number'
        }
    });

    // Whether it is rejected or clamped, gear must remain an integer.
    const reply = await host.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST', 'ERROR']);
    if (reply.type !== 'ERROR') {
        const snapshot = reply.type === 'STATE_SNAPSHOT'
            ? reply
            : await host.waitFor(['STATE_SNAPSHOT'], 2000).catch(() => null);
        if (snapshot) {
            const gear = snapshot.gameState.players[me].gearBonus;
            assert.strictEqual(typeof gear, 'number', `gearBonus must stay numeric, got ${typeof gear}`);
            assert.ok(Number.isFinite(gear));
        }
    }
    await host.close();
});

test('an invalid gender is refused instead of reaching the snapshot', async () => {
    // "MALE" and friends are not values of the Kotlin Gender enum (M/F/NA).
    const { client: host, welcome } = await createGame(server.url, 'GenderHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'e-gender-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    const reply = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_GENDER',
            eventId: 'e-gender',
            actorId: me,
            targetPlayerId: me,
            timestamp: Date.now(),
            gender: 'ROBOT'
        }
    }, ['ERROR', 'STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    assert.strictEqual(reply.type, 'ERROR', 'an unknown gender must be rejected');
    assert.strictEqual(reply.code, 'INVALID_DATA');
    await host.close();
});

test('a second class requires Super Munchkin, and is dropped when it is removed', async () => {
    const { client: host, welcome } = await createGame(server.url, 'SlotHost');
    const me = welcome.yourPlayerId;

    const ev = (type, extra) => ({
        type: 'EVENT_REQUEST',
        event: {
            type, eventId: `s-${type}-${Math.abs(JSON.stringify(extra || {}).length)}`,
            actorId: me, targetPlayerId: me, timestamp: 1, ...extra
        }
    });

    await host.request(ev('GAME_START'), ['STATE_SNAPSHOT', 'ERROR']);

    // Without the card there is no second slot to fill.
    const refused = await host.request(
        ev('SET_CLASS', { newClass: 'CLERIC', isSecondary: true }),
        ['ERROR', 'STATE_SNAPSHOT', 'EVENT_BROADCAST']
    );
    assert.strictEqual(refused.type, 'ERROR', 'a second class without the card must be refused');
    assert.strictEqual(refused.code, 'INVALID_DATA');

    // Turn Super Munchkin on, then the second class sticks.
    await host.settle();
    host.send(ev('SET_SUPER_MUNCHKIN', { enabled: true }));
    await host.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST']);
    await host.settle();
    host.send(ev('SET_CLASS', { newClass: 'CLERIC', isSecondary: true }));
    await host.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    // Force an authoritative snapshot to read the stored state back.
    let snap = await host.request(
        ev('COMBAT_START', { mainPlayerId: me }),
        ['STATE_SNAPSHOT']
    );
    assert.strictEqual(snap.gameState.players[me].secondaryClass, 'CLERIC');
    assert.strictEqual(snap.gameState.players[me].hasSuperMunchkin, true);

    // Losing the card must clear the slot rather than leave it to apply again later.
    await host.settle();
    host.send(ev('SET_SUPER_MUNCHKIN', { enabled: false }));
    await host.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST']);
    await host.settle();
    snap = await host.request(
        ev('COMBAT_ADD_MONSTER', { monster: { id: 'slot-m', baseLevel: 1 } }),
        ['STATE_SNAPSHOT']
    );
    assert.strictEqual(
        snap.gameState.players[me].secondaryClass, 'NONE',
        'the second class must go with the card'
    );

    await host.close();
});

test('an invalid character class is refused', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ClassHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'e-class-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    const reply = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_CLASS',
            eventId: 'e-class',
            actorId: me,
            targetPlayerId: me,
            timestamp: Date.now(),
            newClass: 'NECROMANCER'
        }
    }, ['ERROR', 'STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'INVALID_DATA');
    await host.close();
});

test('events claiming another actor are refused', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ActorHost');
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'Guest');
    await guest.settle();

    // Guest tries to level up the host.
    const reply = await guest.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'INC_LEVEL',
            eventId: 'e-cheat',
            actorId: welcome.yourPlayerId, // not the guest
            targetPlayerId: welcome.yourPlayerId,
            timestamp: Date.now(),
            amount: 5
        }
    }, ['ERROR', 'STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'FORBIDDEN');

    await guest.close();
    await host.close();
});

test('level is clamped to the game maximum', async () => {
    const { client: host, welcome } = await createGame(server.url, 'LevelHost');
    const me = welcome.yourPlayerId;
    const maxLevel = welcome.gameState.settings.maxLevel;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'e-lvl-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL',
            eventId: 'e-lvl',
            actorId: me,
            targetPlayerId: me,
            timestamp: Date.now(),
            level: 9999
        }
    });

    // SET_LEVEL is not in SNAPSHOT_FIRST_EVENT_TYPES, so it only produces an
    // EVENT_BROADCAST. COMBAT_START is snapshot-first, so use it to force the
    // server to publish its authoritative view of the player.
    await host.waitFor(['EVENT_BROADCAST', 'ERROR']);
    const snapshot = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: 'e-force', actorId: me,
            timestamp: Date.now(), mainPlayerId: me
        }
    }, ['STATE_SNAPSHOT']);

    const level = snapshot.gameState.players[me].level;
    assert.strictEqual(typeof level, 'number');
    assert.strictEqual(level, maxLevel - 1, 'manual level is clamped at maxLevel - 1 when combat win is required');
    await host.close();
});

// ─────────────────── combat ───────────────────

test('COMBAT_UPDATE_MONSTER cannot bypass the add-time clamp', async () => {
    // ADD clamped baseLevel/flatModifier; UPDATE did not, so a client could add a
    // legal monster and then raise it to anything.
    const { client: host, welcome } = await createGame(server.url, 'CombatHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'c-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: 'c1', actorId: me,
            timestamp: Date.now(), mainPlayerId: me
        }
    });
    await host.waitFor(['STATE_SNAPSHOT']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_ADD_MONSTER', eventId: 'c2', actorId: me, timestamp: Date.now(),
            monster: { id: 'mon-1', name: 'Rata', baseLevel: 3, flatModifier: 0 }
        }
    });
    await host.waitFor(['STATE_SNAPSHOT']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_UPDATE_MONSTER', eventId: 'c3', actorId: me, timestamp: Date.now(),
            monster: { id: 'mon-1', name: 'Rata', baseLevel: 9999, flatModifier: 9999 }
        }
    });
    const snap = await host.waitFor(['STATE_SNAPSHOT']);

    const monster = snap.gameState.combat.monsters.find(m => m.id === 'mon-1');
    assert.ok(monster, 'the monster should still be present');
    assert.ok(monster.baseLevel <= 20, `baseLevel ${monster.baseLevel} must stay clamped`);
    assert.ok(monster.flatModifier <= 10, `flatModifier ${monster.flatModifier} must stay clamped`);

    await host.close();
});

test('COMBAT_START seeded with too many monsters is refused', async () => {
    const { client: host, welcome } = await createGame(server.url, 'SeedHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'c-seed-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    const reply = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: 'c1', actorId: me, timestamp: Date.now(),
            mainPlayerId: me,
            monsters: Array.from({ length: 20 }, (_, i) => ({ id: `m${i}`, baseLevel: 1 }))
        }
    }, ['ERROR', 'STATE_SNAPSHOT']);

    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'COMBAT_MONSTER_LIMIT');
    await host.close();
});

test('COMBAT_START naming a non-existent main player is refused', async () => {
    // Otherwise the room ends up with a combat nobody is authorised to end.
    const { client: host, welcome } = await createGame(server.url, 'GhostHost');
    const me = welcome.yourPlayerId;

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'c-ghost-start', actorId: me, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);

    const reply = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: 'c1', actorId: me, timestamp: Date.now(),
            mainPlayerId: 'a-player-who-does-not-exist'
        }
    }, ['ERROR', 'STATE_SNAPSHOT']);

    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'PLAYER_NOT_FOUND');
    await host.close();
});

test('a Warrior helper wins a tie, matching the client calculator', async () => {
    // The server only checked the main player, so it overruled a tie the client
    // had already displayed as a win.
    const { client: host, welcome } = await createGame(server.url, 'TieHost');
    const code = welcome.gameState.joinCode;
    const hostId = welcome.yourPlayerId;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'Helper');
    const helperId = guestWelcome.yourPlayerId;
    await Promise.all([host.settle(), guest.settle()]);

    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'tie-start', actorId: hostId, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);
    await guest.settle();

    // Helper becomes a Warrior. Host level 1 + helper level 1 = power 2.
    guest.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_CLASS', eventId: 'g1', actorId: helperId, targetPlayerId: helperId,
            timestamp: Date.now(), newClass: 'WARRIOR'
        }
    });
    await guest.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_START', eventId: 'c1', actorId: hostId,
            timestamp: Date.now(), mainPlayerId: hostId
        }
    });
    await host.waitFor(['STATE_SNAPSHOT']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_ADD_HELPER', eventId: 'c2', actorId: hostId,
            timestamp: Date.now(), helperId
        }
    });
    await host.waitFor(['STATE_SNAPSHOT', 'EVENT_BROADCAST']);

    // A level-2 monster ties the party's power of 2.
    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_ADD_MONSTER', eventId: 'c3', actorId: hostId, timestamp: Date.now(),
            monster: { id: 'tie-mon', name: 'Empate', baseLevel: 2, flatModifier: 0, levels: 1, treasures: 1 }
        }
    });
    await host.waitFor(['STATE_SNAPSHOT']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'COMBAT_END', eventId: 'c4', actorId: hostId, timestamp: Date.now(),
            outcome: 'WIN', levelsGained: 1, treasuresGained: 1, helperLevelsGained: 0
        }
    });
    const after = await host.waitFor(['STATE_SNAPSHOT']);

    assert.strictEqual(after.gameState.combat, null, 'combat should be over');
    assert.strictEqual(
        after.gameState.players[hostId].level, 2,
        'the tie must count as a win, so the main player gains the monster\'s level'
    );

    await guest.close();
    await host.close();
});

// ─────────────────── host authority ───────────────────

test('a non-host cannot kick a player', async () => {
    const { client: host, welcome } = await createGame(server.url, 'KickHost');
    const code = welcome.gameState.joinCode;
    const { client: guest } = await joinGame(server.url, code, 'Guest');

    // The join broadcasts a snapshot to everyone; let it land before asserting.
    await guest.settle();

    const reply = await guest.request(
        { type: 'KICK_PLAYER', targetPlayerId: welcome.yourPlayerId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'PERMISSION_DENIED');

    await guest.close();
    await host.close();
});

test('a non-turn player cannot end the turn', async () => {
    const { client: host, welcome } = await createGame(server.url, 'TurnHost');
    const code = welcome.gameState.joinCode;
    const { client: guest } = await joinGame(server.url, code, 'Guest');

    await Promise.all([host.settle(), guest.settle()]);
    await host.request({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'turn-start', actorId: welcome.yourPlayerId, timestamp: Date.now() }
    }, ['STATE_SNAPSHOT', 'ERROR']);
    await guest.settle();

    const reply = await guest.request({ type: 'END_TURN' }, ['ERROR', 'STATE_SNAPSHOT']);
    assert.strictEqual(reply.type, 'ERROR', 'only the turn holder may end the turn');

    await guest.close();
    await host.close();
});

// ─────────────────── misc ───────────────────

test('successful joins do not consume the join rate-limit budget', async () => {
    // The limit is there to stop join-code guessing. It used to charge every
    // attempt, including successful joins and reconnects, so several players behind
    // one NAT — or one client retrying a dropped connection up to 15 times — could
    // lock the whole address out of a game they were legitimately playing.
    const { client: host, welcome } = await createGame(server.url, 'BudgetHost');
    const code = welcome.gameState.joinCode;

    const joined = [];
    // Well past the 10-attempt budget.
    for (let i = 0; i < 14; i++) {
        const { client, welcome: reply } = await joinGame(server.url, code, `P${i}`);
        joined.push(client);
        if (reply.type === 'ERROR' && reply.code === 'RATE_LIMITED') {
            // Close what we opened before failing.
            await Promise.all(joined.map(c => c.close()));
            await host.close();
            assert.fail(`a legitimate join was rate limited on attempt ${i + 1}`);
        }
        // The room only seats MAX_PLAYERS, so most of these are refused with
        // GAME_FULL — which is fine. GAME_FULL is not a guessing signal either.
        assert.ok(
            reply.type === 'WELCOME' || reply.code === 'GAME_FULL',
            `unexpected reply on attempt ${i + 1}: ${JSON.stringify(reply)}`
        );
    }

    await Promise.all(joined.map(c => c.close()));
    await host.close();
});

test('repeated bad join codes are rate limited', async () => {
    // The other half of the contract: guessing must still be throttled. Uses its
    // own server so the failures do not leak into the shared instance's budget.
    const isolated = await startServer();
    try {
        let limited = false;
        for (let i = 0; i < 15; i++) {
            const { client, welcome } = await joinGame(isolated.url, 'BADCODE1', 'Guesser');
            await client.close();
            if (welcome.code === 'RATE_LIMITED') { limited = true; break; }
            assert.strictEqual(welcome.code, 'INVALID_JOIN_CODE');
        }
        assert.ok(limited, 'guessing join codes must eventually be rate limited');
    } finally {
        await isolated.stop();
    }
});

test('PING is answered with PONG', async () => {
    const c = await TestClient.connect(server.url);
    const pong = await c.request({ type: 'PING' }, ['PONG']);
    assert.strictEqual(pong.type, 'PONG');
    assert.strictEqual(typeof pong.timestamp, 'number');
    await c.close();
});

test('malformed JSON does not take the server down', async () => {
    const c = await TestClient.connect(server.url);
    c.ws.send('{ this is not json');
    // The connection must survive and still answer.
    const pong = await c.request({ type: 'PING' }, ['PONG']);
    assert.strictEqual(pong.type, 'PONG');
    await c.close();
});

test('an unknown message type is ignored without dropping the connection', async () => {
    const c = await TestClient.connect(server.url);
    c.send({ type: 'NO_SUCH_MESSAGE_TYPE' });
    const pong = await c.request({ type: 'PING' }, ['PONG']);
    assert.strictEqual(pong.type, 'PONG');
    await c.close();
});

test('registration is rate limited per IP', async () => {
    // REGISTER had no limit at all while LOGIN did, so account creation was
    // unbounded. Uses its own server so the low limit does not affect other tests.
    const limited = await startServer({ registerLimit: 2 });
    try {
        const first = await registerUser(limited.url, 'rl_one');
        assert.strictEqual(first.reply.type, 'AUTH_SUCCESS');
        await first.client.close();

        const second = await registerUser(limited.url, 'rl_two');
        assert.strictEqual(second.reply.type, 'AUTH_SUCCESS');
        await second.client.close();

        const third = await registerUser(limited.url, 'rl_three');
        assert.strictEqual(third.reply.type, 'ERROR', 'the third registration must be refused');
        assert.strictEqual(third.reply.code, 'RATE_LIMITED');
        await third.client.close();
    } finally {
        await limited.stop();
    }
});

test('CATALOG_ADD without a session is refused', async () => {
    const c = await TestClient.connect(server.url);
    const reply = await c.request(
        { type: 'CATALOG_ADD', monster: { name: 'Colado', level: 5 } },
        ['CATALOG_ADD_SUCCESS', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'UNAUTHORIZED');
    await c.close();
});

test('CATALOG_SEARCH sanitises and bounds its results', async () => {
    const c = await TestClient.connect(server.url);
    const reply = await c.request({ type: 'CATALOG_SEARCH', query: 'dra' }, ['CATALOG_SEARCH_RESULT', 'ERROR']);
    assert.strictEqual(reply.type, 'CATALOG_SEARCH_RESULT');
    assert.ok(Array.isArray(reply.results));
    assert.ok(reply.results.length <= 20, 'results are capped');
    for (const m of reply.results) {
        assert.ok('isUndead' in m, 'results must be camelCase for the client');
    }
    await c.close();
});

test('a too-long CATALOG_SEARCH query is refused', async () => {
    const c = await TestClient.connect(server.url);
    const reply = await c.request(
        { type: 'CATALOG_SEARCH', query: 'x'.repeat(500) },
        ['CATALOG_SEARCH_RESULT', 'ERROR']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'INVALID_DATA');
    await c.close();
});

// ─────────────── event-path authorization ───────────────
//
// The dedicated admin messages (GAME_OVER, END_TURN, DELETE_GAME) always checked
// host/turn ownership, but the EVENT_REQUEST path applied GAME_START, GAME_END
// and END_TURN with no authorization at all, so any seat could bypass the checks
// by phrasing the same action as an event.

const ev = (type, actorId, extra = {}) => ({
    type: 'EVENT_REQUEST',
    event: { type, eventId: `auth-${type}-${actorId}`, actorId, timestamp: 1, ...extra }
});

test('a non-host GAME_END event cannot close the room', async () => {
    const { client: host, welcome } = await createGame(server.url, 'EndHost');
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'EndGuest');
    const guestId = guestWelcome.yourPlayerId;
    await Promise.all([host.settle(), guest.settle()]);

    await host.request(ev('GAME_START', welcome.yourPlayerId), ['STATE_SNAPSHOT', 'ERROR']);
    await guest.settle();

    const refused = await guest.request(
        ev('GAME_END', guestId, { winnerId: guestId }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(refused.type, 'ERROR', 'a guest must not be able to end the game');
    assert.strictEqual(refused.code, 'FORBIDDEN');

    // The room is still alive: the host can still act in it.
    await host.settle();
    const alive = await host.request(
        ev('COMBAT_START', welcome.yourPlayerId, { mainPlayerId: welcome.yourPlayerId }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(alive.type, 'STATE_SNAPSHOT', 'the game must still be running');
    assert.notStrictEqual(alive.gameState.phase, 'FINISHED');

    await host.close();
    await guest.close();
});

test('GAME_END refuses a winnerId that is not a seat in the room', async () => {
    const { client: host, welcome } = await createGame(server.url, 'FakeWinHost');
    await host.request(ev('GAME_START', welcome.yourPlayerId), ['STATE_SNAPSHOT', 'ERROR']);
    const refused = await host.request(
        ev('GAME_END', welcome.yourPlayerId, { winnerId: 'not-a-real-player' }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(refused.type, 'ERROR');
    assert.strictEqual(refused.code, 'PLAYER_NOT_FOUND');
    await host.close();
});

test('an END_TURN event from a player out of turn is refused', async () => {
    const { client: host, welcome } = await createGame(server.url, 'TurnHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'TurnGuest');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    await guest.settle();

    // It is the host's turn (turn starts with the host).
    const refused = await guest.request(
        ev('END_TURN', guestId),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(refused.type, 'ERROR', 'stealing a turn must be refused');
    assert.strictEqual(refused.code, 'FORBIDDEN');

    // The rightful turn player can still pass it.
    await host.settle();
    const passed = await host.request(ev('END_TURN', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(passed.type, 'STATE_SNAPSHOT');
    assert.strictEqual(passed.gameState.turnPlayerId, guestId, 'the turn must pass to the guest');

    await host.close();
    await guest.close();
});

// ─────────── the winning level must come from a kill ───────────

test('the room advertises every rule setting the client knows about', async () => {
    // The server used to send two keys the client does not have and omit four it
    // does, so those four silently sat at their Kotlin defaults.
    const { client, welcome } = await createGame(server.url, 'SettingsHost');
    const settings = welcome.gameState.settings;
    for (const key of [
        'minLevel', 'maxLevel', 'tiesGoToMonsters',
        'levelTenOnlyCombat', 'allowLevelTenOverride', 'turnTimerSeconds'
    ]) {
        assert.ok(key in settings, `settings must carry ${key}`);
    }
    assert.strictEqual(settings.levelTenOnlyCombat, true);
    assert.strictEqual(settings.tiesGoToMonsters, true);
    await client.close();
});

test('levelling to the max by hand does not count as a combat win', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ManualLevelHost');
    const me = welcome.yourPlayerId;
    await host.request(ev('GAME_START', me), ['STATE_SNAPSHOT', 'ERROR']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL', eventId: 'lvl-manual', actorId: me,
            targetPlayerId: me, timestamp: 1, level: 10
        }
    });
    await host.waitFor(['EVENT_BROADCAST', 'ERROR']);

    // COMBAT_START is snapshot-first, so use it to pull the authoritative view.
    const snap = await host.request(
        ev('COMBAT_START', me, { mainPlayerId: me }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const seat = snap.gameState.players[me];
    assert.strictEqual(seat.level, 9, 'manual levelling must clamp below maxLevel when combat win is required');
    assert.strictEqual(
        seat.reachedMaxLevelViaCombat, false,
        'tapping up to the winning level must not qualify as a kill'
    );
    await host.close();
});

test('killing a monster into the max level qualifies as a combat win', async () => {
    const { client: host, welcome } = await createGame(server.url, 'CombatWinHost');
    const me = welcome.yourPlayerId;
    await host.request(ev('GAME_START', me), ['STATE_SNAPSHOT', 'ERROR']);

    // Sit one level short, then win a fight the player cannot lose.
    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL', eventId: 'lvl-9', actorId: me,
            targetPlayerId: me, timestamp: 1, level: 9
        }
    });
    await host.waitFor(['EVENT_BROADCAST', 'ERROR']);

    await host.request(ev('COMBAT_START', me, { mainPlayerId: me }), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(
        ev('COMBAT_ADD_MONSTER', me, { monster: { id: 'weak', name: 'Rata', baseLevel: 1 } }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const ended = await host.request(
        ev('COMBAT_END', me, { outcome: 'WIN' }),
        ['STATE_SNAPSHOT', 'ERROR']
    );

    const seat = ended.gameState.players[me];
    assert.strictEqual(seat.level, 10, 'the kill takes the player to the winning level');
    assert.strictEqual(
        seat.reachedMaxLevelViaCombat, true,
        'a kill must qualify as the way the winning level was reached'
    );
    await host.close();
});

test('dropping back below the max level revokes the combat claim', async () => {
    const { client: host, welcome } = await createGame(server.url, 'RevokeHost');
    const me = welcome.yourPlayerId;
    await host.request(ev('GAME_START', me), ['STATE_SNAPSHOT', 'ERROR']);

    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL', eventId: 'rv-9', actorId: me,
            targetPlayerId: me, timestamp: 1, level: 9
        }
    });
    await host.waitFor(['EVENT_BROADCAST', 'ERROR']);

    await host.request(ev('COMBAT_START', me, { mainPlayerId: me }), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(
        ev('COMBAT_ADD_MONSTER', me, { monster: { id: 'weak2', name: 'Rata', baseLevel: 1 } }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    await host.request(ev('COMBAT_END', me, { outcome: 'WIN' }), ['STATE_SNAPSHOT', 'ERROR']);

    // A curse knocks them back down.
    host.drain();
    host.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'DEC_LEVEL', eventId: 'rv-dec', actorId: me,
            targetPlayerId: me, timestamp: 1, amount: 1
        }
    });
    await host.waitFor(['EVENT_BROADCAST', 'ERROR']);

    const snap = await host.request(
        ev('COMBAT_START', me, { mainPlayerId: me }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const seat = snap.gameState.players[me];
    assert.strictEqual(seat.level, 9);
    assert.strictEqual(
        seat.reachedMaxLevelViaCombat, false,
        'climbing back by hand must not inherit the earlier kill'
    );
    await host.close();
});

// ─────────── snapshot poisoning via the join path ───────────

test('a non-integer avatarId at join cannot reach the broadcast snapshot', async () => {
    // createPlayerState stored playerMeta.avatarId verbatim, so `"pwn"` landed in
    // every STATE_SNAPSHOT. PlayerState.avatarId is a strict Kotlin Int, so the
    // whole room stopped decoding — and the value persisted, surviving restarts.
    const { client: host, welcome } = await createGame(server.url, 'PoisonHost');
    const code = welcome.gameState.joinCode;

    const guest = await TestClient.connect(server.url);
    const guestWelcome = await guest.request(
        { type: 'HELLO', joinCode: code, playerMeta: { name: 'Pwn', avatarId: 'pwn' } },
        ['WELCOME', 'ERROR']
    );
    assert.strictEqual(guestWelcome.type, 'WELCOME');

    for (const seat of Object.values(guestWelcome.gameState.players)) {
        assert.strictEqual(typeof seat.avatarId, 'number', 'avatarId must always be a number');
        assert.ok(Number.isInteger(seat.avatarId), 'avatarId must be an integer');
    }

    await host.close();
    await guest.close();
});

test('an out-of-range or fractional avatarId is clamped rather than broadcast', async () => {
    const { client: host, welcome } = await createGame(server.url, 'ClampHost');
    const code = welcome.gameState.joinCode;

    const guest = await TestClient.connect(server.url);
    const guestWelcome = await guest.request(
        { type: 'HELLO', joinCode: code, playerMeta: { name: 'Frac', avatarId: 3.7 } },
        ['WELCOME', 'ERROR']
    );
    const seat = guestWelcome.gameState.players[guestWelcome.yourPlayerId];
    assert.ok(Number.isInteger(seat.avatarId), `avatarId ${seat.avatarId} must be an integer`);
    assert.ok(seat.avatarId >= 0 && seat.avatarId <= 100, 'avatarId must stay in range');

    await host.close();
    await guest.close();
});

test('an over-long join name is bounded instead of stored verbatim', async () => {
    const { client: host, welcome } = await createGame(server.url, 'NameHost');
    const code = welcome.gameState.joinCode;

    const guest = await TestClient.connect(server.url);
    const guestWelcome = await guest.request(
        { type: 'HELLO', joinCode: code, playerMeta: { name: 'x'.repeat(500), avatarId: 1 } },
        ['WELCOME', 'ERROR']
    );
    const seat = guestWelcome.gameState.players[guestWelcome.yourPlayerId];
    assert.ok(seat.name.length <= 20, `name length ${seat.name.length} must be bounded`);

    await host.close();
    await guest.close();
});

test('an absurd turnTimerSeconds is clamped instead of overflowing setTimeout', async () => {
    // A delay past setTimeout's 32-bit range fires immediately, turning the turn
    // timer into a ~1ms advance/broadcast/DB-write loop for the room's lifetime.
    const { client, welcome } = await createGame(server.url, 'TimerHost', {
        turnTimerSeconds: Number.MAX_SAFE_INTEGER
    });
    assert.strictEqual(welcome.type, 'WELCOME');
    const configured = welcome.gameState.settings.turnTimerSeconds;
    assert.ok(configured <= 7200, `turnTimerSeconds ${configured} must be clamped`);

    // If the timer were looping, the room would be flooding us with snapshots.
    const flood = await client.settle(400);
    assert.ok(flood.length < 5, `expected a quiet room, saw ${flood.length} broadcasts: ${flood}`);

    await client.close();
});

test('the host cannot kick themselves into a hostless room', async () => {
    const { client: host, welcome } = await createGame(server.url, 'SelfKickHost');
    const hostId = welcome.yourPlayerId;
    const reply = await host.request(
        { type: 'KICK_PLAYER', targetPlayerId: hostId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(reply.type, 'ERROR');
    assert.strictEqual(reply.code, 'INVALID_DATA');
    await host.close();
});

test('kicking the combat main player clears the combat instead of stranding it', async () => {
    // Only the main player may end a combat, so kicking them left one that could
    // never be ended and that blocked COMBAT_START for everyone else.
    const { client: host, welcome } = await createGame(server.url, 'KickCombatHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'Victim');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);

    const started = await host.request(
        ev('COMBAT_START', hostId, { mainPlayerId: guestId }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(started.type, 'STATE_SNAPSHOT');
    assert.ok(started.gameState.combat, 'a combat must be active');

    const afterKick = await host.request(
        { type: 'KICK_PLAYER', targetPlayerId: guestId },
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(afterKick.type, 'STATE_SNAPSHOT');
    assert.strictEqual(afterKick.gameState.combat, null, 'the orphaned combat must be cleared');
    assert.ok(afterKick.gameState.turnPlayerId, 'turnPlayerId must never be left null');

    await host.close();
    await guest.close();
});

test('gameplay events are refused once the game is finished', async () => {
    const { client: host, welcome } = await createGame(server.url, 'FinishedHost');
    const hostId = welcome.yourPlayerId;
    const gameId = welcome.gameState.gameId;

    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    const finished = await host.request(
        { type: 'GAME_OVER', gameId, winnerId: null },
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(finished.type, 'STATE_SNAPSHOT', JSON.stringify(finished));
    assert.strictEqual(finished.gameState.phase, 'FINISHED');

    const refused = await host.request(
        ev('COMBAT_START', hostId, { mainPlayerId: hostId }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(refused.type, 'ERROR', 'a finished game must not accept gameplay events');
    await host.close();
});

test('PLAYER_ROLL computes RUN_AWAY success server-side and legacy COMBAT_DICE_ROLL is pruned', async () => {
    const { client: host, welcome } = await createGame(server.url, 'DiceHost');
    const hostId = welcome.yourPlayerId;
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);

    // Client tries to spoof success=true on a roll of 2; server must compute false (2 < 5).
    const failRoll = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'PLAYER_ROLL', eventId: 'roll-fail', actorId: hostId,
            timestamp: Date.now(), result: 2, purpose: 'RUN_AWAY', success: true
        }
    }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(failRoll.type, 'STATE_SNAPSHOT', JSON.stringify(failRoll));
    assert.strictEqual(failRoll.gameState.combat.lastDiceRoll.purpose, 'RUN_AWAY');
    assert.strictEqual(failRoll.gameState.combat.lastDiceRoll.result, 2);
    assert.strictEqual(failRoll.gameState.combat.lastDiceRoll.success, false, 'run away on 2 without bonus must fail');

    // Elf (+1) rolling 4 reaches 5 -> server must compute true.
    await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_RACE', eventId: 'roll-elf', actorId: hostId,
            targetPlayerId: hostId, timestamp: Date.now(), newRace: 'ELF'
        }
    }, ['EVENT_BROADCAST', 'STATE_SNAPSHOT']);
    const elfRoll = await host.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'PLAYER_ROLL', eventId: 'roll-pass', actorId: hostId,
            timestamp: Date.now(), result: 4, purpose: 'RUN_AWAY', success: false
        }
    }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(elfRoll.type, 'STATE_SNAPSHOT', JSON.stringify(elfRoll));
    assert.strictEqual(elfRoll.gameState.combat.lastDiceRoll.success, true, 'Elf +1 turns a 4 into a successful run away');

    // Legacy COMBAT_DICE_ROLL message must no longer produce COMBAT_DICE_ROLL_RESULT.
    host.drain();
    host.send({ type: 'COMBAT_DICE_ROLL', result: 6, purpose: 'RUN_AWAY', success: true });
    const leftover = await host.settle(200);
    assert.ok(
        leftover.every(t => t !== 'COMBAT_DICE_ROLL_RESULT'),
        'legacy COMBAT_DICE_ROLL should be pruned'
    );

    await host.close();
});

test('only the host can start the game, and only once', async () => {
    const { client: host, welcome } = await createGame(server.url, 'StartHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'StartGuest');
    const guestId = guestWelcome.yourPlayerId;

    await guest.settle();
    const refused = await guest.request(ev('GAME_START', guestId), ['ERROR', 'STATE_SNAPSHOT']);
    assert.strictEqual(refused.type, 'ERROR', 'a guest must not be able to start the game');
    assert.strictEqual(refused.code, 'FORBIDDEN');

    await host.settle();
    const started = await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(started.type, 'STATE_SNAPSHOT');
    assert.strictEqual(started.gameState.phase, 'IN_GAME');

    const again = await host.request(ev('GAME_START', hostId), ['ERROR', 'STATE_SNAPSHOT']);
    assert.strictEqual(again.type, 'ERROR', 'starting twice must be refused');
    assert.strictEqual(again.code, 'GAME_ALREADY_STARTED');

    await host.close();
    await guest.close();
});

// ─────────────────── Wave 1A hardening & protocol contracts ───────────────────

test('CREATE_GAME message works and omits pruned fields (races, classes, lastKnownIp)', async () => {
    const client = await TestClient.connect(server.url);
    const welcome = await client.request({
        type: 'CREATE_GAME',
        playerMeta: { name: 'ModernHost', avatarId: 1, gender: 'F' }
    }, ['WELCOME', 'ERROR']);

    assert.strictEqual(welcome.type, 'WELCOME', JSON.stringify(welcome));
    const state = welcome.gameState;
    assert.strictEqual('races' in state, false, 'legacy races catalog must be pruned');
    assert.strictEqual('classes' in state, false, 'legacy classes catalog must be pruned');
    for (const seat of Object.values(state.players)) {
        assert.strictEqual('lastKnownIp' in seat, false, 'lastKnownIp must never be serialized');
    }

    await client.close();
});

test('TRANSFER_HOST enforces host authority and transfers both hostId and originalHostId', async () => {
    const { client: host, welcome } = await createGame(server.url, 'XferHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'XferGuest');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);

    const denied = await guest.request(
        { type: 'TRANSFER_HOST', targetPlayerId: guestId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(denied.type, 'ERROR');
    assert.strictEqual(denied.code, 'PERMISSION_DENIED');

    const guestSnapPromise = guest.waitFor(['STATE_SNAPSHOT']);
    const transferred = await host.request(
        { type: 'TRANSFER_HOST', targetPlayerId: guestId },
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(transferred.type, 'STATE_SNAPSHOT', JSON.stringify(transferred));
    assert.strictEqual(transferred.gameState.hostId, guestId);
    assert.strictEqual(transferred.gameState.originalHostId, guestId);

    const guestSnap = await guestSnapPromise;
    assert.strictEqual(guestSnap.gameState.hostId, guestId, 'recipient client snapshot must update hostId');
    assert.strictEqual(guestSnap.gameState.originalHostId, guestId);
    const oldHostDenied = await host.request(
        { type: 'KICK_PLAYER', targetPlayerId: guestId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(oldHostDenied.type, 'ERROR');
    assert.strictEqual(oldHostDenied.code, 'PERMISSION_DENIED');

    const startedByNewHost = await guest.request(ev('GAME_START', guestId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(startedByNewHost.type, 'STATE_SNAPSHOT');
    assert.strictEqual(startedByNewHost.gameState.phase, 'IN_GAME');

    await guest.close();
    await host.close();
});

test('UPDATE_PROFILE with password requires currentPassword and revokes prior JWTs via token_version', async () => {
    const { client: regClient, reply: reg } = await registerUser(server.url, 'pw_rotate_user', 'initial-secret-1');
    assert.strictEqual(reg.type, 'AUTH_SUCCESS', JSON.stringify(reg));
    const oldToken = reg.token;
    const userId = reg.user.id;

    // Missing currentPassword is rejected.
    const missingPw = await regClient.request({
        type: 'UPDATE_PROFILE',
        userId,
        password: 'new-secret-2'
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(missingPw.type, 'ERROR');
    assert.strictEqual(missingPw.code, 'INVALID_DATA');

    // Wrong currentPassword is rejected.
    const wrongPw = await regClient.request({
        type: 'UPDATE_PROFILE',
        userId,
        currentPassword: 'wrong-password',
        password: 'new-secret-2'
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(wrongPw.type, 'ERROR');
    assert.strictEqual(wrongPw.code, 'AUTH_FAILED');

    // Valid currentPassword rotates token_version and returns a fresh token.
    const updated = await regClient.request({
        type: 'UPDATE_PROFILE',
        userId,
        currentPassword: 'initial-secret-1',
        password: 'new-secret-2'
    }, ['PROFILE_UPDATED', 'ERROR']);
    assert.strictEqual(updated.type, 'PROFILE_UPDATED', JSON.stringify(updated));
    assert.ok(updated.token, 'password change must return a rotated JWT');

    await regClient.close();

    // Old token must fail verification because token_version incremented.
    const staleClient = await TestClient.connect(server.url);
    const staleReply = await staleClient.request(
        { type: 'LOGIN_WITH_TOKEN', token: oldToken },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(staleReply.type, 'ERROR');
    assert.strictEqual(staleReply.code, 'AUTH_FAILED');
    await staleClient.close();

    // New token succeeds.
    const freshClient = await TestClient.connect(server.url);
    const freshReply = await freshClient.request(
        { type: 'LOGIN_WITH_TOKEN', token: updated.token },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(freshReply.type, 'AUTH_SUCCESS', JSON.stringify(freshReply));
    await freshClient.close();
});

test('DELETE_ACCOUNT requires password, removes credentials, and preserves anonymized history', async () => {
    const victim = await registerUser(server.url, 'del_victim', 'delete-secret-1');
    const friend = await registerUser(server.url, 'del_friend', 'friend-secret-1');

    const welcome = await victim.client.request({
        type: 'CREATE_GAME',
        playerMeta: { name: 'DelVictim', avatarId: 0, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    const gameId = welcome.gameState.gameId;
    const victimPlayerId = welcome.yourPlayerId;

    await friend.client.request({
        type: 'HELLO',
        joinCode: welcome.gameState.joinCode,
        playerMeta: { name: 'DelFriend', avatarId: 1, gender: 'F' }
    }, ['WELCOME', 'ERROR']);
    await Promise.all([victim.client.settle(), friend.client.settle()]);

    await victim.client.request(ev('GAME_START', victimPlayerId), ['STATE_SNAPSHOT', 'ERROR']);
    await winCombatToLevel10(victim.client, victimPlayerId, 'delwin');

    victim.client.drain();
    victim.client.send({ type: 'GAME_OVER', gameId, winnerId: victimPlayerId });
    await victim.client.waitFor(['STATE_SNAPSHOT']);
    await pollUntil(async () => {
        const res = await victim.client.request(
            { type: 'GET_HISTORY', userId: victim.reply.user.id },
            ['HISTORY_RESULT', 'ERROR']
        );
        return res.type === 'HISTORY_RESULT' && res.games.some(g => g.id === gameId);
    });

    // Unauthenticated client cannot delete an account.
    const anon = await TestClient.connect(server.url);
    const unauth = await anon.request({ type: 'DELETE_ACCOUNT', password: 'delete-secret-1' }, ['ACCOUNT_DELETED', 'ERROR']);
    assert.strictEqual(unauth.type, 'ERROR');
    assert.strictEqual(unauth.code, 'UNAUTHORIZED');
    await anon.close();

    // Wrong password fails.
    const wrong = await victim.client.request(
        { type: 'DELETE_ACCOUNT', password: 'wrong-password' },
        ['ACCOUNT_DELETED', 'ERROR']
    );
    assert.strictEqual(wrong.type, 'ERROR');
    assert.strictEqual(wrong.code, 'AUTH_FAILED');

    // Correct password deletes account.
    const deleted = await victim.client.request(
        { type: 'DELETE_ACCOUNT', password: 'delete-secret-1' },
        ['ACCOUNT_DELETED', 'ERROR']
    );
    assert.strictEqual(deleted.type, 'ACCOUNT_DELETED', JSON.stringify(deleted));

    // Deleted account cannot log in again.
    const retry = await TestClient.connect(server.url);
    const loginFail = await retry.request(
        { type: 'LOGIN', email: 'del_victim@example.com', password: 'delete-secret-1' },
        ['AUTH_SUCCESS', 'ERROR']
    );
    assert.strictEqual(loginFail.type, 'ERROR');
    assert.strictEqual(loginFail.code, 'AUTH_FAILED');
    await retry.close();

    // Friend's history still cleanly loads the anonymized game without crashing FK joins.
    const hist = await friend.client.request(
        { type: 'GET_HISTORY', userId: friend.reply.user.id },
        ['HISTORY_RESULT', 'ERROR']
    );
    assert.strictEqual(hist.type, 'HISTORY_RESULT', JSON.stringify(hist));
    assert.ok(hist.games.some(g => g.id === gameId), 'anonymized game should remain in participant history');

    await victim.client.close();
    await friend.client.close();
});

test('GAME_OVER and GAME_END anti-farming rejects lobby, solo, low-level, and non-combat-10 wins', async () => {
    const { client: host, welcome } = await createGame(server.url, 'AntiFarmHost');
    const hostId = welcome.yourPlayerId;
    const gameId = welcome.gameState.gameId;
    const code = welcome.gameState.joinCode;
    const { client: guest } = await joinGame(server.url, code, 'AntiFarmGuest');

    await Promise.all([host.settle(), guest.settle()]);

    // 1) Lobby room cannot claim a winner.
    const inLobby = await host.request(
        { type: 'GAME_OVER', gameId, winnerId: hostId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(inLobby.type, 'ERROR');
    assert.strictEqual(inLobby.code, 'INVALID_DATA');

    // Start game -> 2 players IN_GAME.
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);

    // 2) Level 1 winner is rejected via GAME_OVER and GAME_END.
    const lowLevelOver = await host.request(
        { type: 'GAME_OVER', gameId, winnerId: hostId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(lowLevelOver.type, 'ERROR');
    assert.strictEqual(lowLevelOver.code, 'INVALID_DATA');

    const lowLevelEnd = await host.request(
        ev('GAME_END', hostId, { winnerId: hostId }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(lowLevelEnd.type, 'ERROR');
    assert.strictEqual(lowLevelEnd.code, 'INVALID_DATA');

    // 3) Solo IN_GAME room cannot claim a winner even at maxLevel (test with allowLevelTenOverride in a solo room).
    const solo = await createGame(server.url, 'SoloFarmHost', { allowLevelTenOverride: true });
    const soloId = solo.welcome.yourPlayerId;
    await solo.client.request(ev('GAME_START', soloId), ['STATE_SNAPSHOT', 'ERROR']);
    await solo.client.request({
        type: 'EVENT_REQUEST',
        event: {
            type: 'SET_LEVEL', eventId: 'solo-10', actorId: soloId,
            targetPlayerId: soloId, timestamp: Date.now(), level: 10
        }
    }, ['EVENT_BROADCAST', 'ERROR']);
    const soloWinRefused = await solo.client.request(
        { type: 'GAME_OVER', gameId: solo.welcome.gameState.gameId, winnerId: soloId },
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(soloWinRefused.type, 'ERROR');
    assert.strictEqual(soloWinRefused.code, 'INVALID_DATA');

    // 4) Ending early with winnerId: null is permitted.
    const earlyEnd = await solo.client.request(
        { type: 'GAME_OVER', gameId: solo.welcome.gameState.gameId, winnerId: null },
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(earlyEnd.type, 'STATE_SNAPSHOT');
    assert.strictEqual(earlyEnd.gameState.phase, 'FINISHED');

    await solo.client.close();
    await host.close();
    await guest.close();
});

test('COMBAT_END enforces non-empty monsters, expectedSeq CONFLICT, serverResult, and ESCAPE gates', async () => {
    const { client: host, welcome } = await createGame(server.url, 'SecCombatHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'SecCombatGuest');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);

    // 0 monsters WIN and LOSE are both rejected (QA-08).
    const emptyWin = await host.request(
        ev('COMBAT_END', hostId, { outcome: 'WIN' }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(emptyWin.type, 'ERROR');
    assert.strictEqual(emptyWin.code, 'INVALID_DATA');

    const emptyLose = await host.request(
        ev('COMBAT_END', hostId, { outcome: 'LOSE' }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(emptyLose.type, 'ERROR');
    assert.strictEqual(emptyLose.code, 'INVALID_DATA');

    // Add level-10 monster (Host power 1 vs Monster 10).
    const added = await host.request(
        ev('COMBAT_ADD_MONSTER', hostId, {
            monster: { id: 'boss-10', name: 'Dragón', baseLevel: 10, levels: 2, treasures: 3 }
        }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const currentSeq = added.seq;

    // Stale expectedSeq returns CONFLICT + fresh STATE_SNAPSHOT.
    host.drain();
    host.send(ev('COMBAT_END', hostId, { outcome: 'WIN', expectedSeq: currentSeq - 1 }));
    const conflictErr = await host.waitFor(['ERROR']);
    assert.strictEqual(conflictErr.code, 'CONFLICT');
    const resyncSnap = await host.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(resyncSnap.seq, currentSeq);

    // Add helper so guest can touch combat, then verify non-main/non-host helper still gets FORBIDDEN on cancelled:true.
    const withHelper = await host.request(
        ev('COMBAT_ADD_HELPER', hostId, { helperId: guestId }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const latestSeq = withHelper.seq;
    await guest.settle();
    const cancelDenied = await guest.request(
        ev('COMBAT_END', guestId, { outcome: 'ESCAPE', cancelled: true }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(cancelDenied.type, 'ERROR');
    assert.strictEqual(cancelDenied.code, 'FORBIDDEN');

    // ESCAPE with monsters without successful RUN_AWAY roll is refused.
    const escapeNoRoll = await host.request(
        ev('COMBAT_END', hostId, { outcome: 'ESCAPE' }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(escapeNoRoll.type, 'ERROR');
    assert.strictEqual(escapeNoRoll.code, 'INVALID_DATA');

    // Client forging WIN + 9 levels/treasures gets overridden to LOSE with 0 rewards (Hero 2 vs Monster 10).
    const forcedLose = await host.request(
        ev('COMBAT_END', hostId, {
            outcome: 'WIN', expectedSeq: latestSeq,
            levelsGained: 9, treasuresGained: 9, helperLevelsGained: 5
        }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(forcedLose.type, 'STATE_SNAPSHOT');
    assert.strictEqual(forcedLose.gameState.combat, null);
    assert.strictEqual(forcedLose.gameState.players[hostId].level, 1);
    assert.strictEqual(forcedLose.gameState.players[hostId].treasures, 0);

    // Passing RUN_AWAY roll (5+) permits ESCAPE.
    await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(
        ev('COMBAT_ADD_MONSTER', hostId, { monster: { id: 'rat-run', name: 'Rata', baseLevel: 5 } }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    await host.request(
        ev('PLAYER_ROLL', hostId, { result: 5, purpose: 'RUN_AWAY' }),
        ['EVENT_BROADCAST', 'STATE_SNAPSHOT']
    );
    const escaped = await host.request(
        ev('COMBAT_END', hostId, { outcome: 'ESCAPE' }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(escaped.type, 'STATE_SNAPSHOT');
    assert.strictEqual(escaped.gameState.combat, null);

    await guest.close();
    await host.close();
});

test('IN_GAME phase guard rejects LOBBY mutations and HELLO rejects empty names, kicked players, and finished games', async () => {
    const { client: host, welcome } = await createGame(server.url, 'GuardHost');
    const hostId = welcome.yourPlayerId;
    const gameId = welcome.gameState.gameId;
    const code = welcome.gameState.joinCode;

    // LOBBY guards:
    const incInLobby = await host.request(
        ev('INC_LEVEL', hostId, { targetPlayerId: hostId, amount: 1 }),
        ['ERROR', 'EVENT_BROADCAST']
    );
    assert.strictEqual(incInLobby.type, 'ERROR');
    assert.strictEqual(incInLobby.code, 'INVALID_DATA');

    const combatInLobby = await host.request(
        ev('COMBAT_START', hostId, { mainPlayerId: hostId }),
        ['ERROR', 'STATE_SNAPSHOT']
    );
    assert.strictEqual(combatInLobby.type, 'ERROR');
    assert.strictEqual(combatInLobby.code, 'INVALID_DATA');

    // Empty name on HELLO is rejected.
    const blankJoin = await joinGame(server.url, code, '   ');
    assert.strictEqual(blankJoin.welcome.type, 'ERROR');
    assert.strictEqual(blankJoin.welcome.code, 'INVALID_DATA');
    await blankJoin.client.close();

    // Kick guest (authenticated user so kickedUserIds + kickedIps both trigger) and block rejoin.
    const guestAuth = await registerUser(server.url, 'kicked_acct');
    const guestJoin = await guestAuth.client.request({
        type: 'HELLO',
        joinCode: code,
        playerMeta: { name: 'ToKick', avatarId: 0, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    assert.strictEqual(guestJoin.type, 'WELCOME');

    await host.settle();
    const kickedSnap = await host.request({ type: 'KICK_PLAYER', targetPlayerId: guestJoin.yourPlayerId }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(kickedSnap.type, 'STATE_SNAPSHOT');
    await guestAuth.client.close();

    // Kicked user attempting to rejoin on a fresh authenticated connection is blocked.
    const rejoinClient = await TestClient.connect(server.url);
    await rejoinClient.request({ type: 'LOGIN_WITH_TOKEN', token: guestAuth.reply.token }, ['AUTH_SUCCESS', 'ERROR']);
    const rejoinBlocked = await rejoinClient.request({
        type: 'HELLO',
        joinCode: code,
        playerMeta: { name: 'ToKick', avatarId: 0, gender: 'M' }
    }, ['WELCOME', 'ERROR']);
    assert.strictEqual(rejoinBlocked.type, 'ERROR');
    assert.strictEqual(rejoinBlocked.code, 'FORBIDDEN');
    await rejoinClient.close();

    // Start and finish the game early, then reject HELLO into finished/removed room.
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request({ type: 'GAME_OVER', gameId, winnerId: null }, ['STATE_SNAPSHOT', 'ERROR']);

    const lateJoin = await joinGame(server.url, code, 'LatePlayer');
    assert.strictEqual(lateJoin.welcome.type, 'ERROR');
    assert.ok(['INVALID_JOIN_CODE', 'GAME_FINISHED'].includes(lateJoin.welcome.code));

    await lateJoin.client.close();
    await host.close();
});

test('helper disconnect clears combat helperPlayerId and host can END_TURN when turn holder is disconnected', async () => {
    const { client: host, welcome } = await createGame(server.url, 'DiscHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'DiscHelper');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);

    // Pass turn to guest, then start combat with guest as helper.
    const passed = await host.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(passed.gameState.turnPlayerId, guestId);

    await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);
    const withHelper = await host.request(
        ev('COMBAT_ADD_HELPER', hostId, { helperId: guestId }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(withHelper.gameState.combat.helperPlayerId, guestId);

    // Disconnecting helper clears helperPlayerId in the broadcast snapshot.
    host.drain();
    await guest.close();
    const afterDisconnect = await host.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(afterDisconnect.gameState.players[guestId].isConnected, false);
    assert.strictEqual(afterDisconnect.gameState.combat.helperPlayerId, null);

    // Host can advance turn past the disconnected turn holder.
    const passedByHost = await host.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(passedByHost.type, 'STATE_SNAPSHOT', JSON.stringify(passedByHost));
    assert.strictEqual(passedByHost.gameState.turnPlayerId, hostId);

    await host.close();
});

test('CATALOG_ADD supports 0 treasures and CATALOG_SEARCH omits createdBy and filters by expansion', async () => {
    const { client, reply } = await registerUser(server.url, 'cat_fts_user');
    assert.strictEqual(reply.type, 'AUTH_SUCCESS');

    const added = await client.request({
        type: 'CATALOG_ADD',
        monster: {
            name: 'Goblin Pacifista',
            level: 2,
            treasures: 0,
            modifier: -2,
            expansion: 'Wave1APack',
            badStuff: 'Pierdes tu sombrero'
        }
    }, ['CATALOG_ADD_SUCCESS', 'ERROR']);
    assert.strictEqual(added.type, 'CATALOG_ADD_SUCCESS', JSON.stringify(added));
    assert.strictEqual(added.monster.treasures, 0, '0 treasures must be preserved');
    assert.strictEqual('createdBy' in added.monster, false);

    const search = await client.request({
        type: 'CATALOG_SEARCH',
        query: 'Goblin Pacifista',
        expansion: 'Wave1APack'
    }, ['CATALOG_SEARCH_RESULT', 'ERROR']);
    assert.strictEqual(search.type, 'CATALOG_SEARCH_RESULT');
    assert.ok(search.results.length >= 1, 'FTS5 search must find the added monster');
    const hit = search.results.find(m => m.name === 'Goblin Pacifista');
    assert.ok(hit);
    assert.strictEqual(hit.treasures, 0);
    assert.strictEqual('createdBy' in hit, false, 'createdBy must never leak to catalog search');
    assert.strictEqual('created_by' in hit, false);

    await client.close();
});

test('HTTP endpoints (/health, /api/monsters, static /sw.js ETag/304/br) and WS Origin rejection work', async () => {
    const httpBase = server.url.replace(/^ws/, 'http');

    // /health
    const healthRes = await fetch(`${httpBase}/health`);
    assert.strictEqual(healthRes.status, 200);
    const healthBody = await healthRes.json();
    assert.strictEqual(healthBody.status, 'ok');

    // /api/monsters with pagination & expansion params
    const monRes = await fetch(`${httpBase}/api/monsters?q=Dragon&limit=5&offset=0`);
    assert.strictEqual(monRes.status, 200);
    const monList = await monRes.json();
    assert.ok(Array.isArray(monList));
    assert.ok(monList.length <= 5);
    for (const m of monList) {
        assert.strictEqual('createdBy' in m, false);
    }

    // Static /index.html ETag + cache headers + conditional 304 + brotli negotiation
    const htmlHeaders = await new Promise((resolve, reject) => {
        const url = new URL(`${httpBase}/index.html`);
        const req = http.request(url, {
            method: 'GET',
            headers: { 'Accept-Encoding': 'br, gzip' }
        }, res => {
            res.resume();
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers }));
        });
        req.on('error', reject);
        req.end();
    });
    assert.strictEqual(htmlHeaders.status, 200);
    assert.ok(htmlHeaders.headers.etag, 'static asset must include ETag');
    assert.strictEqual(htmlHeaders.headers['cache-control'], 'no-cache');
    assert.strictEqual(htmlHeaders.headers['content-encoding'], 'br');
    assert.match(String(htmlHeaders.headers.vary || ''), /Accept-Encoding/i);

    const notModifiedStatus = await new Promise((resolve, reject) => {
        const url = new URL(`${httpBase}/index.html`);
        const req = http.request(url, {
            method: 'GET',
            headers: {
                'Accept-Encoding': 'br, gzip',
                'If-None-Match': String(htmlHeaders.headers.etag)
            }
        }, res => {
            res.resume();
            res.on('end', () => resolve(res.statusCode));
        });
        req.on('error', reject);
        req.end();
    });
    assert.strictEqual(notModifiedStatus, 304);

    // Disallowed WebSocket Origin rejected with HTTP 403 at handshake.
    const wsStatus = await new Promise((resolve, reject) => {
        const ws = new WebSocket(server.url, { headers: { Origin: 'https://evil.example' } });
        ws.once('unexpected-response', (_req, res) => {
            res.resume();
            resolve(res.statusCode);
        });
        ws.once('open', () => {
            ws.close();
            reject(new Error('Expected disallowed Origin to be rejected'));
        });
        ws.once('error', () => {
            // Expected after unexpected-response on ws; ignore if already resolved.
        });
    });
    assert.strictEqual(wsStatus, 403);
});

test('helper disconnect during active combat cleans up helperPlayerId and broadcasts updated snapshot (QA-04)', async () => {
    const { client: host, welcome } = await createGame(server.url, 'CombatDiscHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: helper, welcome: helperWelcome } = await joinGame(server.url, code, 'CombatDiscHelper');
    const helperId = helperWelcome.yourPlayerId;

    await Promise.all([host.settle(), helper.settle()]);
    await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);
    await host.request(
        ev('COMBAT_ADD_MONSTER', hostId, {
            monster: { id: 'disc-mon', name: 'Gargoyle', baseLevel: 6, levels: 1, treasures: 2 }
        }),
        ['STATE_SNAPSHOT', 'ERROR']
    );

    const withHelper = await host.request(
        ev('COMBAT_ADD_HELPER', hostId, { helperId }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(withHelper.gameState.combat.helperPlayerId, helperId);

    host.drain();
    await helper.close();

    // Server broadcasts PLAYER_STATUS(isConnected: false) plus STATE_SNAPSHOT clearing helperPlayerId.
    const statusMsg = await host.waitFor(['PLAYER_STATUS']);
    assert.strictEqual(statusMsg.playerId, helperId);
    assert.strictEqual(statusMsg.isConnected, false);

    const snap = await host.waitFor(['STATE_SNAPSHOT']);
    assert.ok(snap.gameState.combat, 'active combat must remain open for main player');
    assert.strictEqual(snap.gameState.combat.helperPlayerId, null, 'disconnected helper must be cleared');

    await host.close();
});

test('kicking turnPlayerId while another peer is offline falls back safely and reconnect restores turn cleanly (QA-04)', async () => {
    const { client: host, welcome } = await createGame(server.url, 'KickTurnHost');
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guestA, welcome: welcomeA } = await joinGame(server.url, code, 'KickTurnGuestA');
    const guestAId = welcomeA.yourPlayerId;
    const { client: guestB, welcome: welcomeB } = await joinGame(server.url, code, 'KickTurnGuestB');
    const guestBId = welcomeB.yourPlayerId;

    await Promise.all([host.settle(), guestA.settle(), guestB.settle()]);

    // Deterministic roll order so guestA starts the turn: guestA=6, guestB=4, host=2.
    await host.request(ev('PLAYER_ROLL', hostId, { result: 2 }), ['STATE_SNAPSHOT', 'ERROR']);
    await guestA.request(ev('PLAYER_ROLL', guestAId, { result: 6 }), ['STATE_SNAPSHOT', 'ERROR']);
    await guestB.request(ev('PLAYER_ROLL', guestBId, { result: 4 }), ['STATE_SNAPSHOT', 'ERROR']);
    await Promise.all([host.settle(), guestA.settle(), guestB.settle()]);

    const started = await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(started.type, 'STATE_SNAPSHOT', JSON.stringify(started));
    assert.strictEqual(started.gameState.turnPlayerId, guestAId);

    // Take guestB offline before kicking the current turn owner (guestA).
    host.drain();
    await guestB.close();
    const offlineStatus = await host.waitFor(['PLAYER_STATUS']);
    assert.strictEqual(offlineStatus.playerId, guestBId);
    assert.strictEqual(offlineStatus.isConnected, false);

    // Kick guestA (turnPlayerId) -> skips offline guestB and falls back safely to connected host (never null).
    const kickedSnap = await host.request(
        { type: 'KICK_PLAYER', targetPlayerId: guestAId },
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(kickedSnap.type, 'STATE_SNAPSHOT', JSON.stringify(kickedSnap));
    assert.strictEqual(kickedSnap.gameState.turnPlayerId, hostId, 'turnPlayerId must fall back to connected host');
    assert.ok(!kickedSnap.gameState.players[guestAId], 'kicked player must be removed');

    // Host remains active as turnPlayerId even with guestB offline and can execute turns.
    const soloTurnSnap = await host.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(soloTurnSnap.type, 'STATE_SNAPSHOT');
    assert.strictEqual(soloTurnSnap.gameState.turnPlayerId, hostId);

    await host.close();
    await guestA.close();

    // Also verify an offline peer reconnecting via reconnectToken receives its turn cleanly again via END_TURN.
    const r2 = await createGame(server.url, 'ReconnectTurnHost');
    const r2HostId = r2.welcome.yourPlayerId;
    const r2Code = r2.welcome.gameState.joinCode;
    const p1 = await joinGame(server.url, r2Code, 'Peer1');
    const p1Id = p1.welcome.yourPlayerId;
    const p2 = await joinGame(server.url, r2Code, 'Peer2');
    const p2Id = p2.welcome.yourPlayerId;
    const p2Token = p2.welcome.reconnectToken;

    await Promise.all([r2.client.settle(), p1.client.settle(), p2.client.settle()]);
    await r2.client.request(ev('PLAYER_ROLL', r2HostId, { result: 4 }), ['STATE_SNAPSHOT', 'ERROR']);
    await p1.client.request(ev('PLAYER_ROLL', p1Id, { result: 2 }), ['STATE_SNAPSHOT', 'ERROR']);
    await p2.client.request(ev('PLAYER_ROLL', p2Id, { result: 6 }), ['STATE_SNAPSHOT', 'ERROR']);
    await Promise.all([r2.client.settle(), p1.client.settle(), p2.client.settle()]);

    const r2Start = await r2.client.request(ev('GAME_START', r2HostId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(r2Start.gameState.turnPlayerId, p2Id);

    r2.client.drain();
    await p2.client.close();
    await r2.client.waitFor(['PLAYER_STATUS']);

    // While p2 is offline, host advances past disconnected p2 onto host.
    const skippedOffline = await r2.client.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(skippedOffline.gameState.turnPlayerId, r2HostId);

    // Reconnect p2 with reconnectToken, then rotate turns cleanly to restore turn to p2.
    const { client: p2Back, welcome: p2Welcome } = await joinGame(server.url, r2Code, 'Peer2', {
        playerMeta: { name: 'Peer2', avatarId: 0, gender: 'F', playerId: p2Id },
        reconnectToken: p2Token
    });
    assert.strictEqual(p2Welcome.type, 'WELCOME', JSON.stringify(p2Welcome));
    assert.strictEqual(p2Welcome.yourPlayerId, p2Id);
    await Promise.all([r2.client.settle(), p1.client.settle(), p2Back.settle()]);

    const toP1 = await r2.client.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(toP1.gameState.turnPlayerId, p1Id);
    const p1Notified = await p1.client.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(p1Notified.gameState.turnPlayerId, p1Id);

    const toP2 = await p1.client.request({ type: 'END_TURN' }, ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(toP2.gameState.turnPlayerId, p2Id, 'reconnected peer must cleanly receive turn');

    await p2Back.close();
    await p1.client.close();
    await r2.client.close();
});

test('level 9->10 requires combat win and only freezes turn timer once reachedMaxLevelViaCombat is true (QA-03)', async () => {
    const { client: host, welcome } = await createGame(server.url, 'WinGateHost', {
        turnTimerSeconds: 60
    });
    const hostId = welcome.yourPlayerId;
    const code = welcome.gameState.joinCode;
    const { client: guest, welcome: guestWelcome } = await joinGame(server.url, code, 'WinGateGuest');
    const guestId = guestWelcome.yourPlayerId;

    await Promise.all([host.settle(), guest.settle()]);

    const started = await host.request(ev('GAME_START', hostId), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(started.type, 'STATE_SNAPSHOT');
    assert.ok(Number.isFinite(started.gameState.turnEndsAt) && started.gameState.turnEndsAt > Date.now());

    // Manual SET_LEVEL to 10 clamps at 9, keeps reachedMaxLevelViaCombat false, and does NOT freeze turnEndsAt.
    const manualSet = await host.request(
        {
            type: 'EVENT_REQUEST',
            event: {
                type: 'SET_LEVEL',
                eventId: 'qa03-set-10',
                actorId: hostId,
                targetPlayerId: hostId,
                timestamp: Date.now(),
                level: 10
            }
        },
        ['EVENT_BROADCAST', 'ERROR']
    );
    assert.strictEqual(manualSet.type, 'EVENT_BROADCAST');
    assert.strictEqual(manualSet.event.level, 9, 'manual SET_LEVEL cannot pass level 9 without combat');

    const preCombatSnap = await host.request(ev('COMBAT_START', hostId, { mainPlayerId: hostId }), ['STATE_SNAPSHOT', 'ERROR']);
    assert.strictEqual(preCombatSnap.gameState.players[hostId].level, 9);
    assert.strictEqual(preCombatSnap.gameState.players[hostId].reachedMaxLevelViaCombat, false);
    assert.ok(
        preCombatSnap.gameState.turnEndsAt !== null && preCombatSnap.gameState.turnEndsAt > Date.now(),
        'turn timer must remain active when level is capped at 9'
    );

    // Winning combat from 9 -> 10 sets reachedMaxLevelViaCombat === true and freezes turnEndsAt === null.
    await host.request(
        ev('COMBAT_ADD_MONSTER', hostId, {
            monster: { id: 'qa03-boss', name: 'Potted Plant', baseLevel: 1, levels: 1, treasures: 1 }
        }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    const combatWinSnap = await host.request(
        ev('COMBAT_END', hostId, { outcome: 'WIN', levelsGained: 1, treasuresGained: 1 }),
        ['STATE_SNAPSHOT', 'ERROR']
    );
    assert.strictEqual(combatWinSnap.type, 'STATE_SNAPSHOT');
    assert.strictEqual(combatWinSnap.gameState.players[hostId].level, 10);
    assert.strictEqual(combatWinSnap.gameState.players[hostId].reachedMaxLevelViaCombat, true);
    assert.strictEqual(combatWinSnap.gameState.turnEndsAt, null, 'pending winner confirmation must freeze turn timer');

    // Dropping back below 10 clears reachedMaxLevelViaCombat and resumes the turn timer.
    host.drain();
    host.send(ev('DEC_LEVEL', hostId, { targetPlayerId: hostId, amount: 1 }));
    await host.waitFor(['EVENT_BROADCAST']);
    const resumedSnap = await host.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(resumedSnap.gameState.players[hostId].level, 9);
    assert.strictEqual(resumedSnap.gameState.players[hostId].reachedMaxLevelViaCombat, false);
    assert.ok(
        resumedSnap.gameState.turnEndsAt !== null && resumedSnap.gameState.turnEndsAt > Date.now(),
        'dropping below level 10 must clear pending winner lock and re-arm turnEndsAt'
    );

    // Guest remains unaffected below 10.
    assert.strictEqual(resumedSnap.gameState.players[guestId].reachedMaxLevelViaCombat, false);

    await guest.close();
    await host.close();
});
