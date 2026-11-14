/**
 * Unit & SQLite integration tests for server/catalogManager.js, server/historyManager.js,
 * and catalog/history/leaderboard operations in server/db.js (QA-09).
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert');
const { test, describe, before, after } = require('node:test');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'munchkin-cat-hist-test-'));
process.env.MUNCHKIN_DB_PATH = path.join(tempDir, 'catalog-history-test.db');
process.env.MUNCHKIN_LOG_DIR = path.join(tempDir, 'logs');

const db = require('./db');
const { createCatalogManager } = require('./catalogManager');
const { createHistoryManager } = require('./historyManager');

const noopLogger = { info() {}, warn() {}, error() {}, debug() {} };

/**
 * Helper binding catalogManager and historyManager handlers with an awaitable response spy.
 */
function createManagers() {
    /** @type {((res: any) => void) | null} */
    let pendingResolve = null;

    const sendJson = (_ws, payload) => {
        if (pendingResolve) {
            const resolve = pendingResolve;
            pendingResolve = null;
            resolve({ kind: 'json', payload });
        }
    };
    const sendError = (_ws, code, errorMessage) => {
        if (pendingResolve) {
            const resolve = pendingResolve;
            pendingResolve = null;
            resolve({ kind: 'error', code, message: errorMessage });
        }
    };

    const catalog = createCatalogManager({ db, sendError, safeSend: sendJson, logger: noopLogger });
    const history = createHistoryManager({ db, sendError, safeSend: sendJson, logger: noopLogger });

    /**
     * @param {(ws: any, msg: any) => any} handler
     * @param {Record<string, any>} ws
     * @param {Record<string, any>} message
     * @returns {Promise<any>}
     */
    async function invoke(handler, ws, message) {
        const resPromise = new Promise(resolve => {
            pendingResolve = resolve;
        });
        await handler(ws, message);
        return resPromise;
    }

    return { catalog, history, invoke };
}

/**
 * Queries SQLite count/rows directly for asserting duplicate protection.
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

describe('catalogManager, historyManager, and db.js FTS5/pagination/cache tests (QA-09)', () => {
    /** @type {{ catalog: ReturnType<typeof createCatalogManager>, history: ReturnType<typeof createHistoryManager>, invoke: Function }} */
    let managers;
    let userAlice;
    let userBob;
    let userCharlie;

    before(async () => {
        await db.ready;
        managers = createManagers();
        userAlice = await db.createUser('CatAlice', 'catalice@example.com', 'CatalogPass!1', 0, 'F');
        userBob = await db.createUser('CatBob', 'catbob@example.com', 'CatalogPass!2', 1, 'M');
        userCharlie = await db.createUser('CatCharlie', 'catcharlie@example.com', 'CatalogPass!3', 2, 'NA');
    });

    after(async () => {
        await db.closeDb();
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    test('db.addMonster and CATALOG_ADD preserve treasures: 0 and prevent duplicate (name, level) rows', async () => {
        const wsAlice = { readyState: 1, userId: userAlice.id };
        const wsAnon = { readyState: 1, userId: null };

        // Anonymous CATALOG_ADD is rejected
        const unauth = await managers.invoke(managers.catalog.handleCatalogAdd, wsAnon, {
            monster: { name: 'Ghost Guard', level: 4, treasures: 0 }
        });
        assert.strictEqual(unauth.kind, 'error');
        assert.strictEqual(unauth.code, 'UNAUTHORIZED');

        // Treasures === 0 must be preserved (never coerced via falsy default to 1)
        const zeroId = await db.addMonster({
            name: 'ZeroLoot Imp',
            level: 3,
            modifier: 0,
            treasures: 0,
            levels: 1,
            isUndead: false,
            badStuff: 'Takes nothing because it has nothing',
            expansion: 'qa-zero'
        }, userAlice.id);

        const directRows = await db.searchMonsters('ZeroLoot', { expansion: 'qa-zero' });
        assert.strictEqual(directRows.length, 1);
        assert.strictEqual(directRows[0].id, zeroId);
        assert.strictEqual(directRows[0].treasures, 0, 'db.addMonster must preserve treasures: 0');

        // CATALOG_ADD also preserves treasures: 0
        const addedViaHandler = await managers.invoke(managers.catalog.handleCatalogAdd, wsAlice, {
            monster: {
                name: 'PocketMimic',
                level: 7,
                modifier: 1,
                treasures: 0,
                levels: 2,
                isUndead: true,
                badStuff: 'Lose a small charm',
                expansion: 'qa-zero'
            }
        });
        assert.strictEqual(addedViaHandler.kind, 'json');
        assert.strictEqual(addedViaHandler.payload.type, 'CATALOG_ADD_SUCCESS');
        assert.strictEqual(addedViaHandler.payload.monster.treasures, 0);
        const pocketId = addedViaHandler.payload.monster.id;

        // Duplicate insertion (case-insensitive name + same level) returns existing id without creating a 2nd row
        const dupDbId = await db.addMonster({
            name: 'pocketmimic',
            level: 7,
            treasures: 3,
            levels: 1,
            expansion: 'qa-zero'
        }, userBob.id);
        assert.strictEqual(dupDbId, pocketId, 'duplicate addMonster must resolve existing id');

        const dupHandler = await managers.invoke(managers.catalog.handleCatalogAdd, wsAlice, {
            monster: {
                name: 'POCKETMIMIC',
                level: 7,
                treasures: 4,
                expansion: 'qa-zero'
            }
        });
        assert.strictEqual(dupHandler.kind, 'json');
        assert.strictEqual(dupHandler.payload.monster.id, pocketId);

        const sqlRows = await dbAll(
            'SELECT id, treasures FROM monsters WHERE LOWER(name) = LOWER(?) AND level = ?',
            ['pocketmimic', 7]
        );
        assert.strictEqual(sqlRows.length, 1, 'unique (LOWER(name), level) must leave exactly one monster row');
        assert.strictEqual(sqlRows[0].treasures, 0);

        // Same name at a different level IS allowed
        const diffLevelId = await db.addMonster({
            name: 'PocketMimic',
            level: 9,
            treasures: 2,
            levels: 1,
            expansion: 'qa-zero'
        }, userAlice.id);
        assert.notStrictEqual(diffLevelId, pocketId);
    });

    test('FTS5 prefix searchMonsters, expansion filtering, and limit/offset pagination work end-to-end', async () => {
        const expAlpha = 'exp-alpha-qa';
        const expBeta = 'exp-beta-qa';

        const alphaNames = [
            'ChronoSlime',
            'ChronoWraith',
            'ChronoGargoyle',
            'ChronoBeast',
            'ChronoViper'
        ];
        for (let i = 0; i < alphaNames.length; i++) {
            await db.addMonster({
                name: alphaNames[i],
                level: i + 2,
                treasures: 1,
                levels: 1,
                isUndead: i % 2 === 0,
                badStuff: 'Lose 1 level',
                expansion: expAlpha
            }, userAlice.id);
        }
        await db.addMonster({
            name: 'ChronoDragon',
            level: 15,
            treasures: 4,
            levels: 2,
            isUndead: false,
            badStuff: 'Roasted',
            expansion: expBeta
        }, userAlice.id);

        // FTS5 prefix search across expansions finds all 6 Chrono* monsters
        const allChrono = await db.searchMonsters('Chrono', { limit: 20, offset: 0 });
        assert.strictEqual(allChrono.length, 6);

        // Expansion filtering scopes to expAlpha (5 monsters) vs expBeta (1 monster)
        const betaOnly = await db.searchMonsters('Chrono', { expansion: expBeta, limit: 20, offset: 0 });
        assert.strictEqual(betaOnly.length, 1);
        assert.strictEqual(betaOnly[0].name, 'ChronoDragon');

        // Empty query + expansion pagination walks deterministic pages without overlap
        const page1 = await db.searchMonsters('', { expansion: expAlpha, limit: 2, offset: 0 });
        const page2 = await db.searchMonsters('', { expansion: expAlpha, limit: 2, offset: 2 });
        const page3 = await db.searchMonsters('', { expansion: expAlpha, limit: 2, offset: 4 });

        assert.strictEqual(page1.length, 2);
        assert.strictEqual(page2.length, 2);
        assert.strictEqual(page3.length, 1);

        const combinedNames = [...page1, ...page2, ...page3].map(m => m.name);
        assert.deepStrictEqual(
            combinedNames,
            [...alphaNames].sort((a, b) => a.localeCompare(b))
        );

        // catalogManager CATALOG_SEARCH handles prefix query, __ALL__, expansion, limit, offset
        const ws = { readyState: 1, userId: userAlice.id };
        const shortSkip = await managers.invoke(managers.catalog.handleCatalogSearch, ws, { query: 'C' });
        assert.strictEqual(shortSkip.kind, 'json');
        assert.deepStrictEqual(shortSkip.payload.results, []);

        const handlerPage = await managers.invoke(managers.catalog.handleCatalogSearch, ws, {
            query: '__ALL__',
            expansion: expAlpha,
            limit: 3,
            offset: 1
        });
        assert.strictEqual(handlerPage.kind, 'json');
        assert.strictEqual(handlerPage.payload.type, 'CATALOG_SEARCH_RESULT');
        assert.deepStrictEqual(
            handlerPage.payload.results.map(r => r.name),
            [...alphaNames].sort((a, b) => a.localeCompare(b)).slice(1, 4)
        );
    });

    test('getUserHistory pagination, player_count (including guests), and historyManager auth checks', async () => {
        const t0 = 1_700_000_000_000;

        // Record 4 games involving Alice in chronological order (newest ended_at last)
        await db.recordGame('hist-g1', userAlice.id, t0 + 1000, t0 + 2000, [
            { userId: userAlice.id, playerId: 'p1', joinedAt: t0 + 1000 },
            { userId: userBob.id, playerId: 'p2', joinedAt: t0 + 1000 },
            { userId: null, playerId: 'p-guest-1', joinedAt: t0 + 1000 }
        ], 'CODE01', 'p1');

        await db.recordGame('hist-g2', userBob.id, t0 + 3000, t0 + 4000, [
            { userId: userAlice.id, playerId: 'p1', joinedAt: t0 + 3000 },
            { userId: userBob.id, playerId: 'p2', joinedAt: t0 + 3000 }
        ], 'CODE02', 'p1');

        await db.recordGame('hist-g3', userCharlie.id, t0 + 5000, t0 + 6000, [
            { userId: userAlice.id, playerId: 'p1', joinedAt: t0 + 5000 },
            { userId: userBob.id, playerId: 'p2', joinedAt: t0 + 5000 },
            { userId: userCharlie.id, playerId: 'p3', joinedAt: t0 + 5000 },
            { userId: null, playerId: 'p-guest-2', joinedAt: t0 + 5000 }
        ], 'CODE03', 'p1');

        await db.recordGame('hist-g4', null, t0 + 7000, t0 + 8000, [
            { userId: userAlice.id, playerId: 'p1', joinedAt: t0 + 7000 },
            { userId: null, playerId: 'p-guest-winner', joinedAt: t0 + 7000 }
        ], 'CODE04', 'p1');

        // Limit pagination caps results ordered by ended_at DESC
        const top2 = await db.getUserHistory(userAlice.id, 2);
        assert.strictEqual(top2.length, 2);
        assert.deepStrictEqual(top2.map(g => g.id), ['hist-g4', 'hist-g3']);
        assert.strictEqual(top2[0].player_count, 2, 'guest participant must count in player_count');
        assert.strictEqual(top2[0].winner_name, null, 'guest winner yields null winner_name');
        assert.strictEqual(top2[1].player_count, 4, '3 registered + 1 guest must count as 4 participants');
        assert.strictEqual(top2[1].winner_name, 'CatCharlie');
        assert.strictEqual(top2[1].did_i_win, 0);

        const all4 = await db.getUserHistory(userAlice.id, 10);
        assert.strictEqual(all4.length, 4);
        assert.strictEqual(all4[3].id, 'hist-g1');
        assert.strictEqual(all4[3].did_i_win, 1);
        assert.strictEqual(all4[3].player_count, 3);

        // historyManager authorization and camelCase mapping
        const unauth = await managers.invoke(managers.history.handleGetHistory, { readyState: 1, userId: null }, {});
        assert.strictEqual(unauth.code, 'UNAUTHORIZED');

        const crossUser = await managers.invoke(
            managers.history.handleGetHistory,
            { readyState: 1, userId: userAlice.id },
            { userId: userBob.id }
        );
        assert.strictEqual(crossUser.code, 'FORBIDDEN');

        const aliceHist = await managers.invoke(
            managers.history.handleGetHistory,
            { readyState: 1, userId: userAlice.id },
            { userId: userAlice.id }
        );
        assert.strictEqual(aliceHist.kind, 'json');
        assert.strictEqual(aliceHist.payload.type, 'HISTORY_RESULT');
        assert.deepStrictEqual(aliceHist.payload.stats, { wins: 1, gamesPlayed: 4 });
        assert.strictEqual(aliceHist.payload.games.length, 4);
        assert.strictEqual(aliceHist.payload.games[0].endedAt, t0 + 8000);
        assert.strictEqual(aliceHist.payload.games[1].playerCount, 4);
        assert.strictEqual(aliceHist.payload.games[3].didIWin, true);
    });

    test('getLeaderboard cache is invalidated immediately after db.recordGame', async () => {
        // Warm leaderboardCache before recording a new win
        const beforeRows = await db.getLeaderboard(20);
        const bobBefore = beforeRows.find(r => r.id === userBob.id);
        const aliceBefore = beforeRows.find(r => r.id === userAlice.id);
        assert.strictEqual(bobBefore.wins, 1);
        assert.strictEqual(aliceBefore.wins, 1);

        // Confirm repeated call returns the exact cached array reference while warm
        const cachedRef = await db.getLeaderboard(20);
        assert.strictEqual(cachedRef, beforeRows, 'precondition: leaderboardCache is populated');

        // Record two new wins for Bob immediately after warming cache
        const t = 1_700_000_100_000;
        await db.recordGame('hist-cache-1', userBob.id, t, t + 1000, [
            { userId: userAlice.id, playerId: 'p1', joinedAt: t },
            { userId: userBob.id, playerId: 'p2', joinedAt: t }
        ]);
        await db.recordGame('hist-cache-2', userBob.id, t + 2000, t + 3000, [
            { userId: userBob.id, playerId: 'p2', joinedAt: t + 2000 },
            { userId: userCharlie.id, playerId: 'p3', joinedAt: t + 2000 }
        ]);

        // Must reflect updated wins immediately without waiting 15s TTL
        const afterRows = await db.getLeaderboard(20);
        assert.notStrictEqual(afterRows, beforeRows, 'recordGame must invalidate leaderboardCache');
        assert.strictEqual(afterRows[0].id, userBob.id);
        assert.strictEqual(afterRows[0].wins, 3);
        assert.strictEqual(afterRows[0].games_played, 5);

        // historyManager returns camelCase DTO and caller's rank
        const boardMsg = await managers.invoke(
            managers.history.handleGetLeaderboard,
            { readyState: 1, userId: userBob.id },
            { type: 'GET_LEADERBOARD' }
        );
        assert.strictEqual(boardMsg.kind, 'json');
        assert.strictEqual(boardMsg.payload.type, 'LEADERBOARD_RESULT');
        assert.strictEqual(boardMsg.payload.leaderboard[0].avatarId, 1);
        assert.strictEqual(boardMsg.payload.leaderboard[0].wins, 3);
        assert.deepStrictEqual(boardMsg.payload.me, {
            wins: 3,
            gamesPlayed: 5,
            rank: 1
        });
    });
});
