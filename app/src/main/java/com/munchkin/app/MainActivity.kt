package com.munchkin.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.ui.components.DebugLogViewer
import com.munchkin.app.ui.screens.*
import com.munchkin.app.ui.theme.MunchkinTheme
import com.munchkin.app.viewmodel.GameViewModel
import com.munchkin.app.viewmodel.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Keep screen on during gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            val viewModel: GameViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            
            MunchkinTheme {
                // Intercept system back button
                BackHandler(enabled = uiState.screen != Screen.HOME) {
                    viewModel.goBack()
                }
                
                Box(modifier = Modifier.fillMaxSize()) {
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
                                val savedGame by viewModel.savedGame.collectAsState()
                                val updateInfo by viewModel.updateInfo.collectAsState()
                                val isDownloading by viewModel.isDownloading.collectAsState()
                                HomeScreen(
                                    savedGame = savedGame,
                                    updateInfo = updateInfo,
                                    isDownloading = isDownloading,
                                    onCreateGame = { viewModel.navigateTo(Screen.CREATE_GAME) },
                                    onJoinGame = { viewModel.navigateTo(Screen.JOIN_GAME) },
                                    onResumeGame = { viewModel.resumeSavedGame() },
                                    onDeleteSavedGame = { viewModel.deleteSavedGame() },
                                    onDownloadUpdate = { viewModel.downloadUpdate() },
                                    onDismissUpdate = { viewModel.dismissUpdate() },
                                    onSettings = { viewModel.navigateTo(Screen.SETTINGS) },
                                    onAuth = { viewModel.navigateTo(Screen.AUTH) },
                                    onLogout = { viewModel.logout() },
                                    onLeaderboardClick = { 
                                        viewModel.loadLeaderboard() // Load data
                                        viewModel.navigateTo(Screen.LEADERBOARD) 
                                    },
                                    userProfile = uiState.userProfile
                                )
                            }
                            
                            Screen.CREATE_GAME -> {
                                CreateGameScreen(
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    userProfile = uiState.userProfile,
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
                                    discoveredGames = uiState.discoveredGames,
                                    isDiscovering = uiState.isDiscovering,
                                    userProfile = uiState.userProfile,
                                    onJoinGame = { wsUrl, joinCode, name, avatarId, gender ->
                                        viewModel.joinGame(wsUrl, joinCode, name, avatarId, gender)
                                    },
                                    onJoinDiscoveredGame = { game, name, avatarId, gender ->
                                        viewModel.joinDiscoveredGame(game, name, avatarId, gender)
                                    },
                                    onStartDiscovery = { viewModel.startDiscovery() },
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
                                        pendingWinnerId = uiState.pendingWinnerId,
                                        onPlayerClick = { viewModel.navigateTo(Screen.PLAYER_DETAIL) },
                                        onCombatClick = { viewModel.navigateTo(Screen.COMBAT) },
                                        onCatalogClick = { viewModel.navigateTo(Screen.CATALOG) },
                                        onSettingsClick = { viewModel.navigateTo(Screen.SETTINGS) },
                                        onLeaveGame = { viewModel.leaveGame() },
                                        onConfirmWin = { viewModel.confirmWin(it) },
                                        onDismissWin = { viewModel.dismissWinConfirmation() },
                                        onEndTurn = { viewModel.endTurn() }
                                    )
                                }
                            }
                            
                            Screen.PLAYER_DETAIL -> {
                                val myPlayer = uiState.myPlayer
                                
                                if (myPlayer != null) {
                                    PlayerDetailScreen(
                                        player = myPlayer,
                                        onIncrementLevel = { viewModel.incrementLevel() },
                                        onDecrementLevel = { viewModel.decrementLevel() },
                                        onIncrementGear = { viewModel.incrementGear() },
                                        onDecrementGear = { viewModel.decrementGear() },
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
                                        monsterSearchResults = uiState.monsterSearchResults,
                                        onStartCombat = { viewModel.startCombat() },
                                        onAddMonster = { name, level, mod, isUndead -> 
                                            viewModel.addMonster(name, level, mod, isUndead) 
                                        },
                                        onSearchMonsters = { query -> viewModel.searchMonsters(query) },
                                        onRequestCreateGlobalMonster = { name, level, mod, isUndead ->
                                            viewModel.requestCreateGlobalMonster(name, level, mod, isUndead)
                                        },
                                        onEndCombat = { 
                                            viewModel.endCombat()
                                            viewModel.goBack()
                                        },
                                        onBack = { viewModel.goBack() }
                                    )
                                }
                            }
                            
                            Screen.CATALOG -> {
                                // Placeholder CatalogScreen
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    androidx.compose.foundation.layout.Column(
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                    ) {
                                        Text("Catálogo Global - Próximamente", style = MaterialTheme.typography.headlineSmall)
                                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                                        androidx.compose.material3.Button(onClick = { viewModel.goBack() }) {
                                            Text("Volver")
                                        }
                                    }
                                }
                            }
                            
                            Screen.SETTINGS -> {
                                SettingsScreen(
                                    isCheckingUpdate = uiState.isCheckingUpdate,
                                    onBack = { viewModel.navigateTo(Screen.HOME) },
                                    onCheckUpdate = { viewModel.forceCheckUpdate() }
                                )
                            }
                            
                            Screen.AUTH -> {
                                AuthScreen(
                                    onLogin = { email, pass -> viewModel.login(email, pass) },
                                    onRegister = { user, email, pass -> viewModel.register(user, email, pass) },
                                    onBack = { viewModel.navigateTo(Screen.HOME) },
                                    isLoading = uiState.isLoading,
                                    error = uiState.error
                                )
                            }

                            Screen.PROFILE -> {
                                uiState.userProfile?.let { user ->
                                    ProfileScreen(
                                        userProfile = user,
                                        gameHistory = uiState.gameHistory,
                                        isLoading = uiState.isLoading,
                                        onBack = { viewModel.navigateTo(Screen.HOME) },
                                        onRefresh = { viewModel.loadHistory() }
                                    )
                                }
                            }

                            Screen.LEADERBOARD -> {
                                LeaderboardScreen(
                                    leaderboard = uiState.leaderboard,
                                    isLoading = uiState.isLoading,
                                    onBack = { viewModel.navigateTo(Screen.HOME) },
                                    onRefresh = { viewModel.loadLeaderboard() }
                                )
                            }
                        }
                    }
                    
                    // Debug log viewer with floating button
                    DebugLogViewer()
                }
            }
        }
    }
}
}
