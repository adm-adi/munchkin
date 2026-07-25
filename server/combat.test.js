/**
 * Unit tests for the server's class/race resolution.
 *
 * These mirror DualClassRaceTest.kt on the client. Both sides compute the combat
 * outcome and the server wins, so a disagreement shows up as the COMBAT_END
 * mismatch warning and as a result the player saw change under them.
 */

const test = require('node:test');
const assert = require('node:assert');

const { playerHasClass, playerHasRace } = require('./combatManager');

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
