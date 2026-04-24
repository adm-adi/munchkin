package com.munchkin.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager

class SqliteImportToolTest {

    @Test
    fun `imports legacy users monsters and completed game history`() {
        val dbFile = Files.createTempFile("munchkin-legacy", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$dbFile").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE users (
                            id TEXT PRIMARY KEY,
                            username TEXT NOT NULL,
                            email TEXT UNIQUE NOT NULL,
                            password_hash TEXT NOT NULL,
                            avatar_id INTEGER DEFAULT 0,
                            created_at INTEGER
                        )
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE monsters (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            level INTEGER NOT NULL,
                            modifier INTEGER DEFAULT 0,
                            treasures INTEGER DEFAULT 1,
                            levels INTEGER DEFAULT 1,
                            is_undead BOOLEAN DEFAULT 0,
                            created_by TEXT,
                            created_at INTEGER,
                            bad_stuff TEXT DEFAULT '',
                            expansion TEXT DEFAULT 'base'
                        )
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE games (
                            id TEXT PRIMARY KEY,
                            join_code TEXT,
                            host_id TEXT,
                            started_at INTEGER,
                            ended_at INTEGER,
                            winner_id TEXT
                        )
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE participants (
                            game_id TEXT,
                            user_id TEXT,
                            player_id TEXT,
                            joined_at INTEGER,
                            PRIMARY KEY (game_id, user_id)
                        )
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO users(id, username, email, password_hash, avatar_id, created_at)
                        VALUES ('user-1', 'Alice', 'alice@example.com', '${'$'}2a${'$'}12${'$'}hash', 3, 100)
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO monsters(id, name, level, modifier, treasures, levels, is_undead, created_by, created_at, bad_stuff, expansion)
                        VALUES ('monster-1', 'Undead Test', 8, 1, 2, 1, 1, 'user-1', 110, 'Run', 'base')
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO games(id, join_code, host_id, started_at, ended_at, winner_id)
                        VALUES ('game-1', 'ABC123', 'user-1', 120, 180, 'user-1')
                        """.trimIndent()
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO participants(game_id, user_id, player_id, joined_at)
                        VALUES ('game-1', 'user-1', 'player-1', 120)
                        """.trimIndent()
                    )
                }
            }

            val persistence = InMemoryPersistence()
            SqliteImportTool.import(dbFile.toString(), persistence)

            val user = persistence.findUserById("user-1")
            assertNotNull(user)
            assertEquals("Alice", user?.profile?.username)
            assertEquals(3, user?.profile?.avatarId)

            val monsters = persistence.searchMonsters("undead")
            assertEquals(1, monsters.size)
            assertEquals("monster-1", monsters.first().id)
            assertEquals(true, monsters.first().isUndead)

            val history = persistence.getUserHistory("user-1")
            assertEquals(1, history.size)
            assertEquals("game-1", history.first().id)
            assertEquals("user-1", history.first().winnerId)

            val leaderboard = persistence.getLeaderboard()
            assertEquals(1, leaderboard.size)
            assertEquals(1, leaderboard.first().wins)
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }
}
