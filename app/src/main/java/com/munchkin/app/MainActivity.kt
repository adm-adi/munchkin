package com.munchkin.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.munchkin.app.core.GamePhase
import com.munchkin.app.network.JoinCodeParser
import com.munchkin.app.ui.components.BadStuffDialog
import com.munchkin.app.ui.components.DebugLogViewer
import com.munchkin.app.ui.components.EventEffects
import com.munchkin.app.ui.screens.AuthScreen
import com.munchkin.app.ui.screens.BoardScreen
import com.munchkin.app.ui.screens.CombatScreen
import com.munchkin.app.ui.screens.CreateGameScreen
import com.munchkin.app.ui.screens.HistoryScreen
import com.munchkin.app.ui.screens.HomeScreen
import com.munchkin.app.ui.screens.JoinGameScreen
import com.munchkin.app.ui.screens.LeaderboardScreen
import com.munchkin.app.ui.screens.LobbyScreen
import com.munchkin.app.ui.screens.MonsterCatalogScreen
import com.munchkin.app.ui.screens.PlayerDetailScreen
import com.munchkin.app.ui.screens.ProfileScreen
import com.munchkin.app.ui.screens.SettingsScreen
import com.munchkin.app.ui.screens.TableScreen
import com.munchkin.app.ui.screens.TutorialScreen
import com.munchkin.app.ui.theme.MunchkinTheme
import com.munchkin.app.ui.theme.NeonBackground
import com.munchkin.app.util.LocaleManager
import com.munchkin.app.viewmodel.AuthViewModel
import com.munchkin.app.viewmodel.GameSessionViewModel
import com.munchkin.app.viewmodel.GameUiEvent
import com.munchkin.app.viewmodel.LobbyViewModel

private object Routes {
    const val HOME = "home"
    const val CREATE_GAME = "create_game"
    const val JOIN_GAME_PATTERN = "join_game?code={code}"
    const val LOBBY = "lobby"
    const val TABLE = "table"
    const val BOARD = "board"
    const val COMBAT = "combat"
    const val PLAYER_DETAIL = "player_detail"
    const val CATALOG = "catalog"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val PROFILE = "profile"
    const val HISTORY = "history"
    const val LEADERBOARD = "leaderboard"

    fun joinGame(code: String? = null): String =
        if (!code.isNullOrBlank()) "join_game?code=${Uri.encode(code)}" else "join_game"
}

private val GAMEPLAY_KEEP_SCREEN_ON_ROUTES = setOf(
    Routes.LOBBY,
    Routes.TABLE,
    Routes.BOARD,
    Routes.COMBAT
)

class MainActivity : ComponentActivity() {

    private var pendingDeepLinkCode by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLinkCode = extractDeepLinkCode(intent)

        setContent {
            val container = remember { MunchkinApp.container }
            val coordinator = container.sessionCoordinator
            val authViewModel: AuthViewModel = viewModel(factory = container.viewModelFactory)
            val lobbyViewModel: LobbyViewModel = viewModel(factory = container.viewModelFactory)
            val gameSessionViewModel: GameSessionViewModel = viewModel(factory = container.viewModelFactory)

            val gameState by gameSessionViewModel.gameState.collectAsState()
            val myPlayerId by gameSessionViewModel.myPlayerId.collectAsState()
            val isHost by gameSessionViewModel.isHost.collectAsState()
            val connectionState by gameSessionViewModel.connectionState.collectAsState()
            val reconnectAttempt by gameSessionViewModel.reconnectAttempt.collectAsState()
            val serverTimeOffsetMs by gameSessionViewModel.serverTimeOffsetMs.collectAsState()
            val pendingWinners by gameSessionViewModel.pendingWinners.collectAsState()
            val isCombatResolving by gameSessionViewModel.isCombatResolving.collectAsState()
            val pendingBadStuff by gameSessionViewModel.pendingBadStuff.collectAsState()

            val userProfile by authViewModel.userProfile.collectAsState()
            val hostedGames by authViewModel.hostedGames.collectAsState()
            val authIsLoading by authViewModel.isLoading.collectAsState()
            val authError by authViewModel.error.collectAsState()

            val savedGame by lobbyViewModel.savedGame.collectAsState()
            val updateInfo by lobbyViewModel.updateInfo.collectAsState()
            val isDownloading by lobbyViewModel.isDownloading.collectAsState()
            val isCheckingUpdate by lobbyViewModel.isCheckingUpdate.collectAsState()
            val discoveredGames by lobbyViewModel.discoveredGames.collectAsState()
            val isDiscovering by lobbyViewModel.isDiscovering.collectAsState()
            val largeNumbersMode by lobbyViewModel.largeNumbersMode.collectAsState()
            val lobbyIsLoading by lobbyViewModel.isLoading.collectAsState()
            val lobbyError by lobbyViewModel.error.collectAsState()

            val combinedLoading = authIsLoading || lobbyIsLoading
            val combinedError = lobbyError ?: authError

            LaunchedEffect(Unit) {
                authViewModel.refreshSessionOnStartup()
                lobbyViewModel.checkForUpdates()
            }

            MunchkinTheme(largeNumbersMode = largeNumbersMode) {
                val lifecycleOwner = LocalLifecycleOwner.current
                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }
                val navController = rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route

                val tutorialPrefs = container.tutorialPrefs
                var showTutorial by remember { mutableStateOf(!tutorialPrefs.isShown()) }

                // Scope keep-screen-on strictly to active multiplayer gameplay routes
                val keepScreenOn = !showTutorial && currentRoute in GAMEPLAY_KEEP_SCREEN_ON_ROUTES
                DisposableEffect(keepScreenOn) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Single root event collector -> SnackbarHost & Reconnected toast
                LaunchedEffect(coordinator) {
                    coordinator.events.collect { event ->
                        when (event) {
                            is GameUiEvent.Reconnected ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.reconnected),
                                    Toast.LENGTH_SHORT
                                ).show()
                            is GameUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                            is GameUiEvent.ShowSuccess -> snackbarHostState.showSnackbar(event.message)
                            is GameUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                        }
                    }
                }

                // Nudge socket / check reconnection cleanly across foreground lifecycle
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> gameSessionViewModel.nudgeConnection()
                            Lifecycle.Event.ON_RESUME -> {
                                val route = navController.currentBackStackEntry?.destination?.route
                                val onGameplayRoute = route in GAMEPLAY_KEEP_SCREEN_ON_ROUTES || route == Routes.PLAYER_DETAIL
                                gameSessionViewModel.checkReconnection(allowColdResume = onGameplayRoute)
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = NeonBackground,
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.navigationBarsPadding()
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (showTutorial) {
                            TutorialScreen(
                                onFinish = {
                                    tutorialPrefs.markShown()
                                    showTutorial = false
                                },
                                tutorialPrefs = tutorialPrefs
                            )
                        } else {
                            // Deep-link handling (cold start and onNewIntent)
                            LaunchedEffect(pendingDeepLinkCode) {
                                val code = pendingDeepLinkCode
                                if (!code.isNullOrBlank()) {
                                    pendingDeepLinkCode = null
                                    navController.navigate(Routes.joinGame(code)) {
                                        launchSingleTop = true
                                    }
                                }
                            }

                            // Keep auth/profile backstack synchronized with userProfile transitions
                            LaunchedEffect(userProfile, currentRoute) {
                                if (currentRoute == Routes.AUTH && userProfile != null) {
                                    navController.popBackStack()
                                } else if (currentRoute == Routes.PROFILE && userProfile == null) {
                                    navController.popBackStack(Routes.HOME, inclusive = false)
                                }
                            }

                            // Authoritative gameState navigation transitions (never traps user in Combat)
                            var prevGameId by remember { mutableStateOf<String?>(null) }
                            var prevPhase by remember { mutableStateOf<GamePhase?>(null) }
                            var prevHadCombat by remember { mutableStateOf(false) }

                            val currentGameId = gameState?.gameId?.value
                            val currentPhase = gameState?.phase
                            val hasCombat = gameState?.combat != null

                            LaunchedEffect(currentGameId, currentPhase, hasCombat) {
                                if (currentGameId == null || currentPhase == null) {
                                    if (prevGameId != null) {
                                        val activeRoute = navController.currentDestination?.route
                                        if (activeRoute in GAMEPLAY_KEEP_SCREEN_ON_ROUTES ||
                                            activeRoute == Routes.PLAYER_DETAIL
                                        ) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                    prevGameId = null
                                    prevPhase = null
                                    prevHadCombat = false
                                } else {
                                    val isNewGame = prevGameId != currentGameId
                                    if (isNewGame) {
                                        val dest = if (currentPhase == GamePhase.LOBBY) Routes.LOBBY else Routes.BOARD
                                        navController.navigate(dest) {
                                            popUpTo(Routes.HOME) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    } else if (prevPhase == GamePhase.LOBBY && currentPhase != GamePhase.LOBBY) {
                                        navController.navigate(Routes.BOARD) {
                                            popUpTo(Routes.HOME) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    } else if (!prevHadCombat && hasCombat) {
                                        // Only auto-open Combat on combat == null -> combat != null transition
                                        navController.navigate(Routes.COMBAT) {
                                            launchSingleTop = true
                                        }
                                    } else if (prevHadCombat && !hasCombat &&
                                        navController.currentDestination?.route == Routes.COMBAT
                                    ) {
                                        val popped = navController.popBackStack(Routes.BOARD, inclusive = false)
                                        if (!popped) {
                                            navController.navigate(Routes.BOARD) { launchSingleTop = true }
                                        }
                                    }
                                    prevGameId = currentGameId
                                    prevPhase = currentPhase
                                    prevHadCombat = hasCombat
                                }
                            }

                            val activeState = gameState
                            val activePlayerId = myPlayerId
                            if (activeState != null && activePlayerId != null) {
                                EventEffects(activeState, activePlayerId)
                            }

                            NavHost(
                                navController = navController,
                                startDestination = Routes.HOME,
                                enterTransition = { fadeIn(tween(200)) },
                                exitTransition = { fadeOut(tween(180)) },
                                popEnterTransition = { fadeIn(tween(200)) },
                                popExitTransition = { fadeOut(tween(180)) }
                            ) {
                                composable(Routes.HOME) {
                                    HomeScreen(
                                        savedGame = savedGame,
                                        updateInfo = updateInfo,
                                        isDownloading = isDownloading,
                                        isLoading = combinedLoading,
                                        error = combinedError,
                                        hostedGames = hostedGames,
                                        onCreateGame = {
                                            navController.navigate(Routes.CREATE_GAME) { launchSingleTop = true }
                                        },
                                        onJoinGame = {
                                            navController.navigate(Routes.joinGame()) { launchSingleTop = true }
                                        },
                                        onResumeGame = { lobbyViewModel.resumeSavedGame() },
                                        onResumeHostedGame = { lobbyViewModel.resumeHostedGame(it) },
                                        onDeleteSavedGame = { lobbyViewModel.deleteSavedGame() },
                                        onDeleteHostedGame = { authViewModel.deleteHostedGame(it) },
                                        onClearError = {
                                            lobbyViewModel.clearError()
                                            authViewModel.clearError()
                                        },
                                        onDownloadUpdate = { lobbyViewModel.downloadUpdate() },
                                        onDismissUpdate = { lobbyViewModel.dismissUpdate() },
                                        onSettings = {
                                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                        },
                                        onAuth = {
                                            navController.navigate(Routes.AUTH) { launchSingleTop = true }
                                        },
                                        onLogout = { authViewModel.logout() },
                                        onLeaderboardClick = {
                                            navController.navigate(Routes.LEADERBOARD) { launchSingleTop = true }
                                        },
                                        onHistoryClick = {
                                            navController.navigate(Routes.HISTORY) { launchSingleTop = true }
                                        },
                                        onProfileClick = {
                                            navController.navigate(Routes.PROFILE) { launchSingleTop = true }
                                        },
                                        onStartOfflineGame = { names, superMunchkin, expansions ->
                                            lobbyViewModel.startOfflineGame(names, superMunchkin, expansions)
                                        },
                                        userProfile = userProfile
                                    )
                                }

                                composable(Routes.CREATE_GAME) {
                                    CreateGameScreen(
                                        isLoading = lobbyIsLoading,
                                        error = lobbyError,
                                        userProfile = userProfile,
                                        onCreateGame = { name, avatarId, gender, timerSeconds, superMunchkin, expansionsEnabled ->
                                            lobbyViewModel.createGame(
                                                name = name,
                                                avatarId = avatarId,
                                                gender = gender,
                                                timerSeconds = timerSeconds,
                                                superMunchkin = superMunchkin,
                                                expansionsEnabled = expansionsEnabled
                                            )
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(
                                    route = Routes.JOIN_GAME_PATTERN,
                                    arguments = listOf(
                                        navArgument("code") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = ""
                                        }
                                    )
                                ) { backStackEntry ->
                                    val initialJoinCode = backStackEntry.arguments?.getString("code").orEmpty()
                                    JoinGameScreen(
                                        isLoading = lobbyIsLoading,
                                        error = lobbyError,
                                        discoveredGames = discoveredGames,
                                        isDiscovering = isDiscovering,
                                        userProfile = userProfile,
                                        initialJoinCode = initialJoinCode,
                                        onJoinGame = { joinCode, name, avatarId, gender ->
                                            lobbyViewModel.joinGame(joinCode, name, avatarId, gender)
                                        },
                                        onJoinDiscoveredGame = { game, name, avatarId, gender ->
                                            lobbyViewModel.joinDiscoveredGame(game, name, avatarId, gender)
                                        },
                                        onWatchGame = { code -> lobbyViewModel.watchGame(code) },
                                        onStartDiscovery = { lobbyViewModel.startDiscovery() },
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable(Routes.LOBBY) {
                                    val state = gameState
                                    val pid = myPlayerId
                                    if (state != null && pid != null) {
                                        LobbyScreen(
                                            gameState = state,
                                            myPlayerId = pid,
                                            isHost = isHost,
                                            onStartGame = { gameSessionViewModel.startGame() },
                                            onLeaveGame = { gameSessionViewModel.leaveGame() },
                                            onDeleteGame = { gameSessionViewModel.deleteGame() },
                                            onRollDice = { gameSessionViewModel.rollDiceForStart() },
                                            onSwapPlayers = { p1, p2 -> gameSessionViewModel.swapPlayers(p1, p2) },
                                            onKickPlayer = { gameSessionViewModel.kickPlayer(it) },
                                            onTransferHost = { gameSessionViewModel.transferHost(it) },
                                            connectionState = connectionState,
                                            reconnectAttempt = reconnectAttempt,
                                            onRetryReconnect = { gameSessionViewModel.retryReconnect() }
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                }

                                composable(Routes.TABLE) {
                                    val state = gameState
                                    val pid = myPlayerId
                                    if (state != null && pid != null) {
                                        val canEndTurn = state.turnPlayerId == pid &&
                                            state.phase == GamePhase.IN_GAME
                                        TableScreen(
                                            players = state.playerList,
                                            currentUser = pid,
                                            turnPlayerId = state.turnPlayerId,
                                            timerSeconds = state.settings.turnTimerSeconds,
                                            turnEndsAt = state.turnEndsAt,
                                            serverTimeOffsetMs = serverTimeOffsetMs,
                                            isHost = isHost,
                                            onPlayerClick = { playerId ->
                                                gameSessionViewModel.selectPlayer(playerId)
                                                navController.navigate(Routes.PLAYER_DETAIL) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onEndTurn = if (canEndTurn) {
                                                { gameSessionViewModel.endTurn() }
                                            } else null,
                                            onKickPlayer = { gameSessionViewModel.kickPlayer(it) },
                                            onTransferHost = { gameSessionViewModel.transferHost(it) }
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                }

                                composable(Routes.BOARD) {
                                    val state = gameState
                                    val pid = myPlayerId
                                    val gameLog by gameSessionViewModel.gameLog.collectAsState()
                                    if (state != null && pid != null) {
                                        BoardScreen(
                                            gameState = state,
                                            myPlayerId = pid,
                                            isHost = isHost,
                                            connectionState = connectionState,
                                            pendingWinners = pendingWinners,
                                            onPlayerClick = { playerId ->
                                                gameSessionViewModel.selectPlayer(playerId)
                                                navController.navigate(Routes.PLAYER_DETAIL) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onCombatClick = {
                                                navController.navigate(Routes.COMBAT) { launchSingleTop = true }
                                            },
                                            onSettingsClick = {
                                                navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                            },
                                            onLeaveGame = { gameSessionViewModel.leaveGame() },
                                            onDeleteGame = { gameSessionViewModel.deleteGame() },
                                            onConfirmWin = { gameSessionViewModel.confirmWin(it) },
                                            onDismissWin = { gameSessionViewModel.dismissWinConfirmation(it) },
                                            onEndTurn = { gameSessionViewModel.endTurn() },
                                            serverTimeOffsetMs = serverTimeOffsetMs,
                                            onToggleGender = { gameSessionViewModel.toggleGender() },
                                            onSwapPlayers = gameSessionViewModel::swapPlayers,
                                            onKickPlayer = { gameSessionViewModel.kickPlayer(it) },
                                            onTransferHost = { gameSessionViewModel.transferHost(it) },
                                            onRollDice = { gameSessionViewModel.rollDice(it) },
                                            onUndoLast = { gameSessionViewModel.undoLastAction() },
                                            onToggleTimerPause = { gameSessionViewModel.toggleTurnTimerPause() },
                                            onSwitchOfflineSeat = { gameSessionViewModel.switchOfflineSeat(it) },
                                            logEntries = gameLog,
                                            reconnectAttempt = reconnectAttempt,
                                            onRetryReconnect = { gameSessionViewModel.retryReconnect() }
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                }

                                composable(Routes.COMBAT) {
                                    val state = gameState
                                    val pid = myPlayerId
                                    val monsterSearchResults by gameSessionViewModel.monsterSearchResults.collectAsState()
                                    if (state != null && pid != null) {
                                        val goToBoard: () -> Unit = {
                                            val popped = navController.popBackStack(Routes.BOARD, inclusive = false)
                                            if (!popped) {
                                                navController.navigate(Routes.BOARD) { launchSingleTop = true }
                                            }
                                        }
                                        CombatScreen(
                                            gameState = state,
                                            myPlayerId = pid,
                                            monsterSearchResults = monsterSearchResults,
                                            onStartCombat = { gameSessionViewModel.startCombat() },
                                            onAddMonster = { name, level, mod, undead, badStuff, treasures, levels, condMods ->
                                                gameSessionViewModel.addMonster(
                                                    name, level, mod, undead, badStuff, treasures, levels, condMods
                                                )
                                            },
                                            onUpdateMonsterModifier = { monsterId, flatModifier ->
                                                gameSessionViewModel.updateMonster(monsterId, flatModifier)
                                            },
                                            onRemoveMonster = { gameSessionViewModel.removeMonster(it) },
                                            onSearchMonsters = { gameSessionViewModel.searchMonsters(it) },
                                            onRequestCreateGlobalMonster = { name, level, mod, undead, treasures, levels, badStuff ->
                                                gameSessionViewModel.requestCreateGlobalMonster(
                                                    name, level, mod, undead, treasures, levels, badStuff
                                                )
                                            },
                                            onAddHelper = { gameSessionViewModel.addHelper(it) },
                                            onRemoveHelper = { gameSessionViewModel.removeHelper() },
                                            onAddTempBonus = { target, label, amount ->
                                                gameSessionViewModel.addTempBonus(target, label, amount)
                                            },
                                            onRemoveTempBonus = { gameSessionViewModel.removeTempBonus(it) },
                                            onSetHelperTreasures = { gameSessionViewModel.setHelperTreasureShare(it) },
                                            onUndoLast = { gameSessionViewModel.undoLastAction() },
                                            onModifyModifier = { target, delta ->
                                                gameSessionViewModel.modifyCombatModifier(target, delta)
                                            },
                                            onRollCombatDice = { purpose, result, success, modifier ->
                                                gameSessionViewModel.rollForCombat(purpose, result, success, modifier)
                                            },
                                            onEndCombat = { gameSessionViewModel.endCombat() },
                                            onCancelCombat = { gameSessionViewModel.cancelCombat() },
                                            onResolveRunAway = { success -> gameSessionViewModel.resolveRunAway(success) },
                                            onGoToBoard = goToBoard,
                                            onBack = goToBoard,
                                            isCombatResolving = isCombatResolving,
                                            serverTimeOffsetMs = serverTimeOffsetMs
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                }

                                composable(Routes.PLAYER_DETAIL) {
                                    val selectedPlayer by gameSessionViewModel.selectedPlayer.collectAsState()
                                    val pid = myPlayerId
                                    val player = selectedPlayer
                                    if (player != null) {
                                        PlayerDetailScreen(
                                            player = player,
                                            onIncrementLevel = { gameSessionViewModel.incrementLevel() },
                                            onDecrementLevel = { gameSessionViewModel.decrementLevel() },
                                            onModifyGear = { amount ->
                                                if (amount > 0) gameSessionViewModel.incrementGear(amount)
                                                else gameSessionViewModel.decrementGear(-amount)
                                            },
                                            onApplyDeath = { gameSessionViewModel.applyDeath() },
                                            onSetClass = { gameSessionViewModel.setCharacterClass(it) },
                                            onSetRace = { gameSessionViewModel.setCharacterRace(it) },
                                            onSetSecondaryClass = {
                                                gameSessionViewModel.setCharacterClass(it, isSecondary = true)
                                            },
                                            onSetSecondaryRace = {
                                                gameSessionViewModel.setCharacterRace(it, isSecondary = true)
                                            },
                                            onSetSuperMunchkin = { gameSessionViewModel.setSuperMunchkin(it) },
                                            onSetHalfBreed = { gameSessionViewModel.setHalfBreed(it) },
                                            onAddCurse = { name, cPen, rPen, combats ->
                                                gameSessionViewModel.addCurse(name, cPen, rPen, combats)
                                            },
                                            onRemoveCurse = { gameSessionViewModel.removeCurse(it) },
                                            onBack = { navController.popBackStack() },
                                            isReadOnly = player.playerId != pid,
                                            maxLevel = gameState?.settings?.maxLevel ?: 10,
                                            expansionsEnabled = gameState?.settings?.expansionsEnabled == true
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack()
                                        }
                                    }
                                }

                                composable(Routes.CATALOG) {
                                    MonsterCatalogScreen(
                                        onBack = { navController.popBackStack() },
                                        canCreateMonster = userProfile != null
                                    )
                                }

                                composable(Routes.SETTINGS) {
                                    SettingsScreen(
                                        isCheckingUpdate = isCheckingUpdate,
                                        largeNumbersMode = largeNumbersMode,
                                        onToggleLargeNumbersMode = { lobbyViewModel.setLargeNumbersMode(it) },
                                        onBack = { navController.popBackStack() },
                                        onCheckUpdate = { lobbyViewModel.forceCheckUpdate() },
                                        onCatalogClick = {
                                            navController.navigate(Routes.CATALOG) { launchSingleTop = true }
                                        }
                                    )
                                }

                                composable(Routes.AUTH) {
                                    AuthScreen(
                                        onLogin = { email, pass -> authViewModel.login(email, pass) },
                                        onRegister = { user, email, pass ->
                                            authViewModel.register(user, email, pass)
                                        },
                                        onBack = { navController.popBackStack() },
                                        isLoading = authIsLoading,
                                        error = authError
                                    )
                                }

                                composable(Routes.PROFILE) {
                                    val gameHistory by authViewModel.gameHistory.collectAsState()
                                    val playerTotals by authViewModel.playerTotals.collectAsState()
                                    val user = userProfile
                                    if (user != null) {
                                        ProfileScreen(
                                            userProfile = user,
                                            gameHistory = gameHistory,
                                            playerTotals = playerTotals,
                                            isLoading = authIsLoading,
                                            error = authError,
                                            onBack = { navController.popBackStack() },
                                            onRefresh = { authViewModel.loadHistory() },
                                            onClearError = { authViewModel.clearError() },
                                            onUpdateProfile = { name, newPass, currentPass, avatar, gender ->
                                                authViewModel.updateProfile(
                                                    username = name,
                                                    password = newPass,
                                                    avatarId = avatar,
                                                    gender = gender,
                                                    currentPassword = currentPass
                                                )
                                            },
                                            onDeleteAccount = { pw -> authViewModel.deleteAccount(pw) }
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            navController.popBackStack(Routes.HOME, inclusive = false)
                                        }
                                    }
                                }

                                composable(Routes.HISTORY) {
                                    val gameHistory by authViewModel.gameHistory.collectAsState()
                                    val historyError by authViewModel.historyError.collectAsState()
                                    HistoryScreen(
                                        history = gameHistory,
                                        isLoading = authIsLoading,
                                        error = historyError,
                                        onBack = { navController.popBackStack() },
                                        onRefresh = { authViewModel.loadHistory() }
                                    )
                                }

                                composable(Routes.LEADERBOARD) {
                                    val leaderboard by authViewModel.leaderboard.collectAsState()
                                    val leaderboardSelf by authViewModel.leaderboardSelf.collectAsState()
                                    val leaderboardError by authViewModel.leaderboardError.collectAsState()
                                    LeaderboardScreen(
                                        leaderboard = leaderboard,
                                        isLoading = authIsLoading,
                                        error = leaderboardError,
                                        onBack = { navController.popBackStack() },
                                        onRefresh = { authViewModel.loadLeaderboard() },
                                        self = leaderboardSelf,
                                        currentUserId = userProfile?.id
                                    )
                                }
                            }
                        }

                        // Authoritative defeat Bad Stuff reminder at root overlay level
                        BadStuffDialog(
                            monsters = pendingBadStuff,
                            onDismiss = { gameSessionViewModel.dismissBadStuff() },
                            onDecrementLevel = { gameSessionViewModel.decrementLevel() },
                            onApplyDeath = { gameSessionViewModel.applyDeath() }
                        )

                        if (BuildConfig.DEBUG) {
                            DebugLogViewer(showTrigger = true)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLinkCode(intent)?.let { code ->
            pendingDeepLinkCode = code
        }
    }

    private fun extractDeepLinkCode(intent: Intent?): String? =
        intent?.data?.toString()?.let { JoinCodeParser.extractJoinCode(it) }
}
