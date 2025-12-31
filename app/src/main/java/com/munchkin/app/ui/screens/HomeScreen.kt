package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onCreateGame: () -> Unit,
    onJoinGame: () -> Unit,
    onResumeGame: () -> Unit,
    onDeleteSavedGame: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onSettings: () -> Unit,
    onAuth: () -> Unit,
    onLogout: () -> Unit,
    userProfile: com.munchkin.app.network.UserProfile?,
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
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        LumaGray950,
                        LumaGray900.copy(alpha = 0.95f),
                        LumaGray950
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
                            LumaPrimary.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )
        
        // Settings button
        // Top Bar (Auth + Settings)
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
                            color = Gold400,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Iniciar Sesión",
                            color = LumaGray400
                        )
                    }
                }
                
                if (userProfile != null) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cerrar sesión", color = Color.White) },
                            onClick = {
                                showMenu = false
                                onLogout()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Logout, null, tint = Color.Red)
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
                    tint = LumaGray400
                )
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                                    LumaPrimary.copy(alpha = 0.3f),
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
                color = LumaGray50
            )
            
            Text(
                text = "Mesa Tracker",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                ),
                color = LumaGray500
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Main actions
            GradientButton(
                text = stringResource(R.string.create_game),
                onClick = onCreateGame,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Add,
                gradientColors = listOf(LumaPrimary, GradientPurpleEnd)
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
                                        color = LumaGray100
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${saved.gameState.players.size} jugadores • ${saved.gameState.joinCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LumaGray500
                                    )
                                }
                                
                                IconButton(
                                    onClick = onDeleteSavedGame,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Eliminar",
                                        tint = LumaGray500,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            GradientButton(
                                text = "Continuar",
                                onClick = onResumeGame,
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Default.PlayArrow,
                                gradientColors = listOf(LumaAccent, GradientOrangeEnd)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Version
            Text(
                text = "v1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = LumaGray700
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
