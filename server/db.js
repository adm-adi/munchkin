const sqlite3 = require('sqlite3').verbose();
const bcrypt = require('bcryptjs');
const { v4: uuidv4 } = require('uuid');

const DB_SOURCE = "munchkin.db";

const db = new sqlite3.Database(DB_SOURCE, (err) => {
    if (err) {
        console.error("❌ Error opening database", err.message);
        throw err;
    } else {
        console.log("📂 Connected to SQLite database.");
        initTables();
    }
});

function initTables() {
    db.serialize(() => {
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
            combat_json TEXT,
            created_at INTEGER,
            last_activity_at INTEGER,
            seq INTEGER DEFAULT 0
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

        // Seed Monsters if empty
        db.get("SELECT count(*) as count FROM monsters", [], (err, row) => {
            if (err) {
                console.error("❌ Error checking monsters count", err);
                return;
            }
            if (row && row.count === 0) {
                console.log("🌱 Seeding monsters database...");
                const fs = require('fs');
                const path = require('path');
                const seedPath = path.join(__dirname, 'monsters_seed.sql');

                try {
                    const seedSql = fs.readFileSync(seedPath, 'utf8');
                    db.exec(seedSql, (err) => {
                        if (err) {
                            console.error("❌ Error running seed", err);
                        } else {
                            console.log("✅ Monsters seeded successfully!");
                        }
                    });
                } catch (e) {
                    console.error("❌ Error reading seed file", e);
                }
            } else {
                console.log(`ℹ️ Monsters table has ${row ? row.count : 0} entries.`);
            }
        });
    });
}

// ============== User Operations ==============

function createUser(username, email, password, avatarId = 0) {
    return new Promise((resolve, reject) => {
        const hashedPassword = bcrypt.hashSync(password, 8);
        const id = uuidv4();
        const now = Date.now();

        const sql = `INSERT INTO users(id, username, email, password_hash, avatar_id, created_at) VALUES(?, ?, ?, ?, ?, ?)`;
        const params = [id, username, email, hashedPassword, avatarId, now];

        db.run(sql, params, function (err) {
            if (err) {
                if (err.message.includes("UNIQUE constraint failed: users.email")) {
                    reject(new Error("EMAIL_EXISTS"));
                } else {
                    reject(err);
                }
            } else {
                resolve({ id, username, email, avatarId });
            }
        });
    });
}

function updateUser(userId, newUsername, newPassword) {
    return new Promise((resolve, reject) => {
        let sql = "UPDATE users SET ";
        let params = [];

        if (newUsername) {
            sql += "username = ?, ";
            params.push(newUsername);
        }

        if (newPassword) {
            const hashedPassword = bcrypt.hashSync(newPassword, 8);
            sql += "password_hash = ?, ";
            params.push(hashedPassword);
        }

        // Remove last comma
        sql = sql.slice(0, -2);

        sql += " WHERE id = ?";
        params.push(userId);

        db.run(sql, params, function (err) {
            if (err) {
                reject(err);
            } else {
                // Fetch updated user
                db.get("SELECT * FROM users WHERE id = ?", [userId], (err, row) => {
                    if (err) {
                        reject(err);
                    } else {
                        resolve(row);
                    }
                });
            }
        });
    });
}

function findUserByEmailOrUsername(identifier) {
    return new Promise((resolve, reject) => {
        // Search by email OR username
        const sql = `SELECT * FROM users WHERE email = ? OR username = ?`;
        db.get(sql, [identifier, identifier], (err, row) => {
            if (err) {
                reject(err);
            } else {
                resolve(row);
            }
        });
    });
}

function verifyUser(identifier, password) {
    return new Promise((resolve, reject) => {
        findUserByEmailOrUsername(identifier)
            .then(user => {
                if (!user) {
                    resolve(null); // User not found
                    return;
                }
                const isValid = bcrypt.compareSync(password, user.password_hash);
                if (isValid) {
                    resolve({
                        id: user.id,
                        username: user.username,
                        email: user.email,
                        avatarId: user.avatar_id
                    });
                } else {
                    resolve(null); // Invalid password
                }
            })
            .catch(reject);
    });
}

// ============== Catalog Operations ==============

function searchMonsters(query) {
    return new Promise((resolve, reject) => {
        const sql = `SELECT * FROM monsters WHERE name LIKE ? ORDER BY name LIMIT 20`;
        db.all(sql, [`%${query}%`], (err, rows) => {
            if (err) resolve([]);
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
            const insertSql = `INSERT INTO monsters (id, name, level, modifier, treasures, levels, is_undead, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`;

            db.run(insertSql, [
                id,
                monster.name,
                monster.level,
                monster.modifier || 0,
                monster.treasures || 1,
                monster.levels || 1,
                monster.isUndead ? 1 : 0,
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

function recordGame(gameId, winnerId, startTime, endTime, participants) {
    return new Promise((resolve, reject) => {
        db.serialize(() => {
            db.run("BEGIN TRANSACTION");

            // 1. Record Game
            const gameSql = `INSERT INTO games (id, join_code, host_id, started_at, ended_at, winner_id) VALUES (?, ?, ?, ?, ?, ?)`;
            // gameId from server might be UUID, ensure it matches
            db.run(gameSql, [gameId, "HISTORY", "unknown", startTime, endTime, winnerId], function (err) {
                if (err) {
                    console.error("Error inserting game:", err);
                    db.run("ROLLBACK");
                    return reject(err);
                }
            });

            // 2. Record Participants
            const partSql = `INSERT INTO participants (game_id, user_id, player_id, joined_at) VALUES (?, ?, ?, ?)`;
            const stmt = db.prepare(partSql);

            participants.forEach(p => {
                // If user is logged in, use their real user_id. If guest, maybe store player_id as reference or null.
                // We prefer linking to registered users.
                // Assuming p.userId is passed if available, otherwise maybe just skip or store null?
                // The schema has user_id foreign key, but it might be nullable?
                // If the user was anonymous, we can't link history to them easily in this schema unless we allow nulls.
                // Let's assume we filter for registered users or handle it.
                // For now, only record if we have a valid user_id (registered user).
                if (p.userId && p.userId !== "anon") {
                    stmt.run([gameId, p.userId, p.playerId, startTime]);
                }
            });

            stmt.finalize(() => {
                db.run("COMMIT", (err) => {
                    if (err) reject(err);
                    else resolve(true);
                });
            });
        });
    });
}

function getUserHistory(userId) {
    return new Promise((resolve, reject) => {
        const sql = `
            SELECT 
                g.id, g.ended_at, g.winner_id,
                (SELECT COUNT(*) FROM participants WHERE game_id = g.id) as player_count
            FROM games g
            JOIN participants p ON g.id = p.game_id
            WHERE p.user_id = ?
            ORDER BY g.ended_at DESC
            LIMIT 50
        `;
        db.all(sql, [userId], (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
        });
    });

}

function getLeaderboard() {
    return new Promise((resolve, reject) => {
        const sql = `
            SELECT 
                u.id, u.username, u.avatar_id,
                COUNT(g.id) as wins
            FROM users u
            JOIN games g ON g.winner_id = u.id
            GROUP BY u.id
            ORDER BY wins DESC
            LIMIT 20
        `;
        db.all(sql, [], (err, rows) => {
            if (err) reject(err);
            else resolve(rows);
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
                level: player.level,
                gear: player.gear,
                characterClass: player.characterClass,
                characterRace: player.characterRace,
                hasHalfBreed: player.hasHalfBreed,
                hasSuperMunchkin: player.hasSuperMunchkin,
                isConnected: player.isConnected !== false,
                joinedAt: player.joinedAt
            };
        }

        const sql = `INSERT OR REPLACE INTO active_games 
            (id, join_code, host_id, host_name, phase, turn_player_id, players_json, combat_json, created_at, last_activity_at, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

        db.run(sql, [
            game.id,
            game.joinCode,
            game.hostId,
            game.hostName,
            game.phase || 'LOBBY',
            game.turnPlayerId || null,
            JSON.stringify(playersData),
            game.combat ? JSON.stringify(game.combat) : null,
            game.createdAt,
            Date.now(),
            game.seq || 0
        ], function (err) {
            if (err) {
                console.error('❌ Error saving game:', err);
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
                console.error('❌ Error loading games:', err);
                reject(err);
                return;
            }

            const games = rows.map(row => ({
                id: row.id,
                joinCode: row.join_code,
                hostId: row.host_id,
                hostName: row.host_name,
                phase: row.phase,
                turnPlayerId: row.turn_player_id,
                players: JSON.parse(row.players_json || '{}'),
                combat: row.combat_json ? JSON.parse(row.combat_json) : null,
                createdAt: row.created_at,
                lastActivityAt: row.last_activity_at,
                seq: row.seq
            }));

            console.log(`📂 Loaded ${games.length} active games from database`);
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
                console.error('❌ Error deleting game:', err);
                reject(err);
            } else {
                console.log(`🗑️ Deleted game ${gameId} from database`);
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
                console.error('❌ Error cleaning up games:', err);
                reject(err);
            } else {
                if (this.changes > 0) {
                    console.log(`🧹 Cleaned up ${this.changes} old games`);
                }
                resolve(this.changes);
            }
        });
    });
}

module.exports = {
    db,
    createUser,
    findUserByEmail: findUserByEmailOrUsername,
    findUserByEmailOrUsername,
    verifyUser,
    searchMonsters,
    addMonster,
    recordGame,
    getUserHistory,
    getLeaderboard,
    updateUser,
    // Game persistence
    saveActiveGame,
    loadActiveGames,
    deleteActiveGame,
    cleanupOldGames
};
