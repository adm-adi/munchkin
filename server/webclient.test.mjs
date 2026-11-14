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
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';

import { startServer } from './testServer.js';
import { MSG, messages, events, playerMeta, monsterInstance, tempBonus } from './public/js/protocol.mjs';
import {
    applyEvent, canStart, playerList, tiedPlayerIds, requiresCombatToWin, combatPower
} from './public/js/engine.mjs';
import { calculateResult, runAwayBonus } from './public/js/combat.mjs';
import { GameSocket, ConnState, ServerError, oneOffRequest, authenticatedRequest } from './public/js/net.mjs';
import { session } from './public/js/session.mjs';
import { SUPPORTED_LANGS, AVATARS, avatarName, setLang, getLang } from './public/js/i18n.mjs';
import { encodeQrMatrix, createQrSvg } from './public/js/ui.mjs';

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

    test('normalized MSG constants and new message builders match protocol contract', () => {
        assert.strictEqual(MSG.CREATE_GAME, 'CREATE_GAME');
        assert.strictEqual(MSG.DELETE_ACCOUNT, 'DELETE_ACCOUNT');
        assert.strictEqual(MSG.ACCOUNT_DELETED, 'ACCOUNT_DELETED');
        assert.strictEqual(MSG.TRANSFER_HOST, 'TRANSFER_HOST');

        const hello = messages.hello('ABCD1234', playerMeta({ name: 'Ana' }));
        assert.strictEqual('lastKnownIp' in hello, false);

        const profileMsg = messages.updateProfile({
            userId: 'u1',
            username: 'neo',
            email: '',
            password: null,
            currentPassword: 'old-password',
            avatarId: 0,
            gender: ''
        });
        assert.deepStrictEqual(profileMsg, {
            type: MSG.UPDATE_PROFILE,
            userId: 'u1',
            username: 'neo',
            currentPassword: 'old-password',
            avatarId: 0
        });

        assert.deepStrictEqual(messages.deleteAccount('secret123'), {
            type: MSG.DELETE_ACCOUNT,
            password: 'secret123'
        });
        assert.deepStrictEqual(messages.transferHost('p2'), {
            type: MSG.TRANSFER_HOST,
            targetPlayerId: 'p2'
        });
        assert.deepStrictEqual(messages.catalogSearch('orco', 15), {
            type: MSG.CATALOG_SEARCH,
            query: 'orco',
            limit: 15,
            offset: 0
        });
        assert.deepStrictEqual(messages.catalogSearch('orco', { expansion: 'base', limit: 10, offset: 20 }), {
            type: MSG.CATALOG_SEARCH,
            query: 'orco',
            limit: 10,
            offset: 20,
            expansion: 'base'
        });

        const end = events.combatEnd('p1', 'WIN', {
            levelsGained: 1,
            treasuresGained: 2,
            expectedSeq: 7,
            cancelled: false
        });
        assert.strictEqual(end.levelsGained, 1);
        assert.strictEqual(end.treasuresGained, 2);
        assert.strictEqual(end.expectedSeq, 7);
        assert.strictEqual(end.cancelled, false);
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

    test('tiesGoToMonsters === false wins ties and sets marginToWin to gap (not gap + 1)', () => {
        const stateTie = stateWith(
            { p1: basePlayer({ level: 3, gearBonus: 2 }) },
            { tiesGoToMonsters: false }
        );
        const tieCombat = {
            mainPlayerId: 'p1', helperPlayerId: null, tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({ name: 'Orco', baseLevel: 5 })]
        };
        const tieResult = calculateResult(tieCombat, stateTie);
        assert.strictEqual(tieResult.outcome, 'WIN');
        assert.strictEqual(tieResult.marginToWin, 0);
        assert.strictEqual(tieResult.warriorTieBreak, false);

        const stateLose = stateWith(
            { p1: basePlayer({ level: 2, gearBonus: 1 }) },
            { tiesGoToMonsters: false }
        );
        const loseResult = calculateResult(tieCombat, stateLose);
        assert.strictEqual(loseResult.outcome, 'LOSE');
        assert.strictEqual(loseResult.marginToWin, 2);
    });

    test('dead raceIds/classIds fallbacks are ignored for non-enum condition values', () => {
        const state = stateWith({
            p1: basePlayer({ raceIds: ['custom-race'], classIds: ['custom-class'] })
        });
        const combat = {
            mainPlayerId: 'p1', helperPlayerId: null, tempBonuses: [],
            heroModifier: 0, monsterModifier: 0,
            monsters: [monsterInstance({
                name: 'Raro', baseLevel: 4,
                conditionalModifiers: [
                    {
                        id: 'c1', amount: 5, side: 'MONSTER',
                        conditionType: 'RACE_ID', conditionValue: 'custom-race',
                        scope: 'ANY_PARTICIPANT', applyMode: 'ONCE_IF_MATCH'
                    },
                    {
                        id: 'c2', amount: 5, side: 'MONSTER',
                        conditionType: 'CLASS_ID', conditionValue: 'custom-class',
                        scope: 'ANY_PARTICIPANT', applyMode: 'ONCE_IF_MATCH'
                    }
                ]
            })]
        };
        const result = calculateResult(combat, state);
        assert.strictEqual(result.monstersPower, 4);
    });
});

// ============== 3b. Engine reducer & session & net unit tests ==============

describe('engine reducer semantics', () => {
    function makeState(overrides = {}) {
        return {
            gameId: 'g1',
            joinCode: 'ABCD1234',
            seq: 1,
            phase: 'LOBBY',
            hostId: 'p1',
            turnPlayerId: 'p1',
            winnerId: null,
            playerOrder: ['p1', 'p2', 'p3'],
            settings: {
                minLevel: 1,
                maxLevel: 10,
                tiesGoToMonsters: true,
                levelTenOnlyCombat: true,
                allowLevelTenOverride: false
            },
            combat: null,
            players: {
                p1: {
                    playerId: 'p1', name: 'Ana', avatarId: 0, gender: 'F',
                    level: 1, gearBonus: 0, tempCombatBonus: 0, treasures: 0,
                    characterClass: 'NONE', characterRace: 'HUMAN',
                    secondaryClass: 'NONE', secondaryRace: 'HUMAN',
                    hasSuperMunchkin: false, hasHalfBreed: false,
                    isConnected: true, lastRoll: null, reachedMaxLevelViaCombat: false
                },
                p2: {
                    playerId: 'p2', name: 'Beto', avatarId: 1, gender: 'M',
                    level: 1, gearBonus: 0, tempCombatBonus: 0, treasures: 0,
                    characterClass: 'NONE', characterRace: 'ELF',
                    secondaryClass: 'NONE', secondaryRace: 'HUMAN',
                    hasSuperMunchkin: false, hasHalfBreed: false,
                    isConnected: true, lastRoll: null, reachedMaxLevelViaCombat: false
                },
                p3: {
                    playerId: 'p3', name: 'Cati', avatarId: 2, gender: 'F',
                    level: 1, gearBonus: 0, tempCombatBonus: 0, treasures: 0,
                    characterClass: 'NONE', characterRace: 'HALFLING',
                    secondaryClass: 'NONE', secondaryRace: 'HUMAN',
                    hasSuperMunchkin: false, hasHalfBreed: false,
                    isConnected: false, lastRoll: null, reachedMaxLevelViaCombat: false
                }
            },
            ...overrides
        };
    }

    test('PLAYER_ROLL evaluates LOBBY ties before updating so non-tied get 0 and other tied get null', () => {
        let s = makeState();
        s = applyEvent(s, events.playerRoll('p1', 5));
        s = applyEvent(s, events.playerRoll('p2', 5));
        s = applyEvent(s, events.playerRoll('p3', 2));
        assert.deepStrictEqual(tiedPlayerIds(s).sort(), ['p1', 'p2']);
        assert.strictEqual(canStart(s), false);

        const beforeReroll = s;
        const afterP1 = applyEvent(s, events.playerRoll('p1', 3));
        // Pure reducer: original state must not be mutated.
        assert.strictEqual(beforeReroll.players.p2.lastRoll, 5);
        assert.strictEqual(beforeReroll.players.p3.lastRoll, 2);

        assert.strictEqual(afterP1.players.p1.lastRoll, 3);
        assert.strictEqual(afterP1.players.p2.lastRoll, null);
        assert.strictEqual(afterP1.players.p3.lastRoll, 0);

        const afterP2 = applyEvent(afterP1, events.playerRoll('p2', 6));
        assert.strictEqual(afterP2.players.p2.lastRoll, 6);
        assert.deepStrictEqual(tiedPlayerIds(afterP2), []);
        assert.strictEqual(canStart(afterP2), true);
    });

    test('PLAYER_ROLL clamps result 1..6 and recomputes RUN_AWAY success', () => {
        let s = makeState({
            phase: 'IN_GAME',
            combat: {
                mainPlayerId: 'p2', helperPlayerId: null, monsters: [], tempBonuses: [],
                heroModifier: 0, monsterModifier: 0, lastDiceRoll: null, isActive: true
            }
        });
        // p2 is ELF (+1): 4 + 1 = 5 >= 5 even if client reported success=false
        const elfRoll = applyEvent(s, events.playerRoll('p2', 4, 'RUN_AWAY', false));
        assert.strictEqual(elfRoll.combat.lastDiceRoll.result, 4);
        assert.strictEqual(elfRoll.combat.lastDiceRoll.success, true);

        // p3 is HALFLING (-1): 5 - 1 = 4 < 5 even if client reported success=true
        const halflingRoll = applyEvent(s, events.playerRoll('p3', 5, 'RUN_AWAY', true));
        assert.strictEqual(halflingRoll.combat.lastDiceRoll.success, false);

        // Clamped out-of-range roll
        const highRoll = applyEvent(s, events.playerRoll('p2', 99, 'RANDOM', false));
        assert.strictEqual(highRoll.players.p2.lastRoll, 6);
    });

    test('clamps, secondary trait cleanup, COMBAT_END maxLevel flag, END_TURN and GAME_END', () => {
        let s = makeState({ phase: 'IN_GAME' });
        assert.strictEqual(requiresCombatToWin(s.settings), true);

        // Manual INC_LEVEL stops at maxLevel - 1 (9) when requiresCombatToWin is enabled.
        s = applyEvent(s, events.incLevel('p1', 50));
        assert.strictEqual(s.players.p1.level, 9);

        // Gear clamped 0..999, avatarId clamped 0..100, tempCombatBonus clamped -100..100.
        s = applyEvent(s, events.decGear('p1', 10));
        assert.strictEqual(s.players.p1.gearBonus, 0);
        s = applyEvent(s, events.incGear('p1', 100));
        assert.strictEqual(s.players.p1.gearBonus, 100);
        s = applyEvent(s, { type: 'SET_GEAR', targetPlayerId: 'p1', gear: 5000 });
        assert.strictEqual(s.players.p1.gearBonus, 999);
        s = applyEvent(s, events.setAvatar('p1', 250));
        assert.strictEqual(s.players.p1.avatarId, 100);
        assert.strictEqual(
            combatPower({ level: 5, gearBonus: 2, tempCombatBonus: 500 }),
            5 + 2 + 100
        );

        // Secondary class/race cleared if disabled or equal to primary.
        s = applyEvent(s, events.setSuperMunchkin('p1', true));
        s = applyEvent(s, events.setClass('p1', 'WARRIOR', false));
        s = applyEvent(s, events.setClass('p1', 'WARRIOR', true));
        assert.strictEqual(s.players.p1.secondaryClass, 'NONE');
        s = applyEvent(s, events.setClass('p1', 'CLERIC', true));
        assert.strictEqual(s.players.p1.secondaryClass, 'CLERIC');
        s = applyEvent(s, events.setSuperMunchkin('p1', false));
        assert.strictEqual(s.players.p1.secondaryClass, 'NONE');

        // COMBAT_END WIN marks reachedMaxLevelViaCombat on mainPlayer and Elf helper.
        s = applyEvent(s, events.incLevel('p2', 8)); // p2 (ELF) at level 9
        s = applyEvent(s, events.combatStart('p1', 'p1'));
        s = applyEvent(s, events.combatAddHelper('p1', 'p2'));
        s = applyEvent(s, events.combatEnd('p1', 'WIN', {
            levelsGained: 1, treasuresGained: 2, helperLevelsGained: 1
        }));
        assert.strictEqual(s.players.p1.level, 10);
        assert.strictEqual(s.players.p1.reachedMaxLevelViaCombat, true);
        assert.strictEqual(s.players.p2.level, 10);
        assert.strictEqual(s.players.p2.reachedMaxLevelViaCombat, true);

        // Dropping below maxLevel resets reachedMaxLevelViaCombat.
        s = applyEvent(s, events.decLevel('p1', 1));
        assert.strictEqual(s.players.p1.level, 9);
        assert.strictEqual(s.players.p1.reachedMaxLevelViaCombat, false);

        // END_TURN skips disconnected p3 and stale order entries.
        s = { ...s, playerOrder: ['p1', 'ghost', 'p3', 'p2'], turnPlayerId: 'p1' };
        s = applyEvent(s, events.endTurn('p1'));
        assert.strictEqual(s.turnPlayerId, 'p2');

        // GAME_END records winnerId and clears active combat.
        s = applyEvent(s, events.combatStart('p2', 'p2'));
        s = applyEvent(s, events.gameEnd('p1', 'p2'));
        assert.strictEqual(s.phase, 'FINISHED');
        assert.strictEqual(s.winnerId, 'p2');
        assert.strictEqual(s.combat, null);
    });
});

describe('session storage hardening', () => {
    function withMockStorage(fn) {
        const prev = globalThis.localStorage;
        const store = new Map();
        globalThis.localStorage = {
            getItem: k => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => { store.set(k, String(v)); },
            removeItem: k => { store.delete(k); }
        };
        try {
            return fn(store);
        } finally {
            if (prev === undefined) delete globalThis.localStorage;
            else globalThis.localStorage = prev;
        }
    }

    test('isolates corrupted keys and clearAuth wipes seat and identity', () => {
        withMockStorage(store => {
            session.saveAuth('jwt-123', { id: 'u1', username: 'Ana' });
            session.saveIdentity({ name: 'Ana', avatarId: 2, gender: 'F' });
            session.saveSeat({ joinCode: 'ABCD1234', playerId: 'p1', reconnectToken: 'rt-1' });

            // Corrupt only the seat key.
            store.set('munchkin.seat', '{not valid json');
            assert.strictEqual(session.seat, null);
            assert.strictEqual(session.authToken, 'jwt-123');
            assert.strictEqual(session.profile.username, 'Ana');
            assert.strictEqual(session.identity.name, 'Ana');

            // Re-seed seat and verify clearAuth removes auth, seat, and identity.
            session.saveSeat({ joinCode: 'ABCD1234', playerId: 'p1', reconnectToken: 'rt-1' });
            session.clearAuth();
            assert.strictEqual(session.authToken, null);
            assert.strictEqual(session.profile, null);
            assert.strictEqual(session.seat, null);
            assert.strictEqual(session.identity, null);
        });
    });
});

describe('net resilience', () => {
    test('oneOffRequest rejects cleanly when the socket emits error after open', async () => {
        class MockWs {
            constructor() {
                this.readyState = 0;
                this._listeners = new Map();
                queueMicrotask(() => {
                    this.readyState = 1;
                    this._fire('open', {});
                });
            }
            addEventListener(type, fn) {
                if (!this._listeners.has(type)) this._listeners.set(type, new Set());
                this._listeners.get(type).add(fn);
            }
            removeEventListener(type, fn) {
                this._listeners.get(type)?.delete(fn);
            }
            send() {
                queueMicrotask(() => this._fire('error', new Error('boom')));
            }
            close() { this.readyState = 3; }
            _fire(type, ev) {
                for (const fn of [...(this._listeners.get(type) || [])]) fn(ev);
            }
        }
        await assert.rejects(
            () => oneOffRequest('ws://mock', messages.getLeaderboard(), { WebSocketImpl: MockWs }),
            /Error de conexión/
        );
    });

    test('terminal ServerError during reconnect stops immediately in FAILED_PERMANENTLY', async () => {
        let attempts = 0;
        class FailingWs {
            constructor() {
                this.readyState = 0;
                this._listeners = new Map();
                queueMicrotask(() => {
                    this.readyState = 1;
                    this._fire('open', {});
                });
            }
            addEventListener(type, fn) {
                if (!this._listeners.has(type)) this._listeners.set(type, new Set());
                this._listeners.get(type).add(fn);
            }
            removeEventListener(type, fn) {
                this._listeners.get(type)?.delete(fn);
            }
            send() {
                attempts++;
                queueMicrotask(() => this._fire('message', {
                    data: JSON.stringify({ type: 'ERROR', code: 'GAME_NOT_FOUND', message: 'Gone' })
                }));
            }
            close() { this.readyState = 3; }
            _fire(type, ev) {
                for (const fn of [...(this._listeners.get(type) || [])]) fn(ev);
            }
        }

        const socket = new GameSocket({ url: 'ws://mock', WebSocketImpl: FailingWs });
        socket._last = { joinCode: 'ABCD1234', meta: { name: 'Ana' }, authToken: null };
        socket._sleep = async () => {};
        const errors = [];
        socket.on('error', err => errors.push(err));

        await socket._startReconnectLoop();
        assert.strictEqual(attempts, 1);
        assert.strictEqual(socket.connState, ConnState.FAILED_PERMANENTLY);
        assert.strictEqual(errors.length, 1);
        assert.ok(errors[0] instanceof ServerError);
        assert.strictEqual(errors[0].code, 'GAME_NOT_FOUND');
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

            // Stat mutations are forbidden before GAME_START.
            const lobbyErrPromise = new Promise(resolve => {
                const off = guest.on('error', err => { off(); resolve(err); });
            });
            guest.sendEvent(events.incLevel(guest.myPlayerId));
            const lobbyErr = await lobbyErrPromise;
            assert.strictEqual(lobbyErr.code, 'INVALID_DATA');

            // Roll and start the game so lightweight IN_GAME events are permitted.
            host.sendEvent(events.playerRoll(host.myPlayerId, 5));
            guest.sendEvent(events.playerRoll(guest.myPlayerId, 3));
            await waitForState(host, canStart, 'rolls ready to start');
            host.sendEvent(events.gameStart(host.myPlayerId));
            await waitForState(host, s => s.phase === 'IN_GAME', 'game to enter IN_GAME');

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

            // Guest takes turn, climbs to level 9, and wins a combat to hit maxLevel (10).
            host.sendEvent(events.endTurn(host.myPlayerId));
            await waitForState(guest, s => s.turnPlayerId === guest.myPlayerId, 'turn passed to guest');
            for (let i = 0; i < 8; i++) guest.sendEvent(events.incLevel(guest.myPlayerId));
            await waitForState(guest, s => s.players[guest.myPlayerId]?.level === 9, 'guest reaches level 9');

            guest.sendEvent(events.combatStart(guest.myPlayerId, guest.myPlayerId));
            await waitForState(guest, s => Boolean(s.combat), 'guest final combat');
            guest.sendEvent(events.combatAddMonster(guest.myPlayerId, monsterInstance({
                name: 'Goblin', baseLevel: 2, treasures: 1, levels: 1
            })));
            await waitForState(guest, s => s.combat?.monsters.length === 1, 'goblin added');
            guest.sendEvent(events.combatEnd(guest.myPlayerId, 'WIN', {
                levelsGained: 1, treasuresGained: 1
            }));
            await waitForState(
                host,
                s => !s.combat && s.players[guest.myPlayerId]?.level === 10,
                'guest reaches level 10 via combat'
            );

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

describe('web client modules: session, i18n, engine, and QR renderer (QA-09)', () => {
    test('session.clearAuth wipes seat/reconnectToken/auth cleanly and tolerates corrupt or missing localStorage', () => {
        const hadStorage = Object.prototype.hasOwnProperty.call(globalThis, 'localStorage');
        const origStorage = globalThis.localStorage;
        const store = new Map();

        const memStorage = {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => { store.set(k, String(v)); },
            removeItem: (k) => { store.delete(k); }
        };

        try {
            globalThis.localStorage = memStorage;

            session.saveAuth('jwt-token-1', { id: 'u1', username: 'Hero' });
            session.saveIdentity({ name: 'Hero', avatarId: 2, gender: 'M' });
            session.saveSeat({ joinCode: 'JOIN99', reconnectToken: 'rec-secret', playerId: 'p1' });

            assert.throws(() => session.saveAuth({ token: 'bad' }), TypeError);
            assert.strictEqual(session.authToken, 'jwt-token-1');
            assert.strictEqual(session.seat.reconnectToken, 'rec-secret');

            session.clearAuth();
            assert.strictEqual(session.authToken, null);
            assert.strictEqual(session.profile, null);
            assert.strictEqual(session.seat, null);
            assert.strictEqual(session.identity, null);
            assert.strictEqual(store.size, 0);

            // Corrupt auth/identity entries must not throw or wipe unrelated seat storage
            session.saveSeat({ joinCode: 'KEEP01', reconnectToken: 'keep-tok', playerId: 'p2' });
            store.set('munchkin.auth', '{not valid json');
            store.set('munchkin.identity', '["array-not-object"]');

            assert.strictEqual(session.authToken, null);
            assert.strictEqual(session.profile, null);
            assert.strictEqual(session.identity, null);
            assert.deepStrictEqual(
                { joinCode: session.seat.joinCode, reconnectToken: session.seat.reconnectToken, playerId: session.seat.playerId },
                { joinCode: 'KEEP01', reconnectToken: 'keep-tok', playerId: 'p2' }
            );

            // Absent / throwing localStorage must never crash clearAuth or getters
            delete globalThis.localStorage;
            assert.doesNotThrow(() => session.clearAuth());
            assert.strictEqual(session.authToken, null);
            assert.strictEqual(session.seat, null);

            globalThis.localStorage = {
                getItem() { throw new Error('SecurityError'); },
                setItem() { throw new Error('QuotaExceededError'); },
                removeItem() { throw new Error('SecurityError'); }
            };
            assert.doesNotThrow(() => session.clearAuth());
            assert.doesNotThrow(() => session.saveSeat({ joinCode: 'X', reconnectToken: 'Y', playerId: 'p1' }));
            assert.strictEqual(session.authToken, null);
        } finally {
            if (hadStorage) globalThis.localStorage = origStorage;
            else delete globalThis.localStorage;
        }
    });

    test('i18n has 100% key parity across es/en/fr and canonical AVATARS 0..7 match Android', () => {
        assert.deepStrictEqual(SUPPORTED_LANGS, ['es', 'en', 'fr']);

        const here = path.dirname(fileURLToPath(import.meta.url));
        const i18nSrc = fs.readFileSync(path.join(here, 'public', 'js', 'i18n.mjs'), 'utf8');
        const runnable = `${i18nSrc.replace(/^export\s+/gm, '')}\n;LABELS_BY_LANG;`;
        const labelsByLang = vm.runInNewContext(runnable, { globalThis: {} });

        const esSections = Object.keys(labelsByLang.es).sort();
        for (const lang of ['en', 'fr']) {
            assert.deepStrictEqual(
                Object.keys(labelsByLang[lang]).sort(),
                esSections,
                `${lang} top-level i18n sections must match es`
            );

            for (const section of esSections) {
                const esKeys = Object.keys(labelsByLang.es[section]).sort();
                const langKeys = Object.keys(labelsByLang[lang][section]).sort();
                assert.deepStrictEqual(
                    langKeys,
                    esKeys,
                    `i18n.${lang}.${section} keys must have 100% parity with es`
                );

                if (section === 'abilities') {
                    for (const key of esKeys) {
                        assert.strictEqual(
                            labelsByLang[lang].abilities[key].length,
                            labelsByLang.es.abilities[key].length,
                            `abilities.${key} length mismatch for ${lang}`
                        );
                    }
                } else {
                    for (const key of esKeys) {
                        const esVal = labelsByLang.es[section][key];
                        const langVal = labelsByLang[lang][section][key];
                        assert.ok(
                            typeof langVal === 'string' && langVal.trim().length > 0,
                            `i18n.${lang}.${section}.${key} must be a non-empty string`
                        );
                        const esPlaceholders = [...esVal.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort();
                        const langPlaceholders = [...langVal.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort();
                        assert.deepStrictEqual(
                            langPlaceholders,
                            esPlaceholders,
                            `placeholder mismatch in i18n.${lang}.${section}.${key}`
                        );
                    }
                }
            }
        }

        // Canonical 8 avatars (0..7) match AvatarResources.kt slot order & localized names
        assert.deepStrictEqual(
            AVATARS.map(a => [a.id, a.key]),
            [
                [0, 'WARRIOR'],
                [1, 'WIZARD'],
                [2, 'THIEF'],
                [3, 'CLERIC'],
                [4, 'HUMAN'],
                [5, 'ELF'],
                [6, 'DWARF'],
                [7, 'HALFLING']
            ]
        );

        const prevLang = getLang();
        try {
            setLang('es');
            assert.deepStrictEqual(
                AVATARS.map(a => avatarName(a.id)),
                ['Guerrero', 'Mago', 'Ladrón', 'Clérigo', 'Humano', 'Elfo', 'Enano', 'Mediano']
            );
            setLang('en');
            assert.deepStrictEqual(
                AVATARS.map(a => avatarName(a.id)),
                ['Warrior', 'Wizard', 'Thief', 'Cleric', 'Human', 'Elf', 'Dwarf', 'Halfling']
            );
            setLang('fr');
            assert.strictEqual(AVATARS.map(a => avatarName(a.id)).filter(Boolean).length, 8);
        } finally {
            setLang(prevLang);
        }
    });

    test('engine applyEvent handles PLAYER_ROLL tie-break, reachedMaxLevelViaCombat lifecycle, and GAME_END winnerId', () => {
        const lobbyState = {
            gameId: 'eng-1',
            joinCode: 'ENG1',
            hostId: 'p1',
            phase: 'LOBBY',
            seq: 1,
            settings: { maxLevel: 10, levelTenOnlyCombat: true, allowLevelTenOverride: false },
            playerOrder: ['p1', 'p2', 'p3'],
            players: {
                p1: { playerId: 'p1', name: 'P1', level: 1, gearBonus: 0, lastRoll: 5 },
                p2: { playerId: 'p2', name: 'P2', level: 1, gearBonus: 0, lastRoll: 5 },
                p3: { playerId: 'p3', name: 'P3', level: 1, gearBonus: 0, lastRoll: 2 }
            },
            combat: null
        };

        // Tie-break re-roll by p1 resets other tied player (p2 -> null), eliminates non-tied (p3 -> 0), preserves p1's roll
        const afterTieBreak = applyEvent(lobbyState, events.playerRoll('p1', 6));
        assert.strictEqual(afterTieBreak.players.p1.lastRoll, 6);
        assert.strictEqual(afterTieBreak.players.p2.lastRoll, null);
        assert.strictEqual(afterTieBreak.players.p3.lastRoll, 0);

        // COMBAT_END WIN from level 9 -> 10 marks reachedMaxLevelViaCombat = true
        const combatState = {
            gameId: 'eng-1',
            joinCode: 'ENG1',
            hostId: 'p1',
            phase: 'IN_GAME',
            seq: 10,
            settings: { maxLevel: 10, levelTenOnlyCombat: true, allowLevelTenOverride: false },
            playerOrder: ['p1', 'p2'],
            players: {
                p1: { playerId: 'p1', name: 'P1', level: 9, gearBonus: 0, treasures: 0, reachedMaxLevelViaCombat: false },
                p2: { playerId: 'p2', name: 'P2', level: 10, gearBonus: 0, treasures: 0, reachedMaxLevelViaCombat: false }
            },
            combat: {
                mainPlayerId: 'p1',
                helperPlayerId: null,
                monsters: [{ id: 'm1', name: 'Boss', baseLevel: 5, flatModifier: 0, isUndead: false, levels: 1, treasures: 2, conditionalModifiers: [] }],
                tempBonuses: [],
                heroModifier: 0,
                monsterModifier: 0
            }
        };

        const afterCombatWin = applyEvent(combatState, events.combatEnd('p1', 'WIN', {
            levelsGained: 1,
            treasuresGained: 2
        }));
        assert.strictEqual(afterCombatWin.players.p1.level, 10);
        assert.strictEqual(afterCombatWin.players.p1.reachedMaxLevelViaCombat, true);
        assert.strictEqual(afterCombatWin.combat, null);

        // SET_LEVEL dropping below 10 clears reachedMaxLevelViaCombat
        const dropped = applyEvent(afterCombatWin, {
            eventId: 'e-set-9',
            type: 'SET_LEVEL',
            actorId: 'p1',
            targetPlayerId: 'p1',
            timestamp: Date.now(),
            level: 9
        });
        assert.strictEqual(dropped.players.p1.level, 9);
        assert.strictEqual(dropped.players.p1.reachedMaxLevelViaCombat, false);

        // Already at level 10 before combat cannot forge reachedMaxLevelViaCombat = true
        const alreadyTenCombat = {
            ...afterCombatWin,
            players: {
                ...afterCombatWin.players,
                p2: { playerId: 'p2', name: 'P2', level: 10, gearBonus: 0, treasures: 0, reachedMaxLevelViaCombat: false }
            },
            combat: {
                mainPlayerId: 'p2',
                helperPlayerId: null,
                monsters: [{ id: 'm2', name: 'Rat', baseLevel: 1, flatModifier: 0, isUndead: false, levels: 1, treasures: 1, conditionalModifiers: [] }],
                tempBonuses: [],
                heroModifier: 0,
                monsterModifier: 0
            }
        };
        const afterAlreadyTen = applyEvent(alreadyTenCombat, events.combatEnd('p2', 'WIN', { levelsGained: 1, treasuresGained: 1 }));
        assert.strictEqual(afterAlreadyTen.players.p2.level, 10);
        assert.strictEqual(afterAlreadyTen.players.p2.reachedMaxLevelViaCombat, false);

        // GAME_END adopts event.winnerId and closes combat
        const finished = applyEvent(afterCombatWin, events.gameEnd('p1', 'p1'));
        assert.strictEqual(finished.phase, 'FINISHED');
        assert.strictEqual(finished.winnerId, 'p1');
        assert.strictEqual(finished.combat, null);
    });

    test('ui QR encoder and createQrSvg produce valid SVG markup for universal join URLs', () => {
        const hadNode = Object.prototype.hasOwnProperty.call(globalThis, 'Node');
        const hadDoc = Object.prototype.hasOwnProperty.call(globalThis, 'document');
        const origNode = globalThis.Node;
        const origDoc = globalThis.document;

        function escapeAttr(val) {
            return String(val)
                .replace(/&/g, '&amp;')
                .replace(/"/g, '&quot;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
        }

        class MockSvgNode {
            constructor(tagName, namespaceURI) {
                this.tagName = tagName;
                this.namespaceURI = namespaceURI;
                this.attributes = Object.create(null);
                this.children = [];
            }
            setAttribute(name, value) {
                this.attributes[name] = String(value);
            }
            appendChild(child) {
                this.children.push(child);
                return child;
            }
            toXml() {
                const attrs = Object.entries(this.attributes)
                    .map(([k, v]) => ` ${k}="${escapeAttr(v)}"`)
                    .join('');
                if (this.children.length === 0) {
                    return `<${this.tagName}${attrs}/>`;
                }
                return `<${this.tagName}${attrs}>${this.children.map(c => c.toXml()).join('')}</${this.tagName}>`;
            }
        }

        try {
            globalThis.Node = MockSvgNode;
            globalThis.document = {
                createElementNS(ns, tag) {
                    return new MockSvgNode(tag, ns);
                }
            };

            const payload = 'https://munchking-sirpepo.duckdns.org/#/join/ABCD1234';
            const { size, grid } = encodeQrMatrix(payload);
            assert.ok(size >= 29 && size % 4 === 1, `expected valid QR matrix version dimension, got ${size}`);
            assert.strictEqual(grid.length, size * size);
            // 7x7 finder corners must be dark at (0,0), (0,size-1), (size-1,0)
            assert.strictEqual(grid[0], 1);
            assert.strictEqual(grid[size - 1], 1);
            assert.strictEqual(grid[(size - 1) * size], 1);

            const svgNode = createQrSvg(payload, { label: 'QR ABCD1234', margin: 2 });
            const markup = svgNode.toXml();
            assert.ok(markup.startsWith('<svg '), 'expected <svg> root element');
            assert.ok(markup.endsWith('</svg>'), 'expected closing </svg> tag');
            assert.ok(markup.includes(`viewBox="0 0 ${size + 4} ${size + 4}"`));
            assert.ok(markup.includes('aria-label="QR ABCD1234"'));
            assert.ok(markup.includes('<rect x="0" y="0"'));
            assert.match(markup, /<path d="M[\d,]+h1v1h-1z/);
            assert.ok(!markup.includes('style='), 'CSP forbids inline style attributes');
        } finally {
            if (hadNode) globalThis.Node = origNode;
            else delete globalThis.Node;
            if (hadDoc) globalThis.document = origDoc;
            else delete globalThis.document;
        }
    });
});
