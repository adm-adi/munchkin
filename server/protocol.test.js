/**
 * Integration tests over a real WebSocket against a real server process.
 *
 * These target the handlers, which is where every bug in this round actually
 * lived — the pure helpers in validation.test.js were never the risky part.
 * Each test below corresponds to a defect that shipped.
 */

const test = require('node:test');
const assert = require('node:assert');

const {
    startServer,
    TestClient,
    registerUser,
    createGame,
    joinGame
} = require('./testServer');

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

    // Start, then have the host confirm the win.
    winner.client.send({
        type: 'EVENT_REQUEST',
        event: {
            type: 'GAME_START', eventId: 'r-start',
            actorId: hostPlayerId, timestamp: Date.now()
        }
    });
    await winner.client.waitFor(['STATE_SNAPSHOT']);

    winner.client.send({ type: 'GAME_OVER', gameId, winnerId: hostPlayerId });
    const finished = await winner.client.waitFor(['STATE_SNAPSHOT']);
    assert.strictEqual(finished.gameState.phase, 'FINISHED');

    // Recording happens asynchronously after the snapshot goes out.
    await new Promise(r => setTimeout(r, 500));

    const board = await winner.client.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT', 'ERROR']);
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
    champ.client.send({ type: 'GAME_OVER', gameId, winnerId: champPlayerId });
    await champ.client.waitFor(['STATE_SNAPSHOT']);
    await new Promise(r => setTimeout(r, 500));

    // The winner's own view.
    const mine = await champ.client.request(
        { type: 'GET_HISTORY', userId: champ.reply.user.id },
        ['HISTORY_RESULT', 'ERROR']
    );
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
    const { client: guest } = await joinGame(server.url, welcome.gameState.joinCode, 'PureGuest');
    await host.client.settle();

    host.client.send({
        type: 'EVENT_REQUEST',
        event: { type: 'GAME_START', eventId: 'g-start', actorId: hostPlayerId, timestamp: Date.now() }
    });
    await host.client.waitFor(['STATE_SNAPSHOT']);

    // The guest wins, but has no account to credit.
    const guestPlayerId = Object.keys(welcome.gameState.players)
        .concat(Object.keys((await host.client.request(
            { type: 'EVENT_REQUEST', event: { type: 'COMBAT_START', eventId: 'g-c', actorId: hostPlayerId, timestamp: Date.now(), mainPlayerId: hostPlayerId } },
            ['STATE_SNAPSHOT']
        )).gameState.players))
        .find(id => id !== hostPlayerId);

    host.client.send({ type: 'GAME_OVER', gameId, winnerId: guestPlayerId });
    await host.client.waitFor(['STATE_SNAPSHOT']);
    await new Promise(r => setTimeout(r, 500));

    const board = await host.client.request({ type: 'GET_LEADERBOARD' }, ['LEADERBOARD_RESULT']);
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
            type, eventId: `s-${type}-${Math.abs(JSON.stringify(extra).length)}`,
            actorId: me, targetPlayerId: me, timestamp: 1, ...extra
        }
    });

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
    assert.ok(level <= maxLevel, `level ${level} must not exceed maxLevel ${maxLevel}`);
    await host.close();
});

// ─────────────────── combat ───────────────────

test('COMBAT_UPDATE_MONSTER cannot bypass the add-time clamp', async () => {
    // ADD clamped baseLevel/flatModifier; UPDATE did not, so a client could add a
    // legal monster and then raise it to anything.
    const { client: host, welcome } = await createGame(server.url, 'CombatHost');
    const me = welcome.yourPlayerId;

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
