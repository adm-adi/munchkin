package com.munchkin.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.munchkin.app.BuildConfig
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
import com.munchkin.app.viewmodel.AccountUiState
import com.munchkin.app.viewmodel.AccountViewModel
import com.munchkin.app.viewmodel.CatalogUiState
import com.munchkin.app.viewmodel.CatalogViewModel
import com.munchkin.app.viewmodel.GameDestination
import com.munchkin.app.viewmodel.GameDirectoryUiState
import com.munchkin.app.viewmodel.GameDirectoryViewModel
import com.munchkin.app.viewmodel.GameUiEvent
import com.munchkin.app.viewmodel.GameUiState
import com.munchkin.app.viewmodel.GameViewModel
import com.munchkin.app.viewmodel.HistoryUiState
import com.munchkin.app.viewmodel.HistoryViewModel
import com.munchkin.app.viewmodel.UpdateUiState
import com.munchkin.app.viewmodel.UpdateViewModel

@Composable
fun MunchkinNavHost(
    viewModel: GameViewModel,
    uiState: GameUiState,
    accountViewModel: AccountViewModel,
    accountState: AccountUiState,
    catalogViewModel: CatalogViewModel,
    catalogState: CatalogUiState,
    historyViewModel: HistoryViewModel,
    historyState: HistoryUiState,
    gameDirectoryViewModel: GameDirectoryViewModel,
    gameDirectoryState: GameDirectoryUiState,
    updateViewModel: UpdateViewModel,
    updateState: UpdateUiState
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentScreen = screenForRoute(currentBackStackEntry?.destination?.route)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is GameUiEvent.Navigate) {
                navController.navigateTo(event.destination.toScreen())
            }
        }
    }

    LaunchedEffect(accountState.userProfile?.id, currentScreen) {
        if (accountState.userProfile != null && currentScreen == Screen.AUTH) {
            navController.navigateTo(Screen.HOME)
        }
    }

    LaunchedEffect(catalogViewModel) {
        catalogViewModel.createdMonsters.collect { monster ->
            viewModel.addMonster(
                name = monster.name,
                level = monster.level,
                modifier = monster.modifier,
                isUndead = monster.isUndead,
                treasures = monster.treasures,
                levels = monster.levels,
                badStuff = monster.badStuff
            )
        }
    }

    BackHandler(enabled = currentScreen != Screen.HOME) {
        navController.navigateBackFrom(currentScreen, uiState)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val currentState = uiState.gameState
            val currentPlayer = uiState.myPlayerId
            if (currentState != null && currentPlayer != null) {
                EventEffects(currentState, currentPlayer)
            }

            NavHost(
                navController = navController,
                startDestination = Screen.HOME.route,
                modifier = Modifier.fillMaxSize()
            ) {
                Screen.values().forEach { screen ->
                    composable(screen.route) {
                        GameRoute(
                            screen = screen,
                            uiState = uiState,
                            viewModel = viewModel,
                            accountViewModel = accountViewModel,
                            accountState = accountState,
                            catalogViewModel = catalogViewModel,
                            catalogState = catalogState,
                            historyViewModel = historyViewModel,
                            historyState = historyState,
                            gameDirectoryViewModel = gameDirectoryViewModel,
                            gameDirectoryState = gameDirectoryState,
                            updateViewModel = updateViewModel,
                            updateState = updateState,
                            navigateTo = navController::navigateTo,
                            navigateBack = { navController.navigateBackFrom(currentScreen, uiState) }
                        )
                    }
                }
            }
        }

        DebugLogViewer(showTrigger = BuildConfig.DEBUG)
    }
}

@Composable
private fun GameRoute(
    screen: Screen,
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountViewModel: AccountViewModel,
    accountState: AccountUiState,
    catalogViewModel: CatalogViewModel,
    catalogState: CatalogUiState,
    historyViewModel: HistoryViewModel,
    historyState: HistoryUiState,
    gameDirectoryViewModel: GameDirectoryViewModel,
    gameDirectoryState: GameDirectoryUiState,
    updateViewModel: UpdateViewModel,
    updateState: UpdateUiState,
    navigateTo: (Screen) -> Unit,
    navigateBack: () -> Unit
) {
    when (screen) {
        Screen.HOME -> HomeRoute(
            uiState,
            viewModel,
            accountViewModel,
            accountState,
            historyViewModel,
            gameDirectoryViewModel,
            gameDirectoryState,
            updateViewModel,
            updateState,
            navigateTo
        )
        Screen.CREATE_GAME -> CreateGameRoute(uiState, viewModel, accountState, navigateBack)
        Screen.JOIN_GAME -> JoinGameRoute(
            uiState,
            viewModel,
            accountState,
            gameDirectoryViewModel,
            gameDirectoryState,
            navigateBack
        )
        Screen.LOBBY -> LobbyRoute(uiState, viewModel, accountState)
        Screen.BOARD -> BoardRoute(uiState, viewModel, accountState, catalogViewModel, catalogState, navigateTo)
        Screen.PLAYER_DETAIL -> PlayerDetailRoute(uiState, viewModel, navigateBack)
        Screen.COMBAT -> CombatRoute(uiState, viewModel, accountState, catalogViewModel, catalogState, navigateBack)
        Screen.CATALOG -> MonsterCatalogScreen(
            searchResults = catalogState.searchResults,
            isLoading = catalogState.isLoading,
            error = catalogState.error,
            onSearch = { catalogViewModel.searchMonsters(it) },
            onBack = navigateBack
        )
        Screen.SETTINGS -> SettingsScreen(
            isCheckingUpdate = updateState.isCheckingUpdate,
            onBack = navigateBack,
            onCheckUpdate = { updateViewModel.checkForUpdates() }
        )
        Screen.AUTH -> AuthScreen(
            onLogin = { email, pass -> accountViewModel.login(email, pass) },
            onRegister = { user, email, pass -> accountViewModel.register(user, email, pass) },
            onBack = navigateBack,
            isLoading = accountState.isLoading,
            error = accountState.error
        )
        Screen.HISTORY -> HistoryScreen(
            history = historyState.gameHistory,
            isLoading = historyState.isLoading,
            onBack = navigateBack,
            onRefresh = { historyViewModel.loadHistory() }
        )
        Screen.PROFILE -> accountState.userProfile?.let { user ->
            ProfileScreen(
                userProfile = user,
                gameHistory = historyState.gameHistory,
                isLoading = accountState.isLoading || historyState.isLoading,
                error = accountState.error ?: historyState.error,
                onBack = navigateBack,
                onRefresh = { historyViewModel.loadHistory() },
                onClearError = {
                    accountViewModel.clearError()
                    historyViewModel.clearError()
                },
                onUpdateProfile = { name, pass -> accountViewModel.updateProfile(name, pass) }
            )
        }
        Screen.LEADERBOARD -> LeaderboardScreen(
            leaderboard = historyState.leaderboard,
            isLoading = historyState.isLoading,
            onBack = navigateBack,
            onRefresh = { historyViewModel.loadLeaderboard() }
        )
    }
}

@Composable
private fun HomeRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountViewModel: AccountViewModel,
    accountState: AccountUiState,
    historyViewModel: HistoryViewModel,
    gameDirectoryViewModel: GameDirectoryViewModel,
    gameDirectoryState: GameDirectoryUiState,
    updateViewModel: UpdateViewModel,
    updateState: UpdateUiState,
    navigateTo: (Screen) -> Unit
) {
    val savedGame by viewModel.savedGame.collectAsState()

    HomeScreen(
        savedGame = savedGame,
        updateInfo = updateState.updateInfo,
        isDownloading = updateState.isDownloading,
        isLoading = uiState.isLoading,
        error = uiState.error ?: gameDirectoryState.error,
        hostedGames = gameDirectoryState.hostedGames,
        onCreateGame = { navigateTo(Screen.CREATE_GAME) },
        onJoinGame = { navigateTo(Screen.JOIN_GAME) },
        onResumeGame = { viewModel.resumeSavedGame(accountState.userProfile) },
        onDeleteSavedGame = { viewModel.deleteSavedGame(accountState.userProfile) },
        onDeleteHostedGame = { gameDirectoryViewModel.deleteHostedGame(it) },
        onClearError = {
            viewModel.clearError()
            gameDirectoryViewModel.clearError()
        },
        onDownloadUpdate = { updateViewModel.downloadUpdate() },
        onDismissUpdate = { updateViewModel.dismissUpdate() },
        onSettings = { navigateTo(Screen.SETTINGS) },
        onAuth = { navigateTo(Screen.AUTH) },
        onLogout = {
            accountViewModel.logout()
            gameDirectoryViewModel.clear()
            historyViewModel.clear()
        },
        onLeaderboardClick = {
            historyViewModel.loadLeaderboard()
            navigateTo(Screen.LEADERBOARD)
        },
        onHistoryClick = {
            historyViewModel.loadHistory()
            navigateTo(Screen.HISTORY)
        },
        onProfileClick = {
            historyViewModel.loadHistory()
            navigateTo(Screen.PROFILE)
        },
        userProfile = accountState.userProfile
    )
}

@Composable
private fun CreateGameRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountState: AccountUiState,
    navigateBack: () -> Unit
) {
    CreateGameScreen(
        isLoading = uiState.isLoading,
        error = uiState.error,
        userProfile = accountState.userProfile,
        onCreateGame = { name, avatarId, gender, timerSeconds, superMunchkin ->
            viewModel.createGame(name, avatarId, gender, timerSeconds, superMunchkin, accountState.userProfile)
        },
        onBack = navigateBack
    )
}

@Composable
private fun JoinGameRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountState: AccountUiState,
    gameDirectoryViewModel: GameDirectoryViewModel,
    gameDirectoryState: GameDirectoryUiState,
    navigateBack: () -> Unit
) {
    JoinGameScreen(
        isLoading = uiState.isLoading,
        error = uiState.error ?: gameDirectoryState.error,
        discoveredGames = gameDirectoryState.discoveredGames,
        isDiscovering = gameDirectoryState.isDiscovering,
        userProfile = accountState.userProfile,
        onJoinGame = { wsUrl, joinCode, name, avatarId, gender ->
            viewModel.joinGame(wsUrl, joinCode, name, avatarId, gender, accountState.userProfile)
        },
        onJoinDiscoveredGame = { game, name, avatarId, gender ->
            viewModel.joinDiscoveredGame(game, name, avatarId, gender, accountState.userProfile)
        },
        onStartDiscovery = { gameDirectoryViewModel.discoverGames() },
        onBack = navigateBack
    )
}

@Composable
private fun LobbyRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountState: AccountUiState
) {
    val gameState = uiState.gameState
    val myPlayerId = uiState.myPlayerId
    if (gameState != null && myPlayerId != null) {
        LobbyScreen(
            gameState = gameState,
            myPlayerId = myPlayerId,
            isHost = uiState.isHost,
            connectionInfo = uiState.connectionInfo,
            onStartGame = { viewModel.startGame() },
            onLeaveGame = { viewModel.leaveGame() },
            onDeleteGame = { viewModel.deleteGame() },
            onRollDice = { viewModel.rollDiceForStart() },
            onSwapPlayers = { p1, p2 -> viewModel.swapPlayers(p1, p2) },
            onKickPlayer = { viewModel.kickPlayer(it) },
            connectionState = uiState.connectionState,
            reconnectAttempt = uiState.reconnectAttempt,
            onRetryReconnect = { viewModel.retryReconnect(accountState.userProfile) }
        )
    }
}

@Composable
private fun BoardRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountState: AccountUiState,
    catalogViewModel: CatalogViewModel,
    catalogState: CatalogUiState,
    navigateTo: (Screen) -> Unit
) {
    val gameState = uiState.gameState
    val myPlayerId = uiState.myPlayerId
    if (gameState != null && myPlayerId != null) {
        if (gameState.combat != null) {
            CombatRoute(
                uiState = uiState,
                viewModel = viewModel,
                accountState = accountState,
                catalogViewModel = catalogViewModel,
                catalogState = catalogState,
                navigateBack = { navigateTo(Screen.BOARD) }
            )
        } else {
            val logEntries by viewModel.gameLog.collectAsState()
            BoardScreen(
                gameState = gameState,
                myPlayerId = myPlayerId,
                isHost = uiState.isHost,
                connectionState = uiState.connectionState,
                pendingWinnerId = uiState.pendingWinnerId,
                onPlayerClick = { playerId ->
                    viewModel.selectPlayer(playerId)
                    navigateTo(Screen.PLAYER_DETAIL)
                },
                onCombatClick = { navigateTo(Screen.COMBAT) },
                onCatalogClick = { navigateTo(Screen.CATALOG) },
                onSettingsClick = { navigateTo(Screen.SETTINGS) },
                onLeaveGame = { viewModel.leaveGame() },
                onDeleteGame = { viewModel.deleteGame() },
                onConfirmWin = { viewModel.confirmWin(it) },
                onDismissWin = { viewModel.dismissWinConfirmation() },
                onEndTurn = { viewModel.endTurn() },
                onToggleGender = { viewModel.toggleGender() },
                onSwapPlayers = viewModel::swapPlayers,
                onKickPlayer = { viewModel.kickPlayer(it) },
                logEntries = logEntries,
                reconnectAttempt = uiState.reconnectAttempt,
                onRetryReconnect = { viewModel.retryReconnect(accountState.userProfile) }
            )
        }
    }
}

@Composable
private fun PlayerDetailRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    navigateBack: () -> Unit
) {
    val selectedPlayer = uiState.selectedPlayer
    val myPlayerId = uiState.myPlayerId
    if (selectedPlayer != null) {
        PlayerDetailScreen(
            player = selectedPlayer,
            onIncrementLevel = { viewModel.incrementLevel() },
            onDecrementLevel = { viewModel.decrementLevel() },
            onModifyGear = { amount ->
                if (amount > 0) viewModel.incrementGear(amount) else viewModel.decrementGear(-amount)
            },
            onSetClass = { viewModel.setCharacterClass(it) },
            onSetRace = { viewModel.setCharacterRace(it) },
            onBack = navigateBack,
            isReadOnly = selectedPlayer.playerId != myPlayerId,
            maxLevel = uiState.gameState?.settings?.maxLevel ?: 10
        )
    }
}

@Composable
private fun CombatRoute(
    uiState: GameUiState,
    viewModel: GameViewModel,
    accountState: AccountUiState,
    catalogViewModel: CatalogViewModel,
    catalogState: CatalogUiState,
    navigateBack: () -> Unit
) {
    val gameState = uiState.gameState
    val myPlayerId = uiState.myPlayerId
    if (gameState != null && myPlayerId != null) {
        CombatScreen(
            gameState = gameState,
            myPlayerId = myPlayerId,
            monsterSearchResults = catalogState.searchResults,
            onStartCombat = { viewModel.startCombat() },
            onAddMonster = { monster ->
                viewModel.addMonster(
                    name = monster.name,
                    level = monster.level,
                    modifier = monster.modifier,
                    isUndead = monster.isUndead,
                    treasures = monster.treasures,
                    levels = monster.levels,
                    badStuff = monster.badStuff
                )
            },
            onSearchMonsters = { catalogViewModel.searchMonsters(it) },
            onRequestCreateGlobalMonster = { monster ->
                catalogViewModel.createGlobalMonster(
                    name = monster.name,
                    level = monster.level,
                    modifier = monster.modifier,
                    isUndead = monster.isUndead,
                    userProfile = accountState.userProfile,
                    fallbackOwnerId = myPlayerId.value,
                    treasures = monster.treasures,
                    levels = monster.levels,
                    badStuff = monster.badStuff
                )
            },
            onAddHelper = { viewModel.addHelper(it) },
            onRemoveHelper = { viewModel.removeHelper() },
            onModifyModifier = { target, delta -> viewModel.modifyCombatModifier(target, delta) },
            onRollCombatDice = { purpose, result, success ->
                viewModel.rollForCombat(purpose, result, success)
            },
            onEndCombat = { viewModel.endCombat() },
            onResolveRunAway = { success -> viewModel.resolveRunAway(success) },
            onBack = navigateBack
        )
    }
}

private enum class Screen {
    HOME,
    CREATE_GAME,
    JOIN_GAME,
    LOBBY,
    BOARD,
    PLAYER_DETAIL,
    COMBAT,
    CATALOG,
    SETTINGS,
    AUTH,
    PROFILE,
    LEADERBOARD,
    HISTORY
}

private val Screen.route: String
    get() = name.lowercase()

private fun screenForRoute(route: String?): Screen {
    return Screen.values().firstOrNull { it.route == route } ?: Screen.HOME
}

private fun GameDestination.toScreen(): Screen {
    return when (this) {
        GameDestination.HOME -> Screen.HOME
        GameDestination.LOBBY -> Screen.LOBBY
        GameDestination.BOARD -> Screen.BOARD
        GameDestination.COMBAT -> Screen.COMBAT
    }
}

private fun NavController.navigateTo(screen: Screen) {
    navigate(screen.route) {
        launchSingleTop = true
        if (screen == Screen.HOME) {
            popUpTo(Screen.HOME.route) {
                inclusive = false
            }
        }
    }
}

private fun NavController.navigateBackFrom(currentScreen: Screen, uiState: GameUiState) {
    val target = when (currentScreen) {
        Screen.PLAYER_DETAIL, Screen.COMBAT, Screen.CATALOG -> Screen.BOARD
        Screen.SETTINGS -> if (uiState.gameState != null) Screen.BOARD else Screen.HOME
        else -> Screen.HOME
    }
    navigateTo(target)
}
