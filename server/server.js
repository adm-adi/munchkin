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
const url = require('url');
const { v4: uuidv4 } = require('uuid');
const db = require('./db');

const helmet = require('helmet');
const logger = require('./logger');

// Override console.log to use winston (optional, but ensures we catch everything)
// console.log = (...args) => logger.info(args.join(' '));
// console.error = (...args) => logger.error(args.join(' '));


const PORT = 8765;

// JWT configuration
const crypto = require('crypto');

if (!process.env.JWT_SECRET) {
    logger.error('❌ FATAL: JWT_SECRET environment variable is not set. Refusing to start.');
    process.exit(1);
}
const JWT_SECRET = process.env.JWT_SECRET;
const JWT_EXPIRY_SECONDS = 48 * 60 * 60; // 48 hours

function signToken(payload) {
    const header = { alg: 'HS256', typ: 'JWT' };
    const now = Math.floor(Date.now() / 1000);
    const fullPayload = { ...payload, iat: now, exp: now + JWT_EXPIRY_SECONDS };
    const h = Buffer.from(JSON.stringify(header)).toString('base64url');
    const p = Buffer.from(JSON.stringify(fullPayload)).toString('base64url');
    const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url');
    return `${h}.${p}.${signature}`;
}

function verifyToken(token) {
    try {
        const [h, p, s] = token.split('.');
        if (!h || !p || !s) return null;

        const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url');
        if (signature !== s) return null;

        const payload = JSON.parse(Buffer.from(p, 'base64url').toString());

        // Check expiration
        if (payload.exp && Math.floor(Date.now() / 1000) > payload.exp) {
            logger.info('⏰ Token expired');
            return null;
        }

        return payload;
    } catch (e) {
        return null;
    }
}

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

// Rate limiting for authentication endpoints
const authRateLimits = new Map(); // IP -> { attempts: number, lastAttempt: timestamp }
const RATE_LIMIT_MAX_ATTEMPTS = 5;
const RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000; // 15 minutes

function isRateLimited(ip) {
    const record = authRateLimits.get(ip);
    if (!record) return false;

    // Reset if window expired
    if (Date.now() - record.lastAttempt > RATE_LIMIT_WINDOW_MS) {
        authRateLimits.delete(ip);
        return false;
    }

    return record.attempts >= RATE_LIMIT_MAX_ATTEMPTS;
}

function recordAuthAttempt(ip, success) {
    if (success) {
        authRateLimits.delete(ip);
        return;
    }

    const record = authRateLimits.get(ip) || { attempts: 0, lastAttempt: 0 };
    record.attempts++;
    record.lastAttempt = Date.now();
    authRateLimits.set(ip, record);
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

    const parsedUrl = url.parse(req.url, true);

    // API: Search Monsters
    if (req.method === 'GET' && parsedUrl.pathname === '/api/monsters') {
        const query = (parsedUrl.query.q || '').slice(0, 50); // hard cap at 50 chars
        logger.info(`🔍 Search Monsters: "${query}"`);

        const sql = `SELECT * FROM monsters WHERE name LIKE ? ORDER BY level ASC LIMIT 50`;
        const params = [`%${query}%`];

        db.all(sql, params, (err, rows) => {
            if (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(rows));
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

// Load persisted games on startup
async function loadGamesFromDatabase() {
    try {
        const savedGames = await db.loadActiveGames();
        for (const saved of savedGames) {
            const game = new GameRoom(saved.hostId, saved.joinCode, saved.hostName, 0, 'MALE');
            game.id = saved.id;
            game.phase = saved.phase;
            game.turnPlayerId = saved.turnPlayerId;
            game.combat = saved.combat;
            game.createdAt = saved.createdAt;
            game.seq = saved.seq;

            // Restore players (without ws connections - they'll reconnect)
            for (const [playerId, playerData] of Object.entries(saved.players)) {
                game.players.set(playerId, {
                    ws: null,
                    name: playerData.name,
                    avatarId: playerData.avatarId || 0,
                    gender: playerData.gender || 'MALE',
                    userId: playerData.userId,
                    level: playerData.level || 1,
                    gear: playerData.gear || 0,
                    characterClass: playerData.characterClass || 'NONE',
                    characterRace: playerData.characterRace || 'HUMAN',
                    hasHalfBreed: playerData.hasHalfBreed || false,
                    hasSuperMunchkin: playerData.hasSuperMunchkin || false,
                    isConnected: false, // All start disconnected until they reconnect
                    joinedAt: playerData.joinedAt
                });
            }

            games.set(game.id, game);
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
        this.hostName = hostName;
        this.hostAvatarId = avatarId;
        this.hostGender = gender;
        this.hostUserId = hostUserId; // Authenticated User ID (if any)
        this.players = new Map(); // playerId -> { ws, name, avatarId, gender, level, gear }
        this.seq = 0;
        this.epoch = 0;
        this.createdAt = Date.now();
        this.phase = "LOBBY";
        this.winnerId = null;
        this.turnPlayerId = hostId; // Start with host's turn
        this.combat = null;
        this.maxLevel = 10; // Default; set to 20 for Super Munchkin mode
    }

    broadcast(message, excludePlayerId = null) {
        const data = JSON.stringify(message);
        let sentCount = 0;
        for (const [playerId, player] of this.players) {
            if (playerId !== excludePlayerId) {
                if (player.ws.readyState === WebSocket.OPEN) {
                    player.ws.send(data);
                    sentCount++;
                } else {
                    logger.info(`⚠️ Player ${player.name} (${playerId}) ws not open, state: ${player.ws.readyState}`);
                }
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
            players: players,
            races: {},
            classes: {},
            combat: this.combat,
            phase: this.phase,
            winnerId: this.winnerId,
            turnPlayerId: this.turnPlayerId,
            createdAt: this.createdAt,
            settings: {
                maxLevel: this.maxLevel,
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
        code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return code;
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
        handleDisconnect(ws);
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
        userId: meta.userId || null,
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
    const { playerMeta, superMunchkin } = message;
    const joinCode = generateJoinCode();
    const playerId = playerMeta.playerId || uuidv4();

    logger.info(`🎲 Creating game for ${playerMeta.name} with playerId: ${playerId}, superMunchkin: ${!!superMunchkin}`);

    const game = new GameRoom(playerId, joinCode, playerMeta.name, playerMeta.avatarId, playerMeta.gender, ws.userId);
    if (superMunchkin === true) {
        game.maxLevel = 20;
    }
    game.players.set(playerId, createPlayerState(ws, playerMeta));

    games.set(game.id, game);
    clientGames.set(ws, { gameId: game.id, playerId });

    logger.info(`✅ Game created: ${joinCode} by ${playerMeta.name}`);

    // Send welcome with game state - use "WELCOME" type to match @SerialName
    const response = {
        type: "WELCOME",
        gameState: game.buildGameState(),
        yourPlayerId: playerId
    };

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

    const { joinCode, playerMeta } = message;
    const game = findGameByCode(joinCode);

    if (!game) {
        logger.info(`❌ Invalid join code: ${joinCode}`);
        sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
        return;
    }

    let playerId = playerMeta.playerId?.value || playerMeta.playerId || uuidv4();

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
        player.ws = ws;
        player.isConnected = true;
        clientGames.set(ws, { gameId: game.id, playerId });

        // Cancel cleanup timer if exists
        if (game.cleanupTimer) {
            clearTimeout(game.cleanupTimer);
            game.cleanupTimer = null;
            logger.info(`⏰ Cleanup timer cancelled for ${joinCode}`);
        }

        // Send WELCOME with playerId so client can properly navigate
        ws.send(JSON.stringify({
            type: "WELCOME",
            yourPlayerId: playerId,
            gameState: game.buildGameState()
        }));

        // Broadcast reconnection to other players
        game.broadcast({
            type: "PLAYER_STATUS",
            playerId: playerId,
            isConnected: true
        }, playerId);

        logger.info(`🔄 Player ${playerMeta.name} reconnected to ${joinCode}`);
        return;
    }

    // New player joining
    game.players.set(playerId, createPlayerState(ws, playerMeta));

    clientGames.set(ws, { gameId: game.id, playerId });
    game.seq++;

    logger.info(`✅ Player ${playerMeta.name} joined ${joinCode}`);

    // Send welcome to new player
    ws.send(JSON.stringify({
        type: "WELCOME",
        yourPlayerId: playerId,
        gameState: game.buildGameState()
    }));

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

    // Apply event to game state
    applyEvent(game, event, playerId);

    game.seq++;

    // Broadcast event to all players
    game.broadcast({
        type: "EVENT_BROADCAST",
        gameId: game.id,
        epoch: game.epoch,
        event,
        fromPlayerId: playerId,
        seq: game.seq
    });

    // Persist game state after significant events
    const saveableEvents = ['GAME_START', 'INC_LEVEL', 'DEC_LEVEL', 'SET_LEVEL', 'SET_GEAR',
        'SET_CLASS', 'SET_RACE', 'COMBAT_START', 'COMBAT_END', 'GAME_END',
        'COMBAT_ADD_MONSTER', 'COMBAT_REMOVE_MONSTER', 'COMBAT_UPDATE_MONSTER',
        'COMBAT_ADD_HELPER', 'COMBAT_REMOVE_HELPER',
        'COMBAT_MODIFY_MODIFIER', 'COMBAT_SET_MODIFIER', 'COMBAT_ADD_BONUS', 'COMBAT_REMOVE_BONUS'
    ];
    if (saveableEvents.includes(event.type)) {
        db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
    }
}

function applyEvent(game, event, playerId) {
    const player = game.players.get(playerId);
    if (!player) return;

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
                return;
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
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            if (game.combat.monsters.length >= 6) { sendError(ws, 'COMBAT_MONSTER_LIMIT', 'Máximo 6 monstruos por combate'); return; }
            const m = event.monster || {};
            m.baseLevel = Math.max(1, Math.min(20, m.baseLevel || 1));
            m.flatModifier = Math.max(-10, Math.min(10, m.flatModifier || 0));
            game.combat.monsters.push(m);
            break;
        }
        case 'COMBAT_REMOVE_MONSTER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            game.combat.monsters = game.combat.monsters.filter(m => m.id !== event.monsterId);
            break;
        case 'COMBAT_ADD_HELPER': {
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            if (event.helperId === game.combat.mainPlayerId) { sendError(ws, 'INVALID_HELPER', 'No puedes ayudarte a ti mismo'); return; }
            if (!game.players.has(event.helperId)) { sendError(ws, 'PLAYER_NOT_FOUND', 'Jugador no encontrado'); return; }
            game.combat.helperPlayerId = event.helperId;
            break;
        }
        case 'COMBAT_REMOVE_HELPER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            game.combat.helperPlayerId = null;
            break;
        case 'COMBAT_MODIFY_MODIFIER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            if (event.target === 'HEROES') game.combat.heroModifier = Math.max(-20, Math.min(20, game.combat.heroModifier + event.delta));
            else game.combat.monsterModifier = Math.max(-20, Math.min(20, game.combat.monsterModifier + event.delta));
            break;
        case 'COMBAT_SET_MODIFIER':
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            if (event.target === 'HEROES') game.combat.heroModifier = Math.max(-20, Math.min(20, event.value));
            else game.combat.monsterModifier = Math.max(-20, Math.min(20, event.value));
            break;
        case 'COMBAT_ADD_BONUS': {
            if (!game.combat) { sendError(ws, 'NO_ACTIVE_COMBAT', 'No hay combate activo'); return; }
            if (game.combat.tempBonuses.length >= 20) { sendError(ws, 'COMBAT_BONUS_LIMIT', 'Máximo 20 bonificaciones por combate'); return; }
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
                return;
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

    // Check Win Condition
    if (player.level >= game.maxLevel && !game.winnerId) {
        logger.info(`🏆 Player ${player.name} reached Level ${game.maxLevel}!`);
        closeGame(game, player.id);
    }
}

function getNextTurnPlayerId(game) {
    if (!game.turnPlayerId) return game.hostId;

    // Sort players by ID to ensure deterministic order (matching client logic)
    // In future, support game.playerOrder if added
    const playerIds = Array.from(game.players.keys()).sort();

    let currentIndex = playerIds.indexOf(game.turnPlayerId);
    if (currentIndex === -1) currentIndex = 0;

    // Loop to find next connected
    // We check length + 1 times to handle wrap around and specific case where only 1 player remains
    for (let i = 1; i <= playerIds.length; i++) {
        const nextIndex = (currentIndex + i) % playerIds.length;
        const nextId = playerIds[nextIndex];
        const player = game.players.get(nextId);

        // Skip disconnected players
        if (player && (player.isConnected !== false)) {
            return nextId;
        }
    }

    return game.turnPlayerId; // Fallback: keep same player if everyone else disconnected
}


function closeGame(game, winnerId) {
    if (game.ended) return; // Already closed
    game.ended = true;
    game.winnerId = winnerId;
    game.phase = "FINISHED";

    // Helper to get real userId or null
    const participants = [];
    for (const [pid, p] of game.players) {
        participants.push({
            playerId: pid,
            userId: p.userId, // Can be null if guest
            joinedAt: p.joinedAt
        });
    }

    // Determine stored winner ID (User ID preferred, else Player ID)
    let winnerUserId = null;
    if (winnerId) {
        const winner = game.players.get(winnerId);
        if (winner) winnerUserId = winner.userId || winnerId; // If guest, use PlayerID as placeholder
    }

    db.recordGame(game.id, winnerUserId || "aborted", game.createdAt, Date.now(), participants)
        .then(() => logger.info(`💾 Game ${game.id} recorded in history`))
        .catch(err => logger.error(`❌ Failed to record game ${game.id}`, err));
}

// Rate limiting for catalog add (per userId)
const catalogAddRateLimits = new Map(); // userId -> { count: number, windowStart: timestamp }
const CATALOG_ADD_MAX_PER_MINUTE = 10;
const CATALOG_ADD_WINDOW_MS = 60 * 1000;

function isCatalogAddRateLimited(userId) {
    const record = catalogAddRateLimits.get(userId);
    if (!record) return false;
    if (Date.now() - record.windowStart > CATALOG_ADD_WINDOW_MS) {
        catalogAddRateLimits.delete(userId);
        return false;
    }
    return record.count >= CATALOG_ADD_MAX_PER_MINUTE;
}

function recordCatalogAdd(userId) {
    const record = catalogAddRateLimits.get(userId);
    if (!record || Date.now() - record.windowStart > CATALOG_ADD_WINDOW_MS) {
        catalogAddRateLimits.set(userId, { count: 1, windowStart: Date.now() });
    } else {
        record.count++;
    }
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

function isValidInput(text, maxLength) {
    return text && typeof text === 'string' && text.length > 0 && text.length <= maxLength;
}

function handleGetHistory(ws, message) {
    const { userId } = message;
    if (!userId) return;

    db.getUserHistory(userId)
        .then(games => {
            ws.send(JSON.stringify({
                type: "HISTORY_RESULT",
                games: games.map(g => ({
                    id: g.id,
                    endedAt: g.ended_at,
                    winnerId: g.winner_id,
                    playerCount: g.player_count
                }))
            }));
        })
        .catch(err => {
            logger.error("History error:", err);
            // Optionally send error, but usually UI just shows empty
        });

}

function handleGetLeaderboard(ws) {
    db.getLeaderboard()
        .then(leaderboard => {
            ws.send(JSON.stringify({
                type: "LEADERBOARD_RESULT",
                leaderboard: leaderboard
            }));
        })
        .catch(err => logger.error("Leaderboard error:", err));
}

function handleRegister(ws, message) {
    let { username, email, password, avatarId } = message;

    if (!isValidInput(username, 20) || !isValidInput(password, 100)) {
        sendError(ws, 'INVALID_DATA', 'Invalid or too long username/password');
        return;
    }

    // Auto-generate email if not provided (User just wants username)
    if (!email) {
        const sanitizedParams = username.toLowerCase().replace(/[^a-z0-9]/g, '');
        email = `${sanitizedParams}@munchkin.local`;
    } else if (!isValidInput(email, 100)) {
        sendError(ws, 'INVALID_DATA', 'Email too long');
        return;
    }

    db.createUser(username, email, password, avatarId || 0)
        .then(user => {
            logger.info(`✅ User registered: ${user.username} (${user.id})`);

            // Generate Token
            const token = signToken({ id: user.id, username: user.username, email: user.email });

            // SECURITY: Bind userId to WebSocket session
            ws.userId = user.id;

            ws.send(JSON.stringify({
                type: 'AUTH_SUCCESS',
                user: {
                    id: user.id,
                    username: user.username,
                    email: user.email,
                    avatarId: user.avatarId
                },
                token: token
            }));
        })
        .catch(err => {
            logger.error("Register failed:", err.message);
            if (err.message === "EMAIL_EXISTS") {
                sendError(ws, 'EMAIL_EXISTS', 'El email ya está registrado');
            } else {
                sendError(ws, 'REGISTER_FAILED', 'Error al registrar usuario');
            }
        });
}

function handleLogin(ws, message) {
    const clientIp = ws.clientIp || 'unknown';

    // Rate limiting check
    if (isRateLimited(clientIp)) {
        sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
        return;
    }

    const { email, password } = message;

    if (!isValidInput(email, 100) || !isValidInput(password, 100)) {
        sendError(ws, 'INVALID_DATA', 'Invalid input format');
        return;
    }

    db.verifyUser(email, password)
        .then(user => {
            if (user) {
                recordAuthAttempt(clientIp, true); // Clear rate limit on success
                logger.info(`✅ User logged in: ${user.username}`);

                // Generate Token
                const token = signToken({ id: user.id, username: user.username, email: user.email });

                // SECURITY: Bind userId to WebSocket session
                ws.userId = user.id;

                ws.send(JSON.stringify({
                    type: 'AUTH_SUCCESS',
                    user: {
                        id: user.id,
                        username: user.username,
                        email: user.email,
                        avatarId: user.avatarId
                    },
                    token: token
                }));
            } else {
                recordAuthAttempt(clientIp, false); // Record failed attempt
                logger.info(`❌ Login failed for ${email}`);
                sendError(ws, 'AUTH_FAILED', 'Email o contraseña incorrectos');
            }
        })
        .catch(err => {
            logger.error("Login error:", err);
            sendError(ws, 'LOGIN_ERROR', 'Error interno al iniciar sesión');
        });
}

function handleLoginWithToken(ws, message) {
    const { token } = message;

    if (!token) {
        sendError(ws, 'AUTH_FAILED', 'Missing token');
        return;
    }

    const payload = verifyToken(token);

    if (!payload) {
        logger.info("❌ Invalid or expired token presented");
        sendError(ws, 'AUTH_FAILED', 'Session expired');
        return;
    }

    // Token is valid, get user details to ensure they still exist
    db.getUserById(payload.id)
        .then(user => {
            if (user) {
                logger.info(`✅ User logged in via TOKEN: ${user.username}`);

                // SECURITY: Bind userId to WebSocket session
                ws.userId = user.id;

                ws.send(JSON.stringify({
                    type: 'AUTH_SUCCESS',
                    token: token,
                    user: {
                        id: user.id,
                        username: user.username,
                        email: user.email,
                        avatarId: user.avatarId
                    }
                }));
            } else {
                sendError(ws, 'AUTH_FAILED', 'User not found');
            }
        })
        .catch(err => {
            logger.error("Token login error:", err);
            sendError(ws, 'LOGIN_ERROR', 'Internal error');
        });
}


function handleUpdateProfile(ws, message) {
    const { userId, username, password } = message;

    // Security check: Verify session matches requested update
    if (!ws.userId || ws.userId !== userId) {
        logger.warn(`⚠️ SECURITY: Unauthorized profile update attempt. Session: ${ws.userId}, Target: ${userId}`);
        sendError(ws, 'FORBIDDEN', 'No tienes permiso para editar este perfil');
        return;
    }

    if (username && !isValidInput(username, 20)) {
        sendError(ws, 'INVALID_DATA', 'Username too long');
        return;
    }

    if (password && !isValidInput(password, 100)) {
        sendError(ws, 'INVALID_DATA', 'Password too long');
        return;
    }

    db.updateUser(userId, username, password)
        .then(user => {
            logger.info(`✅ Profile updated for user: ${user.username}`);
            ws.send(JSON.stringify({
                type: "PROFILE_UPDATED",
                user: {
                    id: user.id,
                    username: user.username,
                    email: user.email,
                    avatarId: user.avatarId
                }
            }));
        })
        .catch(err => {
            logger.error("Update profile error:", err);
            sendError(ws, 'UPDATE_FAILED', 'Error al actualizar perfil');
        });
}


function handleGameOver(ws, message) {
    const { gameId, winnerId } = message;

    // Validate host? Ideally yes.
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(gameId);
    if (!game) return;

    if (game.hostId !== clientData.playerId) {
        // Only host can end game
        return;
    }

    // Update game state
    game.phase = "FINISHED";
    game.winnerId = winnerId;
    game.seq++;

    logger.info(`🏁 Game Over: ${game.joinCode}, Winner: ${winnerId}`);

    // Broadcast update so clients see FINISHED phase
    game.broadcast({
        type: "STATE_SNAPSHOT",
        gameState: game.buildGameState(),
        seq: game.seq
    });

    // Prepare participants list
    const participants = [];
    for (const [pid, p] of game.players) {
        participants.push({
            userId: p.userId, // Real user ID if logged in
            playerId: pid
            // could add final level here
        });
    }

    db.recordGame(gameId, winnerId, game.createdAt, Date.now(), participants)
        .then(() => {
            logger.info("💾 Game recorded successfully");
        })
        .catch(err => logger.error("Error recording game:", err));

    // Cleanup game immediately or let it linger?
    // Usually keep it briefly for "Game Over" screen sync.
}

// ============== Turn Management ==============

function handleEndTurn(ws) {
    const clientInfo = clientGames.get(ws);
    if (!clientInfo) {
        return sendError(ws, "GENERAL_ERROR", "No estás en ninguna partida");
    }

    const game = games.get(clientInfo.gameId);
    if (!game) {
        return sendError(ws, "GENERAL_ERROR", "Partida no encontrada");
    }

    // Only current turn player can end their turn
    if (game.turnPlayerId && game.turnPlayerId !== clientInfo.playerId) {
        return sendError(ws, "GENERAL_ERROR", "No es tu turno");
    }

    // Get player order (array of player IDs)
    const playerIds = Array.from(game.players.keys());
    if (playerIds.length === 0) return;

    // Find current player index
    const currentIndex = playerIds.indexOf(game.turnPlayerId);

    // Advance to next player (wrap around)
    const nextIndex = (currentIndex + 1) % playerIds.length;
    game.turnPlayerId = playerIds[nextIndex];
    game.seq++;

    const nextPlayer = game.players.get(game.turnPlayerId);
    logger.info(`🔄 Turn advanced to: ${nextPlayer?.name || game.turnPlayerId}`);

    // Broadcast updated state
    game.broadcast({
        type: "STATE_SNAPSHOT",
        gameState: game.buildGameState(),
        seq: game.seq
    });
}

/**
 * Server-side combat result calculation.
 * Mirrors CombatCalculator.kt logic to validate/override client-claimed outcomes.
 */
function calculateCombatResult(game) {
    const combat = game.combat;
    if (!combat) return null;

    const mainPlayer = game.players.get(combat.mainPlayerId);
    if (!mainPlayer) return null;
    const helperPlayer = combat.helperPlayerId ? game.players.get(combat.helperPlayerId) : null;

    // Hero power
    let heroesPower = (mainPlayer.level || 1) + (mainPlayer.gear || 0);
    if (helperPlayer) heroesPower += (helperPlayer.level || 1) + (helperPlayer.gear || 0);
    heroesPower += (combat.heroModifier || 0);

    // Temp bonuses for heroes
    for (const bonus of (combat.tempBonuses || [])) {
        if (bonus.appliesTo === 'HEROES') heroesPower += (bonus.amount || 0);
    }

    // Intrinsic: Cleric +3 vs any Undead monster
    const hasUndead = (combat.monsters || []).some(m => m.isUndead);
    if (hasUndead) {
        if (mainPlayer.characterClass === 'CLERIC') heroesPower += 3;
        if (helperPlayer && helperPlayer.characterClass === 'CLERIC') heroesPower += 3;
    }

    // Monster power + total rewards
    let monstersPower = 0;
    let totalLevels = 0;
    let totalTreasures = 0;
    for (const m of (combat.monsters || [])) {
        monstersPower += (m.baseLevel || 0) + (m.flatModifier || 0);
        totalLevels += (m.levels || 1);
        totalTreasures += (m.treasures || 1);
    }
    monstersPower += (combat.monsterModifier || 0);

    // Temp bonuses for monsters
    for (const bonus of (combat.tempBonuses || [])) {
        if (bonus.appliesTo === 'MONSTER') monstersPower += (bonus.amount || 0);
    }

    // Outcome: Warrior wins ties
    const isWarrior = mainPlayer.characterClass === 'WARRIOR';
    const outcome = (heroesPower > monstersPower || (heroesPower === monstersPower && isWarrior)) ? 'WIN' : 'LOSE';

    // Elf helper bonus
    const helperLevelsGained = (outcome === 'WIN' && helperPlayer && helperPlayer.characterRace === 'ELF') ? 1 : 0;

    return { outcome, heroesPower, monstersPower, totalLevels, totalTreasures, helperLevelsGained };
}

function handleCombatDiceRoll(ws, message) {
    const clientInfo = clientGames.get(ws);
    if (!clientInfo) {
        return sendError(ws, "GENERAL_ERROR", "No estás en ninguna partida");
    }

    const game = games.get(clientInfo.gameId);
    if (!game) {
        return sendError(ws, "GENERAL_ERROR", "Partida no encontrada");
    }

    const player = game.players.get(clientInfo.playerId);
    if (!player) {
        return sendError(ws, "GENERAL_ERROR", "Jugador no encontrado");
    }

    // Store dice roll in combat state (if combat active)
    const { result, purpose, success } = message;

    // Validate dice result is in range [1, 6]
    const validResult = Math.max(1, Math.min(6, Math.round(Number(result) || 1)));

    const diceRollInfo = {
        playerId: clientInfo.playerId,
        playerName: player.name,
        result: validResult,
        purpose: purpose || "RANDOM",
        success: success || false,
        timestamp: Date.now()
    };

    // Store in game for buildGameState to include
    game.lastCombatDiceRoll = diceRollInfo;

    logger.info(`🎲 ${player.name} rolled ${result} for ${purpose} - ${success ? 'SUCCESS' : 'FAIL'}`);

    // Broadcast the dice roll event to all players
    game.broadcast({
        type: "COMBAT_DICE_ROLL_RESULT",
        diceRoll: diceRollInfo
    });
}

// ============== Catalog Handlers ==============

function handleCatalogSearch(ws, message) {
    const { query } = message;

    if (!query || query.length < 2) {
        ws.send(JSON.stringify({ type: "CATALOG_SEARCH_RESULT", results: [] }));
        return;
    }

    if (query.length > 50) {
        sendError(ws, 'INVALID_DATA', 'Query too long');
        return;
    }

    db.searchMonsters(query)
        .then(results => {
            logger.info(`🔍 Search '${query}' returned ${results.length} monsters`);
            ws.send(JSON.stringify({
                type: "CATALOG_SEARCH_RESULT",
                results: results
            }));
        })
        .catch(err => {
            logger.error("Search error:", err);
            sendError(ws, "SEARCH_ERROR", "Error al buscar monstruos");
        });
}

function handleCatalogAdd(ws, message) {
    const { monster, userId } = message;

    if (!monster || !monster.name || !monster.level) {
        sendError(ws, "INVALID_DATA", "Datos de monstruo inválidos");
        return;
    }

    const rateLimitKey = userId || ws.clientIp || 'anonymous';
    if (isCatalogAddRateLimited(rateLimitKey)) {
        sendError(ws, "RATE_LIMITED", "Demasiados monstruos añadidos. Espera un momento.");
        return;
    }
    recordCatalogAdd(rateLimitKey);

    db.addMonster(monster, userId)
        .then(id => {
            logger.info(`🆕 Added monster: ${monster.name} (${id})`);
            ws.send(JSON.stringify({
                type: "CATALOG_ADD_SUCCESS",
                monster: { ...monster, id }
            }));
        })
        .catch(err => {
            logger.error("Add monster error:", err);
            sendError(ws, "ADD_MONSTER_ERROR", "Error al guardar monstruo");
        });
}

function handleDisconnect(ws) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (game) {
        logger.info(`👋 Player ${clientData.playerId} disconnected from ${game.joinCode}`);

        // Mark player as disconnected but KEEP in game for reconnection
        const player = game.players.get(clientData.playerId);
        if (player) {
            player.ws = null;
            player.isConnected = false;
        }

        // Broadcast disconnect status
        game.broadcast({
            type: "PLAYER_STATUS",
            playerId: clientData.playerId,
            isConnected: false
        });

        // Count connected players
        let connectedCount = 0;
        for (const p of game.players.values()) {
            if (p.isConnected !== false && p.ws) {
                connectedCount++;
            }
        }

        // Only delete game if ALL players are disconnected
        if (connectedCount === 0) {
            // Give players 5 minutes to reconnect before deleting the game
            logger.info(`⏳ All players disconnected from ${game.joinCode}, starting 5-minute cleanup timer`);
            game.cleanupTimer = setTimeout(() => {
                // Check again if still empty
                let stillEmpty = true;
                for (const p of game.players.values()) {
                    if (p.isConnected !== false && p.ws) {
                        stillEmpty = false;
                        break;
                    }
                }
                if (stillEmpty && games.has(clientData.gameId)) {
                    games.delete(clientData.gameId);
                    db.deleteActiveGame(clientData.gameId).catch(err => logger.error('Failed to delete game from DB:', err));
                    logger.info(`🗑️ Game ${game.joinCode} deleted (all players disconnected for 5 min)`);
                }
            }, 5 * 60 * 1000); // 5 minutes
        } else {
            // Host Migration: If host disconnected, assign new connected host
            if (game.hostId === clientData.playerId) {
                // Find first connected player
                for (const [pid, p] of game.players.entries()) {
                    if (p.isConnected !== false && p.ws) {
                        game.hostId = pid;
                        game.hostName = p.name;
                        logger.info(`👑 Host migrated to ${game.hostName} (${pid})`);

                        // Broadcast new state with new host
                        game.broadcast({
                            type: "STATE_SNAPSHOT",
                            gameState: game.buildGameState(),
                            seq: game.seq
                        });
                        break;
                    }
                }
            }

            // Turn Migration: If turn player disconnected, advance turn
            if (game.turnPlayerId === clientData.playerId) {
                const nextPlayerId = getNextTurnPlayerId(game);
                if (nextPlayerId !== game.turnPlayerId) {
                    game.turnPlayerId = nextPlayerId;
                    game.combat = null; // Clear combat

                    logger.info(`⏩ Auto-advancing turn from disconnected player to ${game.players.get(nextPlayerId)?.name}`);

                    // Broadcast new state
                    game.broadcast({
                        type: "STATE_SNAPSHOT",
                        gameState: game.buildGameState(),
                        seq: game.seq
                    });
                }
            }

            // Persist changes
            db.saveActiveGame(game).catch(err => logger.error('Failed to save game:', err));
        }
    }

    clientGames.delete(ws);
}

function handleDeleteGame(ws, message) {
    const clientInfo = clientGames.get(ws);
    if (!clientInfo) return;

    const game = games.get(clientInfo.gameId);
    if (!game) return;

    if (game.hostId !== clientInfo.playerId) {
        sendError(ws, "PERMISSION_DENIED", "Solo el anfitrión puede borrar la partida");
        return;
    }

    logger.info(`🛑 Game ${game.joinCode} deleted by host ${clientInfo.playerId}`);

    // Notify all players
    game.broadcast({
        type: "GAME_DELETED",
        reason: "El anfitrión ha borrado la partida"
    });

    // Close all connections for this game? Or just let them disconnect?
    // Better to let client handle GAME_DELETED event and disconnect.

    games.delete(game.id);
    db.deleteActiveGame(game.id).catch(err => logger.error('Failed to delete game from DB:', err));
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
        const savePromises = [];
        for (const game of games.values()) {
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

function handleSwapPlayers(ws, message) {
    const { player1, player2 } = message;
    const clientInfo = clientGames.get(ws);
    if (!clientInfo) return;

    const game = games.get(clientInfo.gameId);
    if (!game) return;

    // Host check
    if (game.hostId !== clientInfo.playerId) {
        console.warn(`⚠️ Non-host ${clientInfo.playerId} tried to swap players`);
        return;
    }

    if (!game.players.has(player1) || !game.players.has(player2)) {
        console.warn('⚠️ Swap failed: player not found');
        return;
    }

    // Convert map to array entry list to preserve order
    const entries = Array.from(game.players.entries());
    const idx1 = entries.findIndex(([pid]) => pid === player1);
    const idx2 = entries.findIndex(([pid]) => pid === player2);

    if (idx1 === -1 || idx2 === -1) return;

    // Swap
    [entries[idx1], entries[idx2]] = [entries[idx2], entries[idx1]];

    // Rebuild map with new order
    game.players = new Map(entries);

    logger.info(`🔄 Swapped players ${player1} and ${player2}`);

    // Broadcast update
    game.broadcast({
        type: "STATE_UPDATE",
        gameState: game.buildGameState()
    });
}

function handleGetHostedGames(ws) {
    if (!ws.userId) {
        // Not logged in, can't track games
        ws.send(JSON.stringify({
            type: "HOSTED_GAMES_RESULT",
            games: []
        }));
        return;
    }

    const hostedGames = [];
    for (const game of games.values()) {
        if (game.hostUserId === ws.userId) {
            hostedGames.push({
                gameId: game.id,
                joinCode: game.joinCode,
                playerCount: game.players.size,
                phase: game.phase,
                createdAt: game.createdAt
            });
        }
    }

    ws.send(JSON.stringify({
        type: "HOSTED_GAMES_RESULT",
        games: hostedGames
    }));
}

function handleDeleteHostedGame(ws, message) {
    const { gameId } = message;

    // Auth check
    if (!ws.userId) {
        sendError(ws, 'UNAUTHORIZED', 'Debes iniciar sesión');
        return;
    }

    const game = games.get(gameId);
    if (!game) {
        sendError(ws, 'GAME_NOT_FOUND', 'Partida no encontrada');
        return;
    }

    // Authorization check
    if (game.hostUserId !== ws.userId) {
        logger.warn(`⚠️ Unauthorized delete attempt by ${ws.userId} on game ${gameId}`);
        sendError(ws, 'PERMISSION_DENIED', 'No eres el anfitrión de esta partida');
        return;
    }

    logger.info(`🗑️ Host ${ws.userId} deleting game ${game.joinCode} from menu`);

    // Notify players
    game.broadcast({
        type: "GAME_DELETED",
        reason: "La partida ha sido borrada por el anfitrión"
    });

    // Delete
    games.delete(gameId);
    db.deleteActiveGame(gameId).catch(err => logger.error('Failed to delete game from DB:', err));

    // Confirm to host so UI updates
    ws.send(JSON.stringify({
        type: "HOSTED_GAME_DELETED",
        gameId: gameId
    }));
}
