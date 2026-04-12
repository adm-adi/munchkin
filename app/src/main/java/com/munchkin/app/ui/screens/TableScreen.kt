package com.munchkin.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.ui.theme.AvatarResources
import com.munchkin.app.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual Dungeon/Table View.
 * Displays players sitting around a table with neon glass aesthetics.
 */
@Composable
fun TableScreen(
    players: List<PlayerState>,
    currentUser: PlayerId?,
    turnPlayerId: PlayerId? = null,
    onPlayerClick: (PlayerId) -> Unit,
    onEndTurn: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
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
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        val cx = width / 2
        val cy = height / 2
        val radius = minOf(width, height) / 2 * 0.68f

        val anglePerPlayer = 360f / players.size.coerceAtLeast(1)

        // Turn indicator banner at top
        val turnPlayer = players.find { it.playerId == turnPlayerId }
        if (turnPlayer != null) {
            val isMyTurn = turnPlayer.playerId == currentUser
            val bannerColor = if (isMyTurn) NeonPrimary else NeonGray500

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isMyTurn) NeonPrimary.copy(alpha = 0.12f)
                        else GlassBase
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(bannerColor.copy(alpha = 0.6f), bannerColor.copy(alpha = 0.1f))
                        ),
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isMyTurn) {
                        Text(text = "⏳", fontSize = 16.sp)
                    }
                    Text(
                        text = if (isMyTurn) "¡Es tu turno!" else "Turno de ${turnPlayer.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isMyTurn) NeonGray100 else NeonGray300
                    )
                }
            }
        }

        // Table ring (neon border circle)
        Box(
            modifier = Modifier
                .size(with(LocalDensity.current) { (radius * 2 * 0.78f).toDp() })
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            NeonPrimary.copy(alpha = 0.04f),
                            NeonBackground.copy(alpha = 0.6f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            NeonPrimary.copy(alpha = 0.5f),
                            NeonSecondary.copy(alpha = 0.3f),
                            NeonCyan.copy(alpha = 0.4f),
                            NeonPrimary.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Text(
                text = "Munchkin",
                modifier = Modifier.align(Alignment.Center),
                color = NeonPrimary.copy(alpha = 0.18f),
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }

        // Player nodes around the table
        players.forEachIndexed { index, player ->
            val angleDeg = index * anglePerPlayer - 90
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val offsetX = (radius * cos(angleRad)).toInt()
            val offsetY = (radius * sin(angleRad)).toInt()

            val isMe = player.playerId == currentUser
            val isTurn = player.playerId == turnPlayerId

            PlayerAvatarNode(
                player = player,
                isMe = isMe,
                isTurn = isTurn,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(LocalDensity.current) { offsetX.toDp() },
                        y = with(LocalDensity.current) { offsetY.toDp() }
                    )
                    .clickable { onPlayerClick(player.playerId) }
            )
        }

        // End Turn Button
        if (onEndTurn != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(GradientViridian))
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable { onEndTurn() }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.end_turn),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
fun PlayerAvatarNode(
    player: PlayerState,
    isMe: Boolean,
    isTurn: Boolean = false,
    modifier: Modifier = Modifier
) {
    val avatarSize = 80.dp
    val color = getAvatarColor(player.avatarId)
    val drawableId = AvatarResources.getAvatarDrawable(player.avatarId)

    // Pulsing glow for current turn
    val glowAlpha = if (isTurn) {
        val transition = rememberInfiniteTransition(label = "turnGlow")
        transition.animateFloat(
            initialValue = 0.3f, targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse
            ),
            label = "glowAlpha"
        ).value
    } else 0f

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
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .then(
                    if (isTurn) Modifier.drawBehind {
                        drawIntoCanvas { canvas ->
                            val paint = Paint().also { p ->
                                p.asFrameworkPaint().apply {
                                    isAntiAlias = true
                                    this.color = android.graphics.Color.TRANSPARENT
                                    setShadowLayer(
                                        14.dp.toPx(), 0f, 0f,
                                        NeonWarning.copy(alpha = glowAlpha).toArgb()
                                    )
                                }
                            }
                            canvas.drawCircle(
                                androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
                                size.width / 2,
                                paint
                            )
                        }
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    if (isTurn) NeonWarning.copy(alpha = 0.12f)
                    else if (isMe) NeonPrimary.copy(alpha = 0.10f)
                    else Color.White.copy(alpha = 0.05f)
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

            // Combat Strength Badge (TopEnd)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(NeonError)
                    .size(24.dp)
            ) {
                Text(
                    text = "${player.level + player.gearBonus}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Level Badge (TopStart)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(CircleShape)
                    .background(NeonPrimary)
                    .size(24.dp)
            ) {
                Text(
                    text = "${player.level}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Gender Badge (BottomStart)
            val genderSymbol = when (player.gender) {
                com.munchkin.app.core.Gender.M -> "♂"
                com.munchkin.app.core.Gender.F -> "♀"
                com.munchkin.app.core.Gender.NA -> "Ø"
            }
            val genderColor = when (player.gender) {
                com.munchkin.app.core.Gender.M -> Color(0xFF42A5F5)
                com.munchkin.app.core.Gender.F -> Color(0xFFEC407A)
                com.munchkin.app.core.Gender.NA -> NeonGray400
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-4).dp, y = (-4).dp)
                    .clip(CircleShape)
                    .background(genderColor.copy(alpha = 0.9f))
                    .size(20.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            ) {
                Text(
                    text = genderSymbol,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Disconnected overlay
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
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Name with turn highlight
        Text(
            text = player.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isTurn || isMe) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isTurn -> NeonWarning
                isMe -> NeonPrimary
                else -> NeonGray200
            },
            maxLines = 1
        )

        Text(
            text = "Nv ${player.level} • ⚔ ${player.level + player.gearBonus}",
            style = MaterialTheme.typography.bodySmall,
            color = NeonGray400
        )
    }
}
