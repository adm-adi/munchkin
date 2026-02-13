package com.munchkin.app

import android.os.Bundle
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import com.munchkin.app.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.core.DiceRollPurpose
import com.munchkin.app.ui.components.DebugLogViewer
import com.munchkin.app.ui.screens.*
import com.munchkin.app.ui.theme.MunchkinTheme
import com.munchkin.app.viewmodel.GameViewModel
import com.munchkin.app.viewmodel.Screen

class MainActivity : ComponentActivity() {
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.munchkin.app.util.LocaleManager.wrapContext(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Keep screen on during gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            val viewModel: GameViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            
            MunchkinTheme {
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                val scope = rememberCoroutineScope()
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            viewModel.checkReconnection()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Tutorial check
                var showTutorial by remember { 
                    mutableStateOf(!TutorialPrefs.isShown(this@MainActivity)) 
                }
                
                // SoundManager auto-initializes via object access
                 
                
                if (showTutorial) {
                    TutorialScreen(
                        onFinish = {
                            TutorialPrefs.markShown(this@MainActivity)
                            showTutorial = false
                        }
                    )
                } else {
                    // Intercept system back button
                    BackHandler(enabled = uiState.screen != Screen.HOME) {
                        viewModel.goBack()
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            // Global Sound Effects
                            val currentState = uiState.gameState
                            val currentPlayer = uiState.myPlayerId
                            if (currentState != null && currentPlayer != null) {
                                com.munchkin.app.ui.components.EventEffects(currentState, currentPlayer)
                            }

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
                                val hostedGames by viewModel.hostedGames.collectAsState()
                                
                                HomeScreen(
                                    savedGame = savedGame,
                                    updateInfo = updateInfo,
                                    isDownloading = isDownloading,
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    hostedGames = hostedGames,
                                    onCreateGame = { viewModel.navigateTo(Screen.CREATE_GAME) },
                                    onJoinGame = { viewModel.navigateTo(Screen.JOIN_GAME) },
                                    onResumeGame = { viewModel.resumeSavedGame() },
                                    onDeleteSavedGame = { viewModel.deleteSavedGame() },
                                    onDeleteHostedGame = { viewModel.deleteHostedGame(it) },
                                    onClearError = { viewModel.clearError() },
                                    onDownloadUpdate = { viewModel.downloadUpdate() },
                                    onDismissUpdate = { viewModel.dismissUpdate() },
                                    onSettings = { viewModel.navigateTo(Screen.SETTINGS) },
                                    onAuth = { viewModel.navigateTo(Screen.AUTH) },
                                    onLogout = { viewModel.logout() },
                                    onLeaderboardClick = { 
                                        viewModel.loadLeaderboard() // Load data
                                        viewModel.navigateTo(Screen.LEADERBOARD) 
                                    },
                                    onHistoryClick = {
                                        viewModel.loadHistory()
                                        viewModel.navigateTo(Screen.HISTORY)
                                    },
                                    onProfileClick = { viewModel.navigateTo(Screen.PROFILE) },
                                    userProfile = uiState.userProfile
                                )
                            }
                            
                            Screen.CREATE_GAME -> {
                                CreateGameScreen(
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    userProfile = uiState.userProfile,
                                    onCreateGame = { name, avatarId, gender, timerSeconds ->
                                        viewModel.createGame(name, avatarId, gender, timerSeconds)
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
                                        onLeaveGame = { viewModel.leaveGame() },
                                        onDeleteGame = { viewModel.deleteGame() },
                                        onRollDice = { viewModel.rollDiceForStart() },
                                        onSwapPlayers = { p1, p2 -> viewModel.swapPlayers(p1, p2) }
                                    )
                                }
                            }
                            
                            Screen.BOARD -> {
                                val gameState = uiState.gameState
                                val myPlayerId = uiState.myPlayerId
                                
                                if (gameState != null && myPlayerId != null) {
                                    // Auto-show combat to all players when active
                                    if (gameState.combat != null) {
                                        CombatScreen(
                                            gameState = gameState,
                                            myPlayerId = myPlayerId,
                                            monsterSearchResults = uiState.monsterSearchResults,
                                            onStartCombat = { viewModel.startCombat() },
                                            onAddMonster = { name, level, mod, undead ->
                                                viewModel.addMonster(name, level, mod, undead)
                                            },
                                            onSearchMonsters = { viewModel.searchMonsters(it) },
                                            onRequestCreateGlobalMonster = { name, level, mod, undead ->
                                                viewModel.requestCreateGlobalMonster(name, level, mod, undead)
                                            },
                                            onAddHelper = { viewModel.addHelper(it) },
                                            onRemoveHelper = { viewModel.removeHelper() },
                                            onModifyModifier = { target, delta -> viewModel.modifyCombatModifier(target, delta) },
                                        onRollCombatDice = { purpose, result, success -> 
                                            viewModel.rollForCombat(purpose, result, success)
                                            if (purpose == DiceRollPurpose.RUN_AWAY && result != null) {
                                                scope.launch {
                                                    viewModel.endCombat()
                                                    kotlinx.coroutines.delay(500)
                                                    viewModel.endTurn()
                                                }
                                            }
                                        },
                                            onEndCombat = { viewModel.endCombat() },
                                            onBack = { 
                                                // Only main player can cancel combat via back
                                                if (myPlayerId == gameState.combat?.mainPlayerId) {
                                                    viewModel.endCombat()
                                                }
                                            }
                                        )
                                    } else {
                                        BoardScreen(
                                            gameState = gameState,
                                            myPlayerId = myPlayerId,
                                            isHost = uiState.isHost,
                                            connectionState = uiState.connectionState,
                                            pendingWinnerId = uiState.pendingWinnerId,
                                            onPlayerClick = { playerId -> viewModel.selectPlayer(playerId) },
                                            onCombatClick = { viewModel.navigateTo(Screen.COMBAT) },
                                            onCatalogClick = { viewModel.navigateTo(Screen.CATALOG) },
                                            onSettingsClick = { viewModel.navigateTo(Screen.SETTINGS) },

                                            onLeaveGame = { viewModel.leaveGame() },
                                            onDeleteGame = { viewModel.deleteGame() },
                                            onConfirmWin = { viewModel.confirmWin(it) },
                                            onDismissWin = { viewModel.dismissWinConfirmation() },
                                            onEndTurn = { viewModel.endTurn() },
                                            onToggleGender = { viewModel.toggleGender() },
                                            onSwapPlayers = viewModel::swapPlayers,
                                            logEntries = viewModel.gameLog.collectAsState().value
                                        )
                                    }
                                }
                            }
                            
                            Screen.PLAYER_DETAIL -> {
                                val selectedPlayer = uiState.selectedPlayer
                                val myPlayerId = uiState.myPlayerId
                                
                                if (selectedPlayer != null) {
                                    PlayerDetailScreen(
                                        player = selectedPlayer,
                                        onIncrementLevel = { viewModel.incrementLevel() },
                                        onDecrementLevel = { viewModel.decrementLevel() },
                                        onModifyGear = { amount -> 
                                            if (amount > 0) viewModel.incrementGear(amount) 
                                            else viewModel.decrementGear(-amount) // Pass positive magnitude
                                        },
                                        onSetClass = { viewModel.setCharacterClass(it) },
                                        onSetRace = { viewModel.setCharacterRace(it) },
                                        onBack = { viewModel.goBack() },
                                        isReadOnly = selectedPlayer.playerId != myPlayerId
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
                                        onAddMonster = { name, level, mod, undead ->
                                            viewModel.addMonster(name, level, mod, undead)
                                        },
                                        onSearchMonsters = { viewModel.searchMonsters(it) },
                                        onRequestCreateGlobalMonster = { name, level, mod, undead ->
                                            viewModel.requestCreateGlobalMonster(name, level, mod, undead)
                                        },
                                        onAddHelper = { viewModel.addHelper(it) },
                                        onRemoveHelper = { viewModel.removeHelper() },
                                        onModifyModifier = { target, delta -> viewModel.modifyCombatModifier(target, delta) },
                                        onRollCombatDice = { purpose, result, success -> 
                                            viewModel.rollForCombat(purpose, result, success)
                                            if (purpose == DiceRollPurpose.RUN_AWAY && result != null) {
                                                scope.launch {
                                                    viewModel.endCombat()
                                                    kotlinx.coroutines.delay(500)
                                                    viewModel.endTurn()
                                                }
                                            }
                                        },
                                        onEndCombat = { viewModel.endCombat() },
                                        onBack = { viewModel.goBack() }
                                    )
                                }
                            }
                            
                            Screen.CATALOG -> {
                                MonsterCatalogScreen(
                                    onBack = { viewModel.goBack() }
                                )
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

                            Screen.HISTORY -> {
                                HistoryScreen(
                                    history = uiState.gameHistory,
                                    isLoading = uiState.isLoading,
                                    onBack = { viewModel.goBack() },
                                    onRefresh = { viewModel.loadHistory() }
                                )
                            }
                            
                            Screen.PROFILE -> {
                                uiState.userProfile?.let { user ->
                                    ProfileScreen(
                                        userProfile = user,
                                        gameHistory = uiState.gameHistory,
                                        isLoading = uiState.isLoading,
                                        onBack = { viewModel.navigateTo(Screen.HOME) },
                                        onRefresh = { viewModel.loadHistory() },
                                        onUpdateProfile = { name, pass -> viewModel.updateProfile(name, pass) }
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
                        } // when
                    } // AnimatedContent
                    } // Surface
                    
                    // Debug log viewer with floating button
                    DebugLogViewer(showTrigger = BuildConfig.DEBUG)
                } // Box
            } // else (not showTutorial)
            } // MunchkinTheme
        } // setContent
    } // onCreate
} // MainActivity
