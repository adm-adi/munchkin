package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    modifier: Modifier = Modifier
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    
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
                Icon(Icons.Default.FlashOn, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.combat))
            }
        }
    ) { padding ->
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
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
                .fillMaxSize()
                .padding(padding)
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
            items(
                items = gameState.playerList,
                key = { it.playerId.value }
            ) { player ->
                val isMe = player.playerId == myPlayerId
                
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
    
    // Leave confirmation dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.leave_game)) },
            text = { Text(stringResource(R.string.confirm_leave)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        onLeaveGame()
                    }
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }


    // Win Confirmation Dialog
    if (pendingWinnerId != null) {
        val winnerName = gameState.players[pendingWinnerId]?.name ?: "Jugador"
        
        AlertDialog(
            onDismissRequest = { onDismissWin() },
            title = { Text(text = "🏆 ¿Victoria Confirmada?") },
            text = { 
                Text(
                    text = "$winnerName ha alcanzado el nivel 10.\n\n¿Es este el final de la partida? Esta acción no se puede deshacer.",
                    style = MaterialTheme.typography.bodyLarge
                ) 
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmWin(pendingWinnerId) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("¡Sí, ha ganado!")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onDismissWin() }
                ) {
                    Text("Cancelar / Seguir Jugando")
                }
            }
        )
    }
}
