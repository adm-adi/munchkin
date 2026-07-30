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
