package com.munchkin.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.JoinCodeCard
import com.munchkin.app.ui.components.GradientButton
import com.munchkin.app.ui.components.PlayerCard
import com.munchkin.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Lobby screen showing connected players and QR code for joining.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    gameState: GameState,
    myPlayerId: PlayerId,
    isHost: Boolean,
    onStartGame: () -> Unit,
    onLeaveGame: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteGame: () -> Unit = {},
    onRollDice: () -> Unit = {},
    onSwapPlayers: (PlayerId, PlayerId) -> Unit = { _, _ -> },
    onKickPlayer: ((PlayerId) -> Unit)? = null,
    onTransferHost: ((PlayerId) -> Unit)? = null,
    connectionState: ConnectionState = ConnectionState.CONNECTED,
    reconnectAttempt: Int = 0,
    onRetryReconnect: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showLeaveDialog by remember { mutableStateOf(false) }
    var kickTargetPlayer by remember { mutableStateOf<PlayerState?>(null) }
    var transferTargetPlayer by remember { mutableStateOf<PlayerState?>(null) }
    var codeCopied by remember { mutableStateOf(false) }

    LaunchedEffect(codeCopied) {
        if (codeCopied) {
            delay(1800)
            codeCopied = false
        }
    }

    androidx.activity.compose.BackHandler {
        showLeaveDialog = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.5f), NeonBackground)
                )
            )
    ) {
        // Ambient orbs
        AmbientOrb(
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-60).dp, y = (-60).dp),
            color = NeonSecondary, size = 260.dp, alpha = 0.08f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 60.dp, y = 60.dp),
            color = NeonPrimary, size = 220.dp, alpha = 0.07f
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.lobby),
                            color = NeonGray100,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        actionIconContentColor = NeonGray100
                    ),
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            listOf(NeonBackground.copy(alpha = 0.96f), Color.Transparent)
                        )
                    ),
                    actions = {
                        IconButton(
                            onClick = { showLeaveDialog = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.leave_game),
                                tint = NeonGray300
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (isHost) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, NeonBackground.copy(alpha = 0.97f))
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .navigationBarsPadding()
                    ) {
                        GradientButton(
                            text = stringResource(R.string.start_game),
                            onClick = onStartGame,
                            enabled = gameState.canStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp),
                            icon = Icons.Default.PlayArrow,
                            gradientColors = GradientNeonPurple
                        )
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
                // Reconnect banner
                val showBanner = connectionState == ConnectionState.RECONNECTING ||
                                 connectionState == ConnectionState.FAILED_PERMANENTLY
                if (showBanner) {
                    item {
                        val isFailed = connectionState == ConnectionState.FAILED_PERMANENTLY
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
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
                }

                // Join code & share controls visible to ALL players
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        JoinCodeCard(
                            joinCode = gameState.joinCode,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(gameState.joinCode))
                                    codeCopied = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .sizeIn(minHeight = 48.dp),
                                border = BorderStroke(1.dp, GlassBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = NeonSurface,
                                    contentColor = if (codeCopied) NeonSuccess else NeonGray100
                                )
                            ) {
                                Icon(
                                    if (codeCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(
                                        if (codeCopied) R.string.copied_to_clipboard else R.string.copy_code
                                    ),
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val shareUrl = ServerConfig.inviteUrl(gameState.joinCode)
                                    val shareText = context.getString(
                                        R.string.share_room_message,
                                        gameState.joinCode,
                                        shareUrl
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            sendIntent,
                                            context.getString(R.string.share_room)
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .sizeIn(minHeight = 48.dp),
                                border = BorderStroke(1.dp, NeonSecondary.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = NeonSurface,
                                    contentColor = NeonSecondary
                                )
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.share_room),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Game mode badge
                item {
                    val isSuperMunchkin = gameState.settings.maxLevel >= 20
                    val modeColor = if (isSuperMunchkin) NeonSecondary else NeonCyan
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(modeColor.copy(alpha = 0.10f))
                            .border(1.dp, modeColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(vertical = 6.dp, horizontal = 16.dp)
                    ) {
                        Text(
                            text = if (isSuperMunchkin)
                                stringResource(R.string.mode_badge_super)
                            else
                                stringResource(R.string.mode_badge_normal),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = modeColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
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
                            style = MaterialTheme.typography.titleMedium,
                            color = NeonGray100,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NeonPrimary.copy(alpha = 0.12f))
                                .border(1.dp, NeonPrimary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${gameState.players.size}/6",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val prevPlayer = gameState.playerList[index - 1]
                                            onSwapPlayers(player.playerId, prevPlayer.playerId)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.move_player_up),
                                        tint = if (index > 0) NeonPrimary
                                               else NeonGray500.copy(alpha = 0.3f)
                                    )
                                }
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonGray400
                                )
                                IconButton(
                                    onClick = {
                                        if (index < gameState.playerList.size - 1) {
                                            val nextPlayer = gameState.playerList[index + 1]
                                            onSwapPlayers(player.playerId, nextPlayer.playerId)
                                        }
                                    },
                                    enabled = index < gameState.playerList.size - 1,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.move_player_down),
                                        tint = if (index < gameState.playerList.size - 1) NeonPrimary
                                               else NeonGray500.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }

                        // Player card
                        PlayerCard(
                            player = player,
                            isMe = player.playerId == myPlayerId,
                            isHost = player.playerId == gameState.hostId,
                            showDisconnectedBadge = true,
                            showStats = false,
                            modifier = Modifier.weight(1f),
                            onKickPlayer = null,
                            actions = {
                                if (player.lastRoll != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val isTied = gameState.hasRollTie &&
                                            gameState.tiedPlayerIds.contains(player.playerId)
                                    if (isTied) {
                                        val rerollDesc = stringResource(R.string.reroll_dice, player.lastRoll)
                                        Button(
                                            onClick = onRollDice,
                                            enabled = player.playerId == myPlayerId,
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                .semantics { contentDescription = rerollDesc },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NeonError,
                                                contentColor = NeonWhite
                                            )
                                        ) {
                                            Text("🎲 ${player.lastRoll} ⟳", fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        val rollDisplay = if (player.lastRoll == 0) "🎲 -" else "🎲 ${player.lastRoll}"
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(NeonSecondary.copy(alpha = 0.15f))
                                                .border(1.dp, NeonSecondary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = rollDisplay,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (player.lastRoll == 0) NeonGray400 else NeonSecondary
                                            )
                                        }
                                    }
                                } else if (player.playerId == myPlayerId) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Brush.linearGradient(GradientNeonPurple))
                                            .clickable(onClick = onRollDice)
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.roll_dice),
                                            fontWeight = FontWeight.Bold,
                                            color = NeonWhite,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }

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

                // Tie-breaking message
                if (gameState.hasRollTie) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(NeonError.copy(alpha = 0.10f))
                                .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.lobby_tie_message),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonError
                            )
                        }
                    }
                }

                // Waiting message
                if (!gameState.canStart) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(GlassBase)
                                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            val waitingText = when {
                                gameState.players.size < 2 -> stringResource(R.string.waiting_players)
                                !gameState.allPlayersRolled -> stringResource(R.string.lobby_waiting_rolls)
                                else -> stringResource(R.string.waiting_players)
                            }
                            Text(
                                text = waitingText,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGray400
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // Leave confirmation dialog
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
}
