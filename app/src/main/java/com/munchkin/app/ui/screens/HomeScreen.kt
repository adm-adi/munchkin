package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.data.SavedGame
import com.munchkin.app.ui.components.*
import com.munchkin.app.ui.theme.*
import com.munchkin.app.update.UpdateInfo

/**
 * Modern Luma-inspired home screen.
 */
@Composable
fun HomeScreen(
    savedGame: SavedGame?,
    updateInfo: UpdateInfo?,
    isDownloading: Boolean,
    isLoading: Boolean = false,
    error: String? = null,
    onCreateGame: () -> Unit,
    onJoinGame: () -> Unit,
    onResumeGame: () -> Unit,
    onDeleteSavedGame: () -> Unit,
    onClearError: () -> Unit = {},
    onDownloadUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onSettings: () -> Unit,
    onAuth: () -> Unit,
    onLogout: () -> Unit,
    onLeaderboardClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onProfileClick: () -> Unit,
    userProfile: com.munchkin.app.network.UserProfile?,
    hostedGames: List<com.munchkin.app.network.HostedGame> = emptyList(),
    onDeleteHostedGame: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Show update dialog if update available
    var showUpdateDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(updateInfo) {
        if (updateInfo != null) {
            showUpdateDialog = true
        }
    }
    
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo,
            isDownloading = isDownloading,
            onDownload = onDownloadUpdate,
            onDismiss = {
                showUpdateDialog = false
                onDismissUpdate()
            }
        )
    }
    
    // Snackbar for errors
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(
                message = error,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            onClearError()
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NeonBackground,
                        NeonBackground.copy(alpha = 0.95f),
                        NeonBackground
                    )
                )
            )
    ) {
        // Ambient glow effect (top)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-100).dp)
                .size(400.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            NeonPrimary.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.3f))
            
            // Logo with glow
            Box(
                contentAlignment = Alignment.Center
            ) {
                // Glow behind emoji
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    NeonPrimary.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Text(
                    text = "⚔️",
                    fontSize = 72.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Title with gradient
            Text(
                text = "Munchkin",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                color = NeonGray100
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Main actions
            GradientButton(
                text = stringResource(R.string.create_game),
                onClick = onCreateGame,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Add,
                gradientColors = GradientNeonPurple
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            GlassOutlinedButton(
                text = stringResource(R.string.join_game),
                onClick = onJoinGame,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.QrCodeScanner
            )
            
            // Saved game section
            AnimatedVisibility(
                visible = savedGame != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                savedGame?.let { saved ->
                    Column {
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        GlassCard(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Partida guardada",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NeonGray100
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${saved.gameState.players.size} jugadores • ${saved.gameState.joinCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NeonGray500
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            GradientButton(
                                text = if (isLoading) "Conectando..." else "Continuar",
                                onClick = onResumeGame,
                                modifier = Modifier.fillMaxWidth(),
                                icon = if (isLoading) null else Icons.Default.PlayArrow,
                                gradientColors = GradientNeonFire,
                                enabled = !isLoading
                            )
                            
                            if (saved.isHost) {
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                OutlinedButton(
                                    onClick = onDeleteSavedGame,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.delete_party))
                                }
                            }
                        }
                    }
                }
            }

            // Hosted Games Section
            AnimatedVisibility(
                visible = hostedGames.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(
                        text = "Tus Partidas Activas",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGray200,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    hostedGames.forEach { game ->
                        HostedGameCard(
                            game = game,
                            onDelete = { onDeleteHostedGame(game.gameId) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Version
            Text(
                text = "v${com.munchkin.app.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = NeonGray500
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Top Bar (Auth + Settings) - Positioned AFTER Column to be on top for touch events
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Auth Profile Button
            Box {
                var showMenu by remember { mutableStateOf(false) }
                
                TextButton(onClick = { 
                    if (userProfile != null) {
                        showMenu = true
                    } else {
                        onAuth()
                    }
                }) {
                    if (userProfile != null) {
                        Text(
                            text = userProfile.username,
                            color = NeonSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Iniciar Sesión",
                            color = NeonGray400
                        )
                    }
                }
                
                if (userProfile != null) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(NeonSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logout), color = Color.White) },
                            onClick = {
                                showMenu = false
                                onLogout()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Logout, null, tint = NeonError)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.global_ranking), color = Color.White) },
                            onClick = {
                                showMenu = false
                                onLeaderboardClick()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Leaderboard, null, tint = NeonSecondary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.my_games), color = Color.White) },
                            onClick = {
                                showMenu = false
                                onHistoryClick()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.History, null, tint = NeonWarning)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings), color = Color.White) },
                            onClick = {
                                showMenu = false
                                onSettings()
                            },
                             leadingIcon = {
                                Icon(Icons.Default.Settings, null, tint = NeonWarning)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.view_profile), color = Color.White) },
                            onClick = {
                                showMenu = false
                            },
                             leadingIcon = {
                                Icon(Icons.Default.Person, null, tint = NeonPrimary)
                            }
                        )
                    }
                }
            }
            
            Spacer(Modifier.width(8.dp))
            
            // Settings Button
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = NeonGray400
                )
            }
        }
        
        // Snackbar Host at bottom
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

@Composable
private fun HostedGameCard(
    game: com.munchkin.app.network.HostedGame,
    onDelete: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("¿Borrar partida?") },
            text = { Text("Esto eliminará la partida para todos los jugadores. No se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.joinCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${game.playerCount} jugadores • ${game.phase}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray400
                )
            }
            
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Borrar partida",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
