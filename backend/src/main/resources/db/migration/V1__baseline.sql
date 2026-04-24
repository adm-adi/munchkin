CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    avatar_id INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS monsters (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    level INTEGER NOT NULL,
    modifier INTEGER NOT NULL DEFAULT 0,
    treasures INTEGER NOT NULL DEFAULT 1,
    levels INTEGER NOT NULL DEFAULT 1,
    is_undead BOOLEAN NOT NULL DEFAULT FALSE,
    bad_stuff TEXT NOT NULL DEFAULT '',
    expansion TEXT NOT NULL DEFAULT 'base',
    created_by TEXT,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS game_history (
    id TEXT PRIMARY KEY,
    join_code TEXT NOT NULL,
    winner_user_id TEXT,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NOT NULL,
    player_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS game_participants (
    game_id TEXT NOT NULL,
    user_id TEXT,
    player_id TEXT NOT NULL,
    username_snapshot TEXT NOT NULL,
    avatar_id_snapshot INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (game_id, player_id),
    FOREIGN KEY (game_id) REFERENCES game_history(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_monsters_name ON monsters(name);
CREATE INDEX IF NOT EXISTS idx_game_history_winner ON game_history(winner_user_id);
CREATE INDEX IF NOT EXISTS idx_game_participants_user ON game_participants(user_id);
