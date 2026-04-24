package com.munchkin.backend

import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.LeaderboardEntry
import com.munchkin.app.network.UserProfile
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

data class UserAuthRecord(
    val profile: UserProfile,
    val passwordHash: String
)

data class RecordedParticipant(
    val userId: String?,
    val playerId: String,
    val username: String,
    val avatarId: Int
)

data class RecordedGame(
    val id: String,
    val joinCode: String,
    val winnerUserId: String?,
    val startedAt: Long,
    val endedAt: Long,
    val participants: List<RecordedParticipant>
)

interface BackendPersistence {
    fun createUser(username: String, email: String, passwordHash: String, avatarId: Int): UserProfile
    fun importUser(user: UserProfile, passwordHash: String, createdAt: Long)
    fun findUserByIdentifier(identifier: String): UserAuthRecord?
    fun findUserById(userId: String): UserAuthRecord?
    fun updateUser(userId: String, username: String?, passwordHash: String?): UserProfile?
    fun searchMonsters(query: String): List<CatalogMonster>
    fun addMonster(monster: CatalogMonster, createdBy: String?): CatalogMonster
    fun recordGame(game: RecordedGame)
    fun getUserHistory(userId: String): List<GameHistoryItem>
    fun getLeaderboard(): List<LeaderboardEntry>
}

class PostgresPersistence private constructor(
    private val dataSource: DataSource
) : BackendPersistence {

    override fun createUser(username: String, email: String, passwordHash: String, avatarId: Int): UserProfile {
        val id = UUID.randomUUID().toString()
        val sql = """
            INSERT INTO users (id, username, email, password_hash, avatar_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.setString(2, username)
                statement.setString(3, email)
                statement.setString(4, passwordHash)
                statement.setInt(5, avatarId)
                statement.setLong(6, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }

        return UserProfile(id = id, username = username, email = email, avatarId = avatarId)
    }

    override fun importUser(user: UserProfile, passwordHash: String, createdAt: Long) {
        val sql = """
            INSERT INTO users (id, username, email, password_hash, avatar_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                username = EXCLUDED.username,
                email = EXCLUDED.email,
                password_hash = EXCLUDED.password_hash,
                avatar_id = EXCLUDED.avatar_id
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, user.id)
                statement.setString(2, user.username)
                statement.setString(3, user.email)
                statement.setString(4, passwordHash)
                statement.setInt(5, user.avatarId)
                statement.setLong(6, createdAt)
                statement.executeUpdate()
            }
        }
    }

    override fun findUserByIdentifier(identifier: String): UserAuthRecord? {
        val sql = """
            SELECT id, username, email, avatar_id, password_hash
            FROM users
            WHERE lower(email) = lower(?) OR lower(username) = lower(?)
            LIMIT 1
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, identifier)
                statement.setString(2, identifier)
                statement.executeQuery().use(::mapUser)
            }
        }
    }

    override fun findUserById(userId: String): UserAuthRecord? {
        val sql = """
            SELECT id, username, email, avatar_id, password_hash
            FROM users
            WHERE id = ?
            LIMIT 1
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use(::mapUser)
            }
        }
    }

    override fun updateUser(userId: String, username: String?, passwordHash: String?): UserProfile? {
        if (username == null && passwordHash == null) {
            return findUserById(userId)?.profile
        }

        val updates = mutableListOf<String>()
        val values = mutableListOf<Any>()
        if (username != null) {
            updates += "username = ?"
            values += username
        }
        if (passwordHash != null) {
            updates += "password_hash = ?"
            values += passwordHash
        }

        val sql = "UPDATE users SET ${updates.joinToString(", ")} WHERE id = ?"

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value ->
                    statement.setObject(index + 1, value)
                }
                statement.setString(values.size + 1, userId)
                statement.executeUpdate()
            }
        }

        return findUserById(userId)?.profile
    }

    override fun searchMonsters(query: String): List<CatalogMonster> {
        val normalizedQuery = query.trim()
        val sql = """
            SELECT id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_by
            FROM monsters
            WHERE name ILIKE ?
            ORDER BY level ASC, name ASC
            LIMIT 50
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, "%$normalizedQuery%")
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                CatalogMonster(
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
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun addMonster(monster: CatalogMonster, createdBy: String?): CatalogMonster {
        val id = if (monster.id.isBlank()) UUID.randomUUID().toString() else monster.id
        val sql = """
            INSERT INTO monsters (
                id, name, level, modifier, treasures, levels, is_undead, bad_stuff, expansion, created_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                level = EXCLUDED.level,
                modifier = EXCLUDED.modifier,
                treasures = EXCLUDED.treasures,
                levels = EXCLUDED.levels,
                is_undead = EXCLUDED.is_undead,
                bad_stuff = EXCLUDED.bad_stuff,
                expansion = EXCLUDED.expansion,
                created_by = EXCLUDED.created_by
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.setString(2, monster.name)
                statement.setInt(3, monster.level)
                statement.setInt(4, monster.modifier)
                statement.setInt(5, monster.treasures)
                statement.setInt(6, monster.levels)
                statement.setBoolean(7, monster.isUndead)
                statement.setString(8, monster.badStuff)
                statement.setString(9, monster.expansion)
                statement.setString(10, createdBy)
                statement.setLong(11, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }

        return monster.copy(id = id, createdBy = createdBy)
    }

    override fun recordGame(game: RecordedGame) {
        val gameSql = """
            INSERT INTO game_history (id, join_code, winner_user_id, started_at, ended_at, player_count, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
        """.trimIndent()
        val participantSql = """
            INSERT INTO game_participants (game_id, user_id, player_id, username_snapshot, avatar_id_snapshot)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (game_id, player_id) DO NOTHING
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(gameSql).use { statement ->
                statement.setString(1, game.id)
                statement.setString(2, game.joinCode)
                statement.setString(3, game.winnerUserId)
                statement.setLong(4, game.startedAt)
                statement.setLong(5, game.endedAt)
                statement.setInt(6, game.participants.size)
                statement.setLong(7, System.currentTimeMillis())
                statement.executeUpdate()
            }
            connection.prepareStatement(participantSql).use { statement ->
                game.participants.forEach { participant ->
                    statement.setString(1, game.id)
                    statement.setString(2, participant.userId)
                    statement.setString(3, participant.playerId)
                    statement.setString(4, participant.username)
                    statement.setInt(5, participant.avatarId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
            connection.autoCommit = true
        }
    }

    override fun getUserHistory(userId: String): List<GameHistoryItem> {
        val sql = """
            SELECT gh.id, gh.ended_at, gh.winner_user_id, gh.player_count
            FROM game_history gh
            INNER JOIN game_participants gp ON gp.game_id = gh.id
            WHERE gp.user_id = ?
            ORDER BY gh.ended_at DESC
            LIMIT 100
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                GameHistoryItem(
                                    id = resultSet.getString("id"),
                                    endedAt = resultSet.getLong("ended_at"),
                                    winnerId = resultSet.getString("winner_user_id"),
                                    playerCount = resultSet.getInt("player_count")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun getLeaderboard(): List<LeaderboardEntry> {
        val sql = """
            SELECT u.id, u.username, u.avatar_id, COUNT(gh.id) AS wins
            FROM users u
            LEFT JOIN game_history gh ON gh.winner_user_id = u.id
            GROUP BY u.id, u.username, u.avatar_id
            ORDER BY wins DESC, u.username ASC
            LIMIT 100
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                LeaderboardEntry(
                                    id = resultSet.getString("id"),
                                    username = resultSet.getString("username"),
                                    avatarId = resultSet.getInt("avatar_id"),
                                    wins = resultSet.getInt("wins")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun mapUser(resultSet: ResultSet): UserAuthRecord? {
        return if (resultSet.next()) {
            UserAuthRecord(
                profile = UserProfile(
                    id = resultSet.getString("id"),
                    username = resultSet.getString("username"),
                    email = resultSet.getString("email"),
                    avatarId = resultSet.getInt("avatar_id")
                ),
                passwordHash = resultSet.getString("password_hash")
            )
        } else {
            null
        }
    }

    companion object {
        fun connect(config: BackendConfig): PostgresPersistence {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 10
                isAutoCommit = true
            }

            val dataSource = HikariDataSource(hikariConfig)
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            return PostgresPersistence(dataSource)
        }
    }
}

class InMemoryPersistence : BackendPersistence {
    private val users = linkedMapOf<String, UserAuthRecord>()
    private val monsters = linkedMapOf<String, CatalogMonster>()
    private val games = mutableListOf<RecordedGame>()

    override fun createUser(username: String, email: String, passwordHash: String, avatarId: Int): UserProfile {
        if (users.values.any { it.profile.email.equals(email, ignoreCase = true) }) {
            throw IllegalArgumentException("EMAIL_EXISTS")
        }

        val user = UserAuthRecord(
            profile = UserProfile(
                id = UUID.randomUUID().toString(),
                username = username,
                email = email,
                avatarId = avatarId
            ),
            passwordHash = passwordHash
        )
        users[user.profile.id] = user
        return user.profile
    }

    override fun importUser(user: UserProfile, passwordHash: String, createdAt: Long) {
        users[user.id] = UserAuthRecord(
            profile = user,
            passwordHash = passwordHash
        )
    }

    override fun findUserByIdentifier(identifier: String): UserAuthRecord? {
        return users.values.firstOrNull {
            it.profile.email.equals(identifier, ignoreCase = true) ||
                it.profile.username.equals(identifier, ignoreCase = true)
        }
    }

    override fun findUserById(userId: String): UserAuthRecord? = users[userId]

    override fun updateUser(userId: String, username: String?, passwordHash: String?): UserProfile? {
        val existing = users[userId] ?: return null
        val updated = existing.copy(
            profile = existing.profile.copy(username = username ?: existing.profile.username),
            passwordHash = passwordHash ?: existing.passwordHash
        )
        users[userId] = updated
        return updated.profile
    }

    override fun searchMonsters(query: String): List<CatalogMonster> {
        return monsters.values.filter { it.name.contains(query, ignoreCase = true) }
    }

    override fun addMonster(monster: CatalogMonster, createdBy: String?): CatalogMonster {
        val id = if (monster.id.isBlank()) UUID.randomUUID().toString() else monster.id
        val saved = monster.copy(id = id, createdBy = createdBy)
        monsters[saved.id] = saved
        return saved
    }

    override fun recordGame(game: RecordedGame) {
        if (games.none { it.id == game.id }) {
            games += game
        }
    }

    override fun getUserHistory(userId: String): List<GameHistoryItem> {
        return games
            .filter { game -> game.participants.any { it.userId == userId } }
            .sortedByDescending { it.endedAt }
            .map {
                GameHistoryItem(
                    id = it.id,
                    endedAt = it.endedAt,
                    winnerId = it.winnerUserId,
                    playerCount = it.participants.size
                )
            }
    }

    override fun getLeaderboard(): List<LeaderboardEntry> {
        return users.values.map { user ->
            LeaderboardEntry(
                id = user.profile.id,
                username = user.profile.username,
                avatarId = user.profile.avatarId,
                wins = games.count { it.winnerUserId == user.profile.id }
            )
        }.sortedByDescending { it.wins }
    }
}
