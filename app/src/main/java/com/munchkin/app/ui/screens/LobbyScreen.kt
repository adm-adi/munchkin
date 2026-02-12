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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.ui.components.ConnectionInfoCard
import com.munchkin.app.ui.components.PlayerCard
import com.munchkin.app.viewmodel.ConnectionInfo

/**
 * Lobby screen showing connected players and QR code for joining.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    gameState: GameState,
    myPlayerId: PlayerId,
    isHost: Boolean,
    connectionInfo: ConnectionInfo?,
    onStartGame: () -> Unit,
    onLeaveGame: () -> Unit,
    onDeleteGame: () -> Unit = {},
    onRollDice: () -> Unit = {},
    onSwapPlayers: (PlayerId, PlayerId) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showLeaveDialog by remember { mutableStateOf(false) }

    // Handle Back Press
    androidx.activity.compose.BackHandler {
        showLeaveDialog = true
    }
    
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lobby)) },
                actions = {
                    IconButton(onClick = { showLeaveDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = stringResource(R.string.leave_game)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (isHost) {
                Surface(
                    shadowElevation = 8.dp,
                    tonalElevation = 3.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = onStartGame,
                            enabled = gameState.canStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.start_game))
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connection info for host
            if (isHost && connectionInfo != null) {
                item {
                    ConnectionInfoCard(
                        wsUrl = connectionInfo.wsUrl,
                        joinCode = connectionInfo.joinCode,
                        localIp = connectionInfo.localIp,
                        port = connectionInfo.port,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            
            // Players header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.players),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${gameState.players.size}/6",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Player list with reorder controls for host
            itemsIndexed(
                items = gameState.playerList,
                key = { _, p -> p.playerId.value }
            ) { index, player ->

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Reorder controls for host
                        if (isHost && gameState.playerList.size > 1) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                // Move up button
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val prevPlayer = gameState.playerList[index - 1]
                                            onSwapPlayers(player.playerId, prevPlayer.playerId)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move up",
                                        tint = if (index > 0) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                }
                                // Position indicator
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Move down button
                                IconButton(
                                    onClick = {
                                        if (index < gameState.playerList.size - 1) {
                                            val nextPlayer = gameState.playerList[index + 1]
                                            onSwapPlayers(player.playerId, nextPlayer.playerId)
                                        }
                                    },
                                    enabled = index < gameState.playerList.size - 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move down",
                                        tint = if (index < gameState.playerList.size - 1) MaterialTheme.colorScheme.primary 
                                               else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                        
                        // Player card
                        PlayerCard(
                            player = player,
                            isMe = player.playerId == myPlayerId,
                            isHost = player.playerId == gameState.hostId,
                            showDisconnectedBadge = false,
                            modifier = Modifier.weight(1f),
                            actions = {
                                if (player.lastRoll != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = com.munchkin.app.ui.theme.NeonSecondary.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "🎲 ${player.lastRoll}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = com.munchkin.app.ui.theme.NeonSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else if (player.playerId == myPlayerId) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = onRollDice,
                                        modifier = Modifier.height(40.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = com.munchkin.app.ui.theme.NeonPrimary,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Text(stringResource(R.string.roll_dice), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        )
                    }
            }
            
            // Waiting message
            if (!gameState.canStart) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.waiting_players),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Spacer for bottom bar
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
    
    // Leave confirmation dialog
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
