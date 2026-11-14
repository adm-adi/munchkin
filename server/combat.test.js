/**
 * Unit tests for the server's class/race resolution.
 *
 * These mirror DualClassRaceTest.kt on the client. Both sides compute the combat
 * outcome and the server wins, so a disagreement shows up as the COMBAT_END
 * mismatch warning and as a result the player saw change under them.
 */

const test = require('node:test');
const assert = require('node:assert');

const {
    playerHasClass,
    playerHasRace,
    activeClasses,
    activeRaces,
    combatPower,
    getRunAwayBonus,
    requiresCombatToWin,
    calculateCombatResult,
    matchesCondition,
    conditionalBonus
} = require('./combatManager');

function player(overrides = {}) {
    return {
        characterClass: 'NONE',
        secondaryClass: 'NONE',
        characterRace: 'HUMAN',
        secondaryRace: 'HUMAN',
        hasSuperMunchkin: false,
        hasHalfBreed: false,
        gender: 'M',
        level: 1,
        gear: 0,
        tempCombatBonus: 0,
        ...overrides
    };
}

test('the primary class always counts', () => {
    assert.strictEqual(playerHasClass(player({ characterClass: 'WARRIOR' }), 'WARRIOR'), true);
    assert.strictEqual(playerHasClass(player({ characterClass: 'WARRIOR' }), 'CLERIC'), false);
});

test('a second class only counts with Super Munchkin', () => {
    const withoutCard = player({ characterClass: 'WARRIOR', secondaryClass: 'CLERIC' });
    assert.strictEqual(
        playerHasClass(withoutCard, 'CLERIC'), false,
        'the second slot must be inert without the card'
    );

    const withCard = { ...withoutCard, hasSuperMunchkin: true };
    assert.strictEqual(playerHasClass(withCard, 'CLERIC'), true);
    assert.strictEqual(playerHasClass(withCard, 'WARRIOR'), true, 'the primary still counts');
});

test('a second race only counts with Half-Breed', () => {
    const withoutCard = player({ characterRace: 'DWARF', secondaryRace: 'ELF' });
    assert.strictEqual(playerHasRace(withoutCard, 'ELF'), false);

    const withCard = { ...withoutCard, hasHalfBreed: true };
    assert.strictEqual(playerHasRace(withCard, 'ELF'), true);
    assert.strictEqual(playerHasRace(withCard, 'DWARF'), true);
});

test('the flags do not leak across class and race', () => {
    // Super Munchkin must not unlock a second race, nor Half-Breed a second class.
    const smOnly = player({
        characterRace: 'DWARF', secondaryRace: 'ELF', hasSuperMunchkin: true
    });
    assert.strictEqual(playerHasRace(smOnly, 'ELF'), false);

    const hbOnly = player({
        characterClass: 'WARRIOR', secondaryClass: 'CLERIC', hasHalfBreed: true
    });
    assert.strictEqual(playerHasClass(hbOnly, 'CLERIC'), false);
});

test('a missing player is not treated as having abilities', () => {
    // calculateCombatResult calls these with a possibly-absent helper.
    assert.strictEqual(playerHasClass(null, 'WARRIOR'), false);
    assert.strictEqual(playerHasClass(undefined, 'WARRIOR'), false);
    assert.strictEqual(playerHasRace(null, 'ELF'), false);
});

test('the flags are only honoured when strictly true', () => {
    // The value arrives over the wire, so a truthy string must not enable a card.
    const sloppy = player({
        characterClass: 'WARRIOR', secondaryClass: 'CLERIC', hasSuperMunchkin: 'yes'
    });
    assert.strictEqual(playerHasClass(sloppy, 'CLERIC'), false);
});

// ───────────── conditional modifiers (mirror CombatCalculator.kt) ─────────────
//
// The server used to ignore monster.conditionalModifiers entirely while the
// client applied them, so any combat carrying one ended in a COMBAT_END mismatch
// and the server overruled the outcome the players were looking at.

const mod = (overrides = {}) => ({
    id: 'cm-1', amount: 4, side: 'MONSTER',
    conditionType: 'RACE_ID', conditionValue: 'ELF',
    scope: 'ANY_PARTICIPANT', applyMode: 'ONCE_IF_MATCH',
    ...overrides
});

test('RACE_ID matches the primary race and, with Half-Breed, the secondary', () => {
    assert.strictEqual(matchesCondition(mod(), player({ characterRace: 'ELF' })), true);
    assert.strictEqual(matchesCondition(mod(), player({ characterRace: 'DWARF' })), false);
    assert.strictEqual(
        matchesCondition(mod(), player({ characterRace: 'DWARF', secondaryRace: 'ELF' })),
        false, 'the second slot is inert without Half-Breed'
    );
    assert.strictEqual(
        matchesCondition(mod(), player({ characterRace: 'DWARF', secondaryRace: 'ELF', hasHalfBreed: true })),
        true
    );
});

test('RACE_ID/CLASS_ID can target racelessness and classlessness, as on the client', () => {
    // The Kotlin arm `player.characterRace == raceEnum` matches HUMAN/NONE too.
    assert.strictEqual(
        matchesCondition(mod({ conditionValue: 'HUMAN' }), player()), true
    );
    assert.strictEqual(
        matchesCondition(mod({ conditionType: 'CLASS_ID', conditionValue: 'NONE' }), player()), true
    );
    // But an empty secondary slot must not match via Half-Breed alone.
    assert.strictEqual(
        matchesCondition(
            mod({ conditionValue: 'HUMAN' }),
            player({ characterRace: 'ELF', secondaryRace: 'HUMAN', hasHalfBreed: true })
        ),
        false, 'a Half-Breed elf is not "human" just because the second slot is empty'
    );
});

test('GENDER is compared case-sensitively against the exact enum name', () => {
    const p = player();
    p.gender = 'F';
    assert.strictEqual(matchesCondition(mod({ conditionType: 'GENDER', conditionValue: 'F' }), p), true);
    assert.strictEqual(matchesCondition(mod({ conditionType: 'GENDER', conditionValue: 'f' }), p), false);
    assert.strictEqual(matchesCondition(mod({ conditionType: 'GENDER', conditionValue: 'M' }), p), false);
});

test('an unknown condition value never matches', () => {
    assert.strictEqual(matchesCondition(mod({ conditionValue: 'DRAGONBORN' }), player({ characterRace: 'ELF' })), false);
    assert.strictEqual(matchesCondition(mod({ conditionType: 'MOON_PHASE' }), player()), false);
});

test('conditionalBonus honours side, scope, and applyMode', () => {
    const main = player({ characterRace: 'ELF' });
    const helper = player({ characterRace: 'ELF' });
    const monsters = [{ conditionalModifiers: [mod({ amount: 4 })] }];

    assert.strictEqual(conditionalBonus(monsters, main, helper, 'MONSTER'), 4, 'ONCE_IF_MATCH applies once');
    assert.strictEqual(conditionalBonus(monsters, main, helper, 'HEROES'), 0, 'the other side is untouched');

    const perPlayer = [{ conditionalModifiers: [mod({ amount: 4, applyMode: 'PER_MATCHING_PLAYER' })] }];
    assert.strictEqual(conditionalBonus(perPlayer, main, helper, 'MONSTER'), 8, 'PER_MATCHING_PLAYER stacks');

    const helperOnly = [{ conditionalModifiers: [mod({ amount: 4, scope: 'HELPER_ONLY' })] }];
    assert.strictEqual(conditionalBonus(helperOnly, main, null, 'MONSTER'), 0, 'no helper, no match');
    assert.strictEqual(conditionalBonus(helperOnly, main, helper, 'MONSTER'), 4);

    const mainOnly = [{ conditionalModifiers: [mod({ amount: 4, scope: 'MAIN_ONLY' })] }];
    assert.strictEqual(conditionalBonus(mainOnly, player(), helper, 'MONSTER'), 0, 'the helper alone must not trigger MAIN_ONLY');
});

test('activeClasses and activeRaces ignore NONE/HUMAN and duplicates', () => {
    assert.deepStrictEqual(activeClasses(player({ characterClass: 'NONE' })), []);
    assert.deepStrictEqual(
        activeClasses(player({ characterClass: 'WARRIOR', secondaryClass: 'WARRIOR', hasSuperMunchkin: true })),
        ['WARRIOR']
    );
    assert.deepStrictEqual(activeRaces(player({ characterRace: 'HUMAN' })), []);
    assert.deepStrictEqual(
        activeRaces(player({ characterRace: 'ELF', secondaryRace: 'ELF', hasHalfBreed: true })),
        ['ELF']
    );
    assert.strictEqual(playerHasClass(player({ characterClass: 'NONE' }), 'NONE'), false);
    assert.strictEqual(playerHasRace(player({ characterRace: 'HUMAN' }), 'HUMAN'), false);
});

test('getRunAwayBonus reflects Elf +1, Halfling -1, and Half-Breed both 0', () => {
    assert.strictEqual(getRunAwayBonus(player({ characterRace: 'ELF' })), 1);
    assert.strictEqual(getRunAwayBonus(player({ characterRace: 'HALFLING' })), -1);
    assert.strictEqual(
        getRunAwayBonus(player({ characterRace: 'ELF', secondaryRace: 'HALFLING', hasHalfBreed: true })),
        0
    );
});

test('requiresCombatToWin checks levelTenOnlyCombat and allowLevelTenOverride', () => {
    assert.strictEqual(requiresCombatToWin({ levelTenOnlyCombat: true, allowLevelTenOverride: false }), true);
    assert.strictEqual(requiresCombatToWin({ levelTenOnlyCombat: true, allowLevelTenOverride: true }), false);
    assert.strictEqual(requiresCombatToWin({ levelTenOnlyCombat: false, allowLevelTenOverride: false }), false);
});

test('calculateCombatResult includes tempCombatBonus, zeroes rewards on LOSE, and computes marginToWin', () => {
    const main = player({ level: 3, gear: 2, tempCombatBonus: 2 }); // power 7
    assert.strictEqual(combatPower(main), 7);

    const loseGame = {
        tiesGoToMonsters: true,
        players: new Map([['p1', main]]),
        combat: {
            mainPlayerId: 'p1',
            helperPlayerId: null,
            monsters: [{ baseLevel: 8, flatModifier: 0, levels: 2, treasures: 3, isUndead: false }],
            tempBonuses: [],
            heroModifier: 0,
            monsterModifier: 0
        }
    };
    const loseRes = calculateCombatResult(loseGame);
    assert.strictEqual(loseRes.outcome, 'LOSE');
    assert.strictEqual(loseRes.heroesPower, 7);
    assert.strictEqual(loseRes.monstersPower, 8);
    assert.strictEqual(loseRes.totalLevels, 0);
    assert.strictEqual(loseRes.totalTreasures, 0);
    assert.strictEqual(loseRes.helperLevelsGained, 0);
    assert.strictEqual(loseRes.marginToWin, 2, 'tiesGoToMonsters requires gap + 1 to win');

    const warriorLoseRes = calculateCombatResult({
        ...loseGame,
        players: new Map([['p1', player({ level: 3, gear: 2, tempCombatBonus: 2, characterClass: 'WARRIOR' })]])
    });
    assert.strictEqual(warriorLoseRes.marginToWin, 1, 'warrior wins ties so gap alone suffices');

    const winGame = {
        ...loseGame,
        combat: {
            ...loseGame.combat,
            heroModifier: 2 // 7 + 2 = 9 > 8
        }
    };
    const winRes = calculateCombatResult(winGame);
    assert.strictEqual(winRes.outcome, 'WIN');
    assert.strictEqual(winRes.totalLevels, 2);
    assert.strictEqual(winRes.totalTreasures, 3);
    assert.strictEqual(winRes.marginToWin, 0);
});

const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const EXPECTED_COMBAT_KEYS = [
    'heroesPower',
    'monstersPower',
    'outcome',
    'totalLevels',
    'totalTreasures',
    'helperLevelsGained',
    'warriorTieBreak',
    'isWarriorInvolved',
    'marginToWin'
];

test('shared cross-platform combat vectors match both server combatManager and web combat.mjs', async () => {
    const vectorsPath = path.join(__dirname, '..', 'shared', 'combat_vectors.json');
    const vectors = JSON.parse(fs.readFileSync(vectorsPath, 'utf8'));
    assert.ok(Array.isArray(vectors) && vectors.length >= 12, 'expected >= 12 shared combat vectors');

    const webCombat = await import(pathToFileURL(path.join(__dirname, 'public', 'js', 'combat.mjs')).href);

    for (const vector of vectors) {
        const serverPlayers = new Map();
        /** @type {Record<string, any>} */
        const webPlayers = {};

        for (const p of vector.players) {
            const normalized = {
                ...p,
                secondaryClass: p.secondaryClass ?? 'NONE',
                secondaryRace: p.secondaryRace ?? 'HUMAN',
                gearBonus: p.gear ?? 0
            };
            serverPlayers.set(p.playerId, normalized);
            webPlayers[p.playerId] = normalized;
        }

        const serverRes = calculateCombatResult({
            tiesGoToMonsters: vector.tiesGoToMonsters,
            players: serverPlayers,
            combat: vector.combat
        });
        const webRes = webCombat.calculateResult(vector.combat, {
            players: webPlayers,
            settings: { tiesGoToMonsters: vector.tiesGoToMonsters }
        });

        for (const key of EXPECTED_COMBAT_KEYS) {
            assert.strictEqual(
                serverRes[key],
                vector.expected[key],
                `server vector "${vector.id}" mismatch on ${key}`
            );
            assert.strictEqual(
                webRes[key],
                vector.expected[key],
                `web vector "${vector.id}" mismatch on ${key}`
            );
            assert.strictEqual(
                serverRes[key],
                webRes[key],
                `server/web divergence on vector "${vector.id}" field ${key}`
            );
        }
    }
});

test('getRunAwayBonus and web runAwayBonus match for Elf, Halfling, Half-Breed Elf+Halfling, and Human', async () => {
    const webCombat = await import(pathToFileURL(path.join(__dirname, 'public', 'js', 'combat.mjs')).href);

    const cases = [
        {
            label: 'Human gets 0',
            player: player({ characterRace: 'HUMAN', hasHalfBreed: false }),
            expected: 0
        },
        {
            label: 'Elf gets +1',
            player: player({ characterRace: 'ELF', hasHalfBreed: false }),
            expected: 1
        },
        {
            label: 'Halfling gets -1',
            player: player({ characterRace: 'HALFLING', hasHalfBreed: false }),
            expected: -1
        },
        {
            label: 'Half-Breed Elf+Halfling nets 0',
            player: player({ characterRace: 'ELF', secondaryRace: 'HALFLING', hasHalfBreed: true }),
            expected: 0
        },
        {
            label: 'Half-Breed Halfling+Elf nets 0',
            player: player({ characterRace: 'HALFLING', secondaryRace: 'ELF', hasHalfBreed: true }),
            expected: 0
        },
        {
            label: 'Inactive secondary Elf does not give +1',
            player: player({ characterRace: 'HALFLING', secondaryRace: 'ELF', hasHalfBreed: false }),
            expected: -1
        }
    ];

    for (const c of cases) {
        assert.strictEqual(getRunAwayBonus(c.player), c.expected, `server: ${c.label}`);
        assert.strictEqual(webCombat.runAwayBonus(c.player), c.expected, `web: ${c.label}`);
    }
});
