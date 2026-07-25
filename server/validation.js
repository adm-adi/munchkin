/**
 * Pure input-validation helpers shared by the event handlers.
 *
 * These live outside server.js so they can be unit tested: requiring server.js
 * binds a port and opens the database as a side effect.
 *
 * Everything here exists because the client's kotlinx.serialization decoding is
 * strictly typed. A value of the wrong type or an enum name the Kotlin enum does
 * not contain makes decoding throw, and since state is broadcast as a snapshot,
 * one bad value breaks every client in the room rather than just the sender.
 */

const { v4: uuidv4 } = require('uuid');

// These must mirror the Kotlin enums in core/Models.kt exactly.
const VALID_GENDERS = new Set(['M', 'F', 'NA']);
const VALID_CLASSES = new Set(['NONE', 'WARRIOR', 'WIZARD', 'THIEF', 'CLERIC']);
const VALID_RACES = new Set(['HUMAN', 'ELF', 'DWARF', 'HALFLING']);
const DEFAULT_GENDER = 'M';

const MAX_PLAYERS = 6;
const GEAR_LIMIT = 999;
const STEP_LIMIT = 100;
const MAX_NAME_LENGTH = 20;
const MAX_AVATAR_ID = 100;
const MAX_MONSTERS_PER_COMBAT = 6;
const MAX_BONUSES_PER_COMBAT = 20;
// Combat modifiers are intentionally generous (Munchkin items are unbounded);
// the limit exists only to keep the value a sane, serialisable integer.
const MODIFIER_LIMIT = 9999;

/**
 * Coerces a value to an integer within [min, max], returning `fallback` for
 * anything that is not a number or a numeric string.
 *
 * Note the explicit rejection of null, booleans, arrays, and empty strings:
 * Number() maps all of them to 0, so relying on Number.isFinite alone would turn
 * a missing field into a real 0 and silently zero out a player's gear rather than
 * leaving it untouched.
 */
function clampInt(value, min, max, fallback = 0) {
    if (typeof value !== 'number' && typeof value !== 'string') return fallback;
    if (typeof value === 'string' && value.trim() === '') return fallback;
    const n = Math.round(Number(value));
    if (!Number.isFinite(n)) return fallback;
    return Math.max(min, Math.min(max, n));
}

/** Positive step for INC_/DEC_ events; defaults to 1 when absent or unusable. */
function stepAmount(value) {
    const n = Math.round(Number(value));
    if (!Number.isFinite(n) || n <= 0) return 1;
    return Math.min(STEP_LIMIT, n);
}

/**
 * Coerces any stored/incoming gender into a value the Kotlin Gender enum accepts.
 * Older rows may hold "MALE"/"FEMALE", which would break snapshot decoding.
 */
function normalizeGender(value) {
    if (VALID_GENDERS.has(value)) return value;
    if (value === 'MALE') return 'M';
    if (value === 'FEMALE') return 'F';
    return DEFAULT_GENDER;
}

/** Trims and bounds a display name, or returns null when unusable. */
function normalizeName(value) {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim().slice(0, MAX_NAME_LENGTH);
    return trimmed || null;
}

/**
 * Normalises a client-supplied monster into the shape MonsterInstance expects.
 * Applied on both add and update so the bounds cannot be bypassed by adding a
 * legal monster and then updating it.
 */
function sanitizeMonster(raw) {
    const m = raw && typeof raw === 'object' ? raw : {};
    return {
        id: typeof m.id === 'string' && m.id ? m.id : uuidv4(),
        name: typeof m.name === 'string' ? m.name.trim().slice(0, 80) : 'Monstruo',
        baseLevel: clampInt(m.baseLevel, 1, 20, 1),
        flatModifier: clampInt(m.flatModifier, -10, 10, 0),
        levels: clampInt(m.levels, 1, 5, 1),
        treasures: clampInt(m.treasures, 0, 10, 1),
        isUndead: m.isUndead === true,
        conditionalModifiers: Array.isArray(m.conditionalModifiers)
            ? m.conditionalModifiers.slice(0, 10)
            : []
    };
}

/** Normalises a client-supplied temporary combat bonus. */
function sanitizeBonus(raw) {
    const b = raw && typeof raw === 'object' ? raw : {};
    return {
        id: typeof b.id === 'string' && b.id ? b.id : uuidv4(),
        label: typeof b.label === 'string' ? b.label.trim().slice(0, 40) : 'Bonus',
        amount: clampInt(b.amount, -MODIFIER_LIMIT, MODIFIER_LIMIT, 0),
        appliesTo: b.appliesTo === 'MONSTER' ? 'MONSTER' : 'HEROES'
    };
}

module.exports = {
    VALID_GENDERS,
    VALID_CLASSES,
    VALID_RACES,
    DEFAULT_GENDER,
    MAX_PLAYERS,
    GEAR_LIMIT,
    STEP_LIMIT,
    MAX_NAME_LENGTH,
    MAX_AVATAR_ID,
    MAX_MONSTERS_PER_COMBAT,
    MAX_BONUSES_PER_COMBAT,
    MODIFIER_LIMIT,
    clampInt,
    stepAmount,
    normalizeGender,
    normalizeName,
    sanitizeMonster,
    sanitizeBonus
};
