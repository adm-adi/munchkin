/**
 * Local persistence: auth session, player identity prefs, and the current seat.
 *
 * Stored under separate localStorage keys (`munchkin.auth`, `munchkin.identity`,
 * `munchkin.seat`) so a corrupted entry in one domain never wipes the others.
 * Every access is guarded: localStorage can throw in private browsing and
 * blocked-storage contexts, and the app must still work (as a guest, per tab).
 */

const KEYS = {
    auth: 'munchkin.auth',
    identity: 'munchkin.identity',
    seat: 'munchkin.seat'
};

function readKey(key) {
    try {
        const raw = globalThis.localStorage?.getItem(key);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
    } catch {
        return null;
    }
}

function writeKey(key, value) {
    try {
        const storage = globalThis.localStorage;
        if (!storage) return;
        if (value === null || value === undefined) {
            storage.removeItem(key);
        } else {
            storage.setItem(key, JSON.stringify(value));
        }
    } catch {
        // Storage unavailable: the session just won't survive a reload.
    }
}

export const session = {
    // ---- Account (JWT + profile) ----
    get authToken() { return readKey(KEYS.auth)?.authToken || null; },
    get profile() { return readKey(KEYS.auth)?.profile || null; },
    saveAuth(token, profile) {
        if (token !== null && token !== undefined && typeof token !== 'string') {
            throw new TypeError('session.saveAuth expects (token, profile)');
        }
        writeKey(KEYS.auth, { authToken: token || null, profile: profile || null });
    },
    updateProfile(profile) {
        const current = readKey(KEYS.auth) || {};
        writeKey(KEYS.auth, { ...current, profile: profile || null });
    },
    clearAuth() {
        writeKey(KEYS.auth, null);
        writeKey(KEYS.seat, null);
        writeKey(KEYS.identity, null);
    },

    // ---- Identity prefs for guests (name/avatar/gender pre-fill) ----
    get identity() { return readKey(KEYS.identity); },
    saveIdentity(identity) { writeKey(KEYS.identity, identity || null); },

    // ---- Current seat (survives reload / suspension) ----
    get seat() { return readKey(KEYS.seat); },
    saveSeat(seat) {
        writeKey(KEYS.seat, seat ? { ...seat, savedAt: Date.now() } : null);
    },
    updateSeatToken(reconnectToken) {
        const seat = readKey(KEYS.seat);
        if (seat) writeKey(KEYS.seat, { ...seat, reconnectToken, savedAt: Date.now() });
    },
    clearSeat() { writeKey(KEYS.seat, null); }
};
