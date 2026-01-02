package com.munchkin.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.ui.theme.AvatarResources
import com.munchkin.app.ui.theme.getAvatarColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual Dungeon/Table View.
 * Displays players sitting around a table.
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        
        // Center of the table
        val cx = width / 2
        val cy = height / 2
        
        // Radius of the circle ( leave space for avatars)
        val radius = minOf(width, height) / 2 * 0.7f
        
        // Sort players to stabilize order (e.g. by join order or ID)
        // Ideally rotation should put "Me" at the bottom
        val sortedPlayers = players.sortedBy { it.name } // Simple sort for now
        
        val anglePerPlayer = 360f / players.size.coerceAtLeast(1)
        
        // Table Background (Wood or Rug?)
        Box(
            modifier = Modifier
                .size((radius * 2 * 0.8).dp) // Slightly smaller than player circle
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
        ) {
            Text(
                text = "Munchkin\nDungeon",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
        }

        players.forEachIndexed { index, player ->
            // Calculate position
            // Start from -90 degrees (top) or 90 (bottom)
            // Let's put index 0 at bottom (90 deg) if we want? 
            // Standard circle starts at 0 (Right). 
            // -90 is Top.
            val angleDeg = index * anglePerPlayer - 90
            val angleRad = Math.toRadians(angleDeg.toDouble())
            
            // Layout offsets need to be calculated in px then converted to dp/offset
            // This is easier with a custom Layout or just absolute offsets in a Box
            
            // We can use a simplified approach:
            // Box aligned center, then offset
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
                    .then(
                        Modifier.clickable { onPlayerClick(player.playerId) }
                    )
            )
        }
        
        // End Turn Button
        if (onEndTurn != null) {
            androidx.compose.material3.Button(
                onClick = onEndTurn,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp), // Avoid potentially overlapping FAB if it was center (it is End)
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Terminar Turno")
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
    val turnColor = MaterialTheme.colorScheme.tertiary
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(if (isTurn) turnColor.copy(alpha = 0.2f) else Color.White)
                .border(
                    width = when {
                        isTurn -> 4.dp
                        isMe -> 3.dp
                        else -> 2.dp
                    },
                    color = when {
                        isTurn -> turnColor
                        isMe -> MaterialTheme.colorScheme.primary
                        else -> color
                    },
                    shape = CircleShape
                )
        ) {
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = player.name,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit
            )
            
            // Combat Strength Badge (TopEnd)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
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
                    .background(MaterialTheme.colorScheme.primary)
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

            // Gender Badge (BottomStart) - Read Only
            val genderSymbol = when (player.gender) {
                com.munchkin.app.core.Gender.M -> "♂"
                com.munchkin.app.core.Gender.F -> "♀"
                com.munchkin.app.core.Gender.NA -> "Ø"
            }
            val genderColor = when (player.gender) {
                com.munchkin.app.core.Gender.M -> Color(0xFF42A5F5) // Blue
                com.munchkin.app.core.Gender.F -> Color(0xFFEC407A) // Pink
                com.munchkin.app.core.Gender.NA -> com.munchkin.app.ui.theme.NeonGray400
            }
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-4).dp, y = (-4).dp) // Slight offset to not cover avatar too much
                    .clip(CircleShape)
                    .background(genderColor.copy(alpha = 0.9f))
                    .size(20.dp)
                    .border(1.dp, Color.White, CircleShape)
            ) {
                Text(
                    text = genderSymbol,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Name & Level
        Text(
            text = player.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = "Nivel ${player.level} • Fuerza ${player.level + player.gearBonus}",
            style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.secondary
        )
    }
}
