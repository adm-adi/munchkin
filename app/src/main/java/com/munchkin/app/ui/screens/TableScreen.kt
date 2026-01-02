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
    onPlayerClick: (PlayerId) -> Unit,
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
            
            PlayerAvatarNode(
                player = player,
                isMe = isMe,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(LocalDensity.current) { offsetX.toDp() },
                        y = with(LocalDensity.current) { offsetY.toDp() }
                    )
                    .clickable { onPlayerClick(player.playerId) }
            )
        }
    }
}

@Composable
fun PlayerAvatarNode(
    player: PlayerState,
    isMe: Boolean,
    modifier: Modifier = Modifier
) {
    val avatarSize = 80.dp
    val color = getAvatarColor(player.avatarId)
    val drawableId = AvatarResources.getAvatarDrawable(player.avatarId)
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(Color.White) // Per user request: White background
                .border(if (isMe) 4.dp else 2.dp, if (isMe) MaterialTheme.colorScheme.primary else color, CircleShape)
        ) {
            Image(
                painter = painterResource(id = drawableId),
                contentDescription = player.name,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit
            )
            
            // Combat Strength Badge
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
            text = "Lvl ${player.level}",
            style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.secondary
        )
    }
}
