const sqlite3 = require('sqlite3').verbose();
const bcrypt = require('bcryptjs');
const { randomUUID } = require('crypto');
const fs = require('fs');
const path = require('path');
const logger = require('./logger');

// Resolved against this file, not the process CWD: starting the server from a
// different directory used to silently create a second, empty database.
const DB_SOURCE = process.env.MUNCHKIN_DB_PATH || path.join(__dirname, 'munchkin.db');

const BCRYPT_COST = 12;
const LEADERBOARD_CACHE_TTL_MS = 15 * 1000;

/** @type {Map<number, { ts: number, rows: any[] }>} */
const leaderboardCache = new Map();

/** @type {Promise<any>} */
let writeLockQueue = Promise.resolve();

/**
 * Serializes all SQLite write operations and explicit transactions so concurrent
 * async callers never interleave BEGIN/COMMIT statements or contend on SQLITE_BUSY.
 * NOTE: Never nest a withWriteLock call inside another withWriteLock callback.
 * @template T
 * @param {() => Promise<T>} fn
 * @returns {Promise<T>}
 */
function withWriteLock(fn) {
    const next = writeLockQueue.then(() => fn());
    writeLockQueue = next.catch(() => {});
    return next;
}

/**
 * Resolves once the schema, migrations, seed data, and FTS5 index are ready.
 */
let signalReady;
let signalReadyFailed;
const ready = new Promise((resolve, reject) => {
    signalReady = resolve;
    signalReadyFailed = reject;
});

function run(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.run(sql, params, function (err) {
            if (err) reject(err);
            else resolve(this);
        });
    });
}

function get(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.get(sql, params, (err, row) => {
            if (err) reject(err);
            else resolve(row);
        });
    });
}

function all(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.all(sql, params, (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
        });
    });
}

function execSql(sql) {
    return new Promise((resolve, reject) => {
        db.exec(sql, (err) => {
            if (err) reject(err);
            else resolve();
        });
    });
}

async function runPragma(statement) {
    try {
        await run(statement);
    } catch (err) {
        logger.error(`SQLite PRAGMA failed (${statement}):`, err);
        throw err;
    }
}

async function runMigration(sql, label) {
    try {
        await run(sql);
    } catch (err) {
        if (err && typeof err.message === 'string' && err.message.toLowerCase().includes('duplicate column name')) {
            return;
        }
        logger.error(`Migration error (${label}):`, err);
        throw err;
    }
}

const db = new sqlite3.Database(DB_SOURCE, (err) => {
    if (err) {
        logger.error("❌ Error opening database", err.message);
        signalReadyFailed(err);
        return;
    }
    logger.info(`📂 Connected to SQLite database at ${DB_SOURCE}`);
    initTables().catch((initErr) => {
        logger.error("❌ Database initialization failed:", initErr);
        signalReadyFailed(initErr);
    });
});

async function initFts5Catalog() {
    const existing = await get(
        `SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'monsters_fts'`
    );
    // Earlier iterations with content_rowid='id' against a TEXT primary key returned
    // zero rows on FTS5 MATCH; rebuild using SQLite's implicit integer rowid.
    if (existing && typeof existing.sql === 'string' && !existing.sql.includes('content_rowid=rowid') && !existing.sql.includes("content_rowid='rowid'")) {
        await run('DROP TRIGGER IF EXISTS monsters_ai');
        await run('DROP TRIGGER IF EXISTS monsters_ad');
        await run('DROP TRIGGER IF EXISTS monsters_au');
        await run('DROP TABLE IF EXISTS monsters_fts');
    }

    await run(
        `CREATE VIRTUAL TABLE IF NOT EXISTS monsters_fts USING fts5(
            name,
            bad_stuff,
            expansion,
            content='monsters',
            content_rowid='rowid'
        )`
    );

    await run(
        `CREATE TRIGGER IF NOT EXISTS monsters_ai AFTER INSERT ON monsters BEGIN
            INSERT INTO monsters_fts(rowid, name, bad_stuff, expansion)
            VALUES (new.rowid, new.name, new.bad_stuff, new.expansion);
        END`
    );

    await run(
        `CREATE TRIGGER IF NOT EXISTS monsters_ad AFTER DELETE ON monsters BEGIN
            INSERT INTO monsters_fts(monsters_fts, rowid, name, bad_stuff, expansion)
            VALUES ('delete', old.rowid, old.name, old.bad_stuff, old.expansion);
        END`
    );

    await run(
        `CREATE TRIGGER IF NOT EXISTS monsters_au AFTER UPDATE ON monsters BEGIN
            INSERT INTO monsters_fts(monsters_fts, rowid, name, bad_stuff, expansion)
            VALUES ('delete', old.rowid, old.name, old.bad_stuff, old.expansion);
            INSERT INTO monsters_fts(rowid, name, bad_stuff, expansion)
            VALUES (new.rowid, new.name, new.bad_stuff, new.expansion);
        END`
    );

    await run(`INSERT INTO monsters_fts(monsters_fts) VALUES('rebuild')`);
}

async function initTables() {
    // WAL + NORMAL gives fast, crash-safe concurrency; foreign_keys is off by
    // default in SQLite and must be enabled per connection.
    await runPragma('PRAGMA journal_mode = WAL');
    await runPragma('PRAGMA synchronous = NORMAL');
    await runPragma('PRAGMA foreign_keys = ON');
    await runPragma('PRAGMA busy_timeout = 5000');

    // Users Table
    await run(`CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        username TEXT NOT NULL,
        email TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        avatar_id INTEGER DEFAULT 0,
        gender TEXT DEFAULT 'M',
        token_version INTEGER DEFAULT 0,
        created_at INTEGER
    )`);

    // Games Table
    await run(`CREATE TABLE IF NOT EXISTS games (
        id TEXT PRIMARY KEY,
        join_code TEXT,
        host_id TEXT,
        started_at INTEGER,
        ended_at INTEGER,
        winner_id TEXT
    )`);

    // Game Participants Table
    await run(`CREATE TABLE IF NOT EXISTS participants (
        game_id TEXT,
        user_id TEXT,
        player_id TEXT,
        joined_at INTEGER,
        PRIMARY KEY (game_id, user_id),
        FOREIGN KEY(game_id) REFERENCES games(id),
        FOREIGN KEY(user_id) REFERENCES users(id)
    )`);

    // Crowdsourced Monsters Catalog
    await run(`CREATE TABLE IF NOT EXISTS monsters(
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        level INTEGER NOT NULL,
        modifier INTEGER DEFAULT 0,
        treasures INTEGER DEFAULT 1,
        levels INTEGER DEFAULT 1,
        is_undead BOOLEAN DEFAULT 0,
        bad_stuff TEXT DEFAULT '',
        expansion TEXT DEFAULT 'base',
        created_by TEXT,
        created_at INTEGER
    )`);

    // Active Games Table (for persistence across restarts)
    await run(`CREATE TABLE IF NOT EXISTS active_games(
        id TEXT PRIMARY KEY,
        join_code TEXT UNIQUE NOT NULL,
        host_id TEXT NOT NULL,
        host_name TEXT NOT NULL,
        phase TEXT DEFAULT 'LOBBY',
        turn_player_id TEXT,
        players_json TEXT,
        player_order_json TEXT,
        combat_json TEXT,
        created_at INTEGER,
        last_activity_at INTEGER,
        seq INTEGER DEFAULT 0,
        turn_timer_seconds INTEGER DEFAULT 0,
        turn_ends_at INTEGER DEFAULT NULL
    )`);

    // Column migrations for pre-existing databases
    await runMigration(`ALTER TABLE monsters ADD COLUMN treasures INTEGER DEFAULT 1`, 'monsters.treasures');
    await runMigration(`ALTER TABLE monsters ADD COLUMN levels INTEGER DEFAULT 1`, 'monsters.levels');
    await runMigration(`ALTER TABLE monsters ADD COLUMN bad_stuff TEXT DEFAULT ''`, 'monsters.bad_stuff');
    await runMigration(`ALTER TABLE monsters ADD COLUMN expansion TEXT DEFAULT 'base'`, 'monsters.expansion');
    await runMigration(`ALTER TABLE users ADD COLUMN gender TEXT DEFAULT 'M'`, 'users.gender');
    await runMigration(`ALTER TABLE users ADD COLUMN token_version INTEGER DEFAULT 0`, 'users.token_version');

    await runMigration(`ALTER TABLE active_games ADD COLUMN max_level INTEGER DEFAULT 10`, 'active_games.max_level');
    await runMigration(`ALTER TABLE active_games ADD COLUMN winner_id TEXT DEFAULT NULL`, 'active_games.winner_id');
    await runMigration(`ALTER TABLE active_games ADD COLUMN original_host_id TEXT DEFAULT NULL`, 'active_games.original_host_id');
    await runMigration(`ALTER TABLE active_games ADD COLUMN player_order_json TEXT DEFAULT NULL`, 'active_games.player_order_json');
    await runMigration(`ALTER TABLE active_games ADD COLUMN turn_timer_seconds INTEGER DEFAULT 0`, 'active_games.turn_timer_seconds');
    await runMigration(`ALTER TABLE active_games ADD COLUMN turn_ends_at INTEGER DEFAULT NULL`, 'active_games.turn_ends_at');
    await runMigration(`ALTER TABLE active_games ADD COLUMN host_user_id TEXT DEFAULT NULL`, 'active_games.host_user_id');

    // Deduplicate existing monsters by (LOWER(name), level) keeping the minimum id
    await run(
        `DELETE FROM monsters
         WHERE id NOT IN (
             SELECT MIN(id) FROM monsters GROUP BY LOWER(name), level
         )`
    );
    await run(`CREATE UNIQUE INDEX IF NOT EXISTS idx_monsters_name_level ON monsters(name COLLATE NOCASE, level)`);

    // Performance indexes
    await run(`CREATE INDEX IF NOT EXISTS idx_participants_user ON participants(user_id)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_participants_game ON participants(game_id)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_participants_user_game ON participants(user_id, game_id)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_games_winner ON games(winner_id)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_games_ended_at ON games(ended_at DESC)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_monsters_name ON monsters(name)`);
    await run(`CREATE INDEX IF NOT EXISTS idx_active_games_activity ON active_games(last_activity_at)`);

    await run(`DROP INDEX IF EXISTS idx_users_username`);
    try {
        await run(
            `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_nocase
             ON users(username COLLATE NOCASE)`
        );
    } catch (err) {
        logger.error(
            "⚠️ Could not enforce unique usernames — existing duplicates must be " +
            "resolved manually. Login by username stays ambiguous until then:",
            err.message
        );
    }

    // Seed monsters if empty before building the FTS5 index and signaling ready
    const countRow = await get("SELECT count(*) as count FROM monsters");
    if (countRow && countRow.count === 0) {
        logger.info("🌱 Seeding monsters database...");
        const seedPath = path.join(__dirname, 'monsters_seed.sql');
        try {
            const rawSeed = fs.readFileSync(seedPath, 'utf8');
            const seedSql = rawSeed.replace(/INSERT\s+OR\s+REPLACE\s+INTO\s+monsters/gi, 'INSERT OR IGNORE INTO monsters');
            await execSql(seedSql);
            logger.info("✅ Monsters seeded successfully!");
        } catch (seedErr) {
            logger.error("❌ Error running seed:", seedErr);
        }
    } else {
        logger.info(`ℹ️ Monsters table has ${countRow ? countRow.count : 0} entries.`);
    }

    await initFts5Catalog();
    await run('SELECT 1');
    signalReady();
}

// ============== User Operations ==============

/**
 * bcrypt at cost 12 takes hundreds of milliseconds. The sync variants blocked
 * Node's single event loop for that whole time, so one login froze every active
 * game on the server. Always use the async callbacks.
 */
function hashPassword(password) {
    return new Promise((resolve, reject) => {
        bcrypt.hash(password, BCRYPT_COST, (err, hash) => {
            if (err) reject(err);
            else resolve(hash);
        });
    });
}

function comparePassword(password, hash) {
    return new Promise((resolve, reject) => {
        bcrypt.compare(password, hash, (err, isMatch) => {
            if (err) reject(err);
            else resolve(isMatch);
        });
    });
}

async function createUser(username, email, password, avatarId = 0, gender = 'M') {
    const hashedPassword = await hashPassword(password);
    return withWriteLock(() => new Promise((resolve, reject) => {
        const id = randomUUID();
        const now = Date.now();

        const sql = `INSERT INTO users(id, username, email, password_hash, avatar_id, gender, token_version, created_at)
                     VALUES(?, ?, ?, ?, ?, ?, 0, ?)`;
        const params = [id, username, email, hashedPassword, avatarId, gender, now];

        db.run(sql, params, function (err) {
            if (err) {
                if (err.message.includes("UNIQUE constraint failed: users.email")) {
                    reject(new Error("EMAIL_EXISTS"));
                } else if (err.message.includes("UNIQUE constraint failed: users.username")) {
                    reject(new Error("USERNAME_EXISTS"));
                } else {
                    reject(err);
                }
            } else {
                resolve({ id, username, email, avatarId, gender, token_version: 0 });
            }
        });
    }));
}

/**
 * Updates the mutable parts of an account. Avatar and gender are included because
 * they were previously write-once at registration — and registration hardcoded
 * avatar 0 — so every account displayed the same portrait forever.
 *
 * avatarId/gender are passed as null when unchanged; 0 is a valid avatar slot, so
 * they cannot be tested for truthiness.
 */
async function updateUser(userId, newUsername, newPassword, newAvatarId = null, newGender = null) {
    // Guard: nothing to update
    if (!newUsername && !newPassword && newAvatarId === null && newGender === null) {
        throw new Error("NO_CHANGES");
    }

    const assignments = [];
    const params = [];

    if (newUsername) {
        assignments.push("username = ?");
        params.push(newUsername);
    }

    if (newPassword) {
        assignments.push("password_hash = ?");
        params.push(await hashPassword(newPassword));
    }

    if (newAvatarId !== null) {
        assignments.push("avatar_id = ?");
        params.push(newAvatarId);
    }

    if (newGender !== null) {
        assignments.push("gender = ?");
        params.push(newGender);
    }

    const sql = `UPDATE users SET ${assignments.join(", ")} WHERE id = ?`;
    params.push(userId);

    const updated = await withWriteLock(() => new Promise((resolve, reject) => {
        db.run(sql, params, function (err) {
            if (err) {
                if (err.message.includes("UNIQUE constraint failed: users.username")) {
                    reject(new Error("USERNAME_EXISTS"));
                } else {
                    reject(err);
                }
                return;
            }
            // Fetch updated user
            db.get("SELECT * FROM users WHERE id = ?", [userId], (getErr, row) => {
                if (getErr) reject(getErr);
                else if (!row) reject(new Error("USER_NOT_FOUND"));
                else resolve(row);
            });
        });
    }));

    if (newUsername || newAvatarId !== null || newGender !== null) {
        leaderboardCache.clear();
    }
    return updated;
}

function incrementTokenVersion(userId) {
    return withWriteLock(async () => {
        await run(
            `UPDATE users SET token_version = COALESCE(token_version, 0) + 1 WHERE id = ?`,
            [userId]
        );
        const row = await get(`SELECT token_version FROM users WHERE id = ?`, [userId]);
        if (!row) throw new Error("USER_NOT_FOUND");
        return row.token_version || 0;
    });
}

function deleteAccount(userId) {
    return withWriteLock(async () => {
        await run('BEGIN IMMEDIATE TRANSACTION');
        try {
            await run(`UPDATE participants SET user_id = NULL WHERE user_id = ?`, [userId]);
            await run(`UPDATE games SET winner_id = NULL WHERE winner_id = ?`, [userId]);
            await run(`UPDATE active_games SET host_user_id = NULL WHERE host_user_id = ?`, [userId]);
            await run(`UPDATE monsters SET created_by = NULL WHERE created_by = ?`, [userId]);
            const result = await run(`DELETE FROM users WHERE id = ?`, [userId]);
            await run('COMMIT');
            leaderboardCache.clear();
            return (result.changes || 0) > 0;
        } catch (err) {
            logger.error("Error deleting account, rolling back:", err);
            await run('ROLLBACK').catch(rbErr => logger.error("Rollback failed in deleteAccount:", rbErr));
            throw err;
        }
    });
}

function findUserByEmailOrUsername(identifier) {
    return new Promise((resolve, reject) => {
        // The username side is compared NOCASE to match the unique index, so a
        // player who registered "SirPepo" can sign in typing "sirpepo". Email is
        // left exact: its UNIQUE constraint is case-sensitive, and loosening the
        // comparison without the index to match would reintroduce the same
        // ambiguity this is fixing.
        const sql = `SELECT * FROM users WHERE email = ? OR username = ? COLLATE NOCASE`;
        db.get(sql, [identifier, identifier], (err, row) => {
            if (err) {
                reject(err);
            } else {
                resolve(row);
            }
        });
    });
}

// A real bcrypt hash of a throwaway value, compared against when the account
// does not exist so "unknown user" and "wrong password" take the same time.
// Without it, the fast path was a timing oracle for enumerating accounts.
const DUMMY_HASH = '$2a$12$zz7NSBzWS6Co.9elJsnbyeD84HpwfaoSQpCWFNVsFvvPP89sY6Nwa';

async function verifyUser(identifier, password) {
    const user = await findUserByEmailOrUsername(identifier);
    if (!user) {
        await comparePassword(password, DUMMY_HASH).catch(() => false);
        return null; // User not found
    }

    const isValid = await comparePassword(password, user.password_hash);
    if (!isValid) {
        return null; // Invalid password
    }

    return {
        id: user.id,
        username: user.username,
        email: user.email,
        avatarId: user.avatar_id,
        gender: user.gender || 'M',
        token_version: user.token_version || 0
    };
}

function getUserById(userId) {
    return new Promise((resolve, reject) => {
        db.get('SELECT * FROM users WHERE id = ?', [userId], (err, row) => {
            if (err) reject(err);
            else resolve(row || null);
        });
    });
}

// ============== Catalog Operations ==============

function mapMonsterRow(row) {
    return {
        id: row.id,
        name: row.name,
        level: row.level,
        modifier: row.modifier || 0,
        treasures: row.treasures ?? 1,
        levels: row.levels ?? 1,
        isUndead: !!row.is_undead,
        badStuff: row.bad_stuff || '',
        expansion: row.expansion || 'base'
    };
}

function buildFtsMatchQuery(rawQuery) {
    const tokens = String(rawQuery || '')
        .trim()
        .split(/\s+/)
        .map(token => token.replace(/[^\p{L}\p{N}_-]/gu, ''))
        .filter(Boolean);
    if (tokens.length === 0) return null;
    return tokens.map(token => `"${token}"*`).join(' ');
}

/**
 * Searches the monster catalog via FTS5 prefix matching (or LIKE fallback).
 * @param {string} query
 * @param {number | { expansion?: string, limit?: number, offset?: number }} [limitOrOptions]
 */
async function searchMonsters(query, limitOrOptions = {}) {
    /** @type {{ expansion?: string, limit?: number, offset?: number }} */
    const opts = typeof limitOrOptions === 'number'
        ? { limit: limitOrOptions }
        : (limitOrOptions && typeof limitOrOptions === 'object' ? limitOrOptions : {});

    const rawLimit = Number(opts.limit);
    const limit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(50, Math.round(rawLimit))) : 20;
    const rawOffset = Number(opts.offset);
    const offset = Number.isFinite(rawOffset) ? Math.max(0, Math.round(rawOffset)) : 0;
    const expansion = typeof opts.expansion === 'string' && opts.expansion.trim()
        ? opts.expansion.trim().slice(0, 40)
        : null;

    const trimmedQuery = typeof query === 'string' ? query.trim().slice(0, 100) : '';
    const expansionClause = expansion ? `LOWER(COALESCE(expansion, 'base')) = LOWER(?)` : '';

    if (!trimmedQuery) {
        const whereSql = expansionClause ? `WHERE ${expansionClause}` : '';
        const params = expansion ? [expansion, limit, offset] : [limit, offset];
        const rows = await all(
            `SELECT id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion
             FROM monsters
             ${whereSql}
             ORDER BY name COLLATE NOCASE ASC, level ASC
             LIMIT ? OFFSET ?`,
            params
        );
        return rows.map(mapMonsterRow);
    }

    const ftsQuery = buildFtsMatchQuery(trimmedQuery);
    if (ftsQuery) {
        try {
            const ftsExpansion = expansion ? `AND LOWER(COALESCE(m.expansion, 'base')) = LOWER(?)` : '';
            const ftsParams = expansion ? [ftsQuery, expansion, limit, offset] : [ftsQuery, limit, offset];
            const rows = await all(
                `SELECT m.id, m.name, m.level, m.modifier, m.treasures, m.levels, m.is_undead, m.bad_stuff, m.expansion
                 FROM monsters_fts f
                 JOIN monsters m ON m.rowid = f.rowid
                 WHERE monsters_fts MATCH ?
                 ${ftsExpansion}
                 ORDER BY bm25(monsters_fts, 10.0, 2.0, 1.0), m.name COLLATE NOCASE ASC
                 LIMIT ? OFFSET ?`,
                ftsParams
            );
            return rows.map(mapMonsterRow);
        } catch (ftsErr) {
            logger.warn(`FTS5 monster search fallback (${trimmedQuery}): ${ftsErr.message}`);
        }
    }

    const escaped = trimmedQuery.replace(/[\\%_]/g, ch => `\\${ch}`);
    const likePattern = `%${escaped}%`;
    const likeExpansion = expansion ? `AND ${expansionClause}` : '';
    const likeParams = expansion
        ? [likePattern, likePattern, expansion, limit, offset]
        : [likePattern, likePattern, limit, offset];
    const rows = await all(
        `SELECT id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion
         FROM monsters
         WHERE (name LIKE ? ESCAPE '\\' OR bad_stuff LIKE ? ESCAPE '\\')
         ${likeExpansion}
         ORDER BY name COLLATE NOCASE ASC, level ASC
         LIMIT ? OFFSET ?`,
        likeParams
    );
    return rows.map(mapMonsterRow);
}

function addMonster(monster, userId) {
    return withWriteLock(async () => {
        const existing = await get(
            `SELECT id FROM monsters WHERE LOWER(name) = LOWER(?) AND level = ?`,
            [monster.name, monster.level]
        );
        if (existing) return existing.id;

        const id = randomUUID();
        const now = Date.now();
        const insertSql = `INSERT INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_by, created_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

        try {
            await run(insertSql, [
                id,
                monster.name,
                monster.level,
                monster.modifier ?? 0,
                monster.treasures ?? 1,
                monster.levels ?? 1,
                monster.isUndead ? 1 : 0,
                monster.badStuff || '',
                monster.expansion || 'base',
                userId || null,
                now
            ]);
            return id;
        } catch (err) {
            if (err && typeof err.message === 'string' && err.message.includes('UNIQUE constraint failed')) {
                const raceRow = await get(
                    `SELECT id FROM monsters WHERE LOWER(name) = LOWER(?) AND level = ?`,
                    [monster.name, monster.level]
                );
                if (raceRow) return raceRow.id;
            }
            throw err;
        }
    });
}

// ============== Game History Operations ==============

/**
 * Records a finished game plus its participants.
 */
function recordGame(gameId, winnerId, startTime, endTime, participants, joinCode = null, hostId = null) {
    return withWriteLock(async () => {
        await run("BEGIN IMMEDIATE TRANSACTION");
        try {
            await run(
                `INSERT OR REPLACE INTO games (id, join_code, host_id, started_at, ended_at, winner_id)
                 VALUES (?, ?, ?, ?, ?, ?)`,
                [gameId, joinCode, hostId, startTime, endTime, winnerId]
            );

            for (const p of (participants || [])) {
                const userId = p.userId && p.userId !== "anon" ? p.userId : null;
                await run(
                    `INSERT OR REPLACE INTO participants (game_id, user_id, player_id, joined_at)
                     VALUES (?, ?, ?, ?)`,
                    [gameId, userId, p.playerId, p.joinedAt || startTime]
                );
            }

            await run("COMMIT");
            leaderboardCache.clear();
            return true;
        } catch (err) {
            logger.error("Error recording game, rolling back:", err);
            await run("ROLLBACK").catch(() => { /* transaction already unwound */ });
            throw err;
        }
    });
}

/**
 * A player's own game history, scoped with a CTE before joining/counting.
 */
function getUserHistory(userId, limit = 20) {
    const rawLimit = Number(limit);
    const safeLimit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(100, Math.round(rawLimit))) : 20;
    return all(
        `WITH user_games AS (
             SELECT g.id, g.ended_at, g.winner_id
             FROM games g
             JOIN participants p ON g.id = p.game_id
             WHERE p.user_id = ?
             ORDER BY g.ended_at DESC
             LIMIT ?
         )
         SELECT
             ug.id,
             ug.ended_at,
             ug.winner_id,
             w.username AS winner_name,
             (ug.winner_id IS NOT NULL AND ug.winner_id = ?) AS did_i_win,
             (SELECT COUNT(*) FROM participants p2 WHERE p2.game_id = ug.id) AS player_count
         FROM user_games ug
         LEFT JOIN users w ON w.id = ug.winner_id
         ORDER BY ug.ended_at DESC`,
        [userId, safeLimit, userId]
    );
}

/**
 * Ranking of registered accounts by wins, cached briefly to absorb repeated reads.
 */
async function getLeaderboard(limit = 20) {
    const rawLimit = Number(limit);
    const safeLimit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(100, Math.round(rawLimit))) : 20;
    const cached = leaderboardCache.get(safeLimit);
    if (cached && (Date.now() - cached.ts) < LEADERBOARD_CACHE_TTL_MS) {
        return cached.rows;
    }

    const rows = await all(
        `SELECT
             u.id,
             u.username,
             u.avatar_id,
             u.gender,
             COUNT(DISTINCT p.game_id) AS games_played,
             COUNT(DISTINCT CASE WHEN g.winner_id = u.id THEN g.id END) AS wins
         FROM users u
         JOIN participants p ON p.user_id = u.id
         JOIN games g ON g.id = p.game_id
         GROUP BY u.id, u.username, u.avatar_id, u.gender
         ORDER BY wins DESC, games_played ASC, u.username ASC
         LIMIT ?`,
        [safeLimit]
    );
    leaderboardCache.set(safeLimit, { ts: Date.now(), rows });
    return rows;
}

/**
 * Aggregate stats for a single account, so a player can see their own totals even
 * when they fall outside the top of the ranking.
 */
function getUserStats(userId) {
    return new Promise((resolve, reject) => {
        const sql = `
            SELECT
                COUNT(DISTINCT p.game_id) AS games_played,
                COUNT(DISTINCT CASE WHEN g.winner_id = ? THEN g.id END) AS wins
            FROM participants p
            JOIN games g ON g.id = p.game_id
            WHERE p.user_id = ?
        `;
        db.get(sql, [userId, userId], (err, row) => {
            if (err) reject(err);
            else resolve(row || { games_played: 0, wins: 0 });
        });
    });
}

// ============== Active Game Persistence ==============

/**
 * Save or update an active game to the database.
 * Called whenever game state changes significantly.
 */
function saveActiveGame(game) {
    return withWriteLock(() => new Promise((resolve, reject) => {
        // Serialize players (without ws connections or client IPs)
        const playersData = {};
        for (const [playerId, player] of game.players) {
            playersData[playerId] = {
                name: player.name,
                avatarId: player.avatarId,
                gender: player.gender,
                userId: player.userId,
                reconnectTokenHash: player.reconnectTokenHash || null,
                level: player.level,
                gear: player.gear,
                treasures: player.treasures || 0,
                tempCombatBonus: player.tempCombatBonus || 0,
                characterClass: player.characterClass,
                characterRace: player.characterRace,
                secondaryClass: player.secondaryClass || 'NONE',
                secondaryRace: player.secondaryRace || 'HUMAN',
                hasHalfBreed: player.hasHalfBreed,
                hasSuperMunchkin: player.hasSuperMunchkin,
                lastRoll: player.lastRoll ?? null,
                reachedMaxLevelViaCombat: player.reachedMaxLevelViaCombat === true,
                isConnected: player.isConnected !== false,
                joinedAt: player.joinedAt
            };
        }

        const sql = `INSERT INTO active_games
            (id, join_code, host_id, host_name, phase, turn_player_id, players_json, player_order_json, combat_json,
             created_at, last_activity_at, seq, turn_timer_seconds, turn_ends_at, max_level, winner_id, original_host_id,
             host_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                join_code = excluded.join_code,
                host_id = excluded.host_id,
                host_name = excluded.host_name,
                phase = excluded.phase,
                turn_player_id = excluded.turn_player_id,
                players_json = excluded.players_json,
                player_order_json = excluded.player_order_json,
                combat_json = excluded.combat_json,
                last_activity_at = excluded.last_activity_at,
                seq = excluded.seq,
                turn_timer_seconds = excluded.turn_timer_seconds,
                turn_ends_at = excluded.turn_ends_at,
                max_level = excluded.max_level,
                winner_id = excluded.winner_id,
                original_host_id = excluded.original_host_id,
                host_user_id = excluded.host_user_id`;

        db.run(sql, [
            game.id,
            game.joinCode,
            game.hostId,
            game.hostName,
            game.phase || 'LOBBY',
            game.turnPlayerId || null,
            JSON.stringify(playersData),
            JSON.stringify(game.playerOrder || Array.from(game.players.keys())),
            game.combat ? JSON.stringify(game.combat) : null,
            game.createdAt,
            Date.now(),
            game.seq || 0,
            game.turnTimerSeconds || 0,
            game.turnEndsAt || null,
            game.maxLevel || 10,
            game.winnerId || null,
            game.originalHostId || game.hostId,
            game.hostUserId || null
        ], function (err) {
            if (err) {
                logger.error('❌ Error saving game:', err);
                reject(err);
            } else {
                resolve(true);
            }
        });
    }));
}

/**
 * Load all active games from the database.
 * Called on server startup.
 */
function loadActiveGames() {
    return new Promise((resolve, reject) => {
        // Only load games less than 24 hours old
        const maxAge = Date.now() - (24 * 60 * 60 * 1000);
        const sql = `SELECT * FROM active_games WHERE last_activity_at > ?`;

        db.all(sql, [maxAge], (err, rows) => {
            if (err) {
                logger.error('❌ Error loading games:', err);
                reject(err);
                return;
            }

            const games = rows.reduce((acc, row) => {
                let players, playerOrder, combat;
                try {
                    players = JSON.parse(row.players_json || '{}');
                } catch (e) {
                    logger.error(`❌ Corrupted players_json for game ${row.id}, skipping:`, e);
                    return acc;
                }
                try {
                    playerOrder = row.player_order_json
                        ? JSON.parse(row.player_order_json)
                        : Object.keys(players);
                } catch (e) {
                    logger.warn(`⚠️ Corrupted player_order_json for game ${row.id}, rebuilding order:`, e);
                    playerOrder = Object.keys(players);
                }
                try {
                    combat = row.combat_json ? JSON.parse(row.combat_json) : null;
                } catch (e) {
                    logger.warn(`⚠️ Corrupted combat_json for game ${row.id}, resetting combat:`, e);
                    combat = null;
                }
                acc.push({
                    id: row.id,
                    joinCode: row.join_code,
                    hostId: row.host_id,
                    originalHostId: row.original_host_id || row.host_id,
                    hostUserId: row.host_user_id || null,
                    hostName: row.host_name,
                    phase: row.phase,
                    turnPlayerId: row.turn_player_id,
                    players,
                    playerOrder,
                    combat,
                    createdAt: row.created_at,
                    lastActivityAt: row.last_activity_at,
                    seq: row.seq,
                    turnTimerSeconds: row.turn_timer_seconds || 0,
                    turnEndsAt: row.turn_ends_at || null,
                    maxLevel: row.max_level || 10,
                    winnerId: row.winner_id || null
                });
                return acc;
            }, []);

            logger.info(`📂 Loaded ${games.length} active games from database`);
            resolve(games);
        });
    });
}

/**
 * Delete an active game from the database.
 * Called when game ends or is cleaned up.
 */
function deleteActiveGame(gameId) {
    return withWriteLock(() => new Promise((resolve, reject) => {
        db.run(`DELETE FROM active_games WHERE id = ?`, [gameId], function (err) {
            if (err) {
                logger.error('❌ Error deleting game:', err);
                reject(err);
            } else {
                logger.info(`🗑️ Deleted game ${gameId} from database`);
                resolve(true);
            }
        });
    }));
}

/**
 * Clean up old games from the database.
 * Called periodically.
 */
function cleanupOldGames() {
    return withWriteLock(() => new Promise((resolve, reject) => {
        const maxAge = Date.now() - (24 * 60 * 60 * 1000); // 24 hours
        db.run(`DELETE FROM active_games WHERE last_activity_at < ?`, [maxAge], function (err) {
            if (err) {
                logger.error('❌ Error cleaning up games:', err);
                reject(err);
            } else {
                if (this.changes > 0) {
                    logger.info(`🧹 Cleaned up ${this.changes} old games`);
                }
                resolve(this.changes);
            }
        });
    }));
}

function pingDb() {
    return get('SELECT 1 AS ok').then(() => true);
}

async function closeDb() {
    await writeLockQueue.catch(() => {});
    return new Promise((resolve, reject) => {
        db.close((err) => {
            if (err) reject(err);
            else resolve();
        });
    });
}

module.exports = {
    db,
    ready,
    createUser,
    getUserById,
    findUserByEmailOrUsername,
    verifyUser,
    incrementTokenVersion,
    deleteAccount,
    searchMonsters,
    addMonster,
    recordGame,
    getUserHistory,
    getLeaderboard,
    getUserStats,
    updateUser,
    // Game persistence
    saveActiveGame,
    loadActiveGames,
    deleteActiveGame,
    cleanupOldGames,
    pingDb,
    closeDb
};
