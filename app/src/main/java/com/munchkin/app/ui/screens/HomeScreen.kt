package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.data.SavedGame
import com.munchkin.app.ui.components.*
import com.munchkin.app.ui.theme.*
import com.munchkin.app.update.UpdateInfo

/**
 * Dungeon Edition home screen.
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
    onLeaderboardClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onProfileClick: () -> Unit,
    userProfile: com.munchkin.app.network.UserProfile?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null,
    onClearError: () -> Unit = {},
    hostedGames: List<com.munchkin.app.network.HostedGame> = emptyList(),
    onResumeHostedGame: (String) -> Unit = {},
    onDeleteHostedGame: (String) -> Unit = {},
    onCatalogClick: () -> Unit = {}
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
        ) {
            // Non-overlapping top bar (Auth + Settings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    var showMenu by remember { mutableStateOf(false) }

                    TextButton(
                        onClick = {
                            if (userProfile != null) {
                                showMenu = true
                            } else {
                                onAuth()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        if (userProfile != null) {
                            Text(
                                text = userProfile.username,
                                color = NeonSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.login_title),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = { Text(stringResource(R.string.view_profile), color = NeonGray100) },
                                onClick = {
                                    showMenu = false
                                    onProfileClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, null, tint = NeonPrimary)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.global_ranking), color = NeonGray100) },
                                onClick = {
                                    showMenu = false
                                    onLeaderboardClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Leaderboard, null, tint = NeonSecondary)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.my_games), color = NeonGray100) },
                                onClick = {
                                    showMenu = false
                                    onHistoryClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.History, null, tint = NeonWarning)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings), color = NeonGray100) },
                                onClick = {
                                    showMenu = false
                                    onSettings()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, null, tint = NeonWarning)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logout), color = NeonGray100) },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Logout, null, tint = NeonError)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Scrollable main content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo with glow
                Box(contentAlignment = Alignment.Center) {
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

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp
                    ),
                    color = NeonGray100
                )

                // Dismissible inline error banner (Snackbar lives in MainActivity)
                if (error != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(NeonError.copy(alpha = 0.14f))
                            .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = NeonError,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = error,
                            color = NeonGray100,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearError) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = NeonGray200
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Main actions
                GradientButton(
                    text = stringResource(R.string.create_game),
                    onClick = onCreateGame,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Add,
                    gradientColors = GradientNeonPurple
                )

                Spacer(modifier = Modifier.height(14.dp))

                GlassOutlinedButton(
                    text = stringResource(R.string.join_game),
                    onClick = onJoinGame,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.QrCodeScanner
                )

                Spacer(modifier = Modifier.height(12.dp))

                GlassOutlinedButton(
                    text = stringResource(R.string.monster_catalog),
                    onClick = onCatalogClick,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.CollectionsBookmark
                )

                // Saved game section
                AnimatedVisibility(
                    visible = savedGame != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    savedGame?.let { saved ->
                        Column {
                            Spacer(modifier = Modifier.height(28.dp))

                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.saved_game_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NeonGray100
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.saved_game_summary,
                                        saved.gameState.players.size,
                                        saved.gameState.joinCode
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                GradientButton(
                                    text = if (isLoading) {
                                        stringResource(R.string.connecting)
                                    } else {
                                        stringResource(R.string.continue_game)
                                    },
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
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
                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = stringResource(R.string.active_hosted_games),
                            style = MaterialTheme.typography.titleMedium,
                            color = NeonGray200,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        hostedGames.forEach { game ->
                            HostedGameCard(
                                game = game,
                                onResume = { onResumeHostedGame(game.joinCode) },
                                onDelete = { onDeleteHostedGame(game.gameId) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Version
                Text(
                    text = "v${com.munchkin.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HostedGameCard(
    game: com.munchkin.app.network.HostedGame,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.delete_game_title)) },
            text = { Text(stringResource(R.string.delete_game_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onResume
    ) {
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
                    text = "${game.playerCount} ${stringResource(R.string.players).lowercase()} • ${game.phase}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onResume) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.resume_hosted_game),
                    tint = NeonPrimary
                )
            }
            
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_game_title),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                )
            }
        }
    }
}
