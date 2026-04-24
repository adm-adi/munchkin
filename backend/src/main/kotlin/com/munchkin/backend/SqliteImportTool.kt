package com.munchkin.backend

import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.UserProfile
import java.sql.DriverManager
import java.sql.ResultSet

object SqliteImportTool {
    fun import(sqlitePath: String, persistence: BackendPersistence) {
        val sqliteUrl = "jdbc:sqlite:$sqlitePath"
        DriverManager.getConnection(sqliteUrl).use { connection ->
            connection.prepareStatement(
                """
                SELECT id, username, email, password_hash, avatar_id, created_at
                FROM users
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    importUsers(resultSet, persistence)
                }
            }

            connection.prepareStatement(
                """
                SELECT id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_by
                FROM monsters
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        persistence.addMonster(
                            monster = CatalogMonster(
                                id = resultSet.getString("id"),
                                name = resultSet.getString("name"),
                                level = resultSet.getInt("level"),
                                modifier = resultSet.getInt("modifier"),
                                treasures = resultSet.getInt("treasures"),
                                levels = resultSet.getInt("levels"),
                                isUndead = resultSet.getBoolean("is_undead"),
                                badStuff = resultSet.getString("bad_stuff") ?: "",
                                expansion = resultSet.getString("expansion") ?: "base",
                                createdBy = resultSet.getString("created_by")
                            ),
                            createdBy = resultSet.getString("created_by")
                        )
                    }
                }
            }

            val participantSql = """
                SELECT p.game_id, p.user_id, p.player_id, u.username, u.avatar_id
                FROM participants p
                LEFT JOIN users u ON u.id = p.user_id
                ORDER BY p.game_id
            """.trimIndent()
            val participantsByGame = connection.prepareStatement(participantSql).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildMap<String, MutableList<RecordedParticipant>> {
                        while (resultSet.next()) {
                            val gameId = resultSet.getString("game_id") ?: continue
                            getOrPut(gameId) { mutableListOf() }.add(
                                RecordedParticipant(
                                    userId = resultSet.getString("user_id"),
                                    playerId = resultSet.getString("player_id") ?: resultSet.getString("user_id").orEmpty(),
                                    username = resultSet.getString("username") ?: "Player",
                                    avatarId = resultSet.getInt("avatar_id")
                                )
                            )
                        }
                    }
                }
            }

            connection.prepareStatement(
                """
                SELECT id, join_code, winner_id, started_at, ended_at
                FROM games
                WHERE ended_at IS NOT NULL
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val gameId = resultSet.getString("id") ?: continue
                        val winnerId = resultSet.getString("winner_id")?.takeUnless {
                            it.isBlank() || it == "aborted" || it == "unknown"
                        }
                        persistence.recordGame(
                            RecordedGame(
                                id = gameId,
                                joinCode = resultSet.getString("join_code") ?: "HISTORY",
                                winnerUserId = winnerId,
                                startedAt = resultSet.getLong("started_at"),
                                endedAt = resultSet.getLong("ended_at"),
                                participants = participantsByGame[gameId].orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun importUsers(resultSet: ResultSet, persistence: BackendPersistence) {
        while (resultSet.next()) {
            val id = resultSet.getString("id") ?: continue
            val email = resultSet.getString("email") ?: continue
            persistence.importUser(
                user = UserProfile(
                    id = id,
                    username = resultSet.getString("username") ?: email.substringBefore("@"),
                    email = email,
                    avatarId = resultSet.getInt("avatar_id")
                ),
                passwordHash = resultSet.getString("password_hash") ?: continue,
                createdAt = resultSet.getLong("created_at")
            )
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: SqliteImportTool <legacy-sqlite-path>"
        }
        val config = BackendConfig.fromEnvironment()
        val persistence = PostgresPersistence.connect(config)
        import(args[0], persistence)
    }
}
