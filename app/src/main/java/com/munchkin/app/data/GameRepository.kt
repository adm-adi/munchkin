package com.munchkin.app.data

import android.content.Context
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing saved games.
 */
class GameRepository(context: Context) {
    private val dao = MunchkinDatabase.getInstance(context).savedGameDao()
    private val converter = GameStateConverter()
    
    /**
     * Get the latest saved game as a Flow.
     */
    fun getLatestSavedGame(): Flow<SavedGame?> {
        return dao.getLatestSavedGame().map { entity ->
            entity?.let { toSavedGame(it) }
        }
    }
    
    /**
     * Save current game state.
     */
    suspend fun saveGame(
        gameState: GameState,
        myPlayerId: PlayerId,
        isHost: Boolean
    ) {
        val entity = SavedGameEntity(
            gameId = gameState.gameId.value,
            joinCode = gameState.joinCode,
            hostId = gameState.hostId.value,
            myPlayerId = myPlayerId.value,
            isHost = isHost,
            stateJson = converter.fromGameState(gameState)
        )
        dao.save(entity)
    }
    
    /**
     * Delete a saved game.
     */
    suspend fun deleteSavedGame(gameId: String) {
        dao.delete(gameId)
    }
    
    /**
     * Delete all saved games.
     */
    suspend fun deleteAllSavedGames() {
        dao.deleteAll()
    }
    
    private fun toSavedGame(entity: SavedGameEntity): SavedGame? {
        val state = converter.toGameState(entity.stateJson) ?: return null
        return SavedGame(
            gameState = state,
            myPlayerId = PlayerId(entity.myPlayerId),
            isHost = entity.isHost,
            savedAt = entity.savedAt
        )
    }
}

/**
 * Data class representing a saved game.
 */
data class SavedGame(
    val gameState: GameState,
    val myPlayerId: PlayerId,
    val isHost: Boolean,
    val savedAt: Long
)
