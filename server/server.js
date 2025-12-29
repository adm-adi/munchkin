/**
 * Munchkin Tracker - WebSocket Server
 * Runs on Hetzner VPS (23.88.48.58:8765)
 */

const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const PORT = 8765;

// Store active games: gameId -> GameRoom
const games = new Map();

// Store client -> gameId mapping
const clientGames = new Map();

class GameRoom {
    constructor(hostId, joinCode, hostName) {
        this.id = uuidv4();
        this.joinCode = joinCode;
        this.hostId = hostId;
        this.hostName = hostName;
        this.players = new Map(); // playerId -> { ws, name, avatarId, gender, level, gear }
        this.gameState = null;
        this.seq = 0;
        this.createdAt = Date.now();
    }

    broadcast(message, excludePlayerId = null) {
        const data = JSON.stringify(message);
        for (const [playerId, player] of this.players) {
            if (playerId !== excludePlayerId && player.ws.readyState === WebSocket.OPEN) {
                player.ws.send(data);
            }
        }
    }

    getPlayerList() {
        const list = {};
        for (const [playerId, player] of this.players) {
            list[playerId] = {
                playerId,
                name: player.name,
                avatarId: player.avatarId,
                gender: player.gender,
                level: player.level || 1,
                gear: player.gear || 0,
                isAlive: true,
                isHost: playerId === this.hostId
            };
        }
        return list;
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
        if (game.joinCode === joinCode) {
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
        case 'HelloMessage':
            handleHello(ws, message);
            break;

        case 'CreateGameMessage':
            handleCreateGame(ws, message);
            break;

        case 'EventMessage':
            handleEvent(ws, message);
            break;

        case 'PingMessage':
            ws.send(JSON.stringify({ type: 'PongMessage', timestamp: Date.now() }));
            break;

        default:
            console.log('Unknown message type:', message.type);
    }
}

function handleCreateGame(ws, message) {
    const { playerMeta } = message;
    const joinCode = generateJoinCode();
    const playerId = playerMeta.playerId || uuidv4();

    const game = new GameRoom(playerId, joinCode, playerMeta.name);
    game.players.set(playerId, {
        ws,
        name: playerMeta.name,
        avatarId: playerMeta.avatarId,
        gender: playerMeta.gender,
        level: 1,
        gear: 0
    });

    game.gameState = {
        gameId: game.id,
        joinCode: game.joinCode,
        phase: 'LOBBY',
        players: game.getPlayerList(),
        currentPlayerId: playerId,
        turnNumber: 0,
        seq: 0
    };

    games.set(game.id, game);
    clientGames.set(ws, { gameId: game.id, playerId });

    console.log(`🎲 Game created: ${joinCode} by ${playerMeta.name}`);

    // Send welcome with game state
    ws.send(JSON.stringify({
        type: 'WelcomeMessage',
        yourPlayerId: playerId,
        gameState: game.gameState
    }));
}

function handleHello(ws, message) {
    const { joinCode, playerMeta } = message;
    const game = findGameByCode(joinCode);

    if (!game) {
        sendError(ws, 'INVALID_JOIN_CODE', 'Código de partida inválido');
        return;
    }

    const playerId = playerMeta.playerId || uuidv4();

    // Check if reconnecting
    if (game.players.has(playerId)) {
        // Reconnection
        const player = game.players.get(playerId);
        player.ws = ws;
        clientGames.set(ws, { gameId: game.id, playerId });

        ws.send(JSON.stringify({
            type: 'StateSnapshotMessage',
            gameState: game.gameState,
            seq: game.seq
        }));

        console.log(`🔄 Player ${playerMeta.name} reconnected to ${joinCode}`);
        return;
    }

    // New player joining
    game.players.set(playerId, {
        ws,
        name: playerMeta.name,
        avatarId: playerMeta.avatarId,
        gender: playerMeta.gender,
        level: 1,
        gear: 0
    });

    clientGames.set(ws, { gameId: game.id, playerId });

    // Update game state
    game.gameState.players = game.getPlayerList();
    game.seq++;
    game.gameState.seq = game.seq;

    console.log(`👤 Player ${playerMeta.name} joined ${joinCode}`);

    // Send welcome to new player
    ws.send(JSON.stringify({
        type: 'WelcomeMessage',
        yourPlayerId: playerId,
        gameState: game.gameState
    }));

    // Broadcast player joined to others
    game.broadcast({
        type: 'PlayerStatusMessage',
        playerId,
        status: 'CONNECTED'
    }, playerId);

    // Send updated state to all
    game.broadcast({
        type: 'StateSnapshotMessage',
        gameState: game.gameState,
        seq: game.seq
    });
}

function handleEvent(ws, message) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (!game) return;

    const { event } = message;
    console.log(`🎯 Event: ${event.type} from ${clientData.playerId}`);

    // Apply event to game state
    applyEvent(game, event, clientData.playerId);

    game.seq++;
    game.gameState.seq = game.seq;

    // Broadcast event to all players
    game.broadcast({
        type: 'EventBroadcastMessage',
        event,
        fromPlayerId: clientData.playerId,
        seq: game.seq
    });
}

function applyEvent(game, event, playerId) {
    const player = game.gameState.players[playerId];
    if (!player) return;

    switch (event.type) {
        case 'LevelUp':
            player.level = Math.min(10, player.level + 1);
            break;
        case 'LevelDown':
            player.level = Math.max(1, player.level - 1);
            break;
        case 'GearUp':
            player.gear = player.gear + 1;
            break;
        case 'GearDown':
            player.gear = player.gear - 1;
            break;
        case 'SetLevel':
            player.level = Math.max(1, Math.min(10, event.level));
            break;
        case 'SetGear':
            player.gear = event.gear;
            break;
        case 'StartGame':
            game.gameState.phase = 'PLAYING';
            break;
    }
}

function handleDisconnect(ws) {
    const clientData = clientGames.get(ws);
    if (!clientData) return;

    const game = games.get(clientData.gameId);
    if (game) {
        console.log(`👋 Player ${clientData.playerId} disconnected from ${game.joinCode}`);

        // Broadcast disconnect
        game.broadcast({
            type: 'PlayerStatusMessage',
            playerId: clientData.playerId,
            status: 'DISCONNECTED'
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
        type: 'ErrorMessage',
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
