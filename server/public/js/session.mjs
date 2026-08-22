/**
 * Local persistence: auth session, player identity prefs, and the current seat.
 *
 * Mirrors what the Android app keeps in SessionManager/SavedGameDao — the JWT,
 * the profile, and per-game seat credentials (playerId + rotating
 * reconnectToken) so a page reload or an iOS tab suspension can reclaim the
 * same seat instead of joining as a stranger.
 *
 * Every access is guarded: localStorage can throw in private browsing and
 * blocked-storage contexts, and the app must still work (as a guest, per tab).
 */

const KEY = 'munchkin.v1';

function read() {
    try {
        return JSON.parse(localStorage.getItem(KEY)) || {};
    } catch {
        return {};
    }
}

function write(data) {
    try {
        localStorage.setItem(KEY, JSON.stringify(data));
    } catch {
        // Storage unavailable: the session just won't survive a reload.
    }
}

function update(patch) {
    write({ ...read(), ...patch });
}

export const session = {
    // ---- Account (JWT + profile) ----
    get authToken() { return read().authToken || null; },
    get profile() { return read().profile || null; },
    saveAuth(token, profile) { update({ authToken: token, profile }); },
    updateProfile(profile) { update({ profile }); },
    clearAuth() { update({ authToken: null, profile: null }); },

    // ---- Identity prefs for guests (name/avatar/gender pre-fill) ----
    get identity() { return read().identity || null; },
    saveIdentity(identity) { update({ identity }); },

    // ---- Current seat (survives reload / suspension) ----
    get seat() { return read().seat || null; },
    saveSeat(seat) { update({ seat: { ...seat, savedAt: Date.now() } }); },
    updateSeatToken(reconnectToken) {
        const seat = read().seat;
        if (seat) update({ seat: { ...seat, reconnectToken, savedAt: Date.now() } });
    },
    clearSeat() { update({ seat: null }); }
};
