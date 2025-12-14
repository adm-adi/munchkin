package com.munchkin.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.ui.screens.*
import com.munchkin.app.ui.theme.MunchkinTheme
import com.munchkin.app.viewmodel.GameViewModel
import com.munchkin.app.viewmodel.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val viewModel: GameViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            
            // Keep screen on when in game
            DisposableEffect(uiState.screen) {
                if (uiState.screen == Screen.BOARD || uiState.screen == Screen.COMBAT) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            
            MunchkinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnimatedContent(
                        targetState = uiState.screen,
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                slideInHorizontally { it } + fadeIn() togetherWith 
                                slideOutHorizontally { -it } + fadeOut()
                            } else {
                                slideInHorizontally { -it } + fadeIn() togetherWith 
                                slideOutHorizontally { it } + fadeOut()
                            }
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.HOME -> {
                                HomeScreen(
                                    onCreateGame = { viewModel.navigateTo(Screen.CREATE_GAME) },
                                    onJoinGame = { viewModel.navigateTo(Screen.JOIN_GAME) },
                                    onSettings = { viewModel.navigateTo(Screen.SETTINGS) }
                                )
                            }
                            
                            Screen.CREATE_GAME -> {
                                CreateGameScreen(
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    onCreateGame = { name, avatarId, gender ->
                                        viewModel.createGame(name, avatarId, gender)
                                    },
                                    onBack = { viewModel.goBack() }
                                )
                            }
                            
                            Screen.JOIN_GAME -> {
                                JoinGameScreen(
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    onJoinGame = { wsUrl, joinCode, name, avatarId, gender ->
                                        viewModel.joinGame(wsUrl, joinCode, name, avatarId, gender)
                                    },
                                    onBack = { viewModel.goBack() }
                                )
                            }
                            
                            Screen.LOBBY -> {
                                val gameState = uiState.gameState
                                val myPlayerId = uiState.myPlayerId
                                val connectionInfo = uiState.connectionInfo
                                
                                if (gameState != null && myPlayerId != null) {
                                    LobbyScreen(
                                        gameState = gameState,
                                        myPlayerId = myPlayerId,
                                        isHost = uiState.isHost,
                                        connectionInfo = connectionInfo,
                                        onStartGame = { viewModel.startGame() },
                                        onLeaveGame = { viewModel.leaveGame() }
                                    )
                                }
                            }
                            
                            Screen.BOARD -> {
                                val gameState = uiState.gameState
                                val myPlayerId = uiState.myPlayerId
                                
                                if (gameState != null && myPlayerId != null) {
                                    BoardScreen(
                                        gameState = gameState,
                                        myPlayerId = myPlayerId,
                                        isHost = uiState.isHost,
                                        connectionState = uiState.connectionState,
                                        onPlayerClick = { viewModel.navigateTo(Screen.PLAYER_DETAIL) },
                                        onCombatClick = { viewModel.navigateTo(Screen.COMBAT) },
                                        onCatalogClick = { viewModel.navigateTo(Screen.CATALOG) },
                                        onSettingsClick = { viewModel.navigateTo(Screen.SETTINGS) },
                                        onLeaveGame = { viewModel.leaveGame() }
                                    )
                                }
                            }
                            
                            Screen.PLAYER_DETAIL -> {
                                val gameState = uiState.gameState
                                val myPlayer = uiState.myPlayer
                                
                                if (gameState != null && myPlayer != null) {
                                    PlayerDetailScreen(
                                        player = myPlayer,
                                        gameState = gameState,
                                        onIncrementLevel = { viewModel.incrementLevel() },
                                        onDecrementLevel = { viewModel.decrementLevel() },
                                        onIncrementGear = { viewModel.incrementGear() },
                                        onDecrementGear = { viewModel.decrementGear() },
                                        onSetHalfBreed = { viewModel.setHalfBreed(it) },
                                        onSetSuperMunchkin = { viewModel.setSuperMunchkin(it) },
                                        onAddRace = { viewModel.addRace(it) },
                                        onRemoveRace = { viewModel.removeRace(it) },
                                        onAddClass = { viewModel.addClass(it) },
                                        onRemoveClass = { viewModel.removeClass(it) },
                                        onAddRaceToCatalog = { viewModel.addRaceToCatalog(it) },
                                        onAddClassToCatalog = { viewModel.addClassToCatalog(it) },
                                        onBack = { viewModel.goBack() }
                                    )
                                }
                            }
                            
                            Screen.COMBAT -> {
                                val gameState = uiState.gameState
                                val myPlayerId = uiState.myPlayerId
                                
                                if (gameState != null && myPlayerId != null) {
                                    CombatScreen(
                                        gameState = gameState,
                                        myPlayerId = myPlayerId,
                                        onStartCombat = { viewModel.startCombat() },
                                        onAddMonster = { name, level, mod -> 
                                            viewModel.addMonster(name, level, mod) 
                                        },
                                        onEndCombat = { outcome, levels -> 
                                            viewModel.endCombat(outcome, levels)
                                            viewModel.goBack()
                                        },
                                        onBack = { viewModel.goBack() }
                                    )
                                }
                            }
                            
                            Screen.CATALOG -> {
                                // TODO: Implement CatalogScreen
                                HomeScreen(
                                    onCreateGame = { },
                                    onJoinGame = { },
                                    onSettings = { viewModel.goBack() }
                                )
                            }
                            
                            Screen.SETTINGS -> {
                                // TODO: Implement SettingsScreen
                                HomeScreen(
                                    onCreateGame = { },
                                    onJoinGame = { },
                                    onSettings = { viewModel.goBack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
