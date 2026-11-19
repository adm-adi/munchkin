/**
 * Wire protocol for the Munchkin web client.
 *
 * Mirrors network/Protocol.kt and core/Events.kt from the Android app: the
 * server speaks one dialect and the Android client decodes it strictly, so
 * every message built here must match the @SerialName types and field names
 * exactly. Events are re-broadcast verbatim to Android clients, whose
 * kotlinx.serialization decoding throws on a missing field with no default —
 * that is why the builders below always send every field, defaults included
 * (the Kotlin client itself encodes with encodeDefaults = true).
 */

export const MSG = {
    // Client → server
    CREATE_GAME: 'CREATE_GAME',
    HELLO: 'HELLO',
    EVENT_REQUEST: 'EVENT_REQUEST',
    PING: 'PING',
    LIST_GAMES: 'LIST_GAMES',
    REGISTER: 'REGISTER',
    LOGIN: 'LOGIN',
    LOGIN_WITH_TOKEN: 'LOGIN_WITH_TOKEN',
    UPDATE_PROFILE: 'UPDATE_PROFILE',
    DELETE_ACCOUNT: 'DELETE_ACCOUNT',
    GET_HISTORY: 'GET_HISTORY',
    GET_LEADERBOARD: 'GET_LEADERBOARD',
    GET_HOSTED_GAMES: 'GET_HOSTED_GAMES',
    DELETE_HOSTED_GAME: 'DELETE_HOSTED_GAME',
    CATALOG_SEARCH: 'CATALOG_SEARCH',
    CATALOG_ADD: 'CATALOG_ADD',
    GAME_OVER: 'GAME_OVER',
    END_TURN: 'END_TURN',
    KICK_PLAYER: 'KICK_PLAYER',
    SWAP_PLAYERS: 'SWAP_PLAYERS',
    TRANSFER_HOST: 'TRANSFER_HOST',
    DELETE_GAME: 'DELETE_GAME',

    // Server → client
    WELCOME: 'WELCOME',
    STATE_SNAPSHOT: 'STATE_SNAPSHOT',
    EVENT_BROADCAST: 'EVENT_BROADCAST',
    PLAYER_STATUS: 'PLAYER_STATUS',
    ERROR: 'ERROR',
    PONG: 'PONG',
    GAMES_LIST: 'GAMES_LIST',
    AUTH_SUCCESS: 'AUTH_SUCCESS',
    PROFILE_UPDATED: 'PROFILE_UPDATED',
    ACCOUNT_DELETED: 'ACCOUNT_DELETED',
    HISTORY_RESULT: 'HISTORY_RESULT',
    LEADERBOARD_RESULT: 'LEADERBOARD_RESULT',
    HOSTED_GAMES_RESULT: 'HOSTED_GAMES_RESULT',
    HOSTED_GAME_DELETED: 'HOSTED_GAME_DELETED',
    CATALOG_SEARCH_RESULT: 'CATALOG_SEARCH_RESULT',
    CATALOG_ADD_SUCCESS: 'CATALOG_ADD_SUCCESS',
    COMBAT_DICE_ROLL_RESULT: 'COMBAT_DICE_ROLL_RESULT',
    GAME_DELETED: 'GAME_DELETED'
};

export const GENDERS = ['M', 'F', 'NA'];
export const CLASSES = ['NONE', 'WARRIOR', 'WIZARD', 'THIEF', 'CLERIC'];
export const RACES = ['HUMAN', 'ELF', 'DWARF', 'HALFLING'];
export const PHASES = { LOBBY: 'LOBBY', IN_GAME: 'IN_GAME', FINISHED: 'FINISHED' };
export const OUTCOMES = { WIN: 'WIN', LOSE: 'LOSE', ESCAPE: 'ESCAPE' };
export const BONUS_TARGETS = { HEROES: 'HEROES', MONSTER: 'MONSTER' };
export const DICE_PURPOSES = ['COMBAT', 'RUN_AWAY', 'CURSE', 'RANDOM', 'TIE_BREAKER'];

/** Mirrors validation.js server-side: what the server will accept. */
export const LIMITS = {
    MAX_PLAYERS: 6,
    MAX_NAME_LENGTH: 20,
    MAX_AVATAR_ID: 100,
    AVATAR_COUNT: 8,
    MAX_MONSTERS_PER_COMBAT: 6,
    MAX_BONUSES_PER_COMBAT: 20,
    MODIFIER_LIMIT: 9999,
    MAX_TURN_TIMER_SECONDS: 7200,
    JOIN_CODE_LENGTH: 8,
    MIN_PASSWORD_LENGTH: 8
};

export function uuid() {
    if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
    // Older WebViews: RFC-4122-shaped fallback, random enough for event ids.
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

export function playerMeta({ playerId = null, name, avatarId = 0, gender = 'NA', userId = null }) {
    return {
        playerId: playerId || uuid(),
        name,
        avatarId,
        gender,
        userId
    };
}

// ============== Connection / auth messages ==============

export const messages = {
    createGame(meta, { superMunchkin = false, turnTimerSeconds = 0 } = {}) {
        return { type: MSG.CREATE_GAME, playerMeta: meta, superMunchkin, turnTimerSeconds };
    },
    hello(joinCode, meta, reconnectToken = null) {
        return { type: MSG.HELLO, gameId: '', joinCode, playerMeta: meta, reconnectToken };
    },
    eventRequest(event) {
        return { type: MSG.EVENT_REQUEST, event };
    },
    ping() {
        return { type: MSG.PING, timestamp: Date.now() };
    },
    listGames() {
        return { type: MSG.LIST_GAMES };
    },
    register({ username, email, password, avatarId = 0, gender = 'M' }) {
        return { type: MSG.REGISTER, username, email, password, avatarId, gender };
    },
    login(email, password) {
        return { type: MSG.LOGIN, email, password };
    },
    loginWithToken(token) {
        return { type: MSG.LOGIN_WITH_TOKEN, token };
    },
    updateProfile(userIdOrOpts = {}, maybeOpts) {
        const opts = (typeof userIdOrOpts === 'object' && userIdOrOpts !== null)
            ? userIdOrOpts
            : { userId: userIdOrOpts, ...(maybeOpts || {}) };
        const {
            userId = null,
            username = null,
            email = null,
            password = null,
            currentPassword = null,
            avatarId = null,
            gender = null
        } = opts;
        const msg = { type: MSG.UPDATE_PROFILE };
        if (userId !== undefined && userId !== null && userId !== '') msg.userId = userId;
        if (username !== undefined && username !== null && username !== '') msg.username = username;
        if (email !== undefined && email !== null && email !== '') msg.email = email;
        if (password !== undefined && password !== null && password !== '') msg.password = password;
        if (currentPassword !== undefined && currentPassword !== null && currentPassword !== '') {
            msg.currentPassword = currentPassword;
        }
        if (avatarId !== undefined && avatarId !== null && avatarId !== '') msg.avatarId = avatarId;
        if (gender !== undefined && gender !== null && gender !== '') msg.gender = gender;
        return msg;
    },
    deleteAccount(password) {
        return { type: MSG.DELETE_ACCOUNT, password };
    },
    getHistory(userId) {
        return { type: MSG.GET_HISTORY, userId };
    },
    getLeaderboard() {
        return { type: MSG.GET_LEADERBOARD };
    },
    getHostedGames() {
        return { type: MSG.GET_HOSTED_GAMES };
    },
    deleteHostedGame(gameId) {
        return { type: MSG.DELETE_HOSTED_GAME, gameId };
    },
    catalogSearch(query, optsOrLimit = {}) {
        const opts = typeof optsOrLimit === 'number'
            ? { limit: optsOrLimit }
            : (optsOrLimit || {});
        const { expansion = null, limit = 20, offset = 0 } = opts;
        const msg = { type: MSG.CATALOG_SEARCH, query, limit, offset };
        if (expansion !== undefined && expansion !== null && expansion !== '') {
            msg.expansion = expansion;
        }
        return msg;
    },
    catalogAdd(monster, userId = null) {
        return { type: MSG.CATALOG_ADD, monster, userId };
    },
    gameOver(gameId, winnerId) {
        return { type: MSG.GAME_OVER, gameId, winnerId };
    },
    kickPlayer(targetPlayerId) {
        return { type: MSG.KICK_PLAYER, targetPlayerId };
    },
    swapPlayers(player1, player2) {
        return { type: MSG.SWAP_PLAYERS, player1, player2 };
    },
    transferHost(targetPlayerId) {
        return { type: MSG.TRANSFER_HOST, targetPlayerId };
    },
    deleteGame() {
        return { type: MSG.DELETE_GAME, timestamp: Date.now() };
    }
};

// ============== Game events (EVENT_REQUEST payloads) ==============

function base(type, actorId, targetPlayerId = null) {
    return { type, eventId: uuid(), actorId, timestamp: Date.now(), targetPlayerId };
}

export const events = {
    gameStart(actorId) {
        return base('GAME_START', actorId);
    },
    gameEnd(actorId, winnerId = null) {
        return { ...base('GAME_END', actorId), winnerId };
    },
    endTurn(actorId) {
        return base('END_TURN', actorId);
    },
    playerRoll(actorId, result, purpose = 'RANDOM', success = false, modifier = 0) {
        return { ...base('PLAYER_ROLL', actorId, actorId), result, purpose, success, modifier };
    },
    incLevel(actorId, amount = 1) {
        return { ...base('INC_LEVEL', actorId, actorId), amount, reason: null };
    },
    decLevel(actorId, amount = 1) {
        return { ...base('DEC_LEVEL', actorId, actorId), amount };
    },
    setLevel(actorId, level) {
        return { ...base('SET_LEVEL', actorId, actorId), level };
    },
    incGear(actorId, amount = 1) {
        return { ...base('INC_GEAR', actorId, actorId), amount };
    },
    decGear(actorId, amount = 1) {
        return { ...base('DEC_GEAR', actorId, actorId), amount };
    },
    setGear(actorId, gear) {
        return { ...base('SET_GEAR', actorId, actorId), gear };
    },
    setName(actorId, name) {
        return { ...base('SET_NAME', actorId, actorId), name };
    },
    setAvatar(actorId, avatarId) {
        return { ...base('SET_AVATAR', actorId, actorId), avatarId };
    },
    setGender(actorId, gender) {
        return { ...base('SET_GENDER', actorId, actorId), gender };
    },
    setClass(actorId, newClass, isSecondary = false) {
        return { ...base('SET_CLASS', actorId, actorId), newClass, isSecondary };
    },
    setRace(actorId, newRace, isSecondary = false) {
        return { ...base('SET_RACE', actorId, actorId), newRace, isSecondary };
    },
    setHalfBreed(actorId, enabled) {
        return { ...base('SET_HALF_BREED', actorId, actorId), enabled };
    },
    setSuperMunchkin(actorId, enabled) {
        return { ...base('SET_SUPER_MUNCHKIN', actorId, actorId), enabled };
    },
    combatStart(actorId, mainPlayerId) {
        return { ...base('COMBAT_START', actorId), mainPlayerId };
    },
    combatAddHelper(actorId, helperId) {
        return { ...base('COMBAT_ADD_HELPER', actorId), helperId };
    },
    combatRemoveHelper(actorId) {
        return base('COMBAT_REMOVE_HELPER', actorId);
    },
    combatAddMonster(actorId, monster) {
        return { ...base('COMBAT_ADD_MONSTER', actorId), monster };
    },
    combatUpdateMonster(actorId, monster) {
        return { ...base('COMBAT_UPDATE_MONSTER', actorId), monster };
    },
    combatRemoveMonster(actorId, monsterId) {
        return { ...base('COMBAT_REMOVE_MONSTER', actorId), monsterId };
    },
    combatAddBonus(actorId, bonus) {
        return { ...base('COMBAT_ADD_BONUS', actorId), bonus };
    },
    combatRemoveBonus(actorId, bonusId) {
        return { ...base('COMBAT_REMOVE_BONUS', actorId), bonusId };
    },
    combatSetModifier(actorId, target, value) {
        return { ...base('COMBAT_SET_MODIFIER', actorId), target, value };
    },
    combatEnd(actorId, outcome, {
        levelsGained = 0,
        treasuresGained = 0,
        helperLevelsGained = 0,
        expectedSeq = null,
        cancelled = undefined
    } = {}) {
        const event = {
            ...base('COMBAT_END', actorId),
            outcome,
            levelsGained,
            treasuresGained,
            helperLevelsGained
        };
        if (expectedSeq !== undefined && expectedSeq !== null) event.expectedSeq = expectedSeq;
        if (cancelled !== undefined && cancelled !== null) event.cancelled = Boolean(cancelled);
        return event;
    }
};

/** Builds a MonsterInstance in the exact shape core/Combat.kt expects. */
export function monsterInstance({
    id = null,
    name,
    baseLevel,
    flatModifier = 0,
    treasures = 1,
    levels = 1,
    isUndead = false,
    badStuff = '',
    conditionalModifiers = []
}) {
    return {
        id: id || uuid(),
        name,
        baseLevel,
        flatModifier,
        treasures,
        levels,
        isUndead,
        badStuff,
        conditionalModifiers
    };
}

/** Builds a TempBonus in the exact shape core/Combat.kt expects. */
export function tempBonus({ id = null, label, amount, appliesTo }) {
    return { id: id || uuid(), label, amount, appliesTo };
}
