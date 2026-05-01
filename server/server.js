/**
 * Munchkin Tracker - WebSocket Server
 *
 * IMPORTANT: This server must send JSON that matches the kotlinx.serialization
 * format expected by the Android client (Protocol.kt / Models.kt)
 */

const WebSocket = require('ws');
const http = require('http');
const https = require('https');
const fs = require('fs');
const crypto = require('crypto');
const { v4: uuidv4 } = require('uuid');
const db = require('./db');
const { createAuthManager } = require('./authManager');
const { createCatalogManager } = require('./catalogManager');
const { createCombatManager } = require('./combatManager');
const { createGameAdminManager } = require('./gameAdminManager');
const { createHistoryManager } = require('./historyManager');
const { createTurnManager } = require('./turnManager');

const helmet = require('helmet');
const logger = require('./logger');

// Override console.log to use winston (optional, but ensures we catch everything)
// console.log = (...args) => logger.info(args.join(' '));
// console.error = (...args) => logger.error(args.join(' '));


const PORT = 8765;

if (!process.env.JWT_SECRET) {
    logger.error('❌ FATAL: JWT_SECRET environment variable is not set. Refusing to start.');
    process.exit(1);
}
const JWT_SECRET = process.env.JWT_SECRET;
const JWT_EXPIRY_SECONDS = 48 * 60 * 60; // 48 hours


// SSL Configuration
let server;
let isSsl = false;

try {
    if (fs.existsSync('key.pem') && fs.existsSync('cert.pem')) {
        const options = {
            key: fs.readFileSync('key.pem'),
            cert: fs.readFileSync('cert.pem')
        };
        server = https.createServer(options, handleRequest);
        isSsl = true;
        logger.info('🔒 SSL Certificates found. Starting in HTTPS/WSS mode.');
    } else {
        server = http.createServer(handleRequest);
        logger.info('⚠️ No SSL Certificates found (key.pem/cert.pem). Starting in HTTP/WS mode.');
    }
} catch (e) {
    logger.error('Failed to load SSL certs, falling back to HTTP:', e);
    server = http.createServer(handleRequest);
}

// HTTP/HTTPS Request Handler
function handleRequest(req, res) {
    // Apply Helmet Security Headers
    helmet()(req, res, () => {
        // Continue with normal handling
        processRequest(req, res);
    });
}

function processRequest(req, res) {
    // CORS headers - restricted to mobile app and local development
    const allowedOrigins = ['capacitor://localhost', 'http://localhost', 'https://localhost'];
    const origin = req.headers.origin;
    if (origin && allowedOrigins.includes(origin)) {
        res.setHeader('Access-Control-Allow-Origin', origin);
    }
    res.setHeader('Access-Control-Request-Method', '*');
    res.setHeader('Access-Control-Allow-Methods', 'OPTIONS, GET');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

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

    // API: Search Monsters
    if (req.method === 'GET' && parsedUrl.pathname === '/api/monsters') {
        const query = (parsedUrl.searchParams.get('q') || '').slice(0, 50); // hard cap at 50 chars
        logger.info(`🔍 Search Monsters: "${query}"`);

        db.searchMonsters(query)
            .then(rows => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(rows));
            })
            .catch(err => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            });
        return;
    }

    // Health check endpoint
    if (req.method === 'GET' && parsedUrl.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', games: games.size, uptime: process.uptime() }));
        return;
    }

    // Default: 404
    res.writeHead(404);
    res.end();
}

// WebSocket Server attached to HTTP/HTTPS server
const wss = new WebSocket.Server({
    server,
    maxPayload: 50 * 1024 // 50KB limit per message (prevents DoS)
});

server.listen(PORT, '0.0.0.0', () => {
    const protocol = isSsl ? 'HTTPS/WSS' : 'HTTP/WS';
    logger.info(`✅ Server listening on port ${PORT} (${protocol})`);
});

// Store active games: gameId -> GameRoom
const games = new Map();

// Store client -> gameId mapping
const clientGames = new Map();

const {
    getNextTurnPlayerId,
    clearRoomLifecycleTimers,
    syncTurnTimer,
    closeGame
} = createTurnManager({ games, db, logger });

const {
    calculateCombatResult,
    handleCombatDiceRoll
} = createCombatManager({ games, clientGames, sendError, logger });

const {
    handleCatalogSearch,
    handleCatalogAdd
} = createCatalogManager({ db, sendError, logger });

const {
    handleRegister,
    handleLogin,
    handleLoginWithToken,
    handleUpdateProfile
} = createAuthManager({
    db,
    logger,
    sendError,
    jwtSecret: JWT_SECRET,
    jwtExpirySeconds: JWT_EXPIRY_SECONDS
});

const {
    handleGetHistory,
    handleGetLeaderboard
} = createHistoryManager({ db, logger });

// Debounced save: coalesces rapid successive saves (e.g. 6 players leveling at once)
// into a single DB write per game after 500ms of inactivity.
const pendingSaves = new Map(); // gameId -> timeoutHandle

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
    handleGameOver,
    handleEndTurn,
    handleDisconnect,
    handleDeleteGame,
    handleKickPlayer,
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
    getNextTurnPlayerId,
    syncTurnTimer,
    clearRoomLifecycleTimers,
    cancelPendingSave
});

// Load persisted games on startup
async function loadGamesFromDatabase() {
    try {
        const savedGames = await db.loadActiveGames();
        for (const saved of savedGames) {
            const game = new GameRoom(saved.hostId, saved.joinCode, saved.hostName, 0, 'MALE');
            game.id = saved.id;
            game.phase = saved.phase;
            game.ended = saved.phase === "FINISHED";
            game.turnPlayerId = saved.turnPlayerId;
            game.combat = saved.combat;
            game.createdAt = saved.createdAt;
            game.seq = saved.seq;
            game.maxLevel = saved.maxLevel || 10;
            game.turnTimerSeconds = saved.turnTimerSeconds || 0;
            game.turnEndsAt = saved.turnEndsAt || null;
            game.winnerId = saved.winnerId || null;
            game.originalHostId = saved.originalHostId || saved.hostId; // Fallback for old DB records
            game.playerOrder = Array.isArray(saved.playerOrder) && saved.playerOrder.length > 0
                ? saved.playerOrder
                : Object.keys(saved.players || {});

            // Restore players (without ws connections - they'll reconnect)
            for (const [playerId, playerData] of Object.entries(saved.players)) {
                game.players.set(playerId, {
                    ws: null,
                    name: playerData.name,
                    avatarId: playerData.avatarId || 0,
                    gender: playerData.gender || 'MALE',
                    userId: playerData.userId,
                    reconnectTokenHash: playerData.reconnectTokenHash || null,
                    level: playerData.level || 1,
                    gear: playerData.gear || 0,
                    treasures: playerData.treasures || 0,
                    characterClass: playerData.characterClass || 'NONE',
                    characterRace: playerData.characterRace || 'HUMAN',
                    hasHalfBreed: playerData.hasHalfBreed || false,
                    hasSuperMunchkin: playerData.hasSuperMunchkin || false,
                    isConnected: false, // All start disconnected until they reconnect
                    joinedAt: playerData.joinedAt
                });
            }

            games.set(game.id, game);
            syncTurnTimer(game, 'restored game');
            logger.info(`🔄 Restored game ${game.joinCode} with ${game.players.size} players`);
        }
    } catch (err) {
        logger.error('❌ Failed to load games from database:', err);
    }
}

// Call on startup (after a brief delay to ensure DB is ready)
setTimeout(() => loadGamesFromDatabase(), 1000);

class GameRoom {
    constructor(hostId, joinCode, hostName, avatarId, gender, hostUserId = null) {
        this.id = uuidv4();
        this.joinCode = joinCode;
        this.hostId = hostId;
        this.originalHostId = hostId; // Never changes — used to restore host on reconnect
        this.hostName = hostName;
        this.pendingHostMigration = null; // Timer handle for delayed host migration
        this.hostAvatarId = avatarId;
        this.hostGender = gender;
        this.hostUserId = hostUserId; // Authenticated User ID (if any)
        this.players = new Map(); // playerId -> { ws, name, avatarId, gender, level, gear }
        this.seq = 0;
        this.epoch = 0;
        this.createdAt = Date.now();
        this.phase = "LOBBY";
        this.ended = false;
        this.winnerId = null;
        this.turnPlayerId = hostId; // Start with host's turn
        this.playerOrder = [hostId];
        this.combat = null;
        this.maxLevel = 10; // Default; set to 20 for Super Munchkin mode
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
            if (playerId !== excludePlayerId) {
                if (player.ws && player.ws.readyState === WebSocket.OPEN) {
                    player.ws.send(data);
                    sentCount++;
                }
                // Silently skip disconnected players (ws is null or closed)
            }
        }
        logger.info(`📢 Broadcast ${message.type} to ${sentCount} players`);
    }

    // Build GameState in the format expected by kotlinx.serialization
    // IMPORTANT: @JvmInline value classes serialize as raw values, NOT {value: ...}
    buildGameState() {
        const players = {};
        for (const [playerId, player] of this.players) {
            // PlayerState must match Models.kt exactly
            // PlayerId is @JvmInline so it serializes as just the string
            players[playerId] = {
                playerId: playerId,  // raw string, not {value: ...}
                name: player.name,
                avatarId: player.avatarId || 0,
                gender: player.gender || "M",
                level: player.level || 1,
                gearBonus: player.gear || 0,
                tempCombatBonus: 0,
                treasures: player.treasures || 0,
                raceIds: [],
                classIds: [],
                hasHalfBreed: player.hasHalfBreed || false,
                hasSuperMunchkin: player.hasSuperMunchkin || false,
                lastKnownIp: null,
                lastRoll: player.lastRoll || null,
                isConnected: !!player.isConnected,
                characterClass: player.characterClass || "NONE",
                characterRace: player.characterRace || "HUMAN"
            };
        }

        // Add debug log for combat state serialization
        if (this.combat) {
            logger.info(`📦 buildingGameState: Sending Combat State with ${this.combat.tempBonuses.length} bonuses and mods H:${this.combat.heroModifier}/M:${this.combat.monsterModifier}`);
        }

        return {
            gameId: this.id,  // raw string, not {value: ...}
            joinCode: this.joinCode,
            epoch: this.epoch,
            seq: this.seq,
            hostId: this.hostId,  // raw string
            originalHostId: this.originalHostId,
            players: players,
            races: {},
            classes: {},
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
                maxLevel: this.maxLevel,
                turnTimerSeconds: this.turnTimerSeconds || 0,
                allowNegativeGear: true,
                autoNextTurn: false
            }
        };
    }
}

// Generate 8-char join code (~40 bits entropy)
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

    if (!reconnectToken || !player.reconnectTokenHash) {
        return false;
    }

    return timingSafeEqualString(hashReconnectToken(reconnectToken), player.reconnectTokenHash);
}

function buildWelcome(game, playerId, reconnectToken) {
    return {
        type: "WELCOME",
        gameState: game.buildGameState(),
        yourPlayerId: playerId,
        reconnectToken
    };
}

// Find game by join code
function findGameByCode(joinCode) {
    for (const game of games.values()) {
        if (game.joinCode.toUpperCase() === joinCode.toUpperCase()) {
            return game;
        }
    }
    return null;
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
    'COMBAT_MODIFY_MODIFIER',
    'COMBAT_SET_MODIFIER',
    'COMBAT_ADD_BONUS',
    'COMBAT_REMOVE_BONUS'
]);

// WSS already initialized above using http server

// logger.info(`🎮 Munchkin Server running on ws://0.0.0.0:${PORT}`);

wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    ws.clientIp = clientIp; // Store for rate limiting
    logger.info(`📱 Client connected from ${clientIp}`);

    ws.on('message', (messageStr) => {
        let message;
        try {
            message = JSON.parse(messageStr);
        } catch (e) {
            logger.error(`❌ Invalid JSON received: ${e.message}`);
            return; // Ignore malformed messages
        }
        try {
            handleMessage(ws, message);
        } catch (e) {
            logger.error('Error handling message:', e); // Changed from 'Error parsing message'
            sendError(ws, 'SERVER_ERROR', 'An internal server error occurred while processing your message.'); // Changed error type and message
        }
    });

    ws.on('close', () => {
        try {
            handleDisconnect(ws);
        } catch (e) {
            logger.error('Error in handleDisconnect:', e);
        }
    });

    ws.on('error', (err) => {
        logger.error('WebSocket error:', err);
    });
});

function handleMessage(ws, message) {
    logger.info('📨 Received:', message.type);

    switch (message.type) {
        case 'HELLO':
        case 'HelloMessage':
            handleHello(ws, message);
            break;

        case 'CreateGameMessage':
            handleCreateGame(ws, message);
            break;

        case 'EVENT_REQUEST':
        case 'EventMessage':
            handleEvent(ws, message);
            break;

        case 'PING':
        case 'PingMessage':
            ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
            break;

        case 'LIST_GAMES':
        case 'ListGamesMessage':
            handleListGames(ws);
            break;

        case 'REGISTER':
            handleRegister(ws, message);
            break;

        case 'LOGIN':
            handleLogin(ws, message);
            break;

        case 'LOGIN_WITH_TOKEN':
            handleLoginWithToken(ws, message);
            break;

        case 'CATALOG_SEARCH':
            handleCatalogSearch(ws, message);
            break;

        case 'CATALOG_ADD':
            handleCatalogAdd(ws, message);
            break;

        case 'GAME_OVER':
            handleGameOver(ws, message);
            break;

        case 'GET_HISTORY':
            handleGetHistory(ws, message);
            break;

        case 'GET_LEADERBOARD':
            handleGetLeaderboard(ws);
            break;

        case 'UPDATE_PROFILE':
            handleUpdateProfile(ws, message);
            break;

        case 'END_TURN':
            handleEndTurn(ws);
            break;

        case 'KICK_PLAYER':
            handleKickPlayer(ws, message);
            break;

        case 'COMBAT_DICE_ROLL':
            handleCombatDiceRoll(ws, message);
            break;

        case 'SWAP_PLAYERS':
            handleSwapPlayers(ws, message);
            break;

        case 'GET_HOSTED_GAMES':
            handleGetHostedGames(ws);
            break;

        case 'DELETE_HOSTED_GAME':
            handleDeleteHostedGame(ws, message);
            break;

        case 'DELETE_GAME':
            handleDeleteGame(ws, message);
            break;

        default:
            logger.info('Unknown message type:', message.type);
    }
}

function createPlayerState(ws, meta) {
    return {
        ws,
        name: meta.name,
        avatarId: meta.avatarId || 0,
        gender: meta.gender || "MALE",
        userId: ws.userId || null,
        reconnectTokenHash: null,
        level: 1,
        gear: 0,
        treasures: 0,
        characterClass: "NONE",
        characterRace: "HUMAN",
        hasHalfBreed: false,
        hasSuperMunchkin: false,
        isConnected: true,
        joinedAt: Date.now()
    };
}

function handleCreateGame(ws, message) {
    const { playerMeta, superMunchkin, turnTimerSeconds } = message;
    if (!playerMeta || typeof playerMeta.name !== 'string') {
        sendError(ws, 'INVALID_DATA', 'Datos de jugador invalidos');
        return;
    }

    const joinCode = generateJoinCode();
    const playerId = uuidv4();

    logger.info(`🎲 Creating game for ${playerMeta.name} with playerId: ${playerId}, superMunchkin: ${!!superMunchkin}`);

    const game = new GameRoom(playerId, joinCode, playerMeta.name, playerMeta.avatarId, playerMeta.gender, ws.userId);
    if (superMunchkin === true) {
        game.maxLevel = 20;
    }
    game.turnTimerSeconds = Math.max(0, Number(turnTimerSeconds) || 0);
    const player = createPlayerState(ws, playerMeta);
    const reconnectToken = rotateReconnectToken(player);
    game.players.set(playerId, player);

    games.set(game.id, game);
    clientGames.set(ws, { gameId: game.id, playerId });

    logger.info(`✅ Game created: ${joinCode} by ${playerMeta.name}`);

    // Send welcome with game state - use "WELCOME" type to match @SerialName
    const response = buildWelcome(game, playerId, reconnectToken);

    logger.info('📤 Sending WELCOME:', JSON.stringify(response, null, 2));
    ws.send(JSON.stringify(response));

    // Persist game to database
    db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
}

function handleListGames(ws) {
    const availableGames = [];

    for (const game of games.values()) {
        // Only show games in LOBBY phase that aren't full
        if (game.players.size < 6) {
            availableGames.push({
                joinCode: game.joinCode,
                hostName: game.hostName,
                playerCount: game.players.size,
                maxPlayers: 6,
                createdAt: game.createdAt
            });
        }
    }

    logger.info(`📋 Listing ${availableGames.length} available games`);

    ws.send(JSON.stringify({
        type: "GAMES_LIST",
        games: availableGames
    }));
}

function handleHello(ws, message) {
    const clientIp = ws.clientIp || 'unknown';

    // Rate limiting for joining games
    if (isJoinRateLimited(clientIp)) {
        logger.warn(`⚠️ Rate check: Join limit exceeded for ${clientIp}`);
        // We don't send an error to avoid confirming existence, just ignore or delay
        // But for UX, let's send a generic error
        sendError(ws, 'RATE_LIMITED', 'Too many join attempts. Please wait.');
        return;
    }

    // Record attempt
    recordJoinAttempt(clientIp);

    const { joinCode, playerMeta, reconnectToken } = message;
    if (!joinCode || typeof joinCode !== 'string') {
        sendError(ws, 'INVALID_JOIN_CODE', 'Codigo de partida invalido');
        return;
    }

    if (!playerMeta || typeof playerMeta.name !== 'string') {
        sendError(ws, 'INVALID_DATA', 'Datos de jugador invalidos');
        return;
    }

    const game = findGameByCode(joinCode);

    if (!game) {
        logger.info(`❌ Invalid join code: ${joinCode}`);
        sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
        return;
    }

    let playerId = playerMeta.playerId?.value || playerMeta.playerId || null;

    // Check if this user is already in the game under a different playerId (e.g. reinstall, different device)
    if (ws.userId) {
        for (const [existingPid, player] of game.players.entries()) {
            if (player.userId === ws.userId) {
                logger.info(`🔄 Authenticated User ${ws.userId} (${player.name}) recognized as existing player ${existingPid}`);
                playerId = existingPid; // Resume as the existing player
                break;
            }
        }
    }

    logger.info(`👤 Player ${playerMeta.name} joining ${joinCode} with id: ${playerId}`);

    // Check if reconnecting
    if (game.players.has(playerId)) {
        // Reconnection - mark as connected again
        const player = game.players.get(playerId);
        if (!canReconnectPlayer(ws, player, reconnectToken)) {
            logger.warn(`⚠️ Reconnect rejected for ${joinCode}: invalid token/user binding for ${playerId}`);
            sendError(ws, 'UNAUTHORIZED', 'Reconnect token invalid or expired');
            return;
        }

        if (player.ws && player.ws !== ws && player.ws.readyState === WebSocket.OPEN) {
            player.ws.close(1000, 'Reconnected from another device');
        }

        const nextReconnectToken = rotateReconnectToken(player);
        player.ws = ws;
        player.isConnected = true;
        clientGames.set(ws, { gameId: game.id, playerId });

        // Cancel cleanup timer if exists
        if (game.cleanupTimer) {
            clearTimeout(game.cleanupTimer);
            game.cleanupTimer = null;
            logger.info(`⏰ Cleanup timer cancelled for ${joinCode}`);
        }

        // Cancel pending host migration if this player was the host
        if (game.pendingHostMigration && playerId === game.hostId) {
            clearTimeout(game.pendingHostMigration);
            game.pendingHostMigration = null;
            logger.info(`👑 Host ${player.name} reconnected — pending migration cancelled`);
        }

        // Restore original host status if they return after migration already fired
        if (game.originalHostId === playerId && game.hostId !== playerId) {
            game.hostId = playerId;
            game.hostName = player.name;
            logger.info(`👑 Original host ${player.name} reclaimed host status`);
        }

        syncTurnTimer(game, 'player reconnected');

        // Send WELCOME with playerId so client can properly navigate
        ws.send(JSON.stringify(buildWelcome(game, playerId, nextReconnectToken)));

        // Broadcast full STATE_SNAPSHOT to all — ensures every client sees consistent
        // hostId, isConnected flags, and turnPlayerId after any reconnect
        game.seq++;
        game.broadcast({
            type: "STATE_SNAPSHOT",
            gameState: game.buildGameState(),
            seq: game.seq
        });

        db.saveActiveGame(game).catch(err => logger.error('Failed to save after reconnect:', err));

        logger.info(`🔄 Player ${playerMeta.name} reconnected to ${joinCode}`);
        return;
    }

    // New player joining
    if (game.players.size >= 6) {
        sendError(ws, 'GAME_FULL', 'Partida llena');
        return;
    }

    playerId = uuidv4();
    const player = createPlayerState(ws, playerMeta);
    const nextReconnectToken = rotateReconnectToken(player);
    game.players.set(playerId, player);
    if (!Array.isArray(game.playerOrder)) {
        game.playerOrder = [];
    }
    if (!game.playerOrder.includes(playerId)) {
        game.playerOrder.push(playerId);
    }

    clientGames.set(ws, { gameId: game.id, playerId });
    game.seq++;

    logger.info(`✅ Player ${playerMeta.name} joined ${joinCode}`);
    syncTurnTimer(game, 'player joined');

    // Send welcome to new player
    ws.send(JSON.stringify(buildWelcome(game, playerId, nextReconnectToken)));

    // Broadcast player joined to others
    game.broadcast({
        type: "PLAYER_STATUS",
        playerId: playerId,
        isConnected: true
    }, playerId);

    // Send updated state to all
    game.broadcast({
        type: "STATE_SNAPSHOT",
        gameState: game.buildGameState(),
        seq: game.seq
    });

    // Persist updated game
    db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
}

function handleEvent(ws, message) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (!game) return;

    const { event } = message;
    const playerId = clientData.playerId;
    logger.info(`🎯 Event: ${event.type} from ${playerId}`);

    // Security: reject events where the claimed actor doesn't match the authenticated session
    if (event.actorId && event.actorId !== playerId) {
        logger.warn(`⚠️ SECURITY: Actor mismatch. Session: ${playerId}, Event.actorId: ${event.actorId}`);
        sendError(ws, 'FORBIDDEN', 'Action not authorized');
        return;
    }

    if (event.targetPlayerId && event.targetPlayerId !== playerId) {
        sendError(ws, 'FORBIDDEN', 'Action not authorized');
        return;
    }

    // Apply event to game state
    const applied = applyEvent(game, event, playerId, ws);
    if (!applied) {
        return;
    }

    game.seq++;
    const timerStateChanged = syncTurnTimer(game, `event ${event.type}`);
    const snapshotFirstEvent = SNAPSHOT_FIRST_EVENT_TYPES.has(event.type);

    if (!snapshotFirstEvent) {
        // Lightweight events can still be replayed locally by clients.
        game.broadcast({
            type: "EVENT_BROADCAST",
            gameId: game.id,
            epoch: game.epoch,
            event,
            fromPlayerId: playerId,
            seq: game.seq
        });
    }

    if (snapshotFirstEvent || timerStateChanged) {
        game.broadcast({
            type: "STATE_SNAPSHOT",
            gameState: game.buildGameState(),
            seq: game.seq
        });
    }

    // Persist game state after significant events
    const saveableEvents = [
        'GAME_START', 'GAME_END',
        'INC_LEVEL', 'DEC_LEVEL', 'SET_LEVEL',
        'INC_GEAR', 'DEC_GEAR', 'SET_GEAR',
        'SET_CLASS', 'SET_RACE',
        'SET_HALF_BREED', 'SET_SUPER_MUNCHKIN',
        'END_TURN', 'PLAYER_ROLL',
        'COMBAT_START', 'COMBAT_END',
        'COMBAT_ADD_MONSTER', 'COMBAT_REMOVE_MONSTER', 'COMBAT_UPDATE_MONSTER',
        'COMBAT_ADD_HELPER', 'COMBAT_REMOVE_HELPER',
        'COMBAT_MODIFY_MODIFIER', 'COMBAT_SET_MODIFIER', 'COMBAT_ADD_BONUS', 'COMBAT_REMOVE_BONUS'
    ];
    if (saveableEvents.includes(event.type)) {
        debouncedSaveGame(game);
    }
}

function applyEvent(game, event, playerId, ws) {
    const player = game.players.get(playerId);
    if (!player) return false;

    switch (event.type) {
        case 'INC_LEVEL':
            player.level = Math.min(game.maxLevel, player.level + (event.amount || 1));
            break;
        case 'DEC_LEVEL':
            player.level = Math.max(1, player.level - (event.amount || 1));
            break;
        case 'INC_GEAR':
            player.gear = player.gear + (event.amount || 1);
            break;
        case 'DEC_GEAR':
            player.gear = player.gear - (event.amount || 1);
            break;
        case 'SET_LEVEL':
            player.level = Math.max(1, Math.min(game.maxLevel, event.level));
            break;
        case 'SET_GEAR':
            player.gear = event.gear;
            break;
        case 'SET_NAME':
            player.name = event.name;
            break;
        case 'SET_AVATAR':
            player.avatarId = event.avatarId;
            break;
        case 'SET_GENDER':
            player.gender = event.gender;
            break;
        case 'SET_HALF_BREED':
            player.hasHalfBreed = event.enabled;
            break;
        case 'SET_SUPER_MUNCHKIN':
            player.hasSuperMunchkin = event.enabled;
            break;
        case 'PLAYER_ROLL':
            player.lastRoll = event.result;
            break;
        case 'COMBAT_START':
            if (game.combat) {
                sendError(ws, 'COMBAT_ALREADY_ACTIVE', 'Ya hay un combate activo');
                return false;
            }
            game.combat = {
                mainPlayerId: event.mainPlayerId,
                helperPlayerId: null,
                monsters: event.monsters || [],
                tempBonuses: [],
                heroModifier: 0,
                monsterModifier: 0,
                isActive: true
            };
            break;
        case 'COMBAT_UPDATE_MONSTER':
            if (game.combat) {
                game.combat.monsters = game.combat.monsters.map(m =>
                    m.id === event.monster.id ? event.monster : m
                );
            }
            break;
        case 'COMBAT_ADD_MONSTER': {
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            if (game.combat.monsters.length >= 6) { sendError(ws, 'COMBAT_MONSTER_LIMIT', 'Máximo 6 monstruos por combate'); return; }
            if (game.combat.monsters.length >= 6) {
                sendError(ws, 'COMBAT_MONSTER_LIMIT', 'Maximo 6 monstruos por combate');
                return false;
            }
            const m = event.monster || {};
            m.baseLevel = Math.max(1, Math.min(20, m.baseLevel || 1));
            m.flatModifier = Math.max(-10, Math.min(10, m.flatModifier || 0));
            game.combat.monsters.push(m);
            break;
        }
        case 'COMBAT_REMOVE_MONSTER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            game.combat.monsters = game.combat.monsters.filter(m => m.id !== event.monsterId);
            break;
        case 'COMBAT_ADD_HELPER': {
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            if (event.helperId === game.combat.mainPlayerId) { sendError(ws, 'INVALID_HELPER', 'No puedes ayudarte a ti mismo'); return; }
            if (!game.players.has(event.helperId)) { sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado'); return; }
            if (event.helperId === game.combat.mainPlayerId) {
                sendError(ws, 'INVALID_HELPER', 'No puedes ayudarte a ti mismo');
                return false;
            }
            if (!game.players.has(event.helperId)) {
                sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado');
                return false;
            }
            game.combat.helperPlayerId = event.helperId;
            break;
        }
        case 'COMBAT_REMOVE_HELPER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            game.combat.helperPlayerId = null;
            break;
        case 'COMBAT_MODIFY_MODIFIER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            if (event.target === 'HEROES') game.combat.heroModifier = (game.combat.heroModifier || 0) + (event.delta || 0);
            else game.combat.monsterModifier = (game.combat.monsterModifier || 0) + (event.delta || 0);
            break;
        case 'COMBAT_SET_MODIFIER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            if (event.target === 'HEROES') game.combat.heroModifier = (event.value || 0);
            else game.combat.monsterModifier = (event.value || 0);
            break;
        case 'COMBAT_ADD_BONUS': {
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return false; }
            if (game.combat.tempBonuses.length >= 20) { sendError(ws, 'COMBAT_BONUS_LIMIT', 'Máximo 20 bonificaciones por combate'); return; }
            if (game.combat.tempBonuses.length >= 20) {
                sendError(ws, 'COMBAT_BONUS_LIMIT', 'Maximo 20 bonificaciones por combate');
                return false;
            }
            const bonus = event.bonus || {};
            bonus.amount = Math.max(-50, Math.min(50, bonus.amount || 0));
            game.combat.tempBonuses.push(bonus);
            break;
        }
        case 'COMBAT_REMOVE_BONUS':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            game.combat.tempBonuses = game.combat.tempBonuses.filter(b => b.id !== event.bonusId);
            break;
        case 'COMBAT_END': {
            if (!game.combat || event.actorId !== game.combat.mainPlayerId) {
                sendError(ws, 'UNAUTHORIZED', 'Solo el jugador principal puede terminar el combate');
                return false;
            }
            const helperPlayerId = game.combat.helperPlayerId ?? null;

            // Server recalculates combat result — do not trust client-supplied values
            const serverResult = calculateCombatResult(game);
            let finalOutcome = event.outcome; // Trust ESCAPE (run-away flow)
            let finalLevels = 0;
            let finalTreasures = 0;
            let finalHelperLevels = 0;

            if (serverResult && event.outcome !== 'ESCAPE') {
                if (serverResult.outcome !== event.outcome) {
                    logger.warn(`⚠️ COMBAT_END mismatch: client=${event.outcome} server=${serverResult.outcome} heroes=${serverResult.heroesPower} monsters=${serverResult.monstersPower}`);
                }
                finalOutcome = serverResult.outcome;
            }

            if (finalOutcome === 'WIN') {
                finalLevels = serverResult ? serverResult.totalLevels : (event.levelsGained || 0);
                finalTreasures = serverResult ? serverResult.totalTreasures : (event.treasuresGained || 0);
                finalHelperLevels = serverResult ? serverResult.helperLevelsGained : (event.helperLevelsGained || 0);
                player.level = Math.min(game.maxLevel, player.level + finalLevels);
                player.treasures = (player.treasures || 0) + finalTreasures;
                if (finalHelperLevels > 0 && helperPlayerId) {
                    const helperPlayer = game.players.get(helperPlayerId);
                    if (helperPlayer) helperPlayer.level = Math.min(game.maxLevel, helperPlayer.level + finalHelperLevels);
                }
            }

            game.combat = null;
            break;
        }
        case 'GAME_START':
            game.phase = "IN_GAME";
            break;
        case 'SET_CLASS':
            player.characterClass = event.newClass;
            break;
        case 'SET_RACE':
            player.characterRace = event.newRace;
            break;
        case 'GAME_END':
            // Explicit end (e.g. host left)
            logger.info(`🏁 Game ${game.joinCode} explicit end. Winner: ${event.winnerId}`);
            closeGame(game, event.winnerId);
            break;
        case 'END_TURN':
            const nextPlayerId = getNextTurnPlayerId(game);
            game.turnPlayerId = nextPlayerId;
            game.combat = null; // Clear combat state
            logger.info(`cw Turn passed to ${game.players.get(nextPlayerId)?.name}`);
            break;
    }

    if (player.level >= game.maxLevel && !game.winnerId) {
        logger.info(`Player ${player.name} reached Level ${game.maxLevel}; awaiting host confirmation`);
        return true;
    }

    return true;
}

// Rate limiting for join game
const joinRateLimits = new Map(); // IP -> { attempts: number, lastAttempt: timestamp }
const JOIN_RATE_LIMIT_MAX_ATTEMPTS = 10;
const JOIN_RATE_LIMIT_WINDOW_MS = 60 * 1000; // 1 minute

function isJoinRateLimited(ip) {
    const record = joinRateLimits.get(ip);
    if (!record) return false;

    // Reset if window expired
    if (Date.now() - record.lastAttempt > JOIN_RATE_LIMIT_WINDOW_MS) {
        joinRateLimits.delete(ip);
        return false;
    }

    return record.attempts >= JOIN_RATE_LIMIT_MAX_ATTEMPTS;
}

function recordJoinAttempt(ip) {
    const record = joinRateLimits.get(ip) || { attempts: 0, lastAttempt: 0 };
    record.attempts++;
    record.lastAttempt = Date.now();
    joinRateLimits.set(ip, record);
}


function sendError(ws, code, message) {
    if (ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({
        type: "ERROR",
        code,
        message
    }));
}

// Cleanup old games every hour
setInterval(() => {
    const now = Date.now();
    const maxAge = 4 * 60 * 60 * 1000; // 4 hours

    for (const [gameId, game] of games) {
        if (now - game.createdAt > maxAge) {
            clearRoomLifecycleTimers(game, 'cleaning up old game');
            cancelPendingSave(gameId);
            games.delete(gameId);
            db.deleteActiveGame(gameId).catch(err => logger.error('Failed to delete game from DB:', err));
            logger.info(`🧹 Cleaned up old game ${game.joinCode}`);
        }
    }

    // Cleanup database orphans
    db.cleanupOldGames().catch(err => logger.error('Failed to cleanup DB:', err));
}, 60 * 60 * 1000);

// Graceful shutdown: persist all active games before exiting
async function gracefulShutdown(signal) {
    logger.info(`🛑 Received ${signal}. Saving active games and shutting down...`);
    try {
        // Flush any pending debounced saves immediately
        for (const [gameId, handle] of pendingSaves) {
            clearTimeout(handle);
            pendingSaves.delete(gameId);
        }
        const savePromises = [];
        for (const game of games.values()) {
            clearRoomLifecycleTimers(game, 'graceful shutdown');
            savePromises.push(db.saveActiveGame(game).catch(err => logger.error(`Failed to save game ${game.joinCode}:`, err)));
        }
        await Promise.all(savePromises);
        logger.info(`✅ Saved ${savePromises.length} active games. Exiting.`);
    } catch (err) {
        logger.error('Error during shutdown:', err);
    }
    process.exit(0);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

process.on('uncaughtException', (err) => {
    logger.error('💥 Uncaught Exception:', err);
    gracefulShutdown('uncaughtException');
});

process.on('unhandledRejection', (reason) => {
    logger.error('💥 Unhandled Rejection:', reason);
});

logger.info('✅ Server ready to accept connections');
