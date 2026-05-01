package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.isActive
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.PlayerCard
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.ui.theme.*

/**
 * Main game board showing all players and their stats.
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
    onCatalogClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLeaveGame: () -> Unit,
    onDeleteGame: () -> Unit = {},
    pendingWinnerId: PlayerId? = null,
    onConfirmWin: (PlayerId) -> Unit = {},
    onDismissWin: () -> Unit = {},
    onEndTurn: () -> Unit = {},
    onToggleGender: () -> Unit = {},
    onSwapPlayers: (PlayerId, PlayerId) -> Unit = { _, _ -> },
    onKickPlayer: ((PlayerId) -> Unit)? = null,
    logEntries: List<com.munchkin.app.core.GameLogEntry> = emptyList(),
    reconnectAttempt: Int = 0,
    onRetryReconnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTableView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    var showDiceDialog by remember { mutableStateOf(false) }
    var showLogDrawer by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler {
        showLeaveDialog = true
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
        // Ambient orbs
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
                        IconButton(onClick = { isTableView = !isTableView }) {
                            if (isTableView) {
                                Icon(Icons.Default.List, contentDescription = "Vista Lista", tint = NeonGray200)
                            } else {
                                Icon(Icons.Default.GroupWork, contentDescription = "Vista Mesa", tint = NeonGray200)
                            }
                        }
                        IconButton(onClick = { showDiceDialog = true }) {
                            Text("🎲", fontSize = 22.sp)
                        }
                        IconButton(onClick = { showLogDrawer = true }) {
                            Icon(Icons.Default.History, contentDescription = "Historial", tint = NeonGray200)
                        }
                        IconButton(onClick = onCatalogClick) {
                            Icon(Icons.Default.MenuBook, contentDescription = stringResource(R.string.catalog), tint = NeonGray200)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menú", tint = NeonGray200)
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(GradientNeonPurple))
                        .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onCombatClick() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚔️", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.combat),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                                    TextButton(onClick = onRetryReconnect) {
                                        Text(stringResource(R.string.reconnect_retry), color = NeonError)
                                    }
                                }
                            }
                        }
                    }

                    if (isTableView) {
                        val canEndTurn = gameState.turnPlayerId == myPlayerId &&
                                gameState.phase == com.munchkin.app.core.GamePhase.IN_GAME
                        TableScreen(
                            players = gameState.playerList,
                            currentUser = myPlayerId,
                            turnPlayerId = gameState.turnPlayerId,
                            onPlayerClick = onPlayerClick,
                            onEndTurn = if (canEndTurn) onEndTurn else null,
                            modifier = Modifier.weight(1f).fillMaxWidth()
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

                                val currentTurnPlayer = gameState.turnPlayerId?.let { gameState.players[it] }
                                val timerDuration = gameState.settings.turnTimerSeconds
                                val turnEndsAt = gameState.turnEndsAt

                                var currentTimeMs by remember(turnEndsAt, gameState.turnPlayerId) {
                                    mutableLongStateOf(System.currentTimeMillis())
                                }
                                val remainingSeconds = if (timerDuration > 0 && currentTurnPlayer != null && turnEndsAt != null) {
                                    (((turnEndsAt - currentTimeMs).coerceAtLeast(0L) + 999L) / 1000L).toInt()
                                } else {
                                    0
                                }

                                if (timerDuration > 0 && currentTurnPlayer != null && turnEndsAt != null) {
                                    LaunchedEffect(turnEndsAt, gameState.turnPlayerId, timerDuration) {
                                        var lastAnnouncedSecond: Int? = null
                                        while (isActive) {
                                            currentTimeMs = System.currentTimeMillis()
                                            val secondsLeft = (((turnEndsAt - currentTimeMs).coerceAtLeast(0L) + 999L) / 1000L).toInt()

                                            if (secondsLeft != lastAnnouncedSecond) {
                                                if (secondsLeft == 10) {
                                                    com.munchkin.app.ui.components.SoundManager.playTurnStart()
                                                }
                                                if (secondsLeft in 1..5) {
                                                    com.munchkin.app.ui.components.SoundManager.playButtonClick()
                                                }
                                                lastAnnouncedSecond = secondsLeft
                                            }

                                            if (secondsLeft <= 0) {
                                                break
                                            }

                                            kotlinx.coroutines.delay(250)
                                        }
                                        currentTimeMs = System.currentTimeMillis()
                                    }
                                }

                                if (currentTurnPlayer != null) {
                                    val isMyTurn = gameState.turnPlayerId == myPlayerId
                                    val timerColor = when {
                                        turnEndsAt == null -> NeonGray400
                                        remainingSeconds <= 10 -> NeonError
                                        remainingSeconds <= 30 -> NeonWarning
                                        else -> NeonPrimary
                                    }
                                    val turnAccent = if (isMyTurn) NeonPrimary else NeonGray500

                                    // Glass turn indicator
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
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = turnAccent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (isMyTurn) "¡Tu turno!" else "Turno de ${currentTurnPlayer.name}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isMyTurn) NeonGray100 else NeonGray300
                                                )
                                            }
                                            if (timerDuration > 0 && turnEndsAt != null) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.Timer,
                                                        contentDescription = null,
                                                        tint = timerColor,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = timerColor
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Waiting for disconnected player banner
                                    if (!currentTurnPlayer.isConnected) {
                                        Spacer(modifier = Modifier.height(4.dp))
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

                            // Player cards
                            itemsIndexed(
                                items = gameState.playerList,
                                key = { _, p -> p.playerId.value }
                            ) { index, player ->
                                if (isHost && gameState.playerList.size > 1) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (index > 0) {
                                            IconButton(
                                                onClick = {
                                                    val prev = gameState.playerList[index - 1]
                                                    onSwapPlayers(player.playerId, prev.playerId)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, null, tint = NeonGray500)
                                            }
                                        }
                                        if (index < gameState.playerList.lastIndex) {
                                            IconButton(
                                                onClick = {
                                                    val next = gameState.playerList[index + 1]
                                                    onSwapPlayers(player.playerId, next.playerId)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, null, tint = NeonGray500)
                                            }
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
                                    onKickPlayer = if (isHost && !player.isConnected && player.playerId != myPlayerId)
                                        { { onKickPlayer?.invoke(player.playerId) } } else null
                                )
                            }

                            // End Turn button
                            item {
                                if (gameState.turnPlayerId == myPlayerId &&
                                    gameState.phase == com.munchkin.app.core.GamePhase.IN_GAME) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Brush.linearGradient(GradientViridian))
                                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable { onEndTurn() },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.SkipNext,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                stringResource(R.string.end_turn),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                        }
                                    }
                                }
                            }

                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }

    // Dice Dialog
    if (showDiceDialog) {
        com.munchkin.app.ui.components.DiceRollerDialog(
            onDismiss = { showDiceDialog = false }
        )
    }

    // Game Log Drawer
    if (showLogDrawer) {
        com.munchkin.app.ui.components.GameLogDrawer(
            logEntries = logEntries,
            onDismiss = { showLogDrawer = false }
        )
    }

    val pendingWinner = pendingWinnerId?.let { gameState.players[it] }
    if (pendingWinner != null && isHost) {
        AlertDialog(
            onDismissRequest = onDismissWin,
            containerColor = NeonSurface,
            title = {
                Text("Confirmar victoria", color = NeonGray100)
            },
            text = {
                Text(
                    "${pendingWinner.name} ha alcanzado el nivel máximo. ¿Quieres cerrar la partida y registrar la victoria?",
                    color = NeonGray300
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmWin(pendingWinner.playerId) }) {
                    Text("Confirmar", color = NeonPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissWin) {
                    Text(stringResource(R.string.cancel), color = NeonGray400)
                }
            }
        )
    }

    // Leave/Delete Confirmation Dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            containerColor = NeonSurface,
            title = {
                Text(
                    if (isHost) "Borrar Partida" else stringResource(R.string.leave_game),
                    color = NeonGray100
                )
            },
            text = {
                Text(
                    if (isHost) "¿Estás seguro de que quieres borrar la partida? Todos los jugadores serán desconectados."
                    else stringResource(R.string.confirm_leave),
                    color = NeonGray300
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        if (isHost) onDeleteGame() else onLeaveGame()
                    }
                ) {
                    Text(
                        if (isHost) "Borrar" else stringResource(R.string.yes),
                        color = if (isHost) NeonError else NeonPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.no), color = NeonGray400)
                }
            }
        )
    }
}
