/**
 * Unit, DB/handler, and WebSocket tests for server/authManager.js (QA-09).
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert');
const { test, describe, before, after } = require('node:test');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'munchkin-auth-test-'));
process.env.MUNCHKIN_DB_PATH = path.join(tempDir, 'auth-test.db');
process.env.MUNCHKIN_LOG_DIR = path.join(tempDir, 'logs');
process.env.MUNCHKIN_REGISTER_LIMIT = '100';

const db = require('./db');
const { createAuthManager } = require('./authManager');
const { startServer, registerUser, TestClient } = require('./testServer');

const JWT_SECRET = '0123456789abcdef0123456789abcdef-test-secret';
const noopLogger = { info() {}, warn() {}, error() {}, debug() {} };

/**
 * Signs an arbitrary JWT header/payload so verifyToken edge cases (alg, exp, tampering)
 * can be exercised directly without bypassing HMAC format.
 * @param {Record<string, any>} header
 * @param {Record<string, any>} payload
 * @param {string} [secret]
 */
function signRawJwt(header, payload, secret = JWT_SECRET) {
    const encodedHeader = Buffer.from(JSON.stringify(header)).toString('base64url');
    const encodedPayload = Buffer.from(JSON.stringify(payload)).toString('base64url');
    const signature = crypto
        .createHmac('sha256', secret)
        .update(`${encodedHeader}.${encodedPayload}`)
        .digest('base64url');
    return `${encodedHeader}.${encodedPayload}.${signature}`;
}

/**
 * Queries a single SQLite row using callback-based sqlite3.Database.
 * @param {string} sql
 * @param {any[]} [params]
 * @returns {Promise<any>}
 */
function dbGet(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.db.get(sql, params, (err, row) => {
            if (err) reject(err);
            else resolve(row || null);
        });
    });
}

/**
 * Queries all SQLite rows matching the statement.
 * @param {string} sql
 * @param {any[]} [params]
 * @returns {Promise<any[]>}
 */
function dbAll(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.db.all(sql, params, (err, rows) => {
            if (err) reject(err);
            else resolve(rows || []);
        });
    });
}

/**
 * Helper binding a shared authManager instance (preserving rate-limit & token state)
 * and dispatching one handler call awaiting the response callback.
 */
function createBoundInvoker() {
    /** @type {((res: any) => void) | null} */
    let pendingResolve = null;

    const auth = createAuthManager({
        db,
        logger: noopLogger,
        jwtSecret: JWT_SECRET,
        jwtExpirySeconds: 3600,
        safeSend: (_ws, payload) => {
            if (pendingResolve) {
                const resolve = pendingResolve;
                pendingResolve = null;
                resolve({ kind: 'json', payload });
            }
        },
        sendError: (_ws, code, errorMessage) => {
            if (pendingResolve) {
                const resolve = pendingResolve;
                pendingResolve = null;
                resolve({ kind: 'error', code, message: errorMessage });
            }
        }
    });

    /**
     * @param {keyof typeof auth} methodName
     * @param {Record<string, any>} ws
     * @param {Record<string, any>} message
     * @returns {Promise<any>}
     */
    async function call(methodName, ws, message) {
        const resultPromise = new Promise((resolve) => {
            pendingResolve = resolve;
        });
        const fn = /** @type {any} */ (auth[methodName]);
        await fn(ws, message);
        return resultPromise;
    }

    return { auth, call };
}

describe('authManager unit and DB/handler security tests (QA-09)', () => {
    before(async () => {
        await db.ready;
    });

    after(async () => {
        await db.closeDb();
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    test('createAuthManager enforces minimum JWT secret length', () => {
        assert.throws(
            () => createAuthManager({
                db,
                logger: noopLogger,
                sendError: () => {},
                safeSend: () => {},
                jwtSecret: 'too-short-secret',
                jwtExpirySeconds: 3600
            }),
            /JWT_SECRET must be at least 32/
        );
    });

    test('verifyToken rejects alg:none, alg:HS512, missing exp, expired exp, tampered signature, and accepts valid HS256', () => {
        const { auth } = createBoundInvoker();
        const futureExp = Math.floor(Date.now() / 1000) + 600;
        const pastExp = Math.floor(Date.now() / 1000) - 10;

        const algNoneUnsigned = `${Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url')}.${Buffer.from(JSON.stringify({ id: 'u1', exp: futureExp })).toString('base64url')}.`;
        assert.strictEqual(auth.verifyToken(algNoneUnsigned), null, 'unsigned alg:none must fail');

        const algNoneSigned = signRawJwt({ alg: 'none', typ: 'JWT' }, { id: 'u1', exp: futureExp });
        assert.strictEqual(auth.verifyToken(algNoneSigned), null, 'signed token claiming alg:none must fail');

        const algHs512 = signRawJwt({ alg: 'HS512', typ: 'JWT' }, { id: 'u1', exp: futureExp });
        assert.strictEqual(auth.verifyToken(algHs512), null, 'HS512 header must be rejected');

        const missingExp = signRawJwt({ alg: 'HS256', typ: 'JWT' }, { id: 'u1', tv: 0 });
        assert.strictEqual(auth.verifyToken(missingExp), null, 'missing exp claim must be rejected');

        const expiredToken = signRawJwt({ alg: 'HS256', typ: 'JWT' }, { id: 'u1', exp: pastExp });
        assert.strictEqual(auth.verifyToken(expiredToken), null, 'expired exp claim must be rejected');

        const validToken = signRawJwt({ alg: 'HS256', typ: 'JWT' }, { id: 'u1', tv: 0, exp: futureExp });
        const parts = validToken.split('.');
        const flippedSig = parts[2].slice(0, -1) + (parts[2].endsWith('A') ? 'B' : 'A');
        assert.strictEqual(auth.verifyToken(`${parts[0]}.${parts[1]}.${flippedSig}`), null, 'tampered signature must fail');

        const verified = auth.verifyToken(validToken);
        assert.ok(verified && verified.id === 'u1', 'valid HS256 token with future exp must verify');
    });

    test('handleRegister rejects password > 72 UTF-8 bytes (even < 72 chars) and username === password', async () => {
        const { call } = createBoundInvoker();
        const ws = { readyState: 1, clientIp: '10.0.1.1', userId: null };

        // 20 4-byte emoji codepoints: .length === 40 (< 72 chars), UTF-8 bytes === 80 (> 72 bytes)
        const multibyteTooLong = '🔐'.repeat(20);
        assert.ok(multibyteTooLong.length < 72, 'precondition: JS string length is < 72');
        assert.strictEqual(Buffer.byteLength(multibyteTooLong, 'utf8'), 80);

        const tooLongRes = await call('handleRegister', ws, {
            username: 'EmojiUserTooLong',
            email: 'emoji-long@example.com',
            password: multibyteTooLong
        });
        assert.strictEqual(tooLongRes.kind, 'error');
        assert.strictEqual(tooLongRes.code, 'INVALID_DATA');
        assert.match(tooLongRes.message, /72 bytes/);

        // Same password as username (case and whitespace insensitive)
        const sameAsUserRes = await call('handleRegister', ws, {
            username: 'SamePassUser',
            email: 'same-pass@example.com',
            password: 'samepassuser'
        });
        assert.strictEqual(sameAsUserRes.kind, 'error');
        assert.strictEqual(sameAsUserRes.code, 'INVALID_DATA');

        // Exact 72 UTF-8 byte boundary (18 * 4 bytes = 72 bytes) is accepted
        const exact72Bytes = '🔐'.repeat(18);
        assert.strictEqual(Buffer.byteLength(exact72Bytes, 'utf8'), 72);
        const exactRes = await call('handleRegister', ws, {
            username: 'EmojiUser72',
            email: 'emoji-72@example.com',
            password: exact72Bytes
        });
        assert.strictEqual(exactRes.kind, 'json');
        assert.strictEqual(exactRes.payload.type, 'AUTH_SUCCESS');
        assert.strictEqual(exactRes.payload.user.username, 'EmojiUser72');
    });

    test('handleUpdateProfile requires currentPassword and rotates token_version to invalidate old JWTs', async () => {
        const { auth, call } = createBoundInvoker();
        const ws = { readyState: 1, clientIp: '10.0.1.2', userId: null };

        const reg = await call('handleRegister', ws, {
            username: 'RotateUser',
            email: 'rotate@example.com',
            password: 'InitialPassword!1'
        });
        assert.strictEqual(reg.kind, 'json');
        const userId = reg.payload.user.id;
        const oldToken = reg.payload.token;

        // Cross-user update rejected
        const forbidden = await call(
            'handleUpdateProfile',
            { readyState: 1, clientIp: '10.0.1.2', userId: 'someone-else' },
            { userId, username: 'Hijack' }
        );
        assert.strictEqual(forbidden.code, 'FORBIDDEN');

        // Missing currentPassword when changing password
        const missingCurrent = await call('handleUpdateProfile', ws, {
            userId,
            password: 'NewPassword!2'
        });
        assert.strictEqual(missingCurrent.code, 'INVALID_DATA');

        // Wrong currentPassword rejected
        const wrongCurrent = await call('handleUpdateProfile', ws, {
            userId,
            currentPassword: 'WrongPassword!0',
            password: 'NewPassword!2'
        });
        assert.strictEqual(wrongCurrent.code, 'AUTH_FAILED');

        // Valid password update increments token_version and returns fresh token
        const updated = await call('handleUpdateProfile', ws, {
            userId,
            currentPassword: 'InitialPassword!1',
            password: 'NewPassword!2',
            avatarId: 3,
            gender: 'F'
        });
        assert.strictEqual(updated.kind, 'json');
        assert.strictEqual(updated.payload.type, 'PROFILE_UPDATED');
        assert.ok(updated.payload.token && updated.payload.token !== oldToken);

        const row = await db.getUserById(userId);
        assert.ok(row.token_version >= 1, 'token_version must be incremented');

        // Old token fails verifySessionToken and handleLoginWithToken
        assert.strictEqual(await auth.verifySessionToken(oldToken, '10.0.1.2'), null);
        const oldLogin = await call(
            'handleLoginWithToken',
            { readyState: 1, clientIp: '10.0.1.2', userId: null },
            { token: oldToken }
        );
        assert.strictEqual(oldLogin.code, 'AUTH_FAILED');

        // Fresh token succeeds
        const newLogin = await call(
            'handleLoginWithToken',
            { readyState: 1, clientIp: '10.0.1.3', userId: null },
            { token: updated.payload.token }
        );
        assert.strictEqual(newLogin.kind, 'json');
        assert.strictEqual(newLogin.payload.type, 'AUTH_SUCCESS');
        assert.strictEqual(newLogin.payload.user.avatarId, 3);
        assert.strictEqual(newLogin.payload.user.gender, 'F');
    });

    test('handleDeleteAccount validates password, deletes users row, and anonymizes recorded game history', async () => {
        const { call } = createBoundInvoker();
        const victimWs = { readyState: 1, clientIp: '10.0.1.4', userId: null };
        const peerWs = { readyState: 1, clientIp: '10.0.1.5', userId: null };

        const regVictim = await call('handleRegister', victimWs, {
            username: 'DeleteMe',
            email: 'deleteme@example.com',
            password: 'DeletePass!123'
        });
        const regPeer = await call('handleRegister', peerWs, {
            username: 'KeepMe',
            email: 'keepme@example.com',
            password: 'KeepPass!123'
        });

        const victimId = regVictim.payload.user.id;
        const peerId = regPeer.payload.user.id;
        const gameId = 'auth-delete-history-game';

        const now = Date.now();
        await db.recordGame(
            gameId,
            victimId,
            now - 5000,
            now,
            [
                { userId: victimId, playerId: 'p-victim', joinedAt: now - 5000 },
                { userId: peerId, playerId: 'p-peer', joinedAt: now - 4000 }
            ],
            'DEL123',
            'p-victim'
        );

        // Unauthenticated delete fails
        const unauth = await call('handleDeleteAccount', { readyState: 1, clientIp: '10.0.1.4', userId: null }, {
            password: 'DeletePass!123'
        });
        assert.strictEqual(unauth.code, 'UNAUTHORIZED');

        // Wrong password leaves account intact
        const wrongPass = await call('handleDeleteAccount', victimWs, { password: 'WrongPassword!' });
        assert.strictEqual(wrongPass.code, 'AUTH_FAILED');
        assert.ok(await db.getUserById(victimId));

        // Correct password deletes user and anonymizes history participants & games.winner_id
        const deleted = await call('handleDeleteAccount', victimWs, { password: 'DeletePass!123' });
        assert.strictEqual(deleted.kind, 'json');
        assert.strictEqual(deleted.payload.type, 'ACCOUNT_DELETED');
        assert.strictEqual(victimWs.userId, null);
        assert.strictEqual(await db.getUserById(victimId), null);

        const gameRow = await dbGet('SELECT id, winner_id FROM games WHERE id = ?', [gameId]);
        assert.ok(gameRow, 'game history row should remain after account deletion');
        assert.strictEqual(gameRow.winner_id, null, 'winner_id should be anonymized to NULL');

        const participants = await dbAll(
            'SELECT player_id, user_id FROM participants WHERE game_id = ? ORDER BY player_id ASC',
            [gameId]
        );
        assert.strictEqual(participants.length, 2);
        const anonymized = participants.find(p => p.player_id === 'p-victim');
        const surviving = participants.find(p => p.player_id === 'p-peer');
        assert.ok(anonymized);
        assert.strictEqual(anonymized.user_id, null, 'deleted user participant row must have user_id = NULL');
        assert.strictEqual(surviving.user_id, peerId);

        const peerHistory = await db.getUserHistory(peerId);
        assert.strictEqual(peerHistory.length, 1);
        assert.strictEqual(peerHistory[0].player_count, 2, 'anonymized participant must still count toward player_count');
    });

    test('login rate-limiting triggers RATE_LIMITED after 5 failures and resets on successful login', async () => {
        const { call } = createBoundInvoker();
        const setupWs = { readyState: 1, clientIp: '10.0.2.1', userId: null };

        await call('handleRegister', setupWs, {
            username: 'RateLimitHero',
            email: 'ratelimit@example.com',
            password: 'ValidPassword!9'
        });

        // IP A: 5 failed attempts -> 6th attempt is RATE_LIMITED even with valid password
        const blockedIpWs = { readyState: 1, clientIp: '198.51.100.10', userId: null };
        for (let i = 0; i < 5; i++) {
            const fail = await call('handleLogin', blockedIpWs, {
                email: 'ratelimit@example.com',
                password: 'WrongPassword!0'
            });
            assert.strictEqual(fail.code, 'AUTH_FAILED', `attempt ${i + 1} should return AUTH_FAILED`);
        }
        const blocked = await call('handleLogin', blockedIpWs, {
            email: 'ratelimit@example.com',
            password: 'ValidPassword!9'
        });
        assert.strictEqual(blocked.code, 'RATE_LIMITED');

        // IP B: 4 failed attempts, then 1 success resets counter so 5 subsequent failures are allowed
        const resetIpWs = { readyState: 1, clientIp: '198.51.100.11', userId: null };
        for (let i = 0; i < 4; i++) {
            const fail = await call('handleLogin', resetIpWs, {
                email: 'ratelimit@example.com',
                password: 'WrongPassword!0'
            });
            assert.strictEqual(fail.code, 'AUTH_FAILED');
        }
        const okLogin = await call('handleLogin', resetIpWs, {
            email: 'ratelimit@example.com',
            password: 'ValidPassword!9'
        });
        assert.strictEqual(okLogin.kind, 'json');
        assert.strictEqual(okLogin.payload.type, 'AUTH_SUCCESS');

        for (let i = 0; i < 5; i++) {
            const postResetFail = await call('handleLogin', resetIpWs, {
                email: 'ratelimit@example.com',
                password: 'WrongPassword!0'
            });
            assert.strictEqual(
                postResetFail.code,
                'AUTH_FAILED',
                `counter should have reset; post-login failure ${i + 1} must still be AUTH_FAILED`
            );
        }
        const postResetBlocked = await call('handleLogin', resetIpWs, {
            email: 'ratelimit@example.com',
            password: 'WrongPassword!0'
        });
        assert.strictEqual(postResetBlocked.code, 'RATE_LIMITED');
    });
});

describe('authManager WebSocket integration', () => {
    let server;

    before(async () => {
        server = await startServer();
    });

    after(async () => {
        await server.stop();
    });

    test('WS UPDATE_PROFILE rotates JWT and invalidates previous session token on LOGIN_WITH_TOKEN', async () => {
        const reg = await registerUser(server.url, 'ws_auth_user', 'InitialPass!123');
        assert.strictEqual(reg.reply.type, 'AUTH_SUCCESS');
        const oldToken = reg.reply.token;
        const userId = reg.reply.user.id;

        try {
            const updated = await reg.client.request(
                {
                    type: 'UPDATE_PROFILE',
                    userId,
                    currentPassword: 'InitialPass!123',
                    password: 'RotatedPass!456'
                },
                ['PROFILE_UPDATED', 'ERROR']
            );
            assert.strictEqual(updated.type, 'PROFILE_UPDATED');
            assert.ok(updated.token && updated.token !== oldToken);

            const staleClient = await TestClient.connect(server.url);
            try {
                const staleReply = await staleClient.request(
                    { type: 'LOGIN_WITH_TOKEN', token: oldToken },
                    ['AUTH_SUCCESS', 'ERROR']
                );
                assert.strictEqual(staleReply.type, 'ERROR');
                assert.strictEqual(staleReply.code, 'AUTH_FAILED');

                const freshReply = await staleClient.request(
                    { type: 'LOGIN_WITH_TOKEN', token: updated.token },
                    ['AUTH_SUCCESS', 'ERROR']
                );
                assert.strictEqual(freshReply.type, 'AUTH_SUCCESS');
                assert.strictEqual(freshReply.user.id, userId);
            } finally {
                await staleClient.close();
            }
        } finally {
            await reg.client.close();
        }
    });
});
