/**
 * Munchkin Tracker - WebSocket Server
 * Runs on Hetzner VPS (23.88.48.58:8765)
 * 
 * IMPORTANT: This server must send JSON that matches the kotlinx.serialization
 * format expected by the Android client (Protocol.kt / Models.kt)
 */

const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const db = require('./db');

const PORT = 8765;

// Store active games: gameId -> GameRoom
const games = new Map();

// Store client -> gameId mapping
const clientGames = new Map();

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
        this.turnPlayerId = null;
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
                gender: player.gender || "NA",
                level: player.level || 1,
                gearBonus: player.gear || 0,
                tempCombatBonus: 0,
                treasures: player.treasures || 0,
                raceIds: [],
                classIds: [],
                hasHalfBreed: false,
                hasSuperMunchkin: false,
                lastKnownIp: null,
                isConnected: true
            };
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
            combat: null,
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

const wss = new WebSocket.Server({ port: PORT, host: '0.0.0.0' });

console.log(`🎮 Munchkin Server running on ws://0.0.0.0:${PORT}`);

wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
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

        case 'END_TURN':
            handleEndTurn(ws);
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
        gender: playerMeta.gender || "MALE",
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
        // Reconnection
        const player = game.players.get(playerId);
        player.ws = ws;
        clientGames.set(ws, { gameId: game.id, playerId });

        ws.send(JSON.stringify({
            type: "STATE_SNAPSHOT",
            gameState: game.buildGameState(),
            seq: game.seq
        }));

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
        case 'COMBAT_END':
            if (event.outcome === 'WIN') {
                player.level = Math.min(10, player.level + (event.levelsGained || 0));
                player.treasures = (player.treasures || 0) + (event.treasuresGained || 0);
            }
            break;
        case 'GAME_START':
            game.phase = "IN_GAME";
            break;
    }
}

// ============== Auth Handlers ==============

function handleRegister(ws, message) {
    let { username, email, password, avatarId } = message;

    if (!username || !password) {
        sendError(ws, 'INVALID_DATA', 'Missing required fields');
        return;
    }

    // Auto-generate email if not provided (User just wants username)
    if (!email) {
        const sanitizedParams = username.toLowerCase().replace(/[^a-z0-9]/g, '');
        email = `${sanitizedParams}@munchkin.local`;
    }

    db.createUser(username, email, password, avatarId || 0)
        .then(user => {
            console.log(`✅ User registered: ${user.username} (${user.id})`);
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
    const { email, password } = message;

    if (!email || !password) {
        sendError(ws, 'INVALID_DATA', 'Missing email or password');
        return;
    }

    db.verifyUser(email, password)
        .then(user => {
            if (user) {
                console.log(`✅ User logged in: ${user.username}`);
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
                console.log(`❌ Login failed for ${email}`);
                sendError(ws, 'AUTH_FAILED', 'Email o contraseña incorrectos');
            }
        })
        .catch(err => {
            console.error("Login error:", err);
            sendError(ws, 'LOGIN_ERROR', 'Error interno al iniciar sesión');
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
        .then(rows => {
            const leaderboard = rows.map(r => ({
                id: r.id,
                username: r.username,
                avatarId: r.avatar_id,
                wins: r.wins
            }));

            ws.send(JSON.stringify({
                type: "LEADERBOARD_RESULT",
                leaderboard: leaderboard
            }));
        })
        .catch(err => {
            console.error("Leaderboard error:", err);
            sendError(ws, "LEADERBOARD_ERROR", "Error al obtener ranking");
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

        // Broadcast disconnect
        game.broadcast({
            type: "PLAYER_STATUS",
            playerId: clientData.playerId,
            isConnected: false
        });

        // If host left and no players, delete game
        if (game.players.size <= 1) {
            games.delete(clientData.gameId);
            console.log(`🗑️ Game ${game.joinCode} deleted (empty)`);
        }
    }

    clientGames.delete(ws);
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
            console.log(`🧹 Cleaned up old game ${game.joinCode}`);
        }
    }
}, 60 * 60 * 1000);

console.log('✅ Server ready to accept connections');
