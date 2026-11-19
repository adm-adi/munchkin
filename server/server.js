/**
 * Munchkin Tracker - WebSocket Server
 *
 * IMPORTANT: This server must send JSON that matches the kotlinx.serialization
 * format expected by the Android client (Protocol.kt / Models.kt).
 */

const WebSocket = require('ws');
const http = require('http');
const https = require('https');
const net = require('net');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const db = require('./db');
const { createStaticFileServer } = require('./staticFiles');
const { createAuthManager } = require('./authManager');
const { createCatalogManager } = require('./catalogManager');
const {
    createCombatManager,
    VALID_DICE_PURPOSES,
    getRunAwayBonus,
    requiresCombatToWin
} = require('./combatManager');
const { createGameAdminManager } = require('./gameAdminManager');
const { createHistoryManager } = require('./historyManager');
const { createTurnManager } = require('./turnManager');
const helmet = /** @type {any} */ (require('helmet'));
const logger = require('./logger');

/**
 * @typedef {import('ws').WebSocket & {
 *   clientIp?: string,
 *   connectionId?: string,
 *   isAlive?: boolean,
 *   userId?: string | null,
 *   _authPromise?: Promise<any> | null,
 *   _authQueueDepth?: number,
 *   _msgWindowStart?: number,
 *   _msgCount?: number
 * }} AppWebSocket
 */

const {
    VALID_GENDERS,
    VALID_CLASSES,
    VALID_RACES,
    DEFAULT_GENDER,
    MAX_PLAYERS,
    GEAR_LIMIT,
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
    sanitizeBonus
} = require('./validation');

const MIN_JWT_SECRET_LENGTH = 32;
const JWT_EXPIRY_SECONDS = 48 * 60 * 60; // 48 hours
const HEARTBEAT_INTERVAL_MS = 25 * 1000;
const MAX_RATE_LIMIT_ENTRIES = 10_000;
const DEFAULT_ALLOWED_ORIGINS = new Set([
    'capacitor://localhost',
    'http://localhost',
    'https://localhost',
    'app://localhost'
]);

function isPlainObject(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        return false;
    }
    const proto = Object.getPrototypeOf(value);
    return proto === Object.prototype || proto === null;
}

/**
 * Inserts/updates a rate-limit entry in O(1) LRU order and evicts oldest entries
 * when the bounded map exceeds MAX_RATE_LIMIT_ENTRIES.
 * @template V
 * @param {Map<string, V>} map
 * @param {string} key
 * @param {V} entry
 */
function setBoundedMapEntry(map, key, entry) {
    map.delete(key);
    map.set(key, entry);
    while (map.size > MAX_RATE_LIMIT_ENTRIES) {
        const oldestKey = map.keys().next().value;
        if (oldestKey === undefined) break;
        map.delete(oldestKey);
    }
}

function generateJoinCode() {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 8; i++) {
        code += chars.charAt(crypto.randomInt(chars.length));
    }
    return code;
}

function generateReconnectToken() {
    return crypto.randomBytes(32).toString('base64url');
}

function hashReconnectToken(token) {
    return crypto.createHash('sha256').update(token, 'utf8').digest('base64url');
}

function timingSafeEqualString(a, b) {
    const left = Buffer.from(a || '', 'utf8');
    const right = Buffer.from(b || '', 'utf8');
    return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function rotateReconnectToken(player) {
    const token = generateReconnectToken();
    player.reconnectTokenHash = hashReconnectToken(token);
    return token;
}

function canReconnectPlayer(ws, player, reconnectToken) {
    if (player.userId && ws.userId && player.userId === ws.userId) {
        return true;
    }
    if (!reconnectToken || typeof reconnectToken !== 'string' || !player.reconnectTokenHash) {
        return false;
    }
    return timingSafeEqualString(hashReconnectToken(reconnectToken), player.reconnectTokenHash);
}

const SNAPSHOT_FIRST_EVENT_TYPES = new Set([
    'GAME_START',
    'GAME_END',
    'END_TURN',
    'PLAYER_ROLL',
    'COMBAT_START',
    'COMBAT_END',
    'COMBAT_ADD_MONSTER',
    'COMBAT_REMOVE_MONSTER',
    'COMBAT_UPDATE_MONSTER',
    'COMBAT_ADD_HELPER',
    'COMBAT_REMOVE_HELPER',
    'COMBAT_SET_MODIFIER',
    'COMBAT_ADD_BONUS',
    'COMBAT_REMOVE_BONUS'
]);

const SAVEABLE_EVENT_TYPES = new Set([
    'GAME_START',
    'INC_LEVEL', 'DEC_LEVEL', 'SET_LEVEL',
    'INC_GEAR', 'DEC_GEAR', 'SET_GEAR',
    'SET_CLASS', 'SET_RACE',
    'SET_HALF_BREED', 'SET_SUPER_MUNCHKIN',
    'END_TURN', 'PLAYER_ROLL',
    'COMBAT_START', 'COMBAT_END',
    'COMBAT_ADD_MONSTER', 'COMBAT_REMOVE_MONSTER', 'COMBAT_UPDATE_MONSTER',
    'COMBAT_ADD_HELPER', 'COMBAT_REMOVE_HELPER',
    'COMBAT_SET_MODIFIER', 'COMBAT_ADD_BONUS', 'COMBAT_REMOVE_BONUS'
]);

const IN_GAME_ONLY_EVENTS = new Set([
    'INC_LEVEL', 'DEC_LEVEL', 'SET_LEVEL',
    'INC_GEAR', 'DEC_GEAR', 'SET_GEAR',
    'SET_CLASS', 'SET_RACE',
    'SET_HALF_BREED', 'SET_SUPER_MUNCHKIN',
    'END_TURN',
    'COMBAT_START', 'COMBAT_END',
    'COMBAT_ADD_MONSTER', 'COMBAT_REMOVE_MONSTER', 'COMBAT_UPDATE_MONSTER',
    'COMBAT_ADD_HELPER', 'COMBAT_REMOVE_HELPER',
    'COMBAT_SET_MODIFIER', 'COMBAT_ADD_BONUS', 'COMBAT_REMOVE_BONUS'
]);

const AUTH_CHAINED_MESSAGES = new Set([
    'REGISTER',
    'LOGIN',
    'LOGIN_WITH_TOKEN',
    'UPDATE_PROFILE',
    'DELETE_ACCOUNT'
]);

function createAppServer(options = {}) {
    const port = options.port !== undefined ? Number(options.port) : (process.env.PORT !== undefined ? Number(process.env.PORT) : 8765);
    const bindHost = options.host || process.env.MUNCHKIN_HOST || '0.0.0.0';
    const jwtSecret = options.jwtSecret !== undefined ? options.jwtSecret : process.env.JWT_SECRET;
    if (typeof jwtSecret !== 'string' || jwtSecret.length < MIN_JWT_SECRET_LENGTH) {
        throw new Error(`JWT_SECRET must be set and at least ${MIN_JWT_SECRET_LENGTH} characters long.`);
    }

    const tlsKeyPath = options.tlsKeyPath || process.env.MUNCHKIN_TLS_KEY || 'key.pem';
    const tlsCertPath = options.tlsCertPath || process.env.MUNCHKIN_TLS_CERT || 'cert.pem';
    const tlsExplicitlyConfigured = Boolean(
        options.tlsKeyPath || options.tlsCertPath || process.env.MUNCHKIN_TLS_KEY || process.env.MUNCHKIN_TLS_CERT
    );
    const trustProxy = options.trustProxy !== undefined
        ? Boolean(options.trustProxy)
        : (process.env.MUNCHKIN_TRUST_PROXY === '1' || process.env.MUNCHKIN_TRUST_PROXY === 'true');
    const trustedProxyHops = Math.max(1, clampInt(options.trustedProxyHops ?? process.env.MUNCHKIN_TRUSTED_PROXY_HOPS, 1, 10, 1));
    const trustedProxyIps = new Set(
        String(options.trustedProxyIps || process.env.MUNCHKIN_TRUSTED_PROXIES || '127.0.0.1,::1,::ffff:127.0.0.1')
            .split(',')
            .map(s => s.trim())
            .filter(Boolean)
    );
    const createGameMaxPerMin = Number(options.createGameLimit ?? process.env.MUNCHKIN_CREATE_GAME_LIMIT) || 10;
    const maxActiveGames = Number(options.maxActiveGames ?? process.env.MUNCHKIN_MAX_ACTIVE_GAMES) || 500;
    const maxConnectionsPerIp = Number(options.maxConnectionsPerIp ?? process.env.MUNCHKIN_MAX_CONNS_PER_IP) || 40;
    const maxTotalConnections = Number(options.maxTotalConnections ?? process.env.MUNCHKIN_MAX_CONNS) || 2000;
    const maxAuthQueueDepth = 32;
    const maxMessagesPerWindow = 120;
    const msgWindowMs = 10 * 1000;
    const activeConnsByIp = new Map(); // IP -> count

    const extraOriginsRaw = options.allowedOrigins || process.env.MUNCHKIN_ALLOWED_ORIGINS || '';
    const extraOrigins = new Set(
        String(extraOriginsRaw)
            .split(',')
            .map(s => s.trim())
            .filter(Boolean)
    );

    const games = new Map(); // gameId -> GameRoom
    const gamesByCode = new Map(); // UPPERCASE joinCode -> GameRoom
    const clientGames = new Map(); // ws -> { gameId, playerId }
    const pendingSaves = new Map(); // gameId -> timeoutHandle

    // Rate limiting maps (bounded with O(1) LRU eviction)
    const apiRateLimits = new Map(); // IP -> { count, windowStart }
    const API_RATE_LIMIT_MAX = 60;
    const API_RATE_LIMIT_WINDOW_MS = 60 * 1000;

    const joinRateLimits = new Map(); // IP -> { attempts, lastAttempt }
    const JOIN_RATE_LIMIT_MAX_ATTEMPTS = 10;
    const JOIN_RATE_LIMIT_WINDOW_MS = 60 * 1000;

    const createGameRateLimits = new Map(); // IP -> { count, windowStart }
    const CREATE_GAME_WINDOW_MS = 60 * 1000;

    function registerGame(game) {
        games.set(game.id, game);
        if (game.joinCode) {
            gamesByCode.set(game.joinCode.toUpperCase(), game);
        }
    }

    function removeGame(gameId) {
        const existing = games.get(gameId);
        if (existing && existing.joinCode) {
            const codeKey = existing.joinCode.toUpperCase();
            if (gamesByCode.get(codeKey) === existing) {
                gamesByCode.delete(codeKey);
            }
        }
        games.delete(gameId);
    }

    function findGameByCode(joinCode) {
        if (typeof joinCode !== 'string' || !joinCode) return null;
        return gamesByCode.get(joinCode.toUpperCase()) || null;
    }

    function generateUniqueJoinCode() {
        for (let attempt = 0; attempt < 25; attempt++) {
            const code = generateJoinCode();
            if (!findGameByCode(code)) return code;
        }
        return null;
    }

    function isTrustedProxyAddress(addr) {
        if (!addr || typeof addr !== 'string') return false;
        const normalized = addr.startsWith('::ffff:') ? addr.slice(7) : addr;
        return trustedProxyIps.has(addr) || trustedProxyIps.has(normalized);
    }

    function getClientIp(req) {
        const remoteAddr = req.socket?.remoteAddress || 'unknown';
        if (trustProxy && isTrustedProxyAddress(remoteAddr)) {
            const xff = req.headers['x-forwarded-for'];
            const raw = Array.isArray(xff) ? xff.join(',') : (typeof xff === 'string' ? xff : '');
            const parts = raw
                .split(',')
                .map(s => s.trim())
                .filter(s => net.isIP(s) !== 0);
            if (parts.length > 0) {
                const idx = Math.max(0, parts.length - trustedProxyHops);
                return parts[idx];
            }
        }
        return remoteAddr;
    }

    function isAllowedOrigin(origin, hostHeader) {
        if (!origin) return true;
        if (DEFAULT_ALLOWED_ORIGINS.has(origin) || extraOrigins.has(origin)) {
            return true;
        }
        let parsedOrigin;
        try {
            parsedOrigin = new URL(origin);
        } catch {
            return false;
        }
        if (hostHeader && parsedOrigin.host.toLowerCase() === String(hostHeader).toLowerCase()) {
            if (isSsl && parsedOrigin.protocol !== 'https:') {
                return false;
            }
            return true;
        }
        return extraOrigins.has(parsedOrigin.origin);
    }

    function safeSend(ws, payload) {
        if (!ws || ws.readyState !== WebSocket.OPEN) return false;
        try {
            const wire = typeof payload === 'string' ? payload : JSON.stringify(payload);
            ws.send(wire);
            return true;
        } catch (err) {
            logger.warn(`[${ws.connectionId || 'ws'}] Failed to send WS payload: ${err.message}`);
            return false;
        }
    }

    function sendError(ws, code, message) {
        safeSend(ws, {
            type: 'ERROR',
            code,
            message
        });
    }

    function isApiRateLimited(ip) {
        const record = apiRateLimits.get(ip);
        if (!record) return false;
        if (Date.now() - record.windowStart > API_RATE_LIMIT_WINDOW_MS) {
            apiRateLimits.delete(ip);
            return false;
        }
        return record.count >= API_RATE_LIMIT_MAX;
    }

    function recordApiRequest(ip) {
        const now = Date.now();
        const record = apiRateLimits.get(ip);
        const updated = (!record || now - record.windowStart > API_RATE_LIMIT_WINDOW_MS)
            ? { count: 1, windowStart: now }
            : { count: record.count + 1, windowStart: record.windowStart };
        setBoundedMapEntry(apiRateLimits, ip, updated);
    }

    function isJoinRateLimited(ip) {
        const record = joinRateLimits.get(ip);
        if (!record) return false;
        if (Date.now() - record.lastAttempt > JOIN_RATE_LIMIT_WINDOW_MS) {
            joinRateLimits.delete(ip);
            return false;
        }
        return record.attempts >= JOIN_RATE_LIMIT_MAX_ATTEMPTS;
    }

    function recordJoinAttempt(ip, success) {
        if (success) {
            joinRateLimits.delete(ip);
            return;
        }
        const record = joinRateLimits.get(ip) || { attempts: 0, lastAttempt: 0 };
        setBoundedMapEntry(joinRateLimits, ip, {
            attempts: record.attempts + 1,
            lastAttempt: Date.now()
        });
    }

    function isCreateGameRateLimited(ip) {
        const record = createGameRateLimits.get(ip);
        if (!record) return false;
        if (Date.now() - record.windowStart > CREATE_GAME_WINDOW_MS) {
            createGameRateLimits.delete(ip);
            return false;
        }
        return record.count >= createGameMaxPerMin;
    }

    function recordCreateGame(ip) {
        const now = Date.now();
        const record = createGameRateLimits.get(ip);
        const updated = (!record || now - record.windowStart > CREATE_GAME_WINDOW_MS)
            ? { count: 1, windowStart: now }
            : { count: record.count + 1, windowStart: record.windowStart };
        setBoundedMapEntry(createGameRateLimits, ip, updated);
    }

    function pruneExpiredRateLimits() {
        const now = Date.now();
        for (const [ip, record] of joinRateLimits) {
            if (now - record.lastAttempt > JOIN_RATE_LIMIT_WINDOW_MS) {
                joinRateLimits.delete(ip);
            }
        }
        for (const [ip, record] of apiRateLimits) {
            if (now - record.windowStart > API_RATE_LIMIT_WINDOW_MS) {
                apiRateLimits.delete(ip);
            }
        }
        for (const [ip, record] of createGameRateLimits) {
            if (now - record.windowStart > CREATE_GAME_WINDOW_MS) {
                createGameRateLimits.delete(ip);
            }
        }
    }

    const helmetMiddleware = typeof helmet === 'function' ? helmet : helmet.default;
    const applyHelmet = helmetMiddleware({
        hsts: {
            maxAge: 31536000,
            includeSubDomains: true
        },
        contentSecurityPolicy: {
            useDefaults: false,
            directives: {
                'default-src': ["'self'"],
                'script-src': ["'self'"],
                'style-src': ["'self'"],
                'img-src': ["'self'", 'data:'],
                'connect-src': ["'self'", 'ws:', 'wss:'],
                'manifest-src': ["'self'"],
                'base-uri': ["'self'"],
                'form-action': ["'self'"],
                'frame-ancestors': ["'none'"],
                'object-src': ["'none'"]
            }
        }
    });

    const serveStatic = createStaticFileServer({
        rootDir: path.join(__dirname, 'public'),
        logger
    });

    function handleRequest(req, res) {
        applyHelmet(req, res, () => {
            processRequest(req, res);
        });
    }

    function processRequest(req, res) {
        const origin = req.headers.origin;
        if (origin && (DEFAULT_ALLOWED_ORIGINS.has(origin) || extraOrigins.has(origin))) {
            res.setHeader('Access-Control-Allow-Origin', origin);
        }
        res.setHeader('Access-Control-Allow-Methods', 'OPTIONS, GET');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
        res.setHeader('Vary', 'Origin');

        if (req.method === 'OPTIONS') {
            res.writeHead(200);
            res.end();
            return;
        }

        let parsedUrl;
        try {
            parsedUrl = new URL(req.url || '/', 'http://localhost');
        } catch (e) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Invalid URL' }));
            return;
        }

        if (req.method === 'GET' && parsedUrl.pathname === '/api/monsters') {
            const clientIp = getClientIp(req);
            if (isApiRateLimited(clientIp)) {
                res.writeHead(429, { 'Content-Type': 'application/json', 'Retry-After': '60' });
                res.end(JSON.stringify({ error: 'Too many requests' }));
                return;
            }
            recordApiRequest(clientIp);

            const query = (parsedUrl.searchParams.get('q') || '').slice(0, 100);
            const rawExpansion = parsedUrl.searchParams.get('expansion');
            const expansion = rawExpansion && rawExpansion.trim()
                ? rawExpansion.trim().slice(0, 40)
                : undefined;
            const limit = clampInt(parsedUrl.searchParams.get('limit'), 1, 50, 20);
            const offset = clampInt(parsedUrl.searchParams.get('offset'), 0, 10_000, 0);

            db.searchMonsters(query, { expansion, limit, offset })
                .then(rows => {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify(rows));
                })
                .catch(err => {
                    logger.error('API monster search failed:', err);
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Internal server error' }));
                });
            return;
        }

        if (req.method === 'GET' && parsedUrl.pathname === '/health') {
            db.pingDb()
                .then(() => {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ status: 'ok', games: games.size, uptime: process.uptime() }));
                })
                .catch(err => {
                    logger.error('/health DB check failed:', err);
                    res.writeHead(503, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ status: 'error' }));
                });
            return;
        }

        if (serveStatic(req, res, parsedUrl.pathname)) {
            return;
        }

        res.writeHead(404);
        res.end();
    }

    let httpServer;
    let isSsl = false;
    try {
        if (fs.existsSync(tlsKeyPath) && fs.existsSync(tlsCertPath)) {
            const tlsOptions = {
                key: fs.readFileSync(tlsKeyPath),
                cert: fs.readFileSync(tlsCertPath)
            };
            httpServer = https.createServer(tlsOptions, handleRequest);
            isSsl = true;
            logger.info('🔒 SSL Certificates found. Starting in HTTPS/WSS mode.');
        } else if (tlsExplicitlyConfigured) {
            throw new Error(`TLS was configured (${tlsKeyPath} / ${tlsCertPath}) but the files are missing or unreadable`);
        } else {
            httpServer = http.createServer(handleRequest);
            logger.info('⚠️ No SSL Certificates found (key.pem/cert.pem). Starting in HTTP/WS mode.');
        }
    } catch (e) {
        if (tlsExplicitlyConfigured) {
            throw e;
        }
        logger.error('Failed to load SSL certs, falling back to HTTP:', e);
        httpServer = http.createServer(handleRequest);
    }

    const wss = new WebSocket.Server({
        server: httpServer,
        maxPayload: 50 * 1024,
        verifyClient: (info, callback) => {
            const origin = info.req.headers.origin;
            const hostHeader = info.req.headers.host;
            if (!isAllowedOrigin(origin, hostHeader)) {
                logger.warn(`Rejected WebSocket upgrade from disallowed origin: ${origin}`);
                callback(false, 403, 'Forbidden Origin');
                return;
            }
            if (wss.clients.size >= maxTotalConnections) {
                logger.warn('Rejected WebSocket upgrade: global connection cap reached');
                callback(false, 503, 'Server Busy');
                return;
            }
            const clientIp = getClientIp(info.req);
            if ((activeConnsByIp.get(clientIp) || 0) >= maxConnectionsPerIp) {
                logger.warn(`Rejected WebSocket upgrade: per-IP cap reached for ${clientIp}`);
                callback(false, 429, 'Too Many Connections');
                return;
            }
            callback(true);
        }
    });

    function debouncedSaveGame(game, delayMs = 500) {
        const existing = pendingSaves.get(game.id);
        if (existing) clearTimeout(existing);
        const handle = setTimeout(() => {
            pendingSaves.delete(game.id);
            db.saveActiveGame(game).catch(err =>
                logger.error(`💾 Debounced save failed for ${game.joinCode}:`, err));
        }, delayMs);
        pendingSaves.set(game.id, handle);
    }

    function cancelPendingSave(gameId) {
        const handle = pendingSaves.get(gameId);
        if (!handle) return;
        clearTimeout(handle);
        pendingSaves.delete(gameId);
    }

    const {
        getNextTurnPlayerId,
        clearRoomLifecycleTimers,
        syncTurnTimer
    } = createTurnManager({ games, db, logger, cancelPendingSave });

    const { calculateCombatResult } = createCombatManager();

    const {
        handleCatalogSearch,
        handleCatalogAdd
    } = createCatalogManager({ db, sendError, safeSend, logger });

    function scrubDeletedAccount(deletedUserId) {
        for (const game of games.values()) {
            let dirty = false;
            if (game.hostUserId === deletedUserId) {
                game.hostUserId = null;
                dirty = true;
            }
            for (const player of game.players.values()) {
                if (player.userId === deletedUserId) {
                    player.userId = null;
                    dirty = true;
                }
            }
            if (dirty && !game.ended) {
                debouncedSaveGame(game);
            }
        }
        for (const rawClient of wss.clients) {
            const client = /** @type {AppWebSocket} */ (rawClient);
            if (client.userId === deletedUserId) {
                client.userId = null;
            }
        }
    }

    const {
        handleRegister,
        handleLogin,
        handleLoginWithToken,
        handleUpdateProfile,
        handleDeleteAccount
    } = createAuthManager({
        db,
        logger,
        sendError,
        safeSend,
        jwtSecret,
        jwtExpirySeconds: JWT_EXPIRY_SECONDS,
        onAccountDeleted: scrubDeletedAccount
    });

    const {
        handleGetHistory,
        handleGetLeaderboard
    } = createHistoryManager({ db, logger, sendError, safeSend });

    const {
        validateGameOverWinner,
        finalizeGameOver,
        handleGameOver,
        handleEndTurn,
        handleDisconnect,
        handleDeleteGame,
        handleKickPlayer,
        handleTransferHost,
        handleSwapPlayers,
        handleGetHostedGames,
        handleDeleteHostedGame
    } = createGameAdminManager({
        games,
        clientGames,
        db,
        logger,
        WebSocket,
        sendError,
        safeSend,
        getNextTurnPlayerId,
        syncTurnTimer,
        clearRoomLifecycleTimers,
        cancelPendingSave,
        removeGame
    });

    class GameRoom {
        constructor(hostId, joinCode, hostName, avatarId, gender, hostUserId = null) {
            this.id = crypto.randomUUID();
            this.joinCode = joinCode;
            this.hostId = hostId;
            this.originalHostId = hostId;
            this.hostName = hostName;
            this.pendingHostMigration = null;
            this.hostAvatarId = avatarId;
            this.hostGender = gender;
            this.hostUserId = hostUserId;
            this.players = new Map();
            this.kickedUserIds = new Set();
            this.kickedIps = new Set();
            this.seq = 0;
            this.epoch = 0;
            this.createdAt = Date.now();
            this.lastActivity = Date.now();
            this.phase = 'LOBBY';
            this.ended = false;
            this.winnerId = null;
            this.turnPlayerId = hostId;
            this.playerOrder = [hostId];
            this.combat = null;
            this.maxLevel = 10;
            this.minLevel = 1;
            this.tiesGoToMonsters = true;
            this.levelTenOnlyCombat = true;
            this.allowLevelTenOverride = false;
            this.turnTimerSeconds = 0;
            this.turnEndsAt = null;
            this.turnTimerHandle = null;
            this.turnTimerKey = null;
            this.turnTimerNonce = 0;
        }

        broadcast(message, excludePlayerId = null) {
            const data = JSON.stringify(message);
            let sentCount = 0;
            for (const [playerId, player] of this.players) {
                if (playerId !== excludePlayerId && safeSend(player.ws, data)) {
                    sentCount++;
                }
            }
            logger.info(`📢 Broadcast ${message.type} to ${sentCount} players`);
        }

        buildGameState() {
            const players = {};
            for (const [playerId, player] of this.players) {
                players[playerId] = {
                    playerId,
                    name: player.name,
                    avatarId: player.avatarId || 0,
                    gender: player.gender || 'M',
                    level: player.level || 1,
                    gearBonus: player.gear || 0,
                    tempCombatBonus: player.tempCombatBonus || 0,
                    treasures: player.treasures || 0,
                    hasHalfBreed: player.hasHalfBreed || false,
                    hasSuperMunchkin: player.hasSuperMunchkin || false,
                    lastRoll: player.lastRoll ?? null,
                    reachedMaxLevelViaCombat: player.reachedMaxLevelViaCombat === true,
                    isConnected: !!player.isConnected,
                    characterClass: player.characterClass || 'NONE',
                    characterRace: player.characterRace || 'HUMAN',
                    secondaryClass: player.secondaryClass || 'NONE',
                    secondaryRace: player.secondaryRace || 'HUMAN'
                };
            }

            if (this.combat) {
                logger.info(
                    `📦 buildingGameState: Sending Combat State with ${this.combat.tempBonuses.length} bonuses ` +
                    `and mods H:${this.combat.heroModifier}/M:${this.combat.monsterModifier}`
                );
            }

            return {
                gameId: this.id,
                joinCode: this.joinCode,
                epoch: this.epoch,
                seq: this.seq,
                hostId: this.hostId,
                originalHostId: this.originalHostId,
                players,
                combat: this.combat,
                phase: this.phase,
                winnerId: this.winnerId,
                turnPlayerId: this.turnPlayerId,
                turnEndsAt: this.turnEndsAt,
                playerOrder: Array.isArray(this.playerOrder) && this.playerOrder.length > 0
                    ? this.playerOrder
                    : Array.from(this.players.keys()),
                createdAt: this.createdAt,
                settings: {
                    minLevel: this.minLevel,
                    maxLevel: this.maxLevel,
                    tiesGoToMonsters: this.tiesGoToMonsters,
                    levelTenOnlyCombat: this.levelTenOnlyCombat,
                    allowLevelTenOverride: this.allowLevelTenOverride,
                    turnTimerSeconds: this.turnTimerSeconds || 0
                }
            };
        }
    }

    function buildWelcome(game, playerId, reconnectToken) {
        return {
            type: 'WELCOME',
            gameState: game.buildGameState(),
            yourPlayerId: playerId,
            reconnectToken
        };
    }

    function createPlayerState(ws, meta, safeName) {
        return {
            ws,
            name: safeName,
            avatarId: clampInt(meta.avatarId, 0, MAX_AVATAR_ID, 0),
            gender: normalizeGender(meta.gender),
            userId: ws.userId || null,
            lastKnownIp: ws.clientIp || null,
            reconnectTokenHash: null,
            level: 1,
            gear: 0,
            tempCombatBonus: 0,
            treasures: 0,
            characterClass: 'NONE',
            characterRace: 'HUMAN',
            secondaryClass: 'NONE',
            secondaryRace: 'HUMAN',
            hasHalfBreed: false,
            hasSuperMunchkin: false,
            lastRoll: null,
            reachedMaxLevelViaCombat: false,
            isConnected: true,
            joinedAt: Date.now()
        };
    }

    function handleCreateGame(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isCreateGameRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiadas partidas creadas. Espera un momento.');
            return;
        }
        if (games.size >= maxActiveGames) {
            sendError(ws, 'SERVER_BUSY', 'Límite de partidas activas alcanzado. Inténtalo más tarde.');
            return;
        }

        const { playerMeta, superMunchkin, turnTimerSeconds } = message;
        if (!isPlainObject(playerMeta) || typeof playerMeta.name !== 'string') {
            sendError(ws, 'INVALID_DATA', 'Datos de jugador invalidos');
            return;
        }

        const hostName = normalizeName(playerMeta.name);
        if (!hostName) {
            sendError(ws, 'INVALID_DATA', 'Nombre de jugador inválido');
            return;
        }

        const joinCode = generateUniqueJoinCode();
        if (!joinCode) {
            sendError(ws, 'SERVER_ERROR', 'No se pudo generar un código de partida único');
            return;
        }

        if (clientGames.has(ws)) {
            handleDisconnect(ws);
        }

        recordCreateGame(clientIp);
        const playerId = crypto.randomUUID();
        logger.info(`🎲 Creating game for ${hostName} with playerId: ${playerId}, superMunchkin: ${!!superMunchkin}`);

        const game = new GameRoom(
            playerId,
            joinCode,
            hostName,
            clampInt(playerMeta.avatarId, 0, MAX_AVATAR_ID, 0),
            normalizeGender(playerMeta.gender),
            ws.userId || null
        );
        if (superMunchkin === true) {
            game.maxLevel = 20;
        }
        game.turnTimerSeconds = clampInt(turnTimerSeconds, 0, MAX_TURN_TIMER_SECONDS, 0);

        const player = createPlayerState(ws, playerMeta, hostName);
        const reconnectToken = rotateReconnectToken(player);
        game.players.set(playerId, player);

        registerGame(game);
        clientGames.set(ws, { gameId: game.id, playerId });

        logger.info(`✅ Game created: ${joinCode} by ${hostName}`);
        logger.info(`📤 Sending WELCOME for ${joinCode} to ${playerId}`);
        safeSend(ws, buildWelcome(game, playerId, reconnectToken));

        db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
    }

    function handleListGames(ws) {
        const availableGames = [];
        for (const game of games.values()) {
            if (game.phase !== 'LOBBY') continue;
            if (game.ended) continue;
            if (game.players.size >= MAX_PLAYERS) continue;

            availableGames.push({
                joinCode: game.joinCode,
                hostName: game.hostName,
                playerCount: game.players.size,
                maxPlayers: MAX_PLAYERS,
                createdAt: game.createdAt
            });
        }

        logger.info(`📋 Listing ${availableGames.length} available games`);
        safeSend(ws, {
            type: 'GAMES_LIST',
            games: availableGames
        });
    }

    function handleHello(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isJoinRateLimited(clientIp)) {
            logger.warn(`⚠️ Rate check: Join limit exceeded for ${clientIp}`);
            sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera un momento.');
            return;
        }

        const { joinCode, playerMeta, reconnectToken } = message;
        if (!joinCode || typeof joinCode !== 'string') {
            recordJoinAttempt(clientIp, false);
            sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
            return;
        }

        if (!isPlainObject(playerMeta) || typeof playerMeta.name !== 'string') {
            recordJoinAttempt(clientIp, false);
            sendError(ws, 'INVALID_DATA', 'Datos de jugador inválidos');
            return;
        }

        const safeName = normalizeName(playerMeta.name);
        if (!safeName) {
            recordJoinAttempt(clientIp, false);
            sendError(ws, 'INVALID_DATA', 'Nombre de jugador inválido');
            return;
        }

        const game = findGameByCode(joinCode);
        if (!game) {
            recordJoinAttempt(clientIp, false);
            logger.info(`❌ Invalid join code: ${joinCode}`);
            sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
            return;
        }

        if (game.ended || game.phase === 'FINISHED') {
            sendError(ws, 'GAME_FINISHED', 'La partida ya ha terminado');
            return;
        }

        if (ws.userId && game.kickedUserIds?.has(ws.userId)) {
            sendError(ws, 'FORBIDDEN', 'Has sido expulsado de esta partida');
            return;
        }

        let playerId = playerMeta.playerId?.value || playerMeta.playerId || null;
        if (typeof playerId !== 'string') playerId = null;

        if (ws.userId) {
            for (const [existingPid, player] of game.players.entries()) {
                if (player.userId === ws.userId) {
                    logger.info(`🔄 Authenticated User ${ws.userId} (${player.name}) recognized as existing player ${existingPid}`);
                    playerId = existingPid;
                    break;
                }
            }
        }

        logger.info(`👤 Player ${safeName} joining ${joinCode} with id: ${playerId}`);

        if (playerId && game.players.has(playerId)) {
            const player = game.players.get(playerId);
            if (!canReconnectPlayer(ws, player, reconnectToken)) {
                recordJoinAttempt(clientIp, false);
                logger.warn(`⚠️ Reconnect rejected for ${joinCode}: invalid token/user binding for ${playerId}`);
                sendError(ws, 'UNAUTHORIZED', 'Reconnect token invalid or expired');
                return;
            }

            const existingSocketSeat = clientGames.get(ws);
            if (
                existingSocketSeat &&
                (existingSocketSeat.gameId !== game.id || existingSocketSeat.playerId !== playerId)
            ) {
                handleDisconnect(ws);
            }

            if (player.ws && player.ws !== ws) {
                const supersededWs = player.ws;
                clientGames.delete(supersededWs);
                if (supersededWs.readyState === WebSocket.OPEN) {
                    supersededWs.close(1000, 'Reconnected from another device');
                }
            }

            recordJoinAttempt(clientIp, true);
            const nextReconnectToken = rotateReconnectToken(player);
            player.ws = ws;
            player.isConnected = true;
            player.lastKnownIp = clientIp;
            clientGames.set(ws, { gameId: game.id, playerId });

            if (!game.turnPlayerId || !game.players.has(game.turnPlayerId)) {
                game.turnPlayerId = playerId;
            }

            if (game.cleanupTimer) {
                clearTimeout(game.cleanupTimer);
                game.cleanupTimer = null;
                logger.info(`⏰ Cleanup timer cancelled for ${joinCode}`);
            }

            if (game.pendingHostMigration && playerId === game.hostId) {
                clearTimeout(game.pendingHostMigration);
                game.pendingHostMigration = null;
                logger.info(`👑 Host ${player.name} reconnected — pending migration cancelled`);
            }

            if (game.originalHostId === playerId && game.hostId !== playerId) {
                game.hostId = playerId;
                game.hostName = player.name;
                logger.info(`👑 Original host ${player.name} reclaimed host status`);
            }

            syncTurnTimer(game, 'player reconnected');
            safeSend(ws, buildWelcome(game, playerId, nextReconnectToken));

            game.seq++;
            game.broadcast({
                type: 'STATE_SNAPSHOT',
                gameState: game.buildGameState(),
                seq: game.seq
            });

            db.saveActiveGame(game).catch(err => logger.error('Failed to save after reconnect:', err));
            logger.info(`🔄 Player ${safeName} reconnected to ${joinCode}`);
            return;
        }

        if (clientIp && clientIp !== 'unknown' && game.kickedIps?.has(clientIp)) {
            sendError(ws, 'FORBIDDEN', 'Has sido expulsado de esta partida');
            return;
        }

        if (game.players.size >= MAX_PLAYERS) {
            sendError(ws, 'GAME_FULL', 'Partida llena');
            return;
        }

        const existingSocketSeat = clientGames.get(ws);
        if (existingSocketSeat && existingSocketSeat.gameId !== game.id) {
            handleDisconnect(ws);
        }

        recordJoinAttempt(clientIp, true);
        playerId = crypto.randomUUID();
        const player = createPlayerState(ws, playerMeta, safeName);
        const nextReconnectToken = rotateReconnectToken(player);
        game.players.set(playerId, player);
        if (!Array.isArray(game.playerOrder)) {
            game.playerOrder = [];
        }
        if (!game.playerOrder.includes(playerId)) {
            game.playerOrder.push(playerId);
        }

        if (!game.turnPlayerId || !game.players.has(game.turnPlayerId)) {
            game.turnPlayerId = playerId;
        }

        clientGames.set(ws, { gameId: game.id, playerId });
        game.seq++;

        logger.info(`✅ Player ${safeName} joined ${joinCode}`);
        syncTurnTimer(game, 'player joined');

        safeSend(ws, buildWelcome(game, playerId, nextReconnectToken));

        game.broadcast({
            type: 'PLAYER_STATUS',
            playerId,
            isConnected: true
        }, playerId);

        game.broadcast({
            type: 'STATE_SNAPSHOT',
            gameState: game.buildGameState(),
            seq: game.seq
        });

        db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
    }

    function markMaxLevelIfReached(game, player, prevLevel, levelsGained) {
        if (levelsGained > 0 && prevLevel < game.maxLevel && player.level >= game.maxLevel) {
            player.reachedMaxLevelViaCombat = true;
        }
    }

    function canTouchCombat(game, playerId) {
        if (!game.combat) return false;
        return playerId === game.combat.mainPlayerId ||
               playerId === game.combat.helperPlayerId ||
               playerId === game.turnPlayerId ||
               playerId === game.hostId;
    }

    function baseSanitizedEvent(event, playerId, extra = {}) {
        return {
            type: event.type,
            eventId: typeof event.eventId === 'string' && event.eventId ? event.eventId.slice(0, 64) : crypto.randomUUID(),
            timestamp: Number.isFinite(event.timestamp) ? event.timestamp : Date.now(),
            actorId: playerId,
            ...extra
        };
    }

    function applyEvent(game, event, playerId, ws) {
        const player = game.players.get(playerId);
        if (!player) return null;

        if (game.ended || game.phase === 'FINISHED') {
            sendError(ws, 'GAME_NOT_FOUND', 'La partida ya ha terminado');
            return null;
        }

        if (IN_GAME_ONLY_EVENTS.has(event.type) && game.phase !== 'IN_GAME') {
            sendError(ws, 'INVALID_DATA', 'La partida aún no ha comenzado');
            return null;
        }

        const manualLevelCap = requiresCombatToWin(game)
            ? Math.max(game.minLevel, game.maxLevel - 1)
            : game.maxLevel;

        let sanitizedEvent = null;

        switch (event.type) {
            case 'INC_LEVEL': {
                const step = stepAmount(event.amount);
                if (!requiresCombatToWin(game) || player.level < manualLevelCap) {
                    const cap = requiresCombatToWin(game) ? manualLevelCap : game.maxLevel;
                    player.level = Math.min(cap, player.level + step);
                }
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    amount: step
                });
                break;
            }
            case 'DEC_LEVEL': {
                const step = stepAmount(event.amount);
                player.level = Math.max(game.minLevel, player.level - step);
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    amount: step
                });
                break;
            }
            case 'SET_LEVEL': {
                const requested = clampInt(event.level, game.minLevel, game.maxLevel, player.level);
                if (requiresCombatToWin(game) && requested >= game.maxLevel) {
                    if (player.level < game.maxLevel) {
                        player.level = manualLevelCap;
                    }
                } else {
                    player.level = requested;
                }
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    level: player.level
                });
                break;
            }
            case 'INC_GEAR': {
                const step = stepAmount(event.amount);
                player.gear = clampInt(player.gear + step, -GEAR_LIMIT, GEAR_LIMIT, player.gear);
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    amount: step
                });
                break;
            }
            case 'DEC_GEAR': {
                const step = stepAmount(event.amount);
                player.gear = clampInt(player.gear - step, -GEAR_LIMIT, GEAR_LIMIT, player.gear);
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    amount: step
                });
                break;
            }
            case 'SET_GEAR': {
                player.gear = clampInt(event.gear, -GEAR_LIMIT, GEAR_LIMIT, player.gear);
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    gear: player.gear
                });
                break;
            }
            case 'SET_NAME': {
                const trimmed = normalizeName(event.name);
                if (!trimmed) {
                    sendError(ws, 'INVALID_DATA', 'Nombre inválido');
                    return null;
                }
                player.name = trimmed;
                if (game.hostId === playerId) game.hostName = trimmed;
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    name: trimmed
                });
                break;
            }
            case 'SET_AVATAR': {
                player.avatarId = clampInt(event.avatarId, 0, MAX_AVATAR_ID, player.avatarId || 0);
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    avatarId: player.avatarId
                });
                break;
            }
            case 'SET_GENDER': {
                if (!VALID_GENDERS.has(event.gender)) {
                    sendError(ws, 'INVALID_DATA', 'Género inválido');
                    return null;
                }
                player.gender = event.gender;
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    gender: event.gender
                });
                break;
            }
            case 'SET_HALF_BREED': {
                player.hasHalfBreed = event.enabled === true;
                if (!player.hasHalfBreed) player.secondaryRace = 'HUMAN';
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    enabled: player.hasHalfBreed
                });
                break;
            }
            case 'SET_SUPER_MUNCHKIN': {
                player.hasSuperMunchkin = event.enabled === true;
                if (!player.hasSuperMunchkin) player.secondaryClass = 'NONE';
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    enabled: player.hasSuperMunchkin
                });
                break;
            }
            case 'SET_CLASS': {
                if (!VALID_CLASSES.has(event.newClass)) {
                    sendError(ws, 'INVALID_DATA', 'Clase inválida');
                    return null;
                }
                const isSecondary = event.isSecondary === true;
                if (isSecondary) {
                    if (!player.hasSuperMunchkin) {
                        sendError(ws, 'INVALID_DATA', 'Necesitas Súper Munchkin para una segunda clase');
                        return null;
                    }
                    player.secondaryClass = event.newClass === player.characterClass ? 'NONE' : event.newClass;
                } else {
                    player.characterClass = event.newClass;
                    if (!player.hasSuperMunchkin || player.secondaryClass === player.characterClass) {
                        player.secondaryClass = 'NONE';
                    }
                }
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    newClass: event.newClass,
                    isSecondary
                });
                break;
            }
            case 'SET_RACE': {
                if (!VALID_RACES.has(event.newRace)) {
                    sendError(ws, 'INVALID_DATA', 'Raza inválida');
                    return null;
                }
                const isSecondary = event.isSecondary === true;
                if (isSecondary) {
                    if (!player.hasHalfBreed) {
                        sendError(ws, 'INVALID_DATA', 'Necesitas Mestizo para una segunda raza');
                        return null;
                    }
                    player.secondaryRace = event.newRace === player.characterRace ? 'HUMAN' : event.newRace;
                } else {
                    player.characterRace = event.newRace;
                    if (!player.hasHalfBreed || player.secondaryRace === player.characterRace) {
                        player.secondaryRace = 'HUMAN';
                    }
                }
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    targetPlayerId: playerId,
                    newRace: event.newRace,
                    isSecondary
                });
                break;
            }
            case 'PLAYER_ROLL': {
                const rollResult = clampInt(event.result, 1, 6, 1);
                const purpose = VALID_DICE_PURPOSES.has(event.purpose) ? event.purpose : 'RANDOM';
                const modifier = purpose === 'RUN_AWAY'
                    ? clampInt(event.modifier, -10, 10, 0)
                    : 0;
                const isCombatParticipant = Boolean(
                    game.combat && (
                        playerId === game.combat.mainPlayerId ||
                        playerId === game.combat.helperPlayerId ||
                        playerId === game.hostId
                    )
                );
                const runnerPlayer = (
                    purpose === 'RUN_AWAY' &&
                    game.combat &&
                    playerId !== game.combat.mainPlayerId &&
                    playerId !== game.combat.helperPlayerId &&
                    playerId === game.hostId
                )
                    ? (game.players.get(game.combat.mainPlayerId) || player)
                    : player;
                const computedSuccess = purpose === 'RUN_AWAY'
                    ? (rollResult + getRunAwayBonus(runnerPlayer) + modifier) >= 5
                    : (event.success === true);

                if (game.phase === 'LOBBY') {
                    const allPlayers = Array.from(game.players.values());
                    const allRolled = allPlayers.length > 1 && allPlayers.every(p => p.lastRoll !== null && p.lastRoll !== undefined);
                    if (allRolled) {
                        const maxRoll = Math.max(...allPlayers.map(p => p.lastRoll || 0));
                        const tiedAtMax = allPlayers.filter(p => p.lastRoll === maxRoll);
                        if (tiedAtMax.length > 1 && player.lastRoll === maxRoll) {
                            for (const p of allPlayers) {
                                if (p === player) continue;
                                if (p.lastRoll === maxRoll) {
                                    p.lastRoll = null;
                                } else {
                                    p.lastRoll = 0;
                                }
                            }
                        }
                    }
                }

                player.lastRoll = rollResult;
                if (game.combat && isCombatParticipant && (purpose === 'COMBAT' || purpose === 'RUN_AWAY')) {
                    game.combat.lastDiceRoll = {
                        playerId,
                        playerName: runnerPlayer.name || player.name || 'Jugador',
                        result: rollResult,
                        modifier,
                        purpose,
                        success: computedSuccess,
                        timestamp: Date.now()
                    };
                }

                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    result: rollResult,
                    modifier,
                    purpose,
                    success: computedSuccess
                });
                break;
            }
            case 'COMBAT_START': {
                if (game.combat) {
                    sendError(ws, 'COMBAT_ALREADY_ACTIVE', 'Ya hay un combate activo');
                    return null;
                }
                const mainPlayerId = event.mainPlayerId;
                if (!mainPlayerId || !game.players.has(mainPlayerId)) {
                    sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador principal no encontrado');
                    return null;
                }
                if (playerId !== mainPlayerId && playerId !== game.turnPlayerId && playerId !== game.hostId) {
                    sendError(ws, 'FORBIDDEN', 'Solo el jugador en turno o el anfitrión pueden iniciar el combate');
                    return null;
                }
                const initialMonsters = Array.isArray(event.monsters) ? event.monsters : [];
                if (initialMonsters.length > MAX_MONSTERS_PER_COMBAT) {
                    sendError(ws, 'COMBAT_MONSTER_LIMIT', `Máximo ${MAX_MONSTERS_PER_COMBAT} monstruos por combate`);
                    return null;
                }
                const monsters = initialMonsters.map(sanitizeMonster);
                game.combat = {
                    mainPlayerId,
                    helperPlayerId: null,
                    monsters,
                    tempBonuses: [],
                    heroModifier: 0,
                    monsterModifier: 0,
                    lastDiceRoll: null,
                    isActive: true
                };
                sanitizedEvent = baseSanitizedEvent(event, playerId, { mainPlayerId, monsters });
                break;
            }
            case 'COMBAT_UPDATE_MONSTER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                const incoming = event.monster;
                if (!incoming || !incoming.id) {
                    sendError(ws, 'INVALID_DATA', 'Monstruo inválido');
                    return null;
                }
                const sanitized = sanitizeMonster(incoming);
                game.combat.monsters = game.combat.monsters.map(m =>
                    m.id === sanitized.id ? sanitized : m
                );
                sanitizedEvent = baseSanitizedEvent(event, playerId, { monster: sanitized });
                break;
            }
            case 'COMBAT_ADD_MONSTER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                if (game.combat.monsters.length >= MAX_MONSTERS_PER_COMBAT) {
                    sendError(ws, 'COMBAT_MONSTER_LIMIT', `Máximo ${MAX_MONSTERS_PER_COMBAT} monstruos por combate`);
                    return null;
                }
                const monster = sanitizeMonster(event.monster);
                game.combat.monsters.push(monster);
                sanitizedEvent = baseSanitizedEvent(event, playerId, { monster });
                break;
            }
            case 'COMBAT_REMOVE_MONSTER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                const monsterId = typeof event.monsterId === 'string' ? event.monsterId : '';
                game.combat.monsters = game.combat.monsters.filter(m => m.id !== monsterId);
                sanitizedEvent = baseSanitizedEvent(event, playerId, { monsterId });
                break;
            }
            case 'COMBAT_ADD_HELPER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                if (event.helperId === game.combat.mainPlayerId) {
                    sendError(ws, 'INVALID_HELPER', 'No puedes ayudarte a ti mismo');
                    return null;
                }
                if (!event.helperId || !game.players.has(event.helperId)) {
                    sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado');
                    return null;
                }
                game.combat.helperPlayerId = event.helperId;
                sanitizedEvent = baseSanitizedEvent(event, playerId, { helperId: event.helperId });
                break;
            }
            case 'COMBAT_REMOVE_HELPER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                game.combat.helperPlayerId = null;
                sanitizedEvent = baseSanitizedEvent(event, playerId);
                break;
            }
            case 'COMBAT_SET_MODIFIER': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                const value = clampInt(event.value, -MODIFIER_LIMIT, MODIFIER_LIMIT, 0);
                const target = event.target === 'HEROES' ? 'HEROES' : 'MONSTER';
                if (target === 'HEROES') game.combat.heroModifier = value;
                else game.combat.monsterModifier = value;
                sanitizedEvent = baseSanitizedEvent(event, playerId, { target, value });
                break;
            }
            case 'COMBAT_ADD_BONUS': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                if (game.combat.tempBonuses.length >= MAX_BONUSES_PER_COMBAT) {
                    sendError(ws, 'COMBAT_BONUS_LIMIT', `Máximo ${MAX_BONUSES_PER_COMBAT} bonificaciones por combate`);
                    return null;
                }
                const bonus = sanitizeBonus(event.bonus);
                game.combat.tempBonuses.push(bonus);
                sanitizedEvent = baseSanitizedEvent(event, playerId, { bonus });
                break;
            }
            case 'COMBAT_REMOVE_BONUS': {
                if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return null; }
                if (!canTouchCombat(game, playerId)) { sendError(ws, 'FORBIDDEN', 'No autorizado'); return null; }
                const bonusId = typeof event.bonusId === 'string' ? event.bonusId : '';
                game.combat.tempBonuses = game.combat.tempBonuses.filter(b => b.id !== bonusId);
                sanitizedEvent = baseSanitizedEvent(event, playerId, { bonusId });
                break;
            }
            case 'COMBAT_END': {
                if (!game.combat || !canTouchCombat(game, playerId)) {
                    sendError(ws, 'UNAUTHORIZED', 'Solo el jugador en turno, su ayudante o el anfitrión pueden terminar el combate');
                    return null;
                }
                if (
                    event.expectedSeq !== undefined &&
                    event.expectedSeq !== null &&
                    Number(event.expectedSeq) !== game.seq
                ) {
                    sendError(ws, 'CONFLICT', 'El estado del combate ha cambiado');
                    safeSend(ws, {
                        type: 'STATE_SNAPSHOT',
                        gameState: game.buildGameState(),
                        seq: game.seq
                    });
                    return null;
                }

                const requestedOutcome = event.outcome;
                if (requestedOutcome && requestedOutcome !== 'WIN' && requestedOutcome !== 'LOSE' && requestedOutcome !== 'ESCAPE') {
                    sendError(ws, 'INVALID_DATA', 'Resultado de combate inválido');
                    return null;
                }

                const monsters = game.combat.monsters || [];
                if (requestedOutcome === 'ESCAPE') {
                    if (monsters.length > 0) {
                        if (event.cancelled === true) {
                            if (playerId !== game.combat.mainPlayerId && playerId !== game.hostId) {
                                sendError(ws, 'FORBIDDEN', 'Solo el jugador principal o el anfitrión pueden cancelar el combate');
                                return null;
                            }
                        } else {
                            const lastRoll = game.combat.lastDiceRoll;
                            const validRoller = lastRoll && (
                                lastRoll.playerId === game.combat.mainPlayerId ||
                                lastRoll.playerId === game.combat.helperPlayerId ||
                                lastRoll.playerId === game.hostId
                            );
                            if (!validRoller || lastRoll.purpose !== 'RUN_AWAY' || lastRoll.success !== true) {
                                sendError(ws, 'INVALID_DATA', 'Debes superar la tirada de huida (5+) para escapar');
                                return null;
                            }
                        }
                    }
                    game.combat = null;
                    sanitizedEvent = baseSanitizedEvent(event, playerId, {
                        outcome: 'ESCAPE',
                        cancelled: event.cancelled === true,
                        levelsGained: 0,
                        treasuresGained: 0,
                        helperLevelsGained: 0
                    });
                    break;
                }

                if (monsters.length === 0) {
                    sendError(ws, 'INVALID_DATA', 'No hay monstruos en el combate');
                    return null;
                }

                const serverResult = calculateCombatResult(game);
                if (!serverResult) {
                    sendError(ws, 'INVALID_DATA', 'No se pudo calcular el resultado del combate');
                    return null;
                }

                const lastRoll = game.combat.lastDiceRoll;
                const failedRunAway =
                    requestedOutcome === 'LOSE' &&
                    lastRoll &&
                    lastRoll.purpose === 'RUN_AWAY' &&
                    lastRoll.success === false &&
                    (
                        lastRoll.playerId === game.combat.mainPlayerId ||
                        lastRoll.playerId === game.combat.helperPlayerId ||
                        lastRoll.playerId === game.hostId
                    );

                if (!failedRunAway && serverResult.outcome !== requestedOutcome) {
                    logger.warn(
                        `⚠️ COMBAT_END mismatch: client=${requestedOutcome} server=${serverResult.outcome} ` +
                        `heroes=${serverResult.heroesPower} monsters=${serverResult.monstersPower}`
                    );
                }

                const mainPlayer = game.players.get(game.combat.mainPlayerId) || player;
                const helperPlayerId = game.combat.helperPlayerId ?? null;
                const finalOutcome = failedRunAway ? 'LOSE' : serverResult.outcome;
                let finalLevels = 0;
                let finalTreasures = 0;
                let finalHelperLevels = 0;

                if (finalOutcome === 'WIN') {
                    finalLevels = serverResult.totalLevels;
                    finalTreasures = serverResult.totalTreasures;
                    finalHelperLevels = serverResult.helperLevelsGained;

                    const prevMainLevel = mainPlayer.level;
                    mainPlayer.level = Math.min(game.maxLevel, mainPlayer.level + finalLevels);
                    mainPlayer.treasures = (mainPlayer.treasures || 0) + finalTreasures;
                    markMaxLevelIfReached(game, mainPlayer, prevMainLevel, finalLevels);

                    if (finalHelperLevels > 0 && helperPlayerId) {
                        const helperPlayer = game.players.get(helperPlayerId);
                        if (helperPlayer) {
                            const prevHelperLevel = helperPlayer.level;
                            helperPlayer.level = Math.min(game.maxLevel, helperPlayer.level + finalHelperLevels);
                            markMaxLevelIfReached(game, helperPlayer, prevHelperLevel, finalHelperLevels);
                        }
                    }
                }

                game.combat = null;
                sanitizedEvent = baseSanitizedEvent(event, playerId, {
                    outcome: finalOutcome,
                    levelsGained: finalLevels,
                    treasuresGained: finalTreasures,
                    helperLevelsGained: finalHelperLevels
                });
                break;
            }
            case 'GAME_START': {
                if (game.hostId !== playerId) {
                    sendError(ws, 'FORBIDDEN', 'Solo el anfitrión puede iniciar la partida');
                    return null;
                }
                if (game.phase !== 'LOBBY') {
                    sendError(ws, 'GAME_ALREADY_STARTED', 'La partida ya ha comenzado');
                    return null;
                }
                const order = (Array.isArray(game.playerOrder) && game.playerOrder.length > 0)
                    ? game.playerOrder
                    : Array.from(game.players.keys());
                let highestPlayerId = null;
                let highestRoll = 0;
                for (const pid of order) {
                    const p = game.players.get(pid);
                    if (p && (p.lastRoll || 0) > highestRoll) {
                        highestRoll = p.lastRoll;
                        highestPlayerId = pid;
                    }
                }
                game.turnPlayerId = highestPlayerId || order[0] || game.hostId;
                game.phase = 'IN_GAME';
                sanitizedEvent = baseSanitizedEvent(event, playerId);
                break;
            }
            case 'GAME_END': {
                if (game.hostId !== playerId) {
                    sendError(ws, 'FORBIDDEN', 'Solo el anfitrión puede finalizar la partida');
                    return null;
                }
                const normalizedWinnerId = event.winnerId === '' ? null : (event.winnerId ?? null);
                const validationErr = validateGameOverWinner(game, normalizedWinnerId);
                if (validationErr) {
                    sendError(ws, validationErr.code, validationErr.message);
                    return null;
                }
                logger.info(`🏁 Game ${game.joinCode} explicit end. Winner: ${normalizedWinnerId}`);
                finalizeGameOver(game, normalizedWinnerId);
                return { handledAsGameOver: true };
            }
            case 'END_TURN': {
                const turnPlayer = game.turnPlayerId ? game.players.get(game.turnPlayerId) : null;
                const isTurnOwner = !game.turnPlayerId || game.turnPlayerId === playerId;
                const isHostAdvancingDisconnected =
                    game.hostId === playerId &&
                    turnPlayer != null &&
                    turnPlayer.isConnected === false;

                if (!isTurnOwner && !isHostAdvancingDisconnected) {
                    sendError(ws, 'FORBIDDEN', 'No es tu turno');
                    return null;
                }
                const nextPlayerId = getNextTurnPlayerId(game);
                game.turnPlayerId = nextPlayerId;
                game.combat = null;
                logger.info(`cw Turn passed to ${game.players.get(nextPlayerId)?.name}`);
                sanitizedEvent = baseSanitizedEvent(event, playerId);
                break;
            }
            default:
                sendError(ws, 'INVALID_DATA', 'Tipo de evento no soportado');
                return null;
        }

        if (player.level < game.maxLevel) {
            player.reachedMaxLevelViaCombat = false;
        }

        if (player.level >= game.maxLevel && !game.winnerId) {
            logger.info(`Player ${player.name} reached Level ${game.maxLevel}; awaiting host confirmation`);
        }

        return sanitizedEvent;
    }

    function handleEvent(ws, message) {
        const clientData = clientGames.get(ws);
        if (!clientData) return;

        const game = games.get(clientData.gameId);
        if (!game) return;

        const { event } = message;
        if (!isPlainObject(event) || typeof event.type !== 'string') {
            sendError(ws, 'INVALID_DATA', 'Evento inválido');
            return;
        }

        const playerId = clientData.playerId;
        const seatedPlayer = game.players.get(playerId);
        if (!seatedPlayer || seatedPlayer.ws !== ws) {
            return;
        }
        logger.debug(`🎯 Event: ${event.type} from ${playerId}`);

        if (event.actorId && event.actorId !== playerId) {
            logger.warn(`⚠️ SECURITY: Actor mismatch. Session: ${playerId}, Event.actorId: ${event.actorId}`);
            sendError(ws, 'FORBIDDEN', 'Action not authorized');
            return;
        }

        if (event.targetPlayerId && event.targetPlayerId !== playerId) {
            sendError(ws, 'FORBIDDEN', 'Action not authorized');
            return;
        }

        const result = applyEvent(game, event, playerId, ws);
        if (!result) return;
        if ('handledAsGameOver' in result) return;

        game.seq++;
        const timerStateChanged = syncTurnTimer(game, `event ${result.type}`);
        const snapshotFirstEvent = SNAPSHOT_FIRST_EVENT_TYPES.has(result.type);

        if (!snapshotFirstEvent) {
            game.broadcast({
                type: 'EVENT_BROADCAST',
                gameId: game.id,
                epoch: game.epoch,
                event: result,
                fromPlayerId: playerId,
                seq: game.seq
            });
        }

        if (snapshotFirstEvent || timerStateChanged) {
            game.broadcast({
                type: 'STATE_SNAPSHOT',
                gameState: game.buildGameState(),
                seq: game.seq
            });
        }

        if (SAVEABLE_EVENT_TYPES.has(result.type)) {
            debouncedSaveGame(game);
        }
    }

    function dispatchMessage(ws, message) {
        if (message.type === 'PING' || message.type === 'PingMessage') {
            logger.debug(`[${ws.connectionId}] 📨 Received: ${message.type}`);
        } else {
            logger.info(`[${ws.connectionId}] 📨 Received: ${message.type}`);
        }

        const clientData = clientGames.get(ws);
        if (clientData && games.has(clientData.gameId)) {
            const room = games.get(clientData.gameId);
            if (!room.ended && message.type !== 'PING' && message.type !== 'PingMessage') {
                room.lastActivity = Date.now();
            }
        }

        switch (message.type) {
            case 'HELLO':
            case 'HelloMessage':
                handleHello(ws, message);
                return null;
            case 'CREATE_GAME':
            case 'CreateGameMessage':
                handleCreateGame(ws, message);
                return null;
            case 'EVENT_REQUEST':
            case 'EventMessage':
                handleEvent(ws, message);
                return null;
            case 'PING':
            case 'PingMessage':
                safeSend(ws, { type: 'PONG', timestamp: Date.now() });
                return null;
            case 'LIST_GAMES':
            case 'ListGamesMessage':
                handleListGames(ws);
                return null;
            case 'REGISTER':
                return handleRegister(ws, message);
            case 'LOGIN':
                return handleLogin(ws, message);
            case 'LOGIN_WITH_TOKEN':
                return handleLoginWithToken(ws, message);
            case 'CATALOG_SEARCH':
                handleCatalogSearch(ws, message);
                return null;
            case 'CATALOG_ADD':
                handleCatalogAdd(ws, message);
                return null;
            case 'GAME_OVER':
                handleGameOver(ws, message);
                return null;
            case 'GET_HISTORY':
                handleGetHistory(ws, message);
                return null;
            case 'GET_LEADERBOARD':
                handleGetLeaderboard(ws);
                return null;
            case 'UPDATE_PROFILE':
                return handleUpdateProfile(ws, message);
            case 'DELETE_ACCOUNT':
                return handleDeleteAccount(ws, message);
            case 'END_TURN':
                handleEndTurn(ws);
                return null;
            case 'KICK_PLAYER':
                handleKickPlayer(ws, message);
                return null;
            case 'TRANSFER_HOST':
                handleTransferHost(ws, message);
                return null;
            case 'SWAP_PLAYERS':
                handleSwapPlayers(ws, message);
                return null;
            case 'GET_HOSTED_GAMES':
                handleGetHostedGames(ws);
                return null;
            case 'DELETE_HOSTED_GAME':
                handleDeleteHostedGame(ws, message);
                return null;
            case 'DELETE_GAME':
                handleDeleteGame(ws);
                return null;
            default:
                logger.info('Unknown message type:', message.type);
                return null;
        }
    }

    function handleMessage(ws, message) {
        if (!isPlainObject(message) || typeof message.type !== 'string') {
            sendError(ws, 'INVALID_DATA', 'Formato de mensaje inválido');
            return;
        }

        const now = Date.now();
        if (!ws._msgWindowStart || now - ws._msgWindowStart > msgWindowMs) {
            ws._msgWindowStart = now;
            ws._msgCount = 1;
        } else {
            ws._msgCount = (ws._msgCount || 0) + 1;
            if (ws._msgCount > maxMessagesPerWindow) {
                sendError(ws, 'RATE_LIMITED', 'Demasiados mensajes. Espera un momento.');
                return;
            }
        }

        if (ws._authPromise) {
            if ((ws._authQueueDepth || 0) >= maxAuthQueueDepth) {
                sendError(ws, 'RATE_LIMITED', 'Demasiadas peticiones en cola');
                return;
            }
            ws._authQueueDepth = (ws._authQueueDepth || 0) + 1;
            const next = ws._authPromise
                .catch(() => {})
                .then(() => {
                    if (ws.readyState !== WebSocket.OPEN) return;
                    try {
                        return dispatchMessage(ws, message);
                    } catch (err) {
                        logger.error(`[${ws.connectionId}] Error handling queued message:`, err);
                        sendError(ws, 'SERVER_ERROR', 'An internal server error occurred while processing your message.');
                    }
                });
            const tracked = next.finally(() => {
                ws._authQueueDepth = Math.max(0, (ws._authQueueDepth || 1) - 1);
                if (ws._authPromise === tracked) {
                    ws._authPromise = null;
                }
            });
            ws._authPromise = tracked;
            return;
        }

        const result = dispatchMessage(ws, message);
        if (AUTH_CHAINED_MESSAGES.has(message.type) && result && typeof result.then === 'function') {
            const tracked = Promise.resolve(result).finally(() => {
                if (ws._authPromise === tracked) {
                    ws._authPromise = null;
                }
            });
            ws._authPromise = tracked;
        }
    }

    wss.on('connection', (rawWs, req) => {
        const ws = /** @type {AppWebSocket} */ (rawWs);
        const clientIp = getClientIp(req);
        ws.clientIp = clientIp;
        ws.connectionId = 'conn_' + crypto.randomUUID().substring(0, 8);
        ws.isAlive = true;
        ws._authPromise = null;
        ws._authQueueDepth = 0;
        activeConnsByIp.set(clientIp, (activeConnsByIp.get(clientIp) || 0) + 1);

        logger.info(`[${ws.connectionId}] 📱 Client connected from ${clientIp}`);

        ws.on('pong', () => {
            ws.isAlive = true;
        });

        ws.on('message', (messageStr) => {
            const rawText = String(messageStr);
            let message;
            try {
                message = JSON.parse(rawText);
            } catch (e) {
                logger.error(`[${ws.connectionId}] ❌ Invalid JSON received (${rawText.length} bytes), ignoring`);
                return;
            }
            try {
                handleMessage(ws, message);
            } catch (e) {
                logger.error(`[${ws.connectionId}] Error handling message:`, e);
                sendError(ws, 'SERVER_ERROR', 'An internal server error occurred while processing your message.');
            }
        });

        ws.on('close', () => {
            const remaining = (activeConnsByIp.get(clientIp) || 1) - 1;
            if (remaining <= 0) activeConnsByIp.delete(clientIp);
            else activeConnsByIp.set(clientIp, remaining);

            if (stopping) return;
            try {
                logger.info(`[${ws.connectionId}] 🔌 Client disconnected`);
                handleDisconnect(ws);
            } catch (e) {
                logger.error(`[${ws.connectionId}] Error in handleDisconnect:`, e);
            }
        });

        ws.on('error', (err) => {
            logger.error(`[${ws.connectionId}] WebSocket error:`, err);
        });
    });

    const heartbeatInterval = setInterval(() => {
        for (const rawClient of wss.clients) {
            const client = /** @type {AppWebSocket} */ (rawClient);
            if (client.isAlive === false) {
                client.terminate();
                continue;
            }
            client.isAlive = false;
            try {
                client.ping();
            } catch (err) {
                logger.warn(`Failed to ping WS client: ${err.message}`);
                client.terminate();
            }
        }
    }, HEARTBEAT_INTERVAL_MS);
    heartbeatInterval.unref();

    const cleanupInterval = setInterval(() => {
        const now = Date.now();
        const maxAge = 4 * 60 * 60 * 1000;

        for (const [gameId, game] of games) {
            const lastActivity = game.lastActivity || game.createdAt;
            if (now - lastActivity > maxAge) {
                clearRoomLifecycleTimers(game, 'cleaning up old game');
                cancelPendingSave(gameId);
                for (const [clientWs, info] of clientGames.entries()) {
                    if (info.gameId === gameId) clientGames.delete(clientWs);
                }
                removeGame(gameId);
                db.deleteActiveGame(gameId).catch(err => logger.error('Failed to delete game from DB:', err));
                logger.info(`🧹 Cleaned up old game ${game.joinCode} due to inactivity`);
            }
        }

        pruneExpiredRateLimits();
        db.cleanupOldGames().catch(err => logger.error('Failed to cleanup DB:', err));
    }, 60 * 60 * 1000);
    cleanupInterval.unref();

    const metricsInterval = setInterval(() => {
        let totalPlayers = 0;
        for (const game of games.values()) {
            totalPlayers += game.players.size;
        }
        const memUsage = process.memoryUsage();
        logger.info(
            `📊 Metrics | Active Games: ${games.size} | Total Players: ${totalPlayers} | ` +
            `RAM (Heap Used): ${(memUsage.heapUsed / 1024 / 1024).toFixed(2)} MB | ` +
            `Uptime: ${(process.uptime() / 60).toFixed(2)} mins`
        );
    }, 30 * 60 * 1000);
    metricsInterval.unref();

    async function loadGamesFromDatabase() {
        try {
            const savedGames = await db.loadActiveGames();
            for (const saved of savedGames) {
                const game = new GameRoom(saved.hostId, saved.joinCode, saved.hostName, 0, DEFAULT_GENDER);
                game.id = saved.id;
                game.phase = saved.phase;
                game.ended = saved.phase === 'FINISHED';
                game.turnPlayerId = saved.turnPlayerId;
                game.combat = saved.combat;
                game.createdAt = saved.createdAt;
                game.lastActivity = saved.lastActivityAt || saved.createdAt || Date.now();
                game.seq = saved.seq;
                game.maxLevel = saved.maxLevel || 10;
                game.turnTimerSeconds = clampInt(saved.turnTimerSeconds, 0, MAX_TURN_TIMER_SECONDS, 0);
                game.turnEndsAt = saved.turnEndsAt || null;
                game.winnerId = saved.winnerId || null;
                game.originalHostId = saved.originalHostId || saved.hostId;
                game.hostUserId = saved.hostUserId || null;
                game.playerOrder = Array.isArray(saved.playerOrder) && saved.playerOrder.length > 0
                    ? saved.playerOrder
                    : Object.keys(saved.players || {});

                for (const [playerId, playerData] of Object.entries(saved.players)) {
                    game.players.set(playerId, {
                        ws: null,
                        name: normalizeName(playerData.name) || 'Jugador',
                        avatarId: clampInt(playerData.avatarId, 0, MAX_AVATAR_ID, 0),
                        gender: normalizeGender(playerData.gender),
                        userId: playerData.userId,
                        lastKnownIp: null,
                        reconnectTokenHash: playerData.reconnectTokenHash || null,
                        level: playerData.level || 1,
                        gear: playerData.gear || 0,
                        tempCombatBonus: playerData.tempCombatBonus || 0,
                        treasures: playerData.treasures || 0,
                        characterClass: playerData.characterClass || 'NONE',
                        characterRace: playerData.characterRace || 'HUMAN',
                        secondaryClass: playerData.secondaryClass || 'NONE',
                        secondaryRace: playerData.secondaryRace || 'HUMAN',
                        hasHalfBreed: playerData.hasHalfBreed || false,
                        hasSuperMunchkin: playerData.hasSuperMunchkin || false,
                        lastRoll: playerData.lastRoll ?? null,
                        reachedMaxLevelViaCombat: playerData.reachedMaxLevelViaCombat === true,
                        isConnected: false,
                        joinedAt: playerData.joinedAt
                    });
                }

                registerGame(game);
                syncTurnTimer(game, 'restored game');
                logger.info(`🔄 Restored game ${game.joinCode} with ${game.players.size} players`);
            }
        } catch (err) {
            logger.error('❌ Failed to load games from database:', err);
        }
    }

    let stopping = false;

    async function stop() {
        if (stopping) return;
        stopping = true;

        clearInterval(heartbeatInterval);
        clearInterval(cleanupInterval);
        clearInterval(metricsInterval);

        for (const [gameId, handle] of pendingSaves) {
            clearTimeout(handle);
            pendingSaves.delete(gameId);
        }

        const savePromises = [];
        for (const game of games.values()) {
            clearRoomLifecycleTimers(game, 'graceful shutdown');
            if (!game.ended) {
                savePromises.push(
                    db.saveActiveGame(game).catch(err =>
                        logger.error(`Failed to save game ${game.joinCode}:`, err))
                );
            }
        }
        await Promise.all(savePromises);

        for (const client of wss.clients) {
            try {
                client.close(1001, 'Server shutting down');
            } catch (err) {
                logger.warn(`Failed to close client cleanly: ${err.message}`);
            }
        }

        await new Promise((resolve) => wss.close(() => resolve()));
        if (httpServer.listening) {
            await new Promise((resolve) => httpServer.close(() => resolve()));
        }
        logger.info(`✅ Saved ${savePromises.length} active games and stopped listeners.`);
    }

    async function start() {
        await db.ready;
        await loadGamesFromDatabase();
        await new Promise((resolve, reject) => {
            httpServer.once('error', reject);
            httpServer.listen(port, bindHost, () => {
                httpServer.removeListener('error', reject);
                const addr = httpServer.address();
                const boundPort = typeof addr === 'object' && addr ? addr.port : port;
                const protocol = isSsl ? 'HTTPS/WSS' : 'HTTP/WS';
                logger.info(`✅ Server listening on port ${boundPort} (${protocol})`);
                logger.info('✅ Server ready to accept connections');
                resolve();
            });
        });
    }

    return {
        server: httpServer,
        wss,
        games,
        start,
        stop,
        loadGamesFromDatabase
    };
}

if (require.main === module) {
    let app;
    let exiting = false;

    async function shutdown(signal, exitCode = 0) {
        if (exiting) return;
        exiting = true;
        logger.info(`🛑 Received ${signal}. Saving active games and shutting down...`);
        try {
            if (app) {
                await app.stop();
            }
            await db.closeDb();
        } catch (err) {
            logger.error('Error during shutdown:', err);
            exitCode = exitCode || 1;
        }
        process.exit(exitCode);
    }

    process.on('SIGTERM', () => shutdown('SIGTERM', 0));
    process.on('SIGINT', () => shutdown('SIGINT', 0));
    process.on('uncaughtException', (err) => {
        logger.error('💥 Uncaught Exception:', err);
        shutdown('uncaughtException', 1);
    });
    process.on('unhandledRejection', (reason) => {
        logger.error('💥 Unhandled Rejection:', reason);
        shutdown('unhandledRejection', 1);
    });

    try {
        app = createAppServer();
        app.start().catch((err) => {
            logger.error('❌ Fatal error during startup:', err);
            process.exit(1);
        });
    } catch (err) {
        logger.error(`❌ FATAL: ${err.message}`);
        process.exit(1);
    }
}

module.exports = { createAppServer };
