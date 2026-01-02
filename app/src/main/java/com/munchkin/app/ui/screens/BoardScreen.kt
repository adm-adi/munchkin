package com.munchkin.app.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.ui.components.PlayerCard
import com.munchkin.app.network.ConnectionState

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
    logEntries: List<com.munchkin.app.core.GameLogEntry> = emptyList(),
    modifier: Modifier = Modifier
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTableView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) } // Default to Visual Dungeon
    
    // State for Dice Dialog
    var showDiceDialog by remember { mutableStateOf(false) }
    // State for Game Log
    var showLogDrawer by remember { mutableStateOf(false) }

    // Handle Back Press
    androidx.activity.compose.BackHandler {
        showLeaveDialog = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Partida")
                        if (connectionState == ConnectionState.RECONNECTING) {
                            Text(
                                text = stringResource(R.string.reconnecting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
                    // View Toggle (Table/List)
                    IconButton(onClick = { isTableView = !isTableView }) {
                        if (isTableView) {
                            Icon(Icons.Default.List, contentDescription = "Vista Lista")
                        } else {
                            Icon(Icons.Default.GroupWork, contentDescription = "Vista Mesa")
                        }
                    }
                    
                    // Dice Button
                    IconButton(onClick = { showDiceDialog = true }) {
                        Text("🎲", fontSize = 24.sp)
                    }
                    // History / Log Button
                    IconButton(onClick = { showLogDrawer = true }) {
                        Icon(Icons.Default.History, contentDescription = "Historial")
                    }
                    IconButton(onClick = onCatalogClick) {
                        Icon(Icons.Default.MenuBook, contentDescription = stringResource(R.string.catalog))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menú")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    showMenu = false
                                    onSettingsClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    if (isHost) Text("Borrar Partida") else Text(stringResource(R.string.leave_game)) 
                                },
                                onClick = {
                                    showMenu = false
                                    showLeaveDialog = true
                                },
                                leadingIcon = {
                                    if (isHost) Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    else Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCombatClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text("⚔️", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.combat))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Connection lost banner
                AnimatedVisibility(
                    visible = connectionState == ConnectionState.RECONNECTING,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.reconnecting),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                if (isTableView) {
                    val canEndTurn = gameState.turnPlayerId == myPlayerId && gameState.phase == com.munchkin.app.core.GamePhase.IN_GAME
                    TableScreen(
                        players = gameState.players.values.toList(),
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
                        
                        // Turn indicator banner with timer
                        val currentTurnPlayer = gameState.turnPlayerId?.let { gameState.players[it] }
                        val timerDuration = gameState.settings.turnTimerSeconds
                        
                        // Timer countdown state - resets when turn changes
                        var remainingSeconds by remember(gameState.turnPlayerId) { 
                            mutableIntStateOf(timerDuration) 
                        }
                        
                        // Countdown effect when timer is enabled
                        if (timerDuration > 0 && currentTurnPlayer != null) {
                            LaunchedEffect(gameState.turnPlayerId, timerDuration) {
                                remainingSeconds = timerDuration
                                while (remainingSeconds > 0) {
                                    kotlinx.coroutines.delay(1000)
                                    remainingSeconds--
                                    // Warning sound at 10 seconds
                                    if (remainingSeconds == 10) {
                                        com.munchkin.app.ui.components.SoundManager.playTurnStart()
                                    }
                                    // Critical warning at 5 seconds
                                    if (remainingSeconds <= 5 && remainingSeconds > 0) {
                                        com.munchkin.app.ui.components.SoundManager.playButtonClick()
                                    }
                                }
                            }
                        }
                        
                        if (currentTurnPlayer != null) {
                            val isMyTurn = gameState.turnPlayerId == myPlayerId
                            val timerColor = when {
                                timerDuration == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                remainingSeconds <= 10 -> MaterialTheme.colorScheme.error
                                remainingSeconds <= 30 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isMyTurn) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = if (isMyTurn) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isMyTurn) "¡Tu turno!" else "Turno de ${currentTurnPlayer.name}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isMyTurn) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // Timer display
                                    if (timerDuration > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Timer,
                                                contentDescription = null,
                                                tint = timerColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = timerColor
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    
                    // Player cards
                    itemsIndexed(
                        items = gameState.playerList,
                        key = { _, p -> p.playerId.value }
                    ) { index, player ->
                        val isMe = player.playerId == myPlayerId
                        
                        // Host Controls for Reordering (Visible only to Host)
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
                                        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.Gray)
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
                                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Gray)
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
                            onClick = { onPlayerClick(player.playerId) }
                        )
                    }
                    
                    // Turn Action Button
                    item {
                        if (gameState.turnPlayerId == myPlayerId && gameState.phase == com.munchkin.app.core.GamePhase.IN_GAME) {
                            Button(
                                onClick = onEndTurn,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Terminar Turno")
                            }
                        }
                    }
                    
                    // Spacer for FAB
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
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


    // Leave/Delete Confirmation Dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { 
                Text(if (isHost) "Borrar Partida" else stringResource(R.string.leave_game)) 
            },
            text = { 
                Text(if (isHost) "¿Estás seguro de que quieres borrar la partida? Todos los jugadores serán desconectados." else stringResource(R.string.confirm_leave)) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        if (isHost) onDeleteGame() else onLeaveGame()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isHost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isHost) "Borrar" else stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }
}
