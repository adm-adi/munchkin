package com.munchkin.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.ui.components.TurnCountdownBadge
import com.munchkin.app.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual Dungeon/Table View.
 * Displays players seated around a responsive elliptical table using Dungeon Edition tokens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableScreen(
    players: List<PlayerState>,
    currentUser: PlayerId?,
    onPlayerClick: (PlayerId) -> Unit,
    modifier: Modifier = Modifier,
    turnPlayerId: PlayerId? = null,
    timerSeconds: Int = 0,
    turnEndsAt: Long? = null,
    serverTimeOffsetMs: Long = 0L,
    isHost: Boolean = false,
    onEndTurn: (() -> Unit)? = null,
    onKickPlayer: ((PlayerId) -> Unit)? = null,
    onTransferHost: ((PlayerId) -> Unit)? = null
) {
    var showHostRosterDialog by remember { mutableStateOf(false) }
    var hostActionTarget by remember { mutableStateOf<PlayerState?>(null) }
    var confirmTransferTarget by remember { mutableStateOf<PlayerState?>(null) }
    var confirmKickTarget by remember { mutableStateOf<PlayerState?>(null) }

    val turnStartedAt = remember(turnEndsAt, timerSeconds) {
        if (turnEndsAt != null && timerSeconds > 0) turnEndsAt - timerSeconds * 1000L else null
    }
    val canHostManage = isHost &&
        (onTransferHost != null || onKickPlayer != null) &&
        players.any { it.playerId != currentUser }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        NeonSurface.copy(alpha = 0.8f),
                        NeonBackground
                    ),
                    radius = 1200f
                )
            )
    ) {
        val turnPlayer = players.find { it.playerId == turnPlayerId }
        val isCompact = maxHeight < 460.dp || maxWidth < 340.dp
        val avatarSize = if (isCompact) 56.dp else 72.dp
        val nodeHalfWidth = if (isCompact) 44.dp else 52.dp
        val nodeHalfHeight = avatarSize / 2 + 22.dp

        val topReserve = when {
            turnPlayer != null && !turnPlayer.isConnected -> 96.dp
            turnPlayer != null -> 76.dp
            else -> 20.dp
        }
        val bottomReserve = if (onEndTurn != null) 88.dp else 72.dp
        val centerOffsetY = (topReserve - bottomReserve) / 2

        val availHalfW = ((maxWidth / 2) - nodeHalfWidth - 12.dp).coerceAtLeast(48.dp)
        val availHalfH = (((maxHeight - topReserve - bottomReserve) / 2) - nodeHalfHeight).coerceAtLeast(36.dp)
        val radiusX = availHalfW * 0.92f
        val radiusY = availHalfH * 0.90f

        val anglePerPlayer = 360f / players.size.coerceAtLeast(1)

        // Turn indicator banner at top
        if (turnPlayer != null) {
            val isMyTurn = turnPlayer.playerId == currentUser
            val bannerColor = if (isMyTurn) NeonPrimary else NeonGray500

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 16.dp, end = if (canHostManage) 64.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isMyTurn) NeonPrimary.copy(alpha = 0.14f)
                            else GlassBase
                        )
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(bannerColor.copy(alpha = 0.6f), bannerColor.copy(alpha = 0.15f))
                            ),
                            RoundedCornerShape(14.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isMyTurn) {
                            Icon(
                                Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = NeonGray300,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = if (isMyTurn) stringResource(R.string.your_turn)
                            else stringResource(R.string.player_turn, turnPlayer.name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isMyTurn) NeonGray100 else NeonGray300,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (turnStartedAt != null && timerSeconds > 0) {
                            TurnCountdownBadge(
                                turnStartedAt = turnStartedAt,
                                timerSeconds = timerSeconds,
                                serverTimeOffsetMs = serverTimeOffsetMs
                            )
                        }
                    }
                }
                if (!turnPlayer.isConnected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(NeonWarning.copy(alpha = 0.12f))
                            .border(1.dp, NeonWarning.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.waiting_for_player, turnPlayer.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonWarning
                        )
                    }
                }
            }
        }

        // Visible host management button (avoids tiny buttons on each node)
        if (canHostManage) {
            IconButton(
                onClick = { showHostRosterDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Default.ManageAccounts,
                    contentDescription = stringResource(R.string.host_actions),
                    tint = NeonSecondary
                )
            }
        }

        // Responsive elliptical table surface
        val tableWidth = (radiusX * 1.38f).coerceAtLeast(100.dp)
        val tableHeight = (radiusY * 1.28f).coerceAtLeast(72.dp)
        Box(
            modifier = Modifier
                .width(tableWidth)
                .height(tableHeight)
                .align(Alignment.Center)
                .offset(y = centerOffsetY)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            NeonPrimary.copy(alpha = 0.05f),
                            NeonBackground.copy(alpha = 0.65f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            NeonPrimary.copy(alpha = 0.5f),
                            NeonSecondary.copy(alpha = 0.35f),
                            NeonCyan.copy(alpha = 0.4f),
                            NeonPrimary.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.align(Alignment.Center),
                color = NeonPrimary.copy(alpha = 0.20f),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Player nodes positioned dynamically around the ellipse
        players.forEachIndexed { index, player ->
            val angleDeg = index * anglePerPlayer - 90
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val xOffset = radiusX * cos(angleRad).toFloat()
            val yOffset = centerOffsetY + radiusY * sin(angleRad).toFloat()

            val isMe = player.playerId == currentUser
            val isTurn = player.playerId == turnPlayerId

            PlayerAvatarNode(
                player = player,
                isMe = isMe,
                isTurn = isTurn,
                avatarSize = avatarSize,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = xOffset, y = yOffset)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .combinedClickable(
                        onClick = { onPlayerClick(player.playerId) },
                        onLongClick = {
                            if (canHostManage && !isMe) {
                                hostActionTarget = player
                            }
                        }
                    )
                    .padding(4.dp)
            )
        }

        // End Turn Button (>= 48dp touch target, safe padding clear of FAB)
        if (onEndTurn != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 24.dp, start = 20.dp)
                    .navigationBarsPadding()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(GradientViridian))
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable(onClick = onEndTurn)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.end_turn),
                    color = NeonWhite,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }

    // Host roster chooser dialog
    if (showHostRosterDialog) {
        val otherPlayers = players.filter { it.playerId != currentUser }
        AlertDialog(
            onDismissRequest = { showHostRosterDialog = false },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.host_actions), color = NeonGray100) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    otherPlayers.forEach { target ->
                        Surface(
                            onClick = {
                                showHostRosterDialog = false
                                hostActionTarget = target
                            },
                            color = NeonSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GlassBorderDim),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = target.name,
                                    color = NeonGray100,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.table_player_stats,
                                        target.level,
                                        target.combatPower
                                    ),
                                    color = NeonGray300,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showHostRosterDialog = false },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.close), color = NeonGray300)
                }
            }
        )
    }

    // Single player host action chooser
    hostActionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { hostActionTarget = null },
            containerColor = NeonSurface,
            title = { Text(target.name, color = NeonGray100) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onTransferHost != null) {
                        OutlinedButton(
                            onClick = {
                                hostActionTarget = null
                                confirmTransferTarget = target
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp),
                            border = BorderStroke(1.dp, NeonSecondary.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonSecondary)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.transfer_host))
                        }
                    }
                    if (onKickPlayer != null) {
                        OutlinedButton(
                            onClick = {
                                hostActionTarget = null
                                confirmKickTarget = target
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp),
                            border = BorderStroke(1.dp, NeonError.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonError)
                        ) {
                            Icon(Icons.Default.PersonRemove, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.kick_player))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { hostActionTarget = null },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray300)
                }
            }
        )
    }

    confirmTransferTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTransferTarget = null },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.transfer_host), color = NeonGray100) },
            text = { Text(stringResource(R.string.confirm_transfer_host, target.name), color = NeonGray300) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTransferTarget = null
                        onTransferHost?.invoke(target.playerId)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.confirm_action), color = NeonSecondary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmTransferTarget = null },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray400)
                }
            }
        )
    }

    confirmKickTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmKickTarget = null },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.kick_player), color = NeonGray100) },
            text = { Text(stringResource(R.string.confirm_kick, target.name), color = NeonGray300) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmKickTarget = null
                        onKickPlayer?.invoke(target.playerId)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.confirm_action), color = NeonError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmKickTarget = null },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray400)
                }
            }
        )
    }
}

@Composable
fun PlayerAvatarNode(
    player: PlayerState,
    isMe: Boolean,
    modifier: Modifier = Modifier,
    isTurn: Boolean = false,
    avatarSize: Dp = 72.dp
) {
    val color = getAvatarColor(player.avatarId)
    val drawableId = AvatarResources.getAvatarDrawable(player.avatarId, player.gender == Gender.F)

    val glowAlphaState = if (isTurn) {
        val transition = rememberInfiniteTransition(label = "turnGlow")
        transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(
                tween(1000, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )
    } else null

    val borderColor = when {
        isTurn -> NeonWarning
        isMe -> NeonPrimary
        else -> color
    }
    val borderWidth = when {
        isTurn -> 3.dp
        isMe -> 2.dp
        else -> 1.5.dp
    }

    Column(
        modifier = modifier.widthIn(max = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .then(
                    if (isTurn && glowAlphaState != null) Modifier.drawBehind {
                        val alpha = glowAlphaState.value
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.width / 2f
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    NeonWarning.copy(alpha = alpha * 0.65f),
                                    NeonWarning.copy(alpha = alpha * 0.2f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = baseRadius + 14.dp.toPx()
                            ),
                            radius = baseRadius + 14.dp.toPx(),
                            center = center
                        )
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    when {
                        isTurn -> NeonWarning.copy(alpha = 0.12f)
                        isMe -> NeonPrimary.copy(alpha = 0.10f)
                        else -> Color.White.copy(alpha = 0.05f)
                    }
                )
                .border(borderWidth, borderColor, CircleShape)
        ) {
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = player.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )

            if (!player.isConnected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = stringResource(R.string.player_disconnected),
                        tint = NeonWhite,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = player.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isTurn || isMe) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isTurn -> NeonWarning
                isMe -> NeonPrimary
                else -> NeonGray200
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Uses combatPower (which folds in tempCombatBonus) consistently across views.
        Text(
            text = stringResource(R.string.table_player_stats, player.level, player.combatPower),
            style = MaterialTheme.typography.bodySmall,
            color = NeonGray400,
            maxLines = 1
        )
    }
}
