/**
 * Test harness: boots a real server on a free port against a throwaway database,
 * and gives tests a small request/response helper over a real WebSocket.
 *
 * Deliberately exercises the actual process rather than requiring server.js:
 * requiring it binds a port and opens a database as a side effect, and the point
 * of these tests is to cover the protocol handlers end to end.
 */

const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const net = require('node:net');
const WebSocket = require('ws');

function freePort() {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.unref();
        srv.on('error', reject);
        srv.listen(0, '127.0.0.1', () => {
            const { port } = srv.address();
            srv.close(() => resolve(port));
        });
    });
}

/**
 * Starts a server instance. Returns { url, stop, dbPath }.
 */
async function startServer({
    jwtSecret = 'integration-test-secret',
    // Raised well above the production default of 3 so the suite can register an
    // account per test from a single IP. The limit itself is covered by its own
    // test, which starts a server with a deliberately low value.
    registerLimit = 1000
} = {}) {
    const port = await freePort();
    const dbPath = path.join(
        fs.mkdtempSync(path.join(os.tmpdir(), 'munchkin-test-')),
        'test.db'
    );

    const child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
        env: {
            ...process.env,
            JWT_SECRET: jwtSecret,
            MUNCHKIN_DB_PATH: dbPath,
            MUNCHKIN_LOG_DIR: path.dirname(dbPath),
            MUNCHKIN_REGISTER_LIMIT: String(registerLimit),
            PORT: String(port)
        },
        stdio: ['ignore', 'pipe', 'pipe']
    });

    const logs = [];
    child.stdout.on('data', d => logs.push(d.toString()));
    child.stderr.on('data', d => logs.push(d.toString()));

    // Wait for the listening line rather than sleeping a fixed amount.
    await new Promise((resolve, reject) => {
        const timer = setTimeout(
            () => reject(new Error(`server did not start in time:\n${logs.join('')}`)),
            20000
        );
        const check = () => {
            if (logs.join('').includes('listening on port')) {
                clearTimeout(timer);
                resolve();
            }
        };
        child.stdout.on('data', check);
        child.stderr.on('data', check);
        child.on('exit', code => {
            clearTimeout(timer);
            reject(new Error(`server exited with ${code}:\n${logs.join('')}`));
        });
    });

    return {
        url: `ws://127.0.0.1:${port}`,
        dbPath,
        logs: () => logs.join(''),
        async stop() {
            if (child.exitCode === null) {
                child.kill();
                await new Promise(r => child.once('exit', r));
            }
            try { fs.rmSync(path.dirname(dbPath), { recursive: true, force: true }); } catch { /* best effort */ }
        }
    };
}

const DEFAULT_TIMEOUT_MS = 8000;

/**
 * A WebSocket connection with await-able send/receive, so tests can express a
 * conversation as a sequence of steps.
 */
class TestClient {
    constructor(ws) {
        this.ws = ws;
        this.queue = [];
        this.waiters = [];
        ws.on('message', raw => {
            let msg;
            try { msg = JSON.parse(raw.toString()); } catch { return; }
            const waiter = this.waiters.shift();
            if (waiter) waiter(msg);
            else this.queue.push(msg);
        });
    }

    static connect(url) {
        return new Promise((resolve, reject) => {
            const ws = new WebSocket(url);
            const timer = setTimeout(() => reject(new Error('connect timed out')), DEFAULT_TIMEOUT_MS);
            ws.on('open', () => { clearTimeout(timer); resolve(new TestClient(ws)); });
            ws.on('error', err => { clearTimeout(timer); reject(err); });
        });
    }

    send(message) {
        this.ws.send(JSON.stringify(message));
    }

    /** Next message of any type. */
    next(timeoutMs = DEFAULT_TIMEOUT_MS) {
        if (this.queue.length) return Promise.resolve(this.queue.shift());
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('timed out waiting for a message')), timeoutMs);
            this.waiters.push(msg => { clearTimeout(timer); resolve(msg); });
        });
    }

    /**
     * Next message whose type is one of `types`, skipping anything else. Snapshots
     * and status broadcasts interleave with replies, so tests need to filter.
     */
    async waitFor(types, timeoutMs = DEFAULT_TIMEOUT_MS) {
        const wanted = Array.isArray(types) ? types : [types];
        const deadline = Date.now() + timeoutMs;
        const skipped = [];
        while (Date.now() < deadline) {
            const msg = await this.next(Math.max(50, deadline - Date.now()));
            if (wanted.includes(msg.type)) return msg;
            skipped.push(msg.type);
        }
        throw new Error(`never saw ${wanted.join('|')}; saw: ${skipped.join(', ')}`);
    }

    /**
     * Discards anything already buffered and returns what was dropped.
     *
     * Joining a room, another player acting, and timer changes all push broadcasts
     * to every client. Without clearing them first, a request() that accepts
     * STATE_SNAPSHOT as a valid reply would match a stale queued snapshot instead of
     * the actual response to what it just sent.
     */
    drain() {
        const dropped = this.queue.splice(0, this.queue.length);
        return dropped.map(m => m.type);
    }

    /**
     * Waits briefly for in-flight broadcasts to arrive, then discards them.
     *
     * drain() alone only clears what has already been parsed; a broadcast triggered
     * by another client's action (a join, say) may still be on the wire. Call this
     * after such an action, before asserting on a reply.
     */
    async settle(ms = 150) {
        await new Promise(r => setTimeout(r, ms));
        return this.drain();
    }

    /** Sends a message and waits for one of the expected reply types. */
    async request(message, replyTypes, timeoutMs = DEFAULT_TIMEOUT_MS) {
        this.drain();
        this.send(message);
        return this.waitFor(replyTypes, timeoutMs);
    }

    close() {
        return new Promise(resolve => {
            if (this.ws.readyState === WebSocket.CLOSED) return resolve();
            this.ws.once('close', resolve);
            this.ws.close();
        });
    }
}

/** Registers an account and returns { token, user, client }. */
async function registerUser(url, username, password = 'test-password-123') {
    const client = await TestClient.connect(url);
    const reply = await client.request(
        { type: 'REGISTER', username, email: `${username}@example.com`, password },
        ['AUTH_SUCCESS', 'ERROR']
    );
    return { reply, client };
}

/** Creates a game and returns { client, welcome }. */
async function createGame(url, name = 'Host', extra = {}) {
    const client = await TestClient.connect(url);
    const welcome = await client.request(
        {
            type: 'CreateGameMessage',
            playerMeta: { name, avatarId: 0, gender: 'M' },
            ...extra
        },
        ['WELCOME', 'ERROR']
    );
    return { client, welcome };
}

/** Joins an existing game by code. */
async function joinGame(url, joinCode, name, extra = {}) {
    const client = await TestClient.connect(url);
    const welcome = await client.request(
        {
            type: 'HELLO',
            joinCode,
            playerMeta: { name, avatarId: 0, gender: 'F' },
            ...extra
        },
        ['WELCOME', 'ERROR']
    );
    return { client, welcome };
}

module.exports = { startServer, TestClient, registerUser, createGame, joinGame };
