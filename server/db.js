const sqlite3 = require('sqlite3').verbose();
const bcrypt = require('bcryptjs');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const logger = require('./logger');

// Resolved against this file, not the process CWD: starting the server from a
// different directory used to silently create a second, empty database.
const DB_SOURCE = process.env.MUNCHKIN_DB_PATH || path.join(__dirname, 'munchkin.db');

const BCRYPT_COST = 12;

/**
 * Resolves once the schema exists and migrations have run. Callers that touch
 * tables at startup must await this — the old code slept for a second and hoped,
 * which failed outright on a fresh database.
 */
let signalReady;
let signalReadyFailed;
const ready = new Promise((resolve, reject) => {
    signalReady = resolve;
    signalReadyFailed = reject;
});

const db = new sqlite3.Database(DB_SOURCE, (err) => {
    if (err) {
        logger.error("❌ Error opening database", err.message);
        signalReadyFailed(err);
        return;
    }
    logger.info(`📂 Connected to SQLite database at ${DB_SOURCE}`);
    initTables();
});

function initTables() {
    db.serialize(() => {
        // WAL lets reads proceed during writes; busy_timeout stops concurrent
        // writers from failing outright with SQLITE_BUSY. foreign_keys is off by
        // default in SQLite, which made the FK clauses below decorative.
        db.run(`PRAGMA journal_mode = WAL`);
        db.run(`PRAGMA busy_timeout = 5000`);
        db.run(`PRAGMA foreign_keys = ON`);

        // Users Table
        db.run(`CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            avatar_id INTEGER DEFAULT 0,
            created_at INTEGER
        )`);

        // Games Table
        db.run(`CREATE TABLE IF NOT EXISTS games (
            id TEXT PRIMARY KEY,
            join_code TEXT,
            host_id TEXT,
            started_at INTEGER,
            ended_at INTEGER,
            winner_id TEXT
        )`);

        // Game Participants Table
        db.run(`CREATE TABLE IF NOT EXISTS participants (
            game_id TEXT,
            user_id TEXT,
            player_id TEXT,
            joined_at INTEGER,
            PRIMARY KEY (game_id, user_id),
            FOREIGN KEY(game_id) REFERENCES games(id),
            FOREIGN KEY(user_id) REFERENCES users(id)
        )`);

        // Crowdsourced Monsters Catalog
        db.run(`CREATE TABLE IF NOT EXISTS monsters(
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            level INTEGER NOT NULL,
            modifier INTEGER DEFAULT 0,
            treasures INTEGER DEFAULT 1,
            levels INTEGER DEFAULT 1,
            is_undead BOOLEAN DEFAULT 0,
            created_by TEXT,
            created_at INTEGER
        )`);

        // Active Games Table (for persistence across restarts)
        db.run(`CREATE TABLE IF NOT EXISTS active_games(
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

        // Ensure columns exist (for existing databases)
        db.run(`ALTER TABLE monsters ADD COLUMN treasures INTEGER DEFAULT 1`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                // Ignore duplicate
            }
        });
        db.run(`ALTER TABLE monsters ADD COLUMN levels INTEGER DEFAULT 1`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                // Ignore duplicate
            }
        });
        db.run(`ALTER TABLE monsters ADD COLUMN bad_stuff TEXT DEFAULT ''`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                // Ignore duplicate
            }
        });
        db.run(`ALTER TABLE monsters ADD COLUMN expansion TEXT DEFAULT 'base'`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                // Ignore duplicate
            }
        });

        // The account carried an avatar slot but no gender, so nothing could pick the
        // correct portrait variant for it (the ranking had to fall back to a letter).
        db.run(`ALTER TABLE users ADD COLUMN gender TEXT DEFAULT 'M'`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (users.gender):", err);
            }
        });

        // active_games column migrations
        db.run(`ALTER TABLE active_games ADD COLUMN max_level INTEGER DEFAULT 10`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (max_level):", err);
            }
        });
        db.run(`ALTER TABLE active_games ADD COLUMN winner_id TEXT DEFAULT NULL`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (winner_id):", err);
            }
        });
        db.run(`ALTER TABLE active_games ADD COLUMN original_host_id TEXT DEFAULT NULL`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (original_host_id):", err);
            }
        });
        db.run(`ALTER TABLE active_games ADD COLUMN player_order_json TEXT DEFAULT NULL`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (player_order_json):", err);
            }
        });
        db.run(`ALTER TABLE active_games ADD COLUMN turn_timer_seconds INTEGER DEFAULT 0`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (turn_timer_seconds):", err);
            }
        });
        db.run(`ALTER TABLE active_games ADD COLUMN turn_ends_at INTEGER DEFAULT NULL`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (turn_ends_at):", err);
            }
        });
        // GET_HOSTED_GAMES / DELETE_HOSTED_GAME authorize against game.hostUserId,
        // which was never persisted: after a restart every restored room had it
        // undefined, so the owner's own games vanished from their menu and could
        // no longer be deleted from there.
        db.run(`ALTER TABLE active_games ADD COLUMN host_user_id TEXT DEFAULT NULL`, (err) => {
            if (err && !err.message.includes("duplicate column name")) {
                logger.error("Migration error (host_user_id):", err);
            }
        });

        // Performance indexes
        db.run(`CREATE INDEX IF NOT EXISTS idx_participants_user ON participants(user_id)`);
        // The leaderboard joins participants -> games on game_id.
        db.run(`CREATE INDEX IF NOT EXISTS idx_participants_game ON participants(game_id)`);
        db.run(`CREATE INDEX IF NOT EXISTS idx_games_winner ON games(winner_id)`);
        db.run(`CREATE INDEX IF NOT EXISTS idx_monsters_name ON monsters(name)`);
        db.run(`CREATE INDEX IF NOT EXISTS idx_active_games_activity ON active_games(last_activity_at)`);

        // Login accepts either email or username, so a duplicate username makes
        // "which account did I just log into?" ambiguous. Enforce uniqueness going
        // forward; if existing rows already collide the index creation fails and we
        // surface it rather than silently leaving the ambiguity in place.
        //
        // NOCASE, and matched NOCASE in findUserByEmailOrUsername: a
        // case-sensitive index would still let "sirpepo" sit next to "SirPepo",
        // which on a shared leaderboard is impersonation, and would leave a name
        // that can be registered but never logged in with.
        db.run(`DROP INDEX IF EXISTS idx_users_username`); // case-sensitive predecessor
        db.run(
            `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_nocase
             ON users(username COLLATE NOCASE)`,
            (err) => {
                if (err) {
                    logger.error(
                        "⚠️ Could not enforce unique usernames — existing duplicates must be " +
                        "resolved manually. Login by username stays ambiguous until then:",
                        err.message
                    );
                }
            }
        );

        // Seed Monsters if empty
        db.get("SELECT count(*) as count FROM monsters", [], (err, row) => {
            if (err) {
                logger.error("❌ Error checking monsters count", err);
                return;
            }
            if (row && row.count === 0) {
                logger.info("🌱 Seeding monsters database...");
                const fs = require('fs');
                const path = require('path');
                const seedPath = path.join(__dirname, 'monsters_seed.sql');

                try {
                    const seedSql = fs.readFileSync(seedPath, 'utf8');
                    db.exec(seedSql, (err) => {
                        if (err) {
                            logger.error("❌ Error running seed", err);
                        } else {
                            logger.info("✅ Monsters seeded successfully!");
                        }
                    });
                } catch (e) {
                    logger.error("❌ Error reading seed file", e);
                }
            } else {
                logger.info(`ℹ️ Monsters table has ${row ? row.count : 0} entries.`);
            }
        });

        // Queued last inside serialize(), so this runs only after every statement
        // above has been applied. Seeding is deliberately not awaited: it is not
        // needed to serve traffic.
        db.run(`SELECT 1`, (err) => {
            if (err) signalReadyFailed(err);
            else signalReady();
        });
    });
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

function createUser(username, email, password, avatarId = 0, gender = 'M') {
    return hashPassword(password).then(hashedPassword => new Promise((resolve, reject) => {
        const id = uuidv4();
        const now = Date.now();

        const sql = `INSERT INTO users(id, username, email, password_hash, avatar_id, gender, created_at)
                     VALUES(?, ?, ?, ?, ?, ?, ?)`;
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
                resolve({ id, username, email, avatarId, gender });
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

    return new Promise((resolve, reject) => {
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
        gender: user.gender || 'M'
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

function searchMonsters(query) {
    return new Promise((resolve, reject) => {
        // Escape LIKE metacharacters so a query containing % or _ searches for
        // those literal characters instead of acting as a wildcard.
        const escaped = String(query || '').replace(/[\\%_]/g, ch => `\\${ch}`);
        const sql = `SELECT * FROM monsters WHERE name LIKE ? ESCAPE '\\' ORDER BY name LIMIT 20`;
        db.all(sql, [`%${escaped}%`], (err, rows) => {
            if (err) reject(err);
            else resolve(rows.map(row => ({
                id: row.id,
                name: row.name,
                level: row.level,
                modifier: row.modifier || 0,
                treasures: row.treasures || 1,
                levels: row.levels || 1,
                isUndead: !!row.is_undead,
                badStuff: row.bad_stuff || '',
                expansion: row.expansion || 'base',
                createdBy: row.created_by
            })));
        });
    });
}

function addMonster(monster, userId) {
    return new Promise((resolve, reject) => {
        const checkSql = `SELECT id FROM monsters WHERE name = ? AND level = ?`;
        db.get(checkSql, [monster.name, monster.level], (err, row) => {
            if (err) return reject(err);
            if (row) return resolve(row.id); // Already exists

            const id = uuidv4();
            const now = Date.now();
            const insertSql = `INSERT INTO monsters (id, name, level, modifier, treasures, levels, is_undead, bad_stuff, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

            db.run(insertSql, [
                id,
                monster.name,
                monster.level,
                monster.modifier || 0,
                monster.treasures || 1,
                monster.levels || 1,
                monster.isUndead ? 1 : 0,
                monster.badStuff || '',
                userId,
                now
            ], function (err) {
                if (err) reject(err);
                else resolve(id);
            });
        });
    });
}

// ============== Game History Operations ==============

function run(sql, params = []) {
    return new Promise((resolve, reject) => {
        db.run(sql, params, function (err) {
            if (err) reject(err);
            else resolve(this);
        });
    });
}

/**
 * Records a finished game plus its participants.
 *
 * The previous implementation issued BEGIN/ROLLBACK/COMMIT from inside nested
 * sqlite3 callbacks, so the COMMIT stayed queued even after a ROLLBACK, and two
 * concurrent calls could nest BEGIN and fail with "cannot start a transaction
 * within a transaction". Awaiting each step keeps the transaction well-formed.
 *
 * `joinCode` and `hostId` are recorded properly instead of the old "HISTORY" /
 * "unknown" placeholders. Guests (no userId) are stored with a NULL user_id so
 * the player_count in history reflects everyone who actually played.
 */
async function recordGame(gameId, winnerId, startTime, endTime, participants, joinCode = null, hostId = null) {
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
        return true;
    } catch (err) {
        logger.error("Error recording game, rolling back:", err);
        await run("ROLLBACK").catch(() => { /* transaction already unwound */ });
        throw err;
    }
}

/**
 * A player's own game history.
 *
 * Resolves the winner to a username here rather than shipping a raw id: the
 * client had no way to turn a user id into a name, so the history screen showed
 * the literal word "Jugador" for every finished game.
 */
function getUserHistory(userId) {
    return new Promise((resolve, reject) => {
        const sql = `
            SELECT
                g.id,
                g.ended_at,
                g.winner_id,
                w.username AS winner_name,
                (g.winner_id IS NOT NULL AND g.winner_id = ?) AS did_i_win,
                (SELECT COUNT(*) FROM participants WHERE game_id = g.id) as player_count
            FROM games g
            JOIN participants p ON g.id = p.game_id
            LEFT JOIN users w ON w.id = g.winner_id
            WHERE p.user_id = ?
            ORDER BY g.ended_at DESC
            LIMIT 50
        `;
        db.all(sql, [userId, userId], (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
        });
    });

}

/**
 * Ranking of registered accounts by wins, with games played so the client can
 * show a win rate.
 *
 * Joins through `participants`, not `games.winner_id`, for two reasons:
 *  - It counts games played, which counting winners alone cannot do.
 *  - It includes accounts that have played but never won. The previous query
 *    joined on winner_id, so a player did not exist in the ranking until their
 *    first victory.
 *
 * Guests are excluded for free: their participant rows carry a NULL user_id, so
 * they never match a user. Only completed games reach `games` at all, which is
 * the intended definition of a "played" game here — abandoned rooms count for
 * nobody.
 *
 * Ordering is wins first, then fewer games played, which for an equal number of
 * wins is the same as a higher win rate without comparing floats.
 */
function getLeaderboard(limit = 20) {
    return new Promise((resolve, reject) => {
        const sql = `
            SELECT
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
            LIMIT ?
        `;
        db.all(sql, [limit], (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
        });
    });
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
    return new Promise((resolve, reject) => {
        // Serialize players (without ws connections)
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
                characterClass: player.characterClass,
                characterRace: player.characterRace,
                secondaryClass: player.secondaryClass || 'NONE',
                secondaryRace: player.secondaryRace || 'HUMAN',
                hasHalfBreed: player.hasHalfBreed,
                hasSuperMunchkin: player.hasSuperMunchkin,
                reachedMaxLevelViaCombat: player.reachedMaxLevelViaCombat === true,
                isConnected: player.isConnected !== false,
                joinedAt: player.joinedAt
            };
        }

        const sql = `INSERT OR REPLACE INTO active_games
            (id, join_code, host_id, host_name, phase, turn_player_id, players_json, player_order_json, combat_json,
             created_at, last_activity_at, seq, turn_timer_seconds, turn_ends_at, max_level, winner_id, original_host_id,
             host_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

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
    });
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
    return new Promise((resolve, reject) => {
        db.run(`DELETE FROM active_games WHERE id = ?`, [gameId], function (err) {
            if (err) {
                logger.error('❌ Error deleting game:', err);
                reject(err);
            } else {
                logger.info(`🗑️ Deleted game ${gameId} from database`);
                resolve(true);
            }
        });
    });
}

/**
 * Clean up old games from the database.
 * Called periodically.
 */
function cleanupOldGames() {
    return new Promise((resolve, reject) => {
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
    });
}

module.exports = {
    db,
    ready,
    createUser,
    getUserById,
    findUserByEmail: findUserByEmailOrUsername,
    findUserByEmailOrUsername,
    verifyUser,
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
    cleanupOldGames
};
