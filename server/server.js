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
const winston = require('winston');

// Configure Winston Logger
const logger = winston.createLogger({
    level: 'info',
    format: winston.format.combine(
        winston.format.timestamp(),
        winston.format.json()
    ),
    transports: [
        new winston.transports.File({ filename: 'error.log', level: 'error' }),
        new winston.transports.File({ filename: 'combined.log' }),
        new winston.transports.Console({
            format: winston.format.simple()
        })
    ],
});

// Override console.log to use winston (optional, but ensures we catch everything)
// console.log = (...args) => logger.info(args.join(' '));
// console.error = (...args) => logger.error(args.join(' '));


const PORT = 8765;

// JWT configuration
const crypto = require('crypto');
const JWT_SECRET = process.env.JWT_SECRET || "munchkin_secret_key_change_in_prod";

// Simple JWT implementation (since we want to avoid complex dependencies if possible, but using internal crypto is better)
// Actually, let's use a simple signing function for now without extra deps if user didn't install jsonwebtoken
// But plan said `npm install jsonwebtoken`.
// Let's stick to standard internal crypto for signature if we can, or just use a simple base64 approach 
// signed with HMAC.
function signToken(payload) {
    const header = { alg: 'HS256', typ: 'JWT' };
    const h = Buffer.from(JSON.stringify(header)).toString('base64url');
    const p = Buffer.from(JSON.stringify(payload)).toString('base64url');
    const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url');
    return `${h}.${p}.${signature}`;
}

function verifyToken(token) {
    try {
        const [h, p, s] = token.split('.');
        if (!h || !p || !s) return null;

        const signature = crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url');
        if (signature !== s) return null;

        return JSON.parse(Buffer.from(p, 'base64url').toString());
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
        console.log('🔒 SSL Certificates found. Starting in HTTPS/WSS mode.');
    } else {
        server = http.createServer(handleRequest);
        console.log('⚠️ No SSL Certificates found (key.pem/cert.pem). Starting in HTTP/WS mode.');
    }
} catch (e) {
    console.error('Failed to load SSL certs, falling back to HTTP:', e);
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
    if (origin && allowedOrigins.some(allowed => origin.startsWith(allowed))) {
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
        const query = parsedUrl.query.q || '';
        console.log(`🔍 Search Monsters: "${query}"`);

        // SQL Injection protection handled by param binding, but relying on simple LIKE here
        // Note: db.all is async
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

    // Default: 404
    res.writeHead(404);
    res.end();
}

// WebSocket Server attached to HTTP/HTTPS server
const wss = new WebSocket.Server({ server });

server.listen(PORT, '0.0.0.0', () => {
    const protocol = isSsl ? 'HTTPS/WSS' : 'HTTP/WS';
    console.log(`✅ Server listening on port ${PORT} (${protocol})`);
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
            console.log(`🔄 Restored game ${game.joinCode} with ${game.players.size} players`);
        }
    } catch (err) {
        console.error('❌ Failed to load games from database:', err);
    }
}

// Call on startup (after a brief delay to ensure DB is ready)
setTimeout(() => loadGamesFromDatabase(), 1000);

class GameRoom {
    constructor(hostId, joinCode, hostName, avatarId, gender) {
        this.id = uuidv4();
        this.joinCode = joinCode;
        this.hostId = hostId;
        this.hostName = hostName;
        this.hostAvatarId = avatarId;
        this.hostGender = gender;
        this.players = new Map(); // playerId -> { ws, name, avatarId, gender, level, gear }
        this.seq = 0;
        this.epoch = 0;
        this.createdAt = Date.now();
        this.phase = "LOBBY";
        this.winnerId = null;
        this.turnPlayerId = hostId; // Start with host's turn
        this.combat = null;
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
                    console.log(`⚠️ Player ${player.name} (${playerId}) ws not open, state: ${player.ws.readyState}`);
                }
            }
        }
        console.log(`📢 Broadcast ${message.type} to ${sentCount} players`);
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
            console.log(`📦 buildingGameState: Sending Combat State with ${this.combat.tempBonuses.length} bonuses and mods H:${this.combat.heroModifier}/M:${this.combat.monsterModifier}`);
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
                maxLevel: 10,
                allowNegativeGear: true,
                autoNextTurn: false
            }
        };
    }
}

// Generate 6-char join code
function generateJoinCode() {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 6; i++) {
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

// console.log(`🎮 Munchkin Server running on ws://0.0.0.0:${PORT}`);

wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    ws.clientIp = clientIp; // Store for rate limiting
    console.log(`📱 Client connected from ${clientIp}`);

    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            handleMessage(ws, message);
        } catch (e) {
            console.error('Error parsing message:', e);
            sendError(ws, 'PARSE_ERROR', 'Invalid message format');
        }
    });

    ws.on('close', () => {
        handleDisconnect(ws);
    });

    ws.on('error', (err) => {
        console.error('WebSocket error:', err);
    });
});

function handleMessage(ws, message) {
    console.log('📨 Received:', message.type);

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

        case 'DELETE_GAME':
            handleDeleteGame(ws, message);
            break;

        default:
            console.log('Unknown message type:', message.type);
    }
}

function handleCreateGame(ws, message) {
    const { playerMeta } = message;
    const joinCode = generateJoinCode();
    const playerId = playerMeta.playerId || uuidv4();

    console.log(`🎲 Creating game for ${playerMeta.name} with playerId: ${playerId}`);

    const game = new GameRoom(playerId, joinCode, playerMeta.name, playerMeta.avatarId, playerMeta.gender);
    game.players.set(playerId, {
        ws,
        name: playerMeta.name,
        avatarId: playerMeta.avatarId || 0,
        gender: playerMeta.gender || "M",
        userId: playerMeta.userId || null, // Store meaningful userId
        level: 1,
        gear: 0,
        joinedAt: Date.now()
    });

    games.set(game.id, game);
    clientGames.set(ws, { gameId: game.id, playerId });

    console.log(`✅ Game created: ${joinCode} by ${playerMeta.name}`);

    // Send welcome with game state - use "WELCOME" type to match @SerialName
    const response = {
        type: "WELCOME",
        gameState: game.buildGameState(),
        yourPlayerId: playerId
    };

    console.log('📤 Sending WELCOME:', JSON.stringify(response, null, 2));
    ws.send(JSON.stringify(response));

    // Persist game to database
    db.saveActiveGame(game).catch(err => console.error('Failed to save game:', err));
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

    console.log(`📋 Listing ${availableGames.length} available games`);

    ws.send(JSON.stringify({
        type: "GAMES_LIST",
        games: availableGames
    }));
}

function handleHello(ws, message) {
    const clientIp = ws.clientIp || 'unknown';

    // Rate limiting for joining games
    if (isJoinRateLimited(clientIp)) {
        console.warn(`⚠️ Rate check: Join limit exceeded for ${clientIp}`);
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
        console.log(`❌ Invalid join code: ${joinCode}`);
        sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
        return;
    }

    const playerId = playerMeta.playerId?.value || playerMeta.playerId || uuidv4();
    console.log(`👤 Player ${playerMeta.name} joining ${joinCode} with id: ${playerId}`);

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
            console.log(`⏰ Cleanup timer cancelled for ${joinCode}`);
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

        console.log(`🔄 Player ${playerMeta.name} reconnected to ${joinCode}`);
        return;
    }

    // New player joining
    game.players.set(playerId, {
        ws,
        name: playerMeta.name,
        avatarId: playerMeta.avatarId || 0,
        gender: playerMeta.gender || "MALE",
        userId: playerMeta.userId || null,
        level: 1,
        gear: 0,
        level: 1,
        gear: 0,
        characterClass: "NONE",
        characterRace: "HUMAN",
        joinedAt: Date.now()
    });

    clientGames.set(ws, { gameId: game.id, playerId });
    game.seq++;

    console.log(`✅ Player ${playerMeta.name} joined ${joinCode}`);

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
    db.saveActiveGame(game).catch(err => console.error('Failed to save game:', err));
}

function handleEvent(ws, message) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (!game) return;

    const { event } = message;
    const playerId = clientData.playerId;
    console.log(`🎯 Event: ${event.type} from ${playerId}`);

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
        db.saveActiveGame(game).catch(err => console.error('Failed to save game:', err));
    }
}

function applyEvent(game, event, playerId) {
    const player = game.players.get(playerId);
    if (!player) return;

    switch (event.type) {
        case 'INC_LEVEL':
            player.level = Math.min(10, player.level + (event.amount || 1));
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
            player.level = Math.max(1, Math.min(10, event.level));
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
        case 'COMBAT_ADD_MONSTER':
            if (game.combat) {
                game.combat.monsters.push(event.monster);
            }
            break;
        case 'COMBAT_REMOVE_MONSTER':
            if (game.combat) {
                game.combat.monsters = game.combat.monsters.filter(m => m.id !== event.monsterId);
            }
            break;
        case 'COMBAT_ADD_HELPER':
            if (game.combat) game.combat.helperPlayerId = event.helperId;
            break;
        case 'COMBAT_REMOVE_HELPER':
            if (game.combat) game.combat.helperPlayerId = null;
            break;
        case 'COMBAT_MODIFY_MODIFIER':
            if (game.combat) {
                if (event.target === 'HEROES') game.combat.heroModifier += event.delta;
                else game.combat.monsterModifier += event.delta;
            }
            break;
        case 'COMBAT_SET_MODIFIER':
            if (game.combat) {
                if (event.target === 'HEROES') game.combat.heroModifier = event.value;
                else game.combat.monsterModifier = event.value;
            }
            break;
        case 'COMBAT_ADD_BONUS':
            if (game.combat) game.combat.tempBonuses.push(event.bonus);
            break;
        case 'COMBAT_REMOVE_BONUS':
            if (game.combat) game.combat.tempBonuses = game.combat.tempBonuses.filter(b => b.id !== event.bonusId);
            break;
        case 'COMBAT_END':
            game.combat = null;
            if (event.outcome === 'WIN') {
                player.level = Math.min(10, player.level + (event.levelsGained || 0));
                player.treasures = (player.treasures || 0) + (event.treasuresGained || 0);
            }
            break;
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
            console.log(`🏁 Game ${game.joinCode} explicit end. Winner: ${event.winnerId}`);
            closeGame(game, event.winnerId);
            break;
        case 'END_TURN':
            const nextPlayerId = getNextTurnPlayerId(game);
            game.turnPlayerId = nextPlayerId;
            game.combat = null; // Clear combat state
            console.log(`cw Turn passed to ${game.players.get(nextPlayerId)?.name}`);
            break;
    }

    // Check Win Condition
    if (player.level >= 10 && !game.winnerId) {
        console.log(`🏆 Player ${player.name} reached Level 10!`);
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
        .then(() => console.log(`💾 Game ${game.id} recorded in history`))
        .catch(err => console.error(`❌ Failed to record game ${game.id}`, err));
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

// ============== Auth Handlers ==============

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
            console.log(`✅ User registered: ${user.username} (${user.id})`);

            // SECURITY: Bind userId to WebSocket session
            ws.userId = user.id;

            ws.send(JSON.stringify({
                type: 'AUTH_SUCCESS',
                user: {
                    id: user.id,
                    username: user.username,
                    email: user.email,
                    avatarId: user.avatarId
                }
            }));
        })
        .catch(err => {
            console.error("Register failed:", err.message);
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
                console.log(`✅ User logged in: ${user.username}`);

                // SECURITY: Bind userId to WebSocket session
                ws.userId = user.id;

                ws.send(JSON.stringify({
                    type: 'AUTH_SUCCESS',
                    user: {
                        id: user.id,
                        username: user.username,
                        email: user.email,
                        avatarId: user.avatarId
                    }
                }));
            } else {
                recordAuthAttempt(clientIp, false); // Record failed attempt
                console.log(`❌ Login failed for ${email}`);
                sendError(ws, 'AUTH_FAILED', 'Email o contraseña incorrectos');
            }
        })
        .catch(err => {
            console.error("Login error:", err);
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
        console.log("❌ Invalid or expired token presented");
        sendError(ws, 'AUTH_FAILED', 'Session expired');
        return;
    }

    // Token is valid, get user details to ensure they still exist
    db.getUserById(payload.id)
        .then(user => {
            if (user) {
                console.log(`✅ User logged in via TOKEN: ${user.username}`);

                // SECURITY: Bind userId to WebSocket session
                ws.userId = user.id;

                // If token is near expiration, we could issue a new one here
                // For now, just confirm success

                ws.send(JSON.stringify({
                    type: 'AUTH_SUCCESS',
                    token: token, // Echo back or send new one
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
            console.error("Token login error:", err);
            sendError(ws, 'LOGIN_ERROR', 'Internal error');
        });
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
            console.error("History error:", err);
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
        .catch(err => console.error("Leaderboard error:", err));
}

// ============== Auth Handlers ==============

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
            console.log(`✅ User registered: ${user.username} (${user.id})`);

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
            console.error("Register failed:", err.message);
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
                console.log(`✅ User logged in: ${user.username}`);

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
                console.log(`❌ Login failed for ${email}`);
                sendError(ws, 'AUTH_FAILED', 'Email o contraseña incorrectos');
            }
        })
        .catch(err => {
            console.error("Login error:", err);
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
        console.log("❌ Invalid or expired token presented");
        sendError(ws, 'AUTH_FAILED', 'Session expired');
        return;
    }

    // Token is valid, get user details to ensure they still exist
    db.getUserById(payload.id)
        .then(user => {
            if (user) {
                console.log(`✅ User logged in via TOKEN: ${user.username}`);

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
            console.error("Token login error:", err);
            sendError(ws, 'LOGIN_ERROR', 'Internal error');
        });
}


function handleUpdateProfile(ws, message) {
    const { userId, username, password } = message;

    // Security check: Verify session matches requested update
    if (!ws.userId || ws.userId !== userId) {
        console.warn(`⚠️ SECURITY: Unauthorized profile update attempt. Session: ${ws.userId}, Target: ${userId}`);
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
            console.log(`✅ Profile updated for user: ${user.username}`);
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
            console.error("Update profile error:", err);
            sendError(ws, 'UPDATE_FAILED', 'Error al actualizar perfil');
        });
}


function handleEndTurn(ws) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (!game) return;

    // Only current turn player can end turn? Or host?
    // Let's strictly allow only the turn player (or host for override, but let's stick to turn player first)
    // Actually, usually turn player ends turn.
    if (game.turnPlayerId !== clientData.playerId) {
        console.log(`⚠️ Player ${clientData.playerId} tried to end turn but it is ${game.turnPlayerId}'s turn`);
        return;
    }

    // Pass turn to next player
    const playerIds = Array.from(game.players.keys());
    const currentIndex = playerIds.indexOf(game.turnPlayerId);
    let nextIndex = (currentIndex + 1) % playerIds.length;
    game.turnPlayerId = playerIds[nextIndex];

    console.log(`🔄 Turn changed: ${clientData.playerId} -> ${game.turnPlayerId}`);
    game.broadcast(game.buildGameState());
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

    console.log(`🏁 Game Over: ${game.joinCode}, Winner: ${winnerId}`);

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
            console.log("💾 Game recorded successfully");
        })
        .catch(err => console.error("Error recording game:", err));

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
    console.log(`🔄 Turn advanced to: ${nextPlayer?.name || game.turnPlayerId}`);

    // Broadcast updated state
    game.broadcast({
        type: "STATE_SNAPSHOT",
        gameState: game.buildGameState(),
        seq: game.seq
    });
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
    const diceRollInfo = {
        playerId: clientInfo.playerId,
        playerName: player.name,
        result: result,
        purpose: purpose || "RANDOM",
        success: success || false,
        timestamp: Date.now()
    };

    // Store in game for buildGameState to include
    game.lastCombatDiceRoll = diceRollInfo;

    console.log(`🎲 ${player.name} rolled ${result} for ${purpose} - ${success ? 'SUCCESS' : 'FAIL'}`);

    // Broadcast the dice roll event to all players
    game.broadcast({
        type: "COMBAT_DICE_ROLL_RESULT",
        diceRoll: diceRollInfo
    });
}

// ============== Catalog Handlers ==============

function handleCatalogSearch(ws, message) {
    const { query } = message;

    // Allow empty query to return recent random monsters? For not, require 2 chars.
    if (!query || query.length < 2) {
        ws.send(JSON.stringify({
            type: "CATALOG_SEARCH_RESULT",
            results: []
        }));
        return;
    }

    db.searchMonsters(query)
        .then(results => {
            console.log(`🔍 Search '${query}' returned ${results.length} monsters`);
            ws.send(JSON.stringify({
                type: "CATALOG_SEARCH_RESULT",
                results: results
            }));
        })
        .catch(err => {
            console.error("Search error:", err);
            sendError(ws, "SEARCH_ERROR", "Error al buscar monstruos");
        });
}

function handleCatalogAdd(ws, message) {
    const { monster, userId } = message;

    if (!monster || !monster.name || !monster.level) {
        sendError(ws, "INVALID_DATA", "Datos de monstruo inválidos");
        return;
    }

    db.addMonster(monster, userId)
        .then(id => {
            console.log(`🆕 Added monster: ${monster.name} (${id})`);
            ws.send(JSON.stringify({
                type: "CATALOG_ADD_SUCCESS",
                monster: { ...monster, id }
            }));
        })
        .catch(err => {
            console.error("Add monster error:", err);
            sendError(ws, "ADD_MONSTER_ERROR", "Error al guardar monstruo");
        });
}

function handleDisconnect(ws) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (game) {
        console.log(`👋 Player ${clientData.playerId} disconnected from ${game.joinCode}`);

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
            console.log(`⏳ All players disconnected from ${game.joinCode}, starting 5-minute cleanup timer`);
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
                    db.deleteActiveGame(clientData.gameId).catch(err => console.error('Failed to delete game from DB:', err));
                    console.log(`🗑️ Game ${game.joinCode} deleted (all players disconnected for 5 min)`);
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
                        console.log(`👑 Host migrated to ${game.hostName} (${pid})`);

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

                    console.log(`⏩ Auto-advancing turn from disconnected player to ${game.players.get(nextPlayerId)?.name}`);

                    // Broadcast new state
                    game.broadcast({
                        type: "STATE_SNAPSHOT",
                        gameState: game.buildGameState(),
                        seq: game.seq
                    });
                }
            }

            // Persist changes
            db.saveActiveGame(game).catch(err => console.error('Failed to save game:', err));
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

    console.log(`🛑 Game ${game.joinCode} deleted by host ${clientInfo.playerId}`);

    // Notify all players
    game.broadcast({
        type: "GAME_DELETED",
        reason: "El anfitrión ha borrado la partida"
    });

    // Close all connections for this game? Or just let them disconnect?
    // Better to let client handle GAME_DELETED event and disconnect.

    games.delete(game.id);
    db.deleteActiveGame(game.id).catch(err => console.error('Failed to delete game from DB:', err));
}

function sendError(ws, code, message) {
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
            db.deleteActiveGame(gameId).catch(err => console.error('Failed to delete game from DB:', err));
            console.log(`🧹 Cleaned up old game ${game.joinCode}`);
        }
    }

    // Cleanup database orphans
    db.cleanupOldGames().catch(err => console.error('Failed to cleanup DB:', err));
}, 60 * 60 * 1000);

console.log('✅ Server ready to accept connections');

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

    console.log(`🔄 Swapped players ${player1} and ${player2}`);

    // Broadcast update
    game.broadcast({
        type: "STATE_UPDATE",
        gameState: game.buildGameState()
    });
}
