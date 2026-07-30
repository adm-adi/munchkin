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
// 2 hours. The real bound is setTimeout's 32-bit delay (~24.8 days): past it the
// timer fires immediately instead of never, turning the turn timer into a busy
// loop. No table needs a longer turn than this anyway.
const MAX_TURN_TIMER_SECONDS = 7200;
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

// These must mirror the Kotlin enums in core/Combat.kt exactly. A value outside
// them inside a broadcast snapshot makes every client's strict decoding throw.
const VALID_MODIFIER_SIDES = new Set(['MONSTER', 'HEROES']);
const VALID_CONDITION_TYPES = new Set(['RACE_ID', 'CLASS_ID', 'GENDER']);
const VALID_MODIFIER_SCOPES = new Set(['MAIN_ONLY', 'HELPER_ONLY', 'ANY_PARTICIPANT']);
const VALID_APPLY_MODES = new Set(['ONCE_IF_MATCH', 'PER_MATCHING_PLAYER']);

/**
 * Normalises one conditional modifier into the exact shape the Kotlin
 * ConditionalModifier expects, or returns null when it cannot be salvaged.
 *
 * Bounding the list length alone was not enough: the entries themselves were
 * stored and broadcast verbatim, so `{"side":"BANANA"}` from one client broke
 * snapshot decoding for the whole room — and persisted via combat_json.
 */
function sanitizeConditionalModifier(raw) {
    const m = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : null;
    if (!m) return null;
    if (!VALID_MODIFIER_SIDES.has(m.side)) return null;
    if (!VALID_CONDITION_TYPES.has(m.conditionType)) return null;
    return {
        id: typeof m.id === 'string' && m.id ? m.id.slice(0, 64) : uuidv4(),
        amount: clampInt(m.amount, -MODIFIER_LIMIT, MODIFIER_LIMIT, 0),
        side: m.side,
        conditionType: m.conditionType,
        conditionValue: typeof m.conditionValue === 'string' ? m.conditionValue.slice(0, 40) : '',
        scope: VALID_MODIFIER_SCOPES.has(m.scope) ? m.scope : 'ANY_PARTICIPANT',
        applyMode: VALID_APPLY_MODES.has(m.applyMode) ? m.applyMode : 'ONCE_IF_MATCH'
    };
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
        // Dropped entirely before, so the "bad stuff" text a player typed or
        // pulled from the catalog was blanked for the whole room on the next
        // snapshot.
        badStuff: typeof m.badStuff === 'string' ? m.badStuff.trim().slice(0, 200) : '',
        conditionalModifiers: Array.isArray(m.conditionalModifiers)
            ? m.conditionalModifiers.slice(0, 10)
                .map(sanitizeConditionalModifier)
                .filter(Boolean)
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
    VALID_MODIFIER_SIDES,
    VALID_CONDITION_TYPES,
    VALID_MODIFIER_SCOPES,
    VALID_APPLY_MODES,
    DEFAULT_GENDER,
    MAX_PLAYERS,
    GEAR_LIMIT,
    STEP_LIMIT,
    MAX_NAME_LENGTH,
    MAX_AVATAR_ID,
    MAX_MONSTERS_PER_COMBAT,
    MAX_BONUSES_PER_COMBAT,
    MAX_TURN_TIMER_SECONDS,
    MODIFIER_LIMIT,
    clampInt,
    stepAmount,
    normalizeGender,
    normalizeName,
    sanitizeMonster,
    sanitizeConditionalModifier,
    sanitizeBonus
};
