package com.munchkin.app.viewmodel

import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.data.SavedGame
import com.munchkin.app.di.GameSessionCoordinator
import com.munchkin.app.di.StringResolver
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.network.ServerErrorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

/**
 * Shared connection/persistence routines used by LobbyViewModel and GameSessionViewModel
 * to avoid duplicating session setup, token storage, and error handling.
 */
internal object GameConnectionSupport {

    suspend fun resumeSavedGameSession(
        sessionStore: SessionStore,
        savedGameStore: SavedGameStore,
        remoteGateway: RemoteGateway,
        sessionCoordinator: GameSessionCoordinator,
        stringResolver: StringResolver,
        explicitSavedGame: SavedGame? = null
    ): Result<GameState> {
        val saved = explicitSavedGame
            ?: sessionCoordinator.savedGame.value
            ?: savedGameStore.getLatestSavedGame().firstOrNull()
            ?: return Result.failure(IllegalStateException(stringResolver.get(R.string.error_not_found)))

        val user = sessionCoordinator.userProfile.value
        val existingPlayer = saved.gameState.players[saved.myPlayerId]
        val playerMeta = PlayerMeta(
            playerId = saved.myPlayerId,
            name = existingPlayer?.name ?: user?.username ?: stringResolver.get(R.string.default_player_name),
            avatarId = existingPlayer?.avatarId ?: user?.avatarId ?: 0,
            gender = existingPlayer?.gender ?: user?.gender ?: Gender.M,
            userId = user?.id
        )

        return connectAndAttach(
            joinCode = saved.gameState.joinCode,
            playerMeta = playerMeta,
            fallbackPlayerId = saved.myPlayerId,
            sessionStore = sessionStore,
            savedGameStore = savedGameStore,
            remoteGateway = remoteGateway,
            sessionCoordinator = sessionCoordinator,
            clearSavedOnTerminalError = true
        )
    }

    suspend fun connectAndAttach(
        joinCode: String,
        playerMeta: PlayerMeta,
        fallbackPlayerId: PlayerId,
        sessionStore: SessionStore,
        savedGameStore: SavedGameStore,
        remoteGateway: RemoteGateway,
        sessionCoordinator: GameSessionCoordinator,
        clearSavedOnTerminalError: Boolean = false
    ): Result<GameState> {
        sessionCoordinator.clearActiveSession(closeClient = true)
        val reconnectToken = sessionStore.getReconnectToken(joinCode)
        val authToken = sessionStore.getAuthToken()

        return try {
            val connectResult = remoteGateway.connectSessionConnection(
                wsUrl = ServerConfig.WS_URL,
                joinCode = joinCode,
                playerMeta = playerMeta,
                reconnectToken = reconnectToken,
                authToken = authToken
            )

            connectResult.fold(
                onSuccess = { (session, initialState) ->
                    persistAndActivateSession(
                        session = session,
                        initialState = initialState,
                        fallbackPlayerId = fallbackPlayerId,
                        sessionStore = sessionStore,
                        savedGameStore = savedGameStore,
                        sessionCoordinator = sessionCoordinator
                    )
                    Result.success(initialState)
                },
                onFailure = { error ->
                    if (clearSavedOnTerminalError && shouldPurgeSavedGame(error)) {
                        savedGameStore.deleteAllSavedGames()
                        sessionCoordinator.setSavedGame(null)
                    }
                    Result.failure(error)
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun persistAndActivateSession(
        session: SessionConnection,
        initialState: GameState,
        fallbackPlayerId: PlayerId,
        sessionStore: SessionStore,
        savedGameStore: SavedGameStore,
        sessionCoordinator: GameSessionCoordinator
    ) {
        val actualPlayerId = session.myPlayerId.value ?: fallbackPlayerId
        val isHost = initialState.hostId == actualPlayerId
        sessionStore.savePlayerId(initialState.joinCode, actualPlayerId.value)
        session.reconnectToken.value?.let { token ->
            sessionStore.saveReconnectToken(initialState.joinCode, token)
        }
        savedGameStore.saveGame(initialState, actualPlayerId, isHost)
        sessionCoordinator.setSavedGame(
            SavedGame(
                gameState = initialState,
                myPlayerId = actualPlayerId,
                isHost = isHost,
                savedAt = System.currentTimeMillis()
            )
        )
        sessionCoordinator.attachActiveSession(session, initialState, actualPlayerId)
    }

    private fun shouldPurgeSavedGame(error: Throwable): Boolean {
        return error is ServerErrorException && error.code in setOf(
            ErrorCode.GAME_NOT_FOUND,
            ErrorCode.GAME_FINISHED,
            ErrorCode.UNAUTHORIZED
        )
    }
}
