package com.munchkin.app.data

import androidx.room.*
import com.munchkin.app.core.GameState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room Entity for persisting game state.
 */
@Entity(tableName = "saved_games")
data class SavedGameEntity(
    @PrimaryKey
    val gameId: String,
    val joinCode: String,
    val hostId: String,
    val myPlayerId: String,
    val isHost: Boolean,
    val stateJson: String,
    val savedAt: Long = System.currentTimeMillis()
)

/**
 * DAO for saved games.
 */
@Dao
interface SavedGameDao {
    @Query("SELECT * FROM saved_games ORDER BY savedAt DESC LIMIT 1")
    fun getLatestSavedGame(): Flow<SavedGameEntity?>
    
    @Query("SELECT * FROM saved_games WHERE gameId = :gameId")
    suspend fun getByGameId(gameId: String): SavedGameEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(game: SavedGameEntity)
    
    @Query("DELETE FROM saved_games WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
    
    @Query("DELETE FROM saved_games")
    suspend fun deleteAll()
}

/**
 * Type converter for GameState serialization.
 */
class GameStateConverter {
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    fun fromGameState(state: GameState): String {
        return json.encodeToString(state)
    }
    
    fun toGameState(jsonString: String): GameState? {
        return try {
            json.decodeFromString<GameState>(jsonString)
        } catch (e: Exception) {
            null
        }
    }
}
