/**
 * WebSocket layer — port of network/GameClient.kt.
 *
 * One persistent GameSocket per game (HELLO/WELCOME handshake, event requests,
 * automatic reconnection with the seat's rotating reconnectToken), plus one-off
 * request helpers that open a short-lived connection, ask one thing, and close
 * (auth, leaderboard, history, catalog) — exactly how the Android client talks
 * to the same server.
 *
 * The WebSocket implementation is injectable so the protocol flow can be
 * integration-tested from Node (with the `ws` package) against the real server.
 */

import { MSG, messages } from './protocol.mjs';
import { applyEvent, applyPlayerStatus } from './engine.mjs';

export const ConnState = {
    DISCONNECTED: 'DISCONNECTED',
    CONNECTING: 'CONNECTING',
    CONNECTED: 'CONNECTED',
    RECONNECTING: 'RECONNECTING',
    FAILED_PERMANENTLY: 'FAILED_PERMANENTLY'
};

/** A server ERROR reply, carrying the machine-readable code. */
export class ServerError extends Error {
    constructor(code, message) {
        super(message);
        this.name = 'ServerError';
        this.code = code || 'UNKNOWN';
    }
}

export class TimeoutError extends Error {
    constructor() {
        super('El servidor no respondió a tiempo');
        this.name = 'TimeoutError';
    }
}

const RESPONSE_TIMEOUT_MS = 15_000;
const RECONNECT_DELAY_MS = 1_000;
const MAX_RECONNECT_DELAY_MS = 30_000;
const MAX_RECONNECT_ATTEMPTS = 15;
const CLOCK_SYNC_INTERVAL_MS = 20_000;

function messageData(event) {
    const data = event?.data ?? event;
    return typeof data === 'string' ? data : String(data);
}

export class GameSocket {
    constructor({ url, WebSocketImpl = globalThis.WebSocket } = {}) {
        this.url = url;
        this.Ws = WebSocketImpl;
        this.ws = null;
        this.connState = ConnState.DISCONNECTED;
        this.gameState = null;
        this.myPlayerId = null;
        this.reconnectToken = null;
        this.serverTimeOffsetMs = 0;
        this.latencyMs = 0;
        this.reconnectAttempt = 0;

        this._listeners = new Map();
        this._pending = null;          // resolver for the handshake reply
        this._shuttingDown = false;
        this._pingTimer = null;
        this._pingSentAt = null;
        this._reconnectTimer = null;
        this._reconnectWake = null;    // resolves the current backoff delay early
        this._last = null;             // { joinCode, meta, authToken } for reconnects
    }

    on(type, fn) {
        if (!this._listeners.has(type)) this._listeners.set(type, new Set());
        this._listeners.get(type).add(fn);
        return () => this._listeners.get(type)?.delete(fn);
    }

    _emit(type, payload) {
        for (const fn of this._listeners.get(type) || []) {
            try { fn(payload); } catch (e) { console.error(e); }
        }
    }

    _setConnState(state) {
        if (this.connState === state) return;
        this.connState = state;
        this._emit('connection', { state, attempt: this.reconnectAttempt });
    }

    isConnected() {
        return this.connState === ConnState.CONNECTED;
    }

    /** Server-clock "now", for turn deadlines (turnEndsAt is server time). */
    serverNow() {
        return Date.now() + this.serverTimeOffsetMs;
    }

    /**
     * Creates a game and stays connected as its host.
     */
    async create({ meta, superMunchkin = false, turnTimerSeconds = 0, authToken = null }) {
        this._last = { joinCode: null, meta, authToken };
        const welcome = await this._openSession(
            messages.createGame(meta, { superMunchkin, turnTimerSeconds }),
            authToken
        );
        // Reconnects go through HELLO with the room's join code.
        this._last.joinCode = this.gameState?.joinCode || null;
        this._last.meta = { ...meta, playerId: this.myPlayerId };
        return welcome;
    }

    /**
     * Joins (or rejoins) a game by code. `meta.playerId` plus `reconnectToken`
     * reclaim an existing seat; a signed-in user reclaims theirs by account.
     */
    async join({ joinCode, meta, reconnectToken = null, authToken = null }) {
        this.reconnectToken = reconnectToken || this.reconnectToken;
        this._last = { joinCode, meta, authToken };
        const welcome = await this._openSession(
            messages.hello(joinCode, meta, this.reconnectToken),
            authToken
        );
        this._last.meta = { ...meta, playerId: this.myPlayerId };
        return welcome;
    }

    async _openSession(request, authToken) {
        this._shuttingDown = false;
        this._closeSocket();
        if (this.connState !== ConnState.RECONNECTING) {
            this._setConnState(ConnState.CONNECTING);
        }

        const ws = new this.Ws(this.url);
        this.ws = ws;

        try {
            await this._awaitOpen(ws);

            ws.addEventListener('message', event => this._onMessage(ws, event));
            ws.addEventListener('close', () => this._onClose(ws));

            if (authToken) {
                ws.send(JSON.stringify(messages.loginWithToken(authToken)));
                const reply = await this._awaitReply([MSG.AUTH_SUCCESS]);
                this._emit('auth', reply);
            }

            ws.send(JSON.stringify(request));
            const welcome = await this._awaitReply([MSG.WELCOME]);

            this.gameState = welcome.gameState;
            this.myPlayerId = welcome.yourPlayerId;
            if (welcome.reconnectToken) this.reconnectToken = welcome.reconnectToken;

            this.reconnectAttempt = 0;
            this._setConnState(ConnState.CONNECTED);
            this._startPing(ws);
            this._emit('welcome', welcome);
            this._emit('state', this.gameState);
            return welcome;
        } catch (err) {
            this._pending = null;
            if (this.ws === ws) {
                this.ws = null;
                try { ws.close(); } catch { /* already closed */ }
            }
            throw err;
        }
    }

    _awaitOpen(ws) {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new TimeoutError()), RESPONSE_TIMEOUT_MS);
            ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
            ws.addEventListener('error', () => {
                clearTimeout(timer);
                reject(new Error('No se pudo conectar con el servidor'));
            }, { once: true });
        });
    }

    _awaitReply(types) {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this._pending = null;
                reject(new TimeoutError());
            }, RESPONSE_TIMEOUT_MS);
            this._pending = { types, resolve, reject, timer };
        });
    }

    _onMessage(ws, event) {
        if (ws !== this.ws) return;
        let msg;
        try {
            msg = JSON.parse(messageData(event));
        } catch {
            return;
        }

        // Handshake replies win; ERROR during a handshake fails it.
        if (this._pending) {
            const { types, resolve, reject, timer } = this._pending;
            if (types.includes(msg.type)) {
                clearTimeout(timer);
                this._pending = null;
                resolve(msg);
                return;
            }
            if (msg.type === MSG.ERROR) {
                clearTimeout(timer);
                this._pending = null;
                reject(new ServerError(msg.code, msg.message));
                return;
            }
        }

        switch (msg.type) {
            case MSG.STATE_SNAPSHOT:
                this.gameState = msg.gameState;
                this._emit('state', this.gameState);
                break;
            case MSG.EVENT_BROADCAST:
                this.gameState = applyEvent(this.gameState, msg.event);
                this._emit('state', this.gameState);
                break;
            case MSG.PLAYER_STATUS:
                this.gameState = applyPlayerStatus(this.gameState, msg.playerId, msg.isConnected);
                this._emit('state', this.gameState);
                break;
            case MSG.ERROR:
                this._emit('error', new ServerError(msg.code, msg.message));
                break;
            case MSG.PONG: {
                if (this._pingSentAt !== null) {
                    const rtt = Math.max(0, Date.now() - this._pingSentAt);
                    this._pingSentAt = null;
                    this.latencyMs = rtt;
                    this.serverTimeOffsetMs = (msg.timestamp + rtt / 2) - Date.now();
                }
                break;
            }
            case MSG.COMBAT_DICE_ROLL_RESULT:
                this._emit('dice', msg.diceRoll);
                break;
            case MSG.GAME_DELETED:
                this._emit('deleted', msg.reason);
                this.leave();
                break;
            default:
                this._emit('message', msg);
        }
    }

    _onClose(ws) {
        if (ws !== this.ws) return;
        this.ws = null;
        this._stopPing();
        if (this._shuttingDown) {
            this._setConnState(ConnState.DISCONNECTED);
            return;
        }
        // Mid-handshake closes are settled by the handshake's own error path.
        if (this._pending) return;
        this._startReconnectLoop();
    }

    async _startReconnectLoop() {
        if (!this._last?.joinCode) {
            this._setConnState(ConnState.DISCONNECTED);
            return;
        }
        this._setConnState(ConnState.RECONNECTING);

        for (let attempt = 0; attempt < MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (this._shuttingDown) return;
            this.reconnectAttempt = attempt + 1;
            this._emit('connection', { state: ConnState.RECONNECTING, attempt: this.reconnectAttempt });

            const delay = Math.min(RECONNECT_DELAY_MS * (2 ** attempt), MAX_RECONNECT_DELAY_MS);
            await this._sleep(delay);
            if (this._shuttingDown) return;

            try {
                await this._openSession(
                    messages.hello(this._last.joinCode, this._last.meta, this.reconnectToken),
                    this._last.authToken
                );
                return;
            } catch {
                // Fall through to the next, slower attempt.
            }
        }

        this.reconnectAttempt = 0;
        this._setConnState(ConnState.FAILED_PERMANENTLY);
    }

    _sleep(ms) {
        return new Promise(resolve => {
            this._reconnectWake = resolve;
            this._reconnectTimer = setTimeout(() => {
                this._reconnectWake = null;
                resolve();
            }, ms);
        });
    }

    /**
     * Retries right now: the page came back to the foreground, or the network
     * returned. iOS Safari suspends pages when the phone locks, so waiting out
     * a 30-second backoff after unlock feels broken.
     */
    nudge() {
        if (this.connState === ConnState.RECONNECTING && this._reconnectWake) {
            clearTimeout(this._reconnectTimer);
            const wake = this._reconnectWake;
            this._reconnectWake = null;
            wake();
        } else if (this.connState === ConnState.FAILED_PERMANENTLY) {
            this._startReconnectLoop();
        } else if (this.isConnected()) {
            // A stale socket fails the send, which fires close → reconnect.
            this._sendPing();
        }
    }

    _startPing(ws) {
        this._stopPing();
        this._pingTimer = setInterval(() => this._sendPing(), CLOCK_SYNC_INTERVAL_MS);
        this._sendPing(ws);
    }

    _sendPing(ws = this.ws) {
        if (!ws) return;
        try {
            this._pingSentAt = Date.now();
            ws.send(JSON.stringify(messages.ping()));
        } catch {
            try { ws.close(); } catch { /* ignore */ }
        }
    }

    _stopPing() {
        if (this._pingTimer) clearInterval(this._pingTimer);
        this._pingTimer = null;
        this._pingSentAt = null;
    }

    /** Sends a game event (EVENT_REQUEST). Fire and forget, like the app. */
    sendEvent(event) {
        this.sendMessage(messages.eventRequest(event));
    }

    sendMessage(msg) {
        if (!this.ws) throw new Error('No conectado');
        this.ws.send(JSON.stringify(msg));
    }

    /** Deliberate teardown: no reconnection follows. */
    leave() {
        this._shuttingDown = true;
        if (this._reconnectTimer) clearTimeout(this._reconnectTimer);
        this._reconnectWake = null;
        this._stopPing();
        this._closeSocket();
        this.gameState = null;
        this.myPlayerId = null;
        this.reconnectAttempt = 0;
        this.serverTimeOffsetMs = 0;
        this._setConnState(ConnState.DISCONNECTED);
    }

    _closeSocket() {
        const ws = this.ws;
        this.ws = null;
        if (ws) {
            try { ws.close(1000, 'Client disconnecting'); } catch { /* ignore */ }
        }
    }
}

// ============== One-off requests ==============

function openSocket(url, WebSocketImpl) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocketImpl(url);
        const timer = setTimeout(() => {
            try { ws.close(); } catch { /* ignore */ }
            reject(new TimeoutError());
        }, RESPONSE_TIMEOUT_MS);
        ws.addEventListener('open', () => { clearTimeout(timer); resolve(ws); }, { once: true });
        ws.addEventListener('error', () => {
            clearTimeout(timer);
            reject(new Error('No se pudo conectar con el servidor'));
        }, { once: true });
    });
}

function nextMessage(ws) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new TimeoutError()), RESPONSE_TIMEOUT_MS);
        const onMessage = event => {
            clearTimeout(timer);
            ws.removeEventListener('message', onMessage);
            try {
                resolve(JSON.parse(messageData(event)));
            } catch (e) {
                reject(e);
            }
        };
        ws.addEventListener('message', onMessage);
        ws.addEventListener('close', () => {
            clearTimeout(timer);
            reject(new Error('Conexión cerrada'));
        }, { once: true });
    });
}

function throwIfError(msg) {
    if (msg.type === MSG.ERROR) throw new ServerError(msg.code, msg.message);
    return msg;
}

/** Connect → send → one reply → close. */
export async function oneOffRequest(url, message, { WebSocketImpl = globalThis.WebSocket } = {}) {
    const ws = await openSocket(url, WebSocketImpl);
    try {
        ws.send(JSON.stringify(message));
        return throwIfError(await nextMessage(ws));
    } finally {
        try { ws.close(); } catch { /* ignore */ }
    }
}

/** Connect → LOGIN_WITH_TOKEN → send → one reply → close. */
export async function authenticatedRequest(url, token, message, { WebSocketImpl = globalThis.WebSocket } = {}) {
    const ws = await openSocket(url, WebSocketImpl);
    try {
        ws.send(JSON.stringify(messages.loginWithToken(token)));
        const auth = throwIfError(await nextMessage(ws));
        if (auth.type !== MSG.AUTH_SUCCESS) throw new Error('Respuesta de autenticación inesperada');
        ws.send(JSON.stringify(message));
        return { reply: throwIfError(await nextMessage(ws)), auth };
    } finally {
        try { ws.close(); } catch { /* ignore */ }
    }
}
