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
        db.run(`CREATE TABLE IF NOT EXISTS game_participants (
            game_id TEXT,
            user_id TEXT,
            final_level INTEGER,
            is_winner BOOLEAN,
            FOREIGN KEY(game_id) REFERENCES games(id),
            FOREIGN KEY(user_id) REFERENCES users(id)
        )`);
    });
}

// ============== User Operations ==============

function createUser(username, email, password, avatarId = 0) {
    return new Promise((resolve, reject) => {
        const hashedPassword = bcrypt.hashSync(password, 8);
        const id = uuidv4();
        const now = Date.now();

        const sql = `INSERT INTO users (id, username, email, password_hash, avatar_id, created_at) VALUES (?, ?, ?, ?, ?, ?)`;
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

function findUserByEmail(email) {
    return new Promise((resolve, reject) => {
        const sql = `SELECT * FROM users WHERE email = ?`;
        db.get(sql, [email], (err, row) => {
            if (err) {
                reject(err);
            } else {
                resolve(row);
            }
        });
    });
}

function verifyUser(email, password) {
    return new Promise((resolve, reject) => {
        findUserByEmail(email)
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

module.exports = {
    db,
    createUser,
    findUserByEmail,
    verifyUser
};
