/**
 * Unit tests for the server's input validation.
 *
 * Run with: npm test  (uses the built-in node:test runner, no extra deps)
 *
 * These pin the contract that keeps a malformed or hostile event from reaching the
 * broadcast snapshot. The Android client decodes snapshots with strictly-typed
 * kotlinx.serialization, so a wrong-typed value or an unknown enum name does not
 * just reject the sender — it breaks decoding for every client in the room.
 */

const test = require('node:test');
const assert = require('node:assert');

const {
    VALID_GENDERS,
    VALID_CLASSES,
    VALID_RACES,
    MODIFIER_LIMIT,
    clampInt,
    stepAmount,
    normalizeGender,
    normalizeName,
    sanitizeMonster,
    sanitizeConditionalModifier,
    sanitizeBonus
} = require('./validation');

test('clampInt bounds numbers into range', () => {
    assert.strictEqual(clampInt(5, 1, 10), 5);
    assert.strictEqual(clampInt(-99, 1, 10), 1);
    assert.strictEqual(clampInt(999, 1, 10), 10);
    assert.strictEqual(clampInt(4.6, 1, 10), 5, 'rounds to nearest');
});

test('clampInt rejects non-numeric input instead of propagating it', () => {
    // The original code assigned event.gear straight onto the player, so a string
    // here reached the snapshot and broke the client's Int deserialization.
    assert.strictEqual(clampInt('abc', -10, 10, 0), 0);
    assert.strictEqual(clampInt(undefined, -10, 10, 3), 3);
    assert.strictEqual(clampInt(null, -10, 10, 3), 3, 'null is not treated as 0');
    assert.strictEqual(clampInt(NaN, -10, 10, 7), 7);
    assert.strictEqual(clampInt(Infinity, -10, 10, 7), 7);
    assert.strictEqual(clampInt(-Infinity, -10, 10, 7), 7);
    assert.strictEqual(clampInt({}, -10, 10, 7), 7);
    assert.strictEqual(clampInt([], -10, 10, 7), 7, 'empty array coerces to 0 but is not a number');
});

test('clampInt accepts numeric strings', () => {
    // Clients legitimately send JSON numbers, but tolerate a numeric string.
    assert.strictEqual(clampInt('7', 1, 10), 7);
});

test('stepAmount always yields a positive step', () => {
    assert.strictEqual(stepAmount(1), 1);
    assert.strictEqual(stepAmount(5), 5);
    assert.strictEqual(stepAmount(undefined), 1, 'absent amount defaults to 1');
    assert.strictEqual(stepAmount(0), 1, 'zero would make INC_LEVEL a no-op');
    assert.strictEqual(stepAmount(-5), 1, 'negative would invert INC into DEC');
    assert.strictEqual(stepAmount('abc'), 1);
});

test('stepAmount is bounded so a single event cannot jump arbitrarily', () => {
    assert.strictEqual(stepAmount(1e9), 100);
});

test('normalizeGender only ever returns a Kotlin Gender enum value', () => {
    assert.strictEqual(normalizeGender('M'), 'M');
    assert.strictEqual(normalizeGender('F'), 'F');
    assert.strictEqual(normalizeGender('NA'), 'NA');
    // The server used to default to "MALE", which is not a Gender value at all.
    assert.strictEqual(normalizeGender('MALE'), 'M');
    assert.strictEqual(normalizeGender('FEMALE'), 'F');
    assert.strictEqual(normalizeGender(undefined), 'M');
    assert.strictEqual(normalizeGender('nonsense'), 'M');

    for (const input of ['M', 'F', 'NA', 'MALE', 'FEMALE', undefined, null, 42, 'x']) {
        assert.ok(
            VALID_GENDERS.has(normalizeGender(input)),
            `normalizeGender(${JSON.stringify(input)}) must be a valid enum value`
        );
    }
});

test('normalizeName trims, bounds, and rejects empty names', () => {
    assert.strictEqual(normalizeName('  Pepo  '), 'Pepo');
    assert.strictEqual(normalizeName('x'.repeat(50)).length, 20);
    assert.strictEqual(normalizeName(''), null);
    assert.strictEqual(normalizeName('   '), null, 'whitespace-only is not a name');
    assert.strictEqual(normalizeName(undefined), null);
    assert.strictEqual(normalizeName(42), null, 'non-strings are rejected');
    assert.strictEqual(normalizeName({}), null);
});

test('sanitizeMonster clamps level and modifier into range', () => {
    const m = sanitizeMonster({ id: 'm1', name: 'Dragón', baseLevel: 999, flatModifier: 500 });
    assert.strictEqual(m.baseLevel, 20);
    assert.strictEqual(m.flatModifier, 10);

    const low = sanitizeMonster({ id: 'm2', baseLevel: -5, flatModifier: -500 });
    assert.strictEqual(low.baseLevel, 1);
    assert.strictEqual(low.flatModifier, -10);
});

test('sanitizeMonster is idempotent, so update cannot bypass the add-time clamp', () => {
    // COMBAT_ADD_MONSTER clamped but COMBAT_UPDATE_MONSTER did not, so a client
    // could add a legal monster and then update it to any values it liked.
    const once = sanitizeMonster({ id: 'm1', baseLevel: 999, flatModifier: 999 });
    const twice = sanitizeMonster(once);
    assert.deepStrictEqual(twice, once);
});

test('sanitizeMonster fills defaults for missing or malformed input', () => {
    const m = sanitizeMonster(undefined);
    assert.ok(m.id, 'generates an id so the monster is addressable for remove/update');
    assert.strictEqual(m.name, 'Monstruo');
    assert.strictEqual(m.baseLevel, 1);
    assert.strictEqual(m.flatModifier, 0);
    assert.strictEqual(m.levels, 1);
    assert.strictEqual(m.treasures, 1);
    assert.strictEqual(m.isUndead, false);
    assert.deepStrictEqual(m.conditionalModifiers, []);
});

test('sanitizeMonster coerces isUndead strictly', () => {
    // Anything other than a real boolean true must not enable the Cleric bonus.
    assert.strictEqual(sanitizeMonster({ isUndead: true }).isUndead, true);
    assert.strictEqual(sanitizeMonster({ isUndead: 'true' }).isUndead, false);
    assert.strictEqual(sanitizeMonster({ isUndead: 1 }).isUndead, false);
});

test('sanitizeMonster bounds the conditional modifier list', () => {
    const many = Array.from({ length: 50 }, (_, i) => ({
        amount: i, side: 'HEROES', conditionType: 'GENDER', conditionValue: 'F'
    }));
    assert.strictEqual(sanitizeMonster({ conditionalModifiers: many }).conditionalModifiers.length, 10);
    assert.deepStrictEqual(sanitizeMonster({ conditionalModifiers: 'nope' }).conditionalModifiers, []);
});

test('sanitizeMonster drops conditional modifiers that would break Kotlin decoding', () => {
    // Bounding the count alone let `{"side":"BANANA"}` through into the broadcast
    // snapshot, where the strict ModifierSide enum made every client throw.
    const m = sanitizeMonster({
        conditionalModifiers: [
            { side: 'BANANA', conditionType: 'GENDER', conditionValue: 'F', amount: 2 },
            { side: 'HEROES', conditionType: 'ASTROLOGY', conditionValue: 'F', amount: 2 },
            'not-an-object',
            null,
            { side: 'HEROES', conditionType: 'GENDER', conditionValue: 'F', amount: 2 }
        ]
    });
    assert.strictEqual(m.conditionalModifiers.length, 1, 'only the valid entry survives');
    assert.strictEqual(m.conditionalModifiers[0].side, 'HEROES');
});

test('sanitizeConditionalModifier normalises every field the Kotlin type requires', () => {
    const mod = sanitizeConditionalModifier({
        side: 'MONSTER',
        conditionType: 'RACE_ID',
        conditionValue: 'ELF',
        amount: '4',
        scope: 'garbage',
        applyMode: 'garbage'
    });
    assert.ok(mod.id, 'an id is generated so the modifier is addressable');
    assert.strictEqual(mod.amount, 4);
    assert.strictEqual(mod.scope, 'ANY_PARTICIPANT', 'unknown scope falls back to a valid enum value');
    assert.strictEqual(mod.applyMode, 'ONCE_IF_MATCH', 'unknown applyMode falls back to a valid enum value');
    assert.strictEqual(typeof mod.conditionValue, 'string');

    assert.strictEqual(sanitizeConditionalModifier({ conditionType: 'GENDER' }), null, 'missing side is unsalvageable');
    assert.strictEqual(sanitizeConditionalModifier({ side: 'HEROES' }), null, 'missing conditionType is unsalvageable');
    assert.strictEqual(sanitizeConditionalModifier({ side: 'HEROES', conditionType: 'GENDER', amount: 1e9 }).amount, MODIFIER_LIMIT);
});

test('sanitizeMonster caps the name length', () => {
    assert.strictEqual(sanitizeMonster({ name: 'x'.repeat(500) }).name.length, 80);
});

test('sanitizeBonus normalises target and amount', () => {
    assert.strictEqual(sanitizeBonus({ appliesTo: 'MONSTER' }).appliesTo, 'MONSTER');
    assert.strictEqual(sanitizeBonus({ appliesTo: 'HEROES' }).appliesTo, 'HEROES');
    assert.strictEqual(
        sanitizeBonus({ appliesTo: 'garbage' }).appliesTo,
        'HEROES',
        'an unknown target must fall back to a valid BonusTarget'
    );
    assert.strictEqual(sanitizeBonus({ amount: 'abc' }).amount, 0);
    assert.strictEqual(sanitizeBonus({ amount: 1e9 }).amount, MODIFIER_LIMIT);
});

test('sanitizeBonus generates an id so the bonus can be removed again', () => {
    const b = sanitizeBonus({ amount: 3 });
    assert.ok(b.id, 'COMBAT_REMOVE_BONUS filters by id');
});

test('enum whitelists match the Kotlin enums in core/Models.kt', () => {
    // Drift here is what breaks snapshot decoding for the whole room.
    assert.deepStrictEqual([...VALID_GENDERS].sort(), ['F', 'M', 'NA']);
    assert.deepStrictEqual(
        [...VALID_CLASSES].sort(),
        ['CLERIC', 'NONE', 'THIEF', 'WARRIOR', 'WIZARD']
    );
    assert.deepStrictEqual(
        [...VALID_RACES].sort(),
        ['DWARF', 'ELF', 'HALFLING', 'HUMAN']
    );
});
