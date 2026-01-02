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
    onRollDice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    
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
            
            // Player list
            items(
                items = gameState.playerList,
                key = { it.playerId.value }
            ) { player ->
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally() + fadeIn(),
                    exit = slideOutHorizontally() + fadeOut()
                ) {
                    PlayerCard(
                        player = player,
                        isMe = player.playerId == myPlayerId,
                        isHost = player.playerId == gameState.hostId,
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
                                    Text("Lanzar 🎲", fontWeight = FontWeight.Bold)
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
}
