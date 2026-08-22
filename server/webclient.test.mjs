/**
 * Web client tests, in two layers:
 *
 *  1. Static file serving — the web app is served by server.js itself, so the
 *     suite boots the real server (testServer.js) and asserts the shell, the ES
 *     modules and the PWA assets come back with the right MIME types, and that
 *     path traversal cannot escape public/ (server.js and munchkin.db live one
 *     directory up).
 *
 *  2. Protocol integration — drives the real server through the web client's
 *     own modules (protocol.mjs builders, net.mjs GameSocket with the `ws`
 *     implementation injected, engine.mjs reducer, combat.mjs calculator).
 *     This is the proof that the web client speaks the same dialect as the
 *     Android app: create/join, lightweight event broadcasts folding into the
 *     same state the server later snapshots, the full combat loop with the
 *     server agreeing with the local calculator, and seat reconnection.
 *
 * ES module on purpose (.mjs): node --test runs it alongside the CommonJS
 * suites, and it can import the browser modules directly.
 */

import { test, describe, before, after } from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import { WebSocket } from 'ws';

import { startServer } from './testServer.js';
import { messages, events, playerMeta, monsterInstance, tempBonus } from './public/js/protocol.mjs';
import { applyEvent, canStart, playerList } from './public/js/engine.mjs';
import { calculateResult, runAwayBonus } from './public/js/combat.mjs';
import { GameSocket, ConnState, oneOffRequest, authenticatedRequest } from './public/js/net.mjs';

let server;
let baseHttp;

before(async () => {
    server = await startServer();
    baseHttp = server.url.replace('ws://', 'http://');
});

after(async () => {
    await server.stop();
});

/** GET with a raw, un-normalised path (fetch would resolve `..` client-side). */
function rawGet(rawPath) {
    const { hostname, port } = new URL(baseHttp);
    return new Promise((resolve, reject) => {
        const req = http.request({ hostname, port, path: rawPath, method: 'GET' }, res => {
            let body = '';
            res.on('data', d => { body += d; });
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
        });
        req.on('error', reject);
        req.end();
    });
}

function waitForState(socket, predicate, what = 'state', timeoutMs = 8000) {
    return new Promise((resolve, reject) => {
        if (socket.gameState && predicate(socket.gameState)) return resolve(socket.gameState);
        const timer = setTimeout(() => {
            off();
            reject(new Error(`timed out waiting for ${what}`));
        }, timeoutMs);
        const off = socket.on('state', state => {
            if (predicate(state)) {
                clearTimeout(timer);
                off();
                resolve(state);
            }
        });
    });
}

function waitForConnState(socket, wanted, timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
        if (socket.connState === wanted) return resolve();
        const timer = setTimeout(() => {
            off();
            reject(new Error(`timed out waiting for connection state ${wanted}`));
        }, timeoutMs);
        const off = socket.on('connection', ({ state }) => {
            if (state === wanted) {
                clearTimeout(timer);
                off();
                resolve();
            }
        });
    });
}

// ============== 1. Static file serving ==============

describe('static web client serving', () => {
    test('serves the SPA shell at / as text/html', async () => {
        const res = await rawGet('/');
        assert.strictEqual(res.status, 200);
        assert.match(res.headers['content-type'], /text\/html/);
        assert.match(res.body, /<div id="app">/);
        assert.match(res.headers['cache-control'], /no-cache/);
    });

    test('serves ES modules with a JavaScript MIME type', async () => {
        const res = await rawGet('/js/app.mjs');
        assert.strictEqual(res.status, 200);
        assert.match(res.headers['content-type'], /text\/javascript/);
    });

    test('serves the stylesheet, manifest and icons', async () => {
        const css = await rawGet('/css/app.css');
        assert.strictEqual(css.status, 200);
        assert.match(css.headers['content-type'], /text\/css/);

        const manifest = await rawGet('/manifest.webmanifest');
        assert.strictEqual(manifest.status, 200);
        assert.match(manifest.headers['content-type'], /application\/manifest\+json/);
        assert.strictEqual(JSON.parse(manifest.body).short_name, 'Munchkin');

        const icon = await rawGet('/icon-180.png');
        assert.strictEqual(icon.status, 200);
        assert.match(icon.headers['content-type'], /image\/png/);
    });

    test('keeps /health and /api/monsters working alongside the static app', async () => {
        const health = await rawGet('/health');
        assert.strictEqual(health.status, 200);
        assert.strictEqual(JSON.parse(health.body).status, 'ok');

        const monsters = await rawGet('/api/monsters?q=a');
        assert.strictEqual(monsters.status, 200);
        assert.ok(Array.isArray(JSON.parse(monsters.body)));
    });

    test('sets a restrictive Content-Security-Policy on responses', async () => {
        const res = await rawGet('/');
        const csp = res.headers['content-security-policy'];
        assert.ok(csp, 'CSP header missing');
        assert.match(csp, /default-src 'self'/);
        assert.match(csp, /connect-src 'self' ws: wss:/);
        assert.doesNotMatch(csp, /upgrade-insecure-requests/);
    });

    test('path traversal cannot escape public/', async () => {
        for (const attempt of [
            '/../server.js',
            '/..%2Fserver.js',
            '/%2e%2e/server.js',
            '/js/../../server.js',
            '/js/..%2f..%2fserver.js',
            '/..%5Cserver.js'
        ]) {
            const res = await rawGet(attempt);
            assert.notStrictEqual(res.status, 200, `traversal served: ${attempt}`);
            assert.doesNotMatch(res.body, /require\(/, `server source leaked via ${attempt}`);
        }
    });

    test('never serves the database or unknown file types', async () => {
        const db = await rawGet('/../munchkin.db');
        assert.notStrictEqual(db.status, 200);
        // Even a .db dropped inside public/ would not be served: unknown
        // extensions fall through to the 404.
        const unknown = await rawGet('/munchkin.db');
        assert.strictEqual(unknown.status, 404);
    });

    test('dotfiles are not served', async () => {
        const res = await rawGet('/.gitignore');
        assert.strictEqual(res.status, 404);
    });

    test('a missing asset with a known extension is a 404, not a crash', async () => {
        const res = await rawGet('/js/no-such-file.mjs');
        assert.strictEqual(res.status, 404);
    });
});

// ============== 2. Protocol builders (Android-compat shapes) ==============

describe('protocol builders', () => {
    test('events carry every field the Kotlin decoder requires', () => {
        const inc = events.incLevel('p1', 1);
        for (const key of ['type', 'eventId', 'actorId', 'timestamp', 'targetPlayerId', 'amount']) {
            assert.ok(key in inc, `INC_LEVEL missing ${key}`);
        }
        assert.strictEqual(inc.type, 'INC_LEVEL');
        assert.strictEqual(inc.targetPlayerId, 'p1');

        const setClass = events.setClass('p1', 'WARRIOR');
        assert.strictEqual(setClass.isSecondary, false);
        assert.strictEqual(setClass.newClass, 'WARRIOR');

        const roll = events.playerRoll('p1', 6);
        assert.strictEqual(roll.purpose, 'RANDOM');
        assert.strictEqual(roll.success, false);
    });

    test('monster and bonus instances match the Kotlin field names', () => {
        const monster = monsterInstance({ name: 'Orco', baseLevel: 4 });
        for (const key of ['id', 'name', 'baseLevel', 'flatModifier', 'treasures', 'levels', 'isUndead', 'badStuff', 'conditionalModifiers']) {
            assert.ok(key in monster, `MonsterInstance missing ${key}`);
        }
        const bonus = tempBonus({ label: 'Poción', amount: 3, appliesTo: 'HEROES' });
        for (const key of ['id', 'label', 'amount', 'appliesTo']) {
            assert.ok(key in bonus, `TempBonus missing ${key}`);
        }
    });
});

// ============== 3. Combat calculator (pure) ==============

describe('combat calculator', () => {
    function stateWith(players, settings = {}) {
        return {
            players,
            settings: { minLevel: 1, maxLevel: 10, tiesGoToMonsters: true, ...settings }
        };
    }
    const basePlayer = overrides => ({
        playerId: 'p1', name: 'Ana', level: 3, gearBonus: 2, tempCombatBonus: 0,
        characterClass: 'NONE', characterRace: 'HUMAN',
        secondaryClass: 'NONE', secondaryRace: 'HUMAN',
        hasHalfBreed: false, hasSuperMunchkin: false, raceIds: [], classIds: [],
        gender: 'F', ...overrides
    });

    test('a warrior wins ties', () => {
        const state = stateWith({ p1: basePlayer({ characterClass: 'WARRIOR' }) });
        const combat = {
            mainPlayerId: 'p1', helperPlayerId: null, tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({ name: 'Orco', baseLevel: 5 })]
        };
        const result = calculateResult(combat, state);
        assert.strictEqual(result.heroesPower, 5);
        assert.strictEqual(result.monstersPower, 5);
        assert.strictEqual(result.outcome, 'WIN');
        assert.strictEqual(result.warriorTieBreak, true);
    });

    test('without a warrior, ties go to the monsters', () => {
        const state = stateWith({ p1: basePlayer() });
        const combat = {
            mainPlayerId: 'p1', helperPlayerId: null, tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({ name: 'Orco', baseLevel: 5 })]
        };
        const result = calculateResult(combat, state);
        assert.strictEqual(result.outcome, 'LOSE');
        assert.strictEqual(result.marginToWin, 1);
    });

    test('cleric gets +3 against undead, and an elf helper levels up on a win', () => {
        const state = stateWith({
            p1: basePlayer({ characterClass: 'CLERIC' }),
            p2: basePlayer({ playerId: 'p2', name: 'Beto', characterRace: 'ELF', level: 1, gearBonus: 0 })
        });
        const combat = {
            mainPlayerId: 'p1', helperPlayerId: 'p2', tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({ name: 'Esqueleto', baseLevel: 6, isUndead: true, levels: 1, treasures: 2 })]
        };
        const result = calculateResult(combat, state);
        // 5 (main) + 1 (helper) + 3 (cleric vs undead) = 9 vs 6
        assert.strictEqual(result.heroesPower, 9);
        assert.strictEqual(result.outcome, 'WIN');
        assert.strictEqual(result.helperLevelsGained, 1);
        assert.strictEqual(result.totalTreasures, 2);
    });

    test('conditional modifiers apply per matching player', () => {
        const state = stateWith({
            p1: basePlayer({ gender: 'F' }),
            p2: basePlayer({ playerId: 'p2', gender: 'F', level: 1, gearBonus: 0 })
        });
        const combat = {
            mainPlayerId: 'p1', helperPlayerId: 'p2', tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({
                name: 'Misógino', baseLevel: 2,
                conditionalModifiers: [{
                    id: 'cm1', amount: 3, side: 'MONSTER',
                    conditionType: 'GENDER', conditionValue: 'F',
                    scope: 'ANY_PARTICIPANT', applyMode: 'PER_MATCHING_PLAYER'
                }]
            })]
        };
        const result = calculateResult(combat, state);
        assert.strictEqual(result.monstersPower, 2 + 3 * 2);
    });

    test('run-away bonus: elf +1, halfling -1, half-breed with both nets zero', () => {
        assert.strictEqual(runAwayBonus(basePlayer({ characterRace: 'ELF' })), 1);
        assert.strictEqual(runAwayBonus(basePlayer({ characterRace: 'HALFLING' })), -1);
        assert.strictEqual(runAwayBonus(basePlayer({
            characterRace: 'ELF', hasHalfBreed: true, secondaryRace: 'HALFLING'
        })), 0);
        assert.strictEqual(runAwayBonus(basePlayer()), 0);
    });
});

// ============== 4. Live protocol integration ==============

describe('web client against the real server', () => {
    test('create, join, and lightweight events keep the local reducer in sync', async () => {
        const host = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        const guest = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        try {
            const welcome = await host.create({
                meta: playerMeta({ name: 'Ana', avatarId: 1, gender: 'F' })
            });
            assert.strictEqual(welcome.gameState.phase, 'LOBBY');
            assert.strictEqual(welcome.gameState.joinCode.length, 8);
            assert.ok(welcome.reconnectToken, 'host got no reconnect token');

            await guest.join({
                joinCode: welcome.gameState.joinCode,
                meta: playerMeta({ name: 'Beto', avatarId: 3, gender: 'M' })
            });
            await waitForState(host, s => Object.keys(s.players).length === 2, 'host to see the guest');

            // Lightweight events arrive as EVENT_BROADCAST and are folded in by
            // the engine.mjs reducer on every client, including the sender.
            guest.sendEvent(events.incLevel(guest.myPlayerId));
            guest.sendEvent(events.incLevel(guest.myPlayerId));
            guest.sendEvent(events.incGear(guest.myPlayerId));
            guest.sendEvent(events.setClass(guest.myPlayerId, 'WIZARD'));

            const check = s => {
                const p = s.players[guest.myPlayerId];
                return p && p.level === 3 && p.gearBonus === 1 && p.characterClass === 'WIZARD';
            };
            await waitForState(host, check, 'broadcast events to reach the host');
            await waitForState(guest, check, 'broadcast events to reach the guest');

            // The reducer's view must match the next authoritative snapshot —
            // a third join forces the server to broadcast one to everybody.
            const reduced = structuredClone(host.gameState);
            const third = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
            try {
                await third.join({
                    joinCode: welcome.gameState.joinCode,
                    meta: playerMeta({ name: 'Cati', avatarId: 5, gender: 'F' })
                });
                const snapshot = await waitForState(
                    host, s => Object.keys(s.players).length === 3, 'authoritative snapshot');
                const before = reduced.players[guest.myPlayerId];
                const afterServer = snapshot.players[guest.myPlayerId];
                assert.strictEqual(afterServer.level, before.level, 'reducer level diverged from server');
                assert.strictEqual(afterServer.gearBonus, before.gearBonus, 'reducer gear diverged from server');
                assert.strictEqual(afterServer.characterClass, before.characterClass, 'reducer class diverged from server');
            } finally {
                third.leave();
            }
        } finally {
            host.leave();
            guest.leave();
        }
    });

    test('full game: rolls, start, combat win confirmed by the server, escape, game over', async () => {
        const host = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        const guest = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        try {
            const welcome = await host.create({ meta: playerMeta({ name: 'Ana', avatarId: 0, gender: 'F' }) });
            const joinCode = welcome.gameState.joinCode;
            await guest.join({ joinCode, meta: playerMeta({ name: 'Beto', avatarId: 2, gender: 'M' }) });
            await waitForState(host, s => Object.keys(s.players).length === 2, 'guest join');

            // Deterministic rolls: host 6, guest 3 — host starts.
            host.sendEvent(events.playerRoll(host.myPlayerId, 6));
            guest.sendEvent(events.playerRoll(guest.myPlayerId, 3));
            await waitForState(host, s => canStart(s), 'both dice rolled');

            host.sendEvent(events.gameStart(host.myPlayerId));
            await waitForState(host, s => s.phase === 'IN_GAME', 'game start');
            assert.strictEqual(host.gameState.turnPlayerId, host.myPlayerId, 'highest roller starts');

            // Combat: level-2 monster vs level-1 host — needs gear to win.
            host.sendEvent(events.combatStart(host.myPlayerId, host.myPlayerId));
            await waitForState(host, s => Boolean(s.combat), 'combat start');

            host.sendEvent(events.combatAddMonster(host.myPlayerId, monsterInstance({
                name: 'Orco de prueba', baseLevel: 2, treasures: 2, levels: 1
            })));
            await waitForState(host, s => s.combat?.monsters.length === 1, 'monster added');

            let local = calculateResult(host.gameState.combat, host.gameState);
            assert.strictEqual(local.outcome, 'LOSE');

            for (let i = 0; i < 5; i++) host.sendEvent(events.incGear(host.myPlayerId));
            await waitForState(host, s => s.players[host.myPlayerId].gearBonus === 5, 'gear applied');

            local = calculateResult(host.gameState.combat, host.gameState);
            assert.strictEqual(local.outcome, 'WIN');

            host.sendEvent(events.combatEnd(host.myPlayerId, local.outcome, {
                levelsGained: local.totalLevels,
                treasuresGained: local.totalTreasures,
                helperLevelsGained: local.helperLevelsGained
            }));
            // The server recomputes the outcome itself; matching rewards in the
            // snapshot prove the local calculator agrees with combatManager.js.
            const afterWin = await waitForState(host, s => !s.combat && s.players[host.myPlayerId].level === 2, 'combat win applied');
            assert.strictEqual(afterWin.players[host.myPlayerId].treasures, 2);

            // Escape: no rewards, no penalty.
            host.sendEvent(events.combatStart(host.myPlayerId, host.myPlayerId));
            await waitForState(host, s => Boolean(s.combat), 'second combat');
            host.sendEvent(events.combatAddMonster(host.myPlayerId, monsterInstance({
                name: 'Dragón', baseLevel: 18
            })));
            await waitForState(host, s => s.combat?.monsters.length === 1, 'dragon added');
            host.sendEvent(events.playerRoll(host.myPlayerId, 5, 'RUN_AWAY', true));
            host.sendEvent(events.combatEnd(host.myPlayerId, 'ESCAPE'));
            const afterEscape = await waitForState(host, s => !s.combat, 'escape resolved');
            assert.strictEqual(afterEscape.players[host.myPlayerId].level, 2, 'escape must not change level');

            // Host confirms the guest as winner (message path, like the app).
            host.sendMessage(messages.gameOver(host.gameState.gameId, guest.myPlayerId));
            const finished = await waitForState(guest, s => s.phase === 'FINISHED', 'game over');
            assert.strictEqual(finished.winnerId, guest.myPlayerId);
        } finally {
            host.leave();
            guest.leave();
        }
    });

    test('a dropped guest reclaims the same seat via reconnectToken', async () => {
        const host = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        const guest = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        try {
            const welcome = await host.create({ meta: playerMeta({ name: 'Ana', avatarId: 0, gender: 'F' }) });
            await guest.join({
                joinCode: welcome.gameState.joinCode,
                meta: playerMeta({ name: 'Beto', avatarId: 2, gender: 'M' })
            });
            const seatId = guest.myPlayerId;
            assert.ok(guest.reconnectToken, 'guest got no reconnect token');

            await waitForState(host, s => Object.keys(s.players).length === 2, 'guest join');

            // Simulate a dropped connection (not a deliberate leave).
            guest.ws.close();
            await waitForConnState(guest, ConnState.CONNECTED);
            assert.strictEqual(guest.myPlayerId, seatId, 'reconnect produced a different seat');

            await waitForState(
                host,
                s => s.players[seatId]?.isConnected === true && Object.keys(s.players).length === 2,
                'host to see the guest back in the same seat'
            );
        } finally {
            host.leave();
            guest.leave();
        }
    });

    test('auth one-offs: register, leaderboard with self, history, hosted games', async () => {
        const registered = await oneOffRequest(server.url, messages.register({
            username: 'webuser',
            email: 'webuser@example.com',
            password: 'super-secreta-123',
            avatarId: 4,
            gender: 'NA'
        }), { WebSocketImpl: WebSocket });
        assert.strictEqual(registered.type, 'AUTH_SUCCESS');
        assert.ok(registered.token, 'register returned no JWT');
        assert.strictEqual(registered.user.username, 'webuser');

        const { reply: leaderboard } = await authenticatedRequest(
            server.url, registered.token, messages.getLeaderboard(), { WebSocketImpl: WebSocket });
        assert.strictEqual(leaderboard.type, 'LEADERBOARD_RESULT');
        assert.ok(Array.isArray(leaderboard.leaderboard));
        // Own totals only appear once the account has finished a game.
        assert.strictEqual(leaderboard.me, null);

        const { reply: history } = await authenticatedRequest(
            server.url, registered.token, messages.getHistory(registered.user.id), { WebSocketImpl: WebSocket });
        assert.strictEqual(history.type, 'HISTORY_RESULT');
        assert.ok(Array.isArray(history.games));

        // A game created with the token shows up in (and is deletable from)
        // the account's hosted games.
        const host = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        try {
            await host.create({
                meta: playerMeta({ name: 'WebUser', avatarId: 4, gender: 'NA', userId: registered.user.id }),
                authToken: registered.token
            });
            const gameId = host.gameState.gameId;

            const { reply: hosted } = await authenticatedRequest(
                server.url, registered.token, messages.getHostedGames(), { WebSocketImpl: WebSocket });
            assert.ok(hosted.games.some(g => g.gameId === gameId), 'hosted game missing from list');

            const { reply: deleted } = await authenticatedRequest(
                server.url, registered.token, messages.deleteHostedGame(gameId), { WebSocketImpl: WebSocket });
            assert.strictEqual(deleted.type, 'HOSTED_GAME_DELETED');
        } finally {
            host.leave();
        }
    });

    test('engine helpers order players and gate the start correctly', async () => {
        const host = new GameSocket({ url: server.url, WebSocketImpl: WebSocket });
        try {
            await host.create({ meta: playerMeta({ name: 'Sola', avatarId: 0, gender: 'NA' }) });
            const state = host.gameState;
            assert.strictEqual(playerList(state).length, 1);
            assert.strictEqual(playerList(state)[0].playerId, host.myPlayerId);
            assert.strictEqual(canStart(state), false, 'one player must not be able to start');

            // The reducer must tolerate events for players it does not know.
            const unknown = applyEvent(state, events.incLevel('nadie'));
            assert.ok(unknown, 'reducer crashed on unknown target');
        } finally {
            host.leave();
        }
    });
});
