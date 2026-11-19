package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.GameLogEntry
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.DiceRollerDialog
import com.munchkin.app.ui.components.GameLogDrawer
import com.munchkin.app.ui.components.PlayerCard
import com.munchkin.app.ui.components.TurnCountdownBadge
import com.munchkin.app.ui.theme.*

/**
 * Main game board showing all players, turn timer, active combat link, and quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    gameState: GameState,
    myPlayerId: PlayerId,
    isHost: Boolean,
    connectionState: ConnectionState,
    onPlayerClick: (PlayerId) -> Unit,
    onCombatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLeaveGame: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteGame: () -> Unit = {},
    pendingWinners: List<PlayerState> = emptyList(),
    onConfirmWin: (PlayerId) -> Unit = {},
    onDismissWin: (PlayerId?) -> Unit = {},
    onEndTurn: () -> Unit = {},
    /** Offset to add to the local clock to get server time; see GameClient. */
    serverTimeOffsetMs: Long = 0L,
    onToggleGender: () -> Unit = {},
    onSwapPlayers: (PlayerId, PlayerId) -> Unit = { _, _ -> },
    onKickPlayer: ((PlayerId) -> Unit)? = null,
    onTransferHost: ((PlayerId) -> Unit)? = null,
    onRollDice: (Int) -> Unit = {},
    onUndoLast: () -> Unit = {},
    onToggleTimerPause: () -> Unit = {},
    onSwitchOfflineSeat: (PlayerId) -> Unit = {},
    logEntries: List<GameLogEntry> = emptyList(),
    reconnectAttempt: Int = 0,
    onRetryReconnect: () -> Unit = {}
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTableView by rememberSaveable { mutableStateOf(true) }
    var showDiceDialog by remember { mutableStateOf(false) }
    var showLogDrawer by remember { mutableStateOf(false) }
    var kickTargetPlayer by remember { mutableStateOf<PlayerState?>(null) }
    var transferTargetPlayer by remember { mutableStateOf<PlayerState?>(null) }

    androidx.activity.compose.BackHandler {
        showLeaveDialog = true
    }

    val canEndTurn = gameState.turnPlayerId == myPlayerId &&
        gameState.phase == GamePhase.IN_GAME
    val currentTurnPlayer = gameState.turnPlayerId?.let { gameState.players[it] }
    val timerDuration = gameState.settings.turnTimerSeconds
    val turnEndsAt = gameState.turnEndsAt
    val turnStartedAt = remember(turnEndsAt, timerDuration) {
        if (turnEndsAt != null && timerDuration > 0) turnEndsAt - timerDuration * 1000L else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.4f), NeonBackground)
                )
            )
    ) {
        AmbientOrb(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-80).dp),
            color = NeonPrimary, size = 280.dp, alpha = 0.08f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-60).dp, y = 80.dp),
            color = NeonCyan, size = 220.dp, alpha = 0.06f
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.game_label),
                                color = NeonGray100,
                                fontWeight = FontWeight.Bold
                            )
                            if (connectionState == ConnectionState.RECONNECTING) {
                                Text(
                                    text = stringResource(R.string.reconnecting),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonError
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        actionIconContentColor = NeonGray100,
                        navigationIconContentColor = NeonGray100
                    ),
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            listOf(NeonBackground.copy(alpha = 0.96f), Color.Transparent)
                        )
                    ),
                    actions = {
                        val canUndoNow = gameState.canUndo &&
                            gameState.phase == GamePhase.IN_GAME &&
                            (isHost || gameState.turnPlayerId == myPlayerId)
                        if (canUndoNow) {
                            IconButton(
                                onClick = onUndoLast,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = stringResource(R.string.action_undo),
                                    tint = NeonSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { isTableView = !isTableView },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            if (isTableView) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(R.string.switch_to_list_view),
                                    tint = NeonGray200
                                )
                            } else {
                                Icon(
                                    Icons.Default.GroupWork,
                                    contentDescription = stringResource(R.string.switch_to_table_view),
                                    tint = NeonGray200
                                )
                            }
                        }
                        IconButton(
                            onClick = { showDiceDialog = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Casino,
                                contentDescription = stringResource(R.string.roll_dice),
                                tint = NeonGray200
                            )
                        }
                        IconButton(
                            onClick = { showLogDrawer = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = stringResource(R.string.game_log_button),
                                tint = NeonGray200
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.menu),
                                    tint = NeonGray200
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(NeonSurface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings), color = NeonGray100) },
                                    onClick = { showMenu = false; onSettingsClick() },
                                    leadingIcon = { Icon(Icons.Default.Settings, null, tint = NeonPrimary) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        if (isHost) Text(stringResource(R.string.delete_game), color = NeonError)
                                        else Text(stringResource(R.string.leave_game), color = NeonGray100)
                                    },
                                    onClick = { showMenu = false; showLeaveDialog = true },
                                    leadingIcon = {
                                        if (isHost) Icon(Icons.Default.Delete, null, tint = NeonError)
                                        else Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = NeonGray300)
                                    }
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (isTableView) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(GradientNeonPurple))
                            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                            .clickable(onClick = onCombatClick)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚔️", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.combat),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonWhite
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Connection lost / reconnect banner
                AnimatedVisibility(
                    visible = connectionState == ConnectionState.RECONNECTING ||
                        connectionState == ConnectionState.FAILED_PERMANENTLY,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    val isFailed = connectionState == ConnectionState.FAILED_PERMANENTLY
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(NeonError.copy(alpha = 0.10f))
                            .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isFailed) {
                                Icon(
                                    Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = NeonError,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = NeonError
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isFailed)
                                    stringResource(R.string.reconnect_failed)
                                else if (reconnectAttempt > 0)
                                    stringResource(R.string.reconnecting_attempt, reconnectAttempt, 15)
                                else
                                    stringResource(R.string.reconnecting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonError,
                                modifier = Modifier.weight(1f)
                            )
                            if (isFailed) {
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = onRetryReconnect,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Text(stringResource(R.string.reconnect_retry), color = NeonError)
                                }
                            }
                        }
                    }
                }

                // Active combat return banner (when user stepped back to Board while combat is active)
                if (gameState.combat != null) {
                    Surface(
                        onClick = onCombatClick,
                        color = NeonPrimary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, NeonPrimary.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .sizeIn(minHeight = 48.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⚔️", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = stringResource(R.string.return_to_combat),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGray100,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = NeonSecondary
                            )
                        }
                    }
                }

                if (isTableView) {
                    TableScreen(
                        players = gameState.playerList,
                        currentUser = myPlayerId,
                        turnPlayerId = gameState.turnPlayerId,
                        timerSeconds = timerDuration,
                        turnEndsAt = turnEndsAt,
                        serverTimeOffsetMs = serverTimeOffsetMs,
                        isHost = isHost,
                        onPlayerClick = onPlayerClick,
                        onEndTurn = if (canEndTurn) onEndTurn else null,
                        onKickPlayer = onKickPlayer,
                        onTransferHost = onTransferHost,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))

                            if (currentTurnPlayer != null) {
                                val isMyTurn = gameState.turnPlayerId == myPlayerId
                                val turnAccent = if (isMyTurn) NeonPrimary else NeonGray500

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isMyTurn) NeonPrimary.copy(alpha = 0.09f) else GlassBase
                                        )
                                        .border(
                                            width = if (isMyTurn) 1.5.dp else 1.dp,
                                            brush = Brush.linearGradient(
                                                listOf(
                                                    turnAccent.copy(alpha = if (isMyTurn) 0.7f else 0.25f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = turnAccent,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isMyTurn) stringResource(R.string.your_turn)
                                                else stringResource(R.string.player_turn, currentTurnPlayer.name),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isMyTurn) NeonGray100 else NeonGray300,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        val pausedMs = gameState.turnTimerPausedRemainingMs
                                        if (timerDuration > 0 && (turnStartedAt != null || pausedMs != null)) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (pausedMs != null) {
                                                val secs = ((pausedMs + 999L) / 1000L).coerceAtLeast(0L)
                                                val mm = secs / 60
                                                val ss = (secs % 60).toString().padStart(2, '0')
                                                Surface(
                                                    color = NeonSurfaceVariant,
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(1.dp, NeonSecondary)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.timer_paused_format, "$mm:$ss"),
                                                        color = NeonSecondary,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                    )
                                                }
                                            } else if (turnStartedAt != null) {
                                                TurnCountdownBadge(
                                                    turnStartedAt = turnStartedAt,
                                                    timerSeconds = timerDuration,
                                                    serverTimeOffsetMs = serverTimeOffsetMs
                                                )
                                            }
                                            if (isHost || isMyTurn) {
                                                IconButton(
                                                    onClick = onToggleTimerPause,
                                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                ) {
                                                    Icon(
                                                        if (pausedMs != null) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                        contentDescription = stringResource(
                                                            if (pausedMs != null) R.string.timer_resume else R.string.timer_pause
                                                        ),
                                                        tint = NeonSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Offline Pass & Play quick seat switcher
                                if (gameState.joinCode == "LOCAL") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val activeSeatName = gameState.players[myPlayerId]?.name.orEmpty()
                                    Text(
                                        text = stringResource(R.string.offline_active_seat, activeSeatName),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NeonSecondary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        gameState.playerList.forEach { p ->
                                            FilterChip(
                                                selected = p.playerId == myPlayerId,
                                                onClick = { onSwitchOfflineSeat(p.playerId) },
                                                label = {
                                                    Text(
                                                        text = p.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                            )
                                        }
                                    }
                                }

                                if (!currentTurnPlayer.isConnected) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(NeonWarning.copy(alpha = 0.09f))
                                            .border(1.dp, NeonWarning.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.WifiOff,
                                                contentDescription = null,
                                                tint = NeonWarning,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.waiting_for_player, currentTurnPlayer.name),
                                                color = NeonWarning,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Player cards with accessible host reorder + host action overflow menu
                        itemsIndexed(
                            items = gameState.playerList,
                            key = { _, p -> p.playerId.value }
                        ) { index, player ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isHost && gameState.playerList.size > 1) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    val prev = gameState.playerList[index - 1]
                                                    onSwapPlayers(player.playerId, prev.playerId)
                                                }
                                            },
                                            enabled = index > 0,
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.KeyboardArrowUp,
                                                contentDescription = stringResource(R.string.move_player_up),
                                                tint = if (index > 0) NeonPrimary else NeonGray500.copy(alpha = 0.35f)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (index < gameState.playerList.lastIndex) {
                                                    val next = gameState.playerList[index + 1]
                                                    onSwapPlayers(player.playerId, next.playerId)
                                                }
                                            },
                                            enabled = index < gameState.playerList.lastIndex,
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = stringResource(R.string.move_player_down),
                                                tint = if (index < gameState.playerList.lastIndex) NeonPrimary else NeonGray500.copy(alpha = 0.35f)
                                            )
                                        }
                                    }
                                }

                                PlayerCard(
                                    player = player,
                                    isMe = player.playerId == myPlayerId,
                                    isHost = player.playerId == gameState.hostId,
                                    isTurn = player.playerId == gameState.turnPlayerId,
                                    onToggleGender = if (player.playerId == myPlayerId) onToggleGender else null,
                                    onClick = { onPlayerClick(player.playerId) },
                                    onKickPlayer = null,
                                    modifier = Modifier.weight(1f),
                                    actions = {
                                        if (isHost && player.playerId != myPlayerId &&
                                            (onTransferHost != null || onKickPlayer != null)
                                        ) {
                                            var showPlayerMenu by remember(player.playerId) { mutableStateOf(false) }
                                            Box {
                                                IconButton(
                                                    onClick = { showPlayerMenu = true },
                                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.MoreVert,
                                                        contentDescription = stringResource(R.string.player_actions, player.name),
                                                        tint = NeonGray300
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = showPlayerMenu,
                                                    onDismissRequest = { showPlayerMenu = false },
                                                    modifier = Modifier.background(NeonSurface)
                                                ) {
                                                    if (onTransferHost != null) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    stringResource(R.string.transfer_host),
                                                                    color = NeonSecondary
                                                                )
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    Icons.Default.SwapHoriz,
                                                                    contentDescription = null,
                                                                    tint = NeonSecondary
                                                                )
                                                            },
                                                            onClick = {
                                                                showPlayerMenu = false
                                                                transferTargetPlayer = player
                                                            }
                                                        )
                                                    }
                                                    if (onKickPlayer != null) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    stringResource(R.string.kick_player),
                                                                    color = NeonError
                                                                )
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    Icons.Default.PersonRemove,
                                                                    contentDescription = null,
                                                                    tint = NeonError
                                                                )
                                                            },
                                                            onClick = {
                                                                showPlayerMenu = false
                                                                kickTargetPlayer = player
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Docked bottom bar for List View — prevents any overlap between End Turn and Combat CTA
                    Surface(
                        color = NeonSurface.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, GlassBorderDim),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .navigationBarsPadding(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canEndTurn) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 50.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Brush.linearGradient(GradientViridian))
                                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                        .clickable(onClick = onEndTurn)
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.SkipNext,
                                            contentDescription = null,
                                            tint = NeonWhite,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.end_turn),
                                            color = NeonWhite,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .sizeIn(minWidth = 48.dp, minHeight = 50.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.linearGradient(GradientNeonPurple))
                                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                    .clickable(onClick = onCombatClick)
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text("⚔️", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.combat),
                                        color = NeonWhite,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Quick Dice Dialog — broadcasts PLAYER_ROLL to all players
    if (showDiceDialog) {
        DiceRollerDialog(
            onDismiss = { showDiceDialog = false },
            showModifier = false,
            onRollComplete = { roll, _ ->
                onRollDice(roll)
                showDiceDialog = false
            }
        )
    }

    // Game Log Drawer
    if (showLogDrawer) {
        GameLogDrawer(
            logEntries = logEntries,
            onDismiss = { showLogDrawer = false }
        )
    }

    // Host single- or multi-winner confirmation chooser
    if (pendingWinners.isNotEmpty() && isHost && gameState.phase != GamePhase.FINISHED) {
        if (pendingWinners.size == 1) {
            val winner = pendingWinners.first()
            AlertDialog(
                onDismissRequest = { onDismissWin(winner.playerId) },
                containerColor = NeonSurface,
                title = {
                    Text(stringResource(R.string.confirm_victory_title), color = NeonGray100)
                },
                text = {
                    Text(
                        stringResource(R.string.confirm_victory_message, winner.name),
                        color = NeonGray300
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onConfirmWin(winner.playerId) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.confirm_action), color = NeonPrimary)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onDismissWin(winner.playerId) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonGray400)
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { onDismissWin(null) },
                containerColor = NeonSurface,
                title = {
                    Text(stringResource(R.string.choose_winner_title), color = NeonGray100)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.choose_winner_message),
                            color = NeonGray300,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        pendingWinners.forEach { candidate ->
                            Surface(
                                onClick = { onConfirmWin(candidate.playerId) },
                                color = NeonSurfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, NeonSecondary.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = candidate.name,
                                        color = NeonGray100,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.standings_stats_format,
                                            candidate.level,
                                            candidate.combatPower,
                                            candidate.treasures
                                        ),
                                        color = NeonSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { onDismissWin(null) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonGray400)
                    }
                }
            )
        }
    }

    // Victory / Game Over Dialog when phase == FINISHED
    if (gameState.phase == GamePhase.FINISHED) {
        val winner = gameState.winnerId?.let { gameState.players[it] }
            ?: gameState.playerList.maxByOrNull { it.level }
        val standings = remember(gameState.players) {
            gameState.playerList.sortedWith(
                compareByDescending<PlayerState> { it.playerId == gameState.winnerId }
                    .thenByDescending { it.level }
                    .thenByDescending { it.combatPower }
                    .thenByDescending { it.treasures }
            )
        }
        AlertDialog(
            onDismissRequest = {},
            containerColor = NeonSurface,
            title = {
                Text(
                    stringResource(R.string.victory_dialog_title),
                    color = NeonWarning,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.victory_dialog_winner, winner?.name ?: "?"),
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGray100,
                        fontWeight = FontWeight.SemiBold
                    )
                    val summary = gameState.matchSummary
                    if (summary != null) {
                        val mins = maxOf(1, (summary.durationMs / 60_000L).toInt())
                        Text(
                            text = "⏱️ ${stringResource(R.string.stats_duration_min, mins)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val slayerName = summary.mvpSlayerId?.let { gameState.players[it]?.name }
                        val helperName = summary.bestHelperId?.let { gameState.players[it]?.name }
                        val deathsName = summary.mostDeathsId?.let { gameState.players[it]?.name }
                        if (slayerName != null) {
                            Text(
                                text = stringResource(R.string.stats_mvp_slayer, slayerName),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (helperName != null) {
                            Text(
                                text = stringResource(R.string.stats_best_helper, helperName),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonGray200
                            )
                        }
                        if (deathsName != null) {
                            Text(
                                text = stringResource(R.string.stats_most_deaths, deathsName),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonError
                            )
                        }
                    }
                    HorizontalDivider(color = GlassBorder)
                    val statsById = remember(summary) {
                        summary?.playerStats?.associateBy { it.playerId } ?: emptyMap()
                    }
                    standings.forEachIndexed { idx, p ->
                        val medal = when (idx) {
                            0 -> "🥇"
                            1 -> "🥈"
                            2 -> "🥉"
                            else -> "#${idx + 1}"
                        }
                        val pStat = statsById[p.playerId]
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$medal ${p.name}",
                                    color = if (idx == 0) NeonWarning else NeonGray200,
                                    fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.standings_stats_format,
                                        p.level,
                                        p.combatPower,
                                        p.treasures
                                    ),
                                    color = NeonGray300,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (pStat != null && (pStat.monstersKilled > 0 || pStat.deaths > 0)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (pStat.monstersKilled > 0) {
                                        Text(
                                            text = stringResource(R.string.stats_kills_badge, pStat.monstersKilled),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonSecondary
                                        )
                                    }
                                    if (pStat.deaths > 0) {
                                        Text(
                                            text = stringResource(R.string.stats_deaths_badge, pStat.deaths),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonError
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onLeaveGame,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPrimary)
                ) {
                    Text(
                        stringResource(R.string.return_to_home),
                        color = NeonWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    // Transfer Host Confirmation
    transferTargetPlayer?.let { target ->
        AlertDialog(
            onDismissRequest = { transferTargetPlayer = null },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.transfer_host), color = NeonGray100) },
            text = { Text(stringResource(R.string.confirm_transfer_host, target.name), color = NeonGray300) },
            confirmButton = {
                TextButton(
                    onClick = {
                        transferTargetPlayer = null
                        onTransferHost?.invoke(target.playerId)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.confirm_action), color = NeonSecondary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { transferTargetPlayer = null },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray400)
                }
            }
        )
    }

    // Kick Player Confirmation
    kickTargetPlayer?.let { target ->
        AlertDialog(
            onDismissRequest = { kickTargetPlayer = null },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.kick_player), color = NeonGray100) },
            text = { Text(stringResource(R.string.confirm_kick, target.name), color = NeonGray300) },
            confirmButton = {
                TextButton(
                    onClick = {
                        kickTargetPlayer = null
                        onKickPlayer?.invoke(target.playerId)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.confirm_action), color = NeonError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { kickTargetPlayer = null },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray400)
                }
            }
        )
    }

    // Leave / Delete Confirmation Dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            containerColor = NeonSurface,
            title = {
                Text(
                    if (isHost) stringResource(R.string.delete_game_title) else stringResource(R.string.leave_game),
                    color = NeonGray100
                )
            },
            text = {
                Text(
                    if (isHost) stringResource(R.string.delete_game_confirm)
                    else stringResource(R.string.confirm_leave),
                    color = NeonGray300
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        if (isHost) onDeleteGame() else onLeaveGame()
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        if (isHost) stringResource(R.string.delete_action) else stringResource(R.string.yes),
                        color = if (isHost) NeonError else NeonPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveDialog = false },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.no), color = NeonGray400)
                }
            }
        )
    }
}
