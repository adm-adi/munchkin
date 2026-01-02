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
    onPlayerClick: () -> Unit,
    onCombatClick: () -> Unit,
    onCatalogClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLeaveGame: () -> Unit,
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
    
    // State for Dice Dialog
    var showDiceDialog by remember { mutableStateOf(false) }
    // State for Game Log
    var showLogDrawer by remember { mutableStateOf(false) }


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
                                text = { Text(stringResource(R.string.leave_game)) },
                                onClick = {
                                    showMenu = false
                                    showLeaveDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
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
                
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Turn indicator banner
                        val currentTurnPlayer = gameState.turnPlayerId?.let { gameState.players[it] }
                        if (currentTurnPlayer != null) {
                            val isMyTurn = gameState.turnPlayerId == myPlayerId
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
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isMyTurn) 
                                            MaterialTheme.colorScheme.primary 
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isMyTurn) "¡Tu turno!" else "Turno de ${currentTurnPlayer.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isMyTurn) 
                                            MaterialTheme.colorScheme.primary 
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                            onClick = { onPlayerClick() } // For now generic click
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

            // Dice FAB (Bottom Right - Fixed Overlap)
            FloatingActionButton(
                onClick = { showDiceDialog = true },
                containerColor = Color(0xFFFF9800),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("🎲", fontSize = 24.sp)
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
}
