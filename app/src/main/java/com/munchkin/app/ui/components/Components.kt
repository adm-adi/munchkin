package com.munchkin.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerState
import com.munchkin.app.ui.theme.*

/**
 * Large counter component for level/gear with animated number changes.
 */
@Composable
fun CounterButton(
    value: Int,
    label: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    minValue: Int? = null,
    maxValue: Int? = null,
    showSign: Boolean = false,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    
    // Shake animation when at limit
    var shouldShake by remember { mutableStateOf(false) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (shouldShake) 10f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        finishedListener = { shouldShake = false },
        label = "shake"
    )
    
    // Scale animation on value change
    var lastValue by remember { mutableIntStateOf(value) }
    val scale by animateFloatAsState(
        targetValue = if (value != lastValue) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    
    LaunchedEffect(value) {
        lastValue = value
    }
    
    val displayValue = if (showSign && value >= 0) "+$value" else value.toString()
    
    Column(
        modifier = modifier
            .graphicsLayer { translationX = shakeOffset },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Decrement button
            FilledIconButton(
                enabled = enabled,
                onClick = {
                    if (minValue != null && value <= minValue) {
                        shouldShake = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDecrement()
                    }
                },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrementar",
                    modifier = Modifier.size(28.dp)
                )
            }
            
            // Value display
            Text(
                text = displayValue,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp
                ),
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Increment button
            FilledIconButton(
                enabled = enabled,
                onClick = {
                    if (maxValue != null && value >= maxValue) {
                        shouldShake = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onIncrement()
                    }
                },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Incrementar",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Player avatar circle with initial or image.
 */
@Composable
fun PlayerAvatar(
    player: PlayerState,
    modifier: Modifier = Modifier,
    size: Int = 48,
    showBorder: Boolean = false
) {
    val backgroundColor = getAvatarColor(player.avatarId)
    val initial = player.name.firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glow ring when border shown
        if (showBorder) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(NeonPrimary.copy(alpha = 0.35f), Color.Transparent)
                        ),
                        CircleShape
                    )
            )
        }
        Box(
            modifier = Modifier
                .size((size - 2).dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(backgroundColor, backgroundColor.copy(alpha = 0.7f))
                    )
                )
                .then(
                    if (showBorder) Modifier.border(2.dp, NeonPrimary.copy(alpha = 0.8f), CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = (size / 2).sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }
    }
}

/**
 * Compact player card for board view.
 */
@Composable
fun PlayerCard(
    player: PlayerState,
    isMe: Boolean,
    isHost: Boolean,
    isTurn: Boolean = false,
    showDisconnectedBadge: Boolean = true,
    showStats: Boolean = true,
    onToggleGender: (() -> Unit)? = null,
    onKickPlayer: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable (RowScope.() -> Unit) = {}
) {
    val accentColor = when {
        isTurn -> NeonWarning
        isMe -> NeonPrimary
        isHost -> NeonSecondary
        else -> NeonGray500
    }
    val borderColor by animateColorAsState(
        targetValue = accentColor,
        label = "borderColor"
    )

    // Pulsing glow for current turn player
    val glowAlpha = if (isTurn) {
        val transition = rememberInfiniteTransition(label = "turnGlow")
        transition.animateFloat(
            initialValue = 0.2f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse
            ),
            label = "turnGlowAlpha"
        ).value
    } else 0f

    val containerBg = when {
        isTurn -> NeonWarning.copy(alpha = 0.07f)
        isMe -> NeonPrimary.copy(alpha = 0.06f)
        else -> GlassBase
    }
    val borderWidth = if (isTurn || isMe) 1.5.dp else 1.dp
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isTurn) Modifier.drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().also { p ->
                            p.asFrameworkPaint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.TRANSPARENT
                                setShadowLayer(14.dp.toPx(), 0f, 0f,
                                    NeonWarning.copy(alpha = glowAlpha).toArgb())
                            }
                        }
                        canvas.drawRoundRect(0f, 0f, size.width, size.height,
                            16.dp.toPx(), 16.dp.toPx(), paint)
                    }
                } else Modifier
            )
            .clip(shape)
            .background(containerBg)
            .border(
                width = borderWidth,
                brush = Brush.linearGradient(
                    listOf(borderColor.copy(alpha = if (isTurn) glowAlpha else 0.6f),
                           borderColor.copy(alpha = 0.2f))
                ),
                shape = shape
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // Glass highlight overlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.05f),
                        0.5f to Color.Transparent
                    )
                )
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!player.isConnected && showDisconnectedBadge) Modifier.graphicsLayer { alpha = 0.55f } else Modifier)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with glow for current player
                Box {
                    if (isMe) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    com.munchkin.app.ui.theme.NeonPrimary.copy(alpha = 0.2f),
                                    CircleShape
                                )
                        )
                    }
                    PlayerAvatar(
                        player = player,
                        size = 48,
                        showBorder = isMe
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Name and traits
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = player.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                            color = com.munchkin.app.ui.theme.NeonGray100,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isHost) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "👑",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (isMe) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = com.munchkin.app.ui.theme.NeonPrimary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "TÚ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.munchkin.app.ui.theme.NeonPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Gender Icon (Clickable for self)
                        val genderSymbol = when (player.gender) {
                            Gender.M -> "♂"
                            Gender.F -> "♀"
                            Gender.NA -> "Ø"
                        }
                        val genderColor = when (player.gender) {
                            Gender.M -> Color(0xFF42A5F5) // Blue
                            Gender.F -> Color(0xFFEC407A) // Pink
                            Gender.NA -> com.munchkin.app.ui.theme.NeonGray400
                        }
                        
                        Surface(
                            shape = CircleShape,
                            color = genderColor.copy(alpha = 0.1f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(if (isMe && onToggleGender != null) Modifier.clickable { onToggleGender() } else Modifier)
                        ) {
                            Text(
                                text = genderSymbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = genderColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (isTurn) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = com.munchkin.app.ui.theme.NeonWarning.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "TURNO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.munchkin.app.ui.theme.NeonWarning,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (!player.isConnected && showDisconnectedBadge) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.player_disconnected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                    
                    // Stats and Actions column
                Column(horizontalAlignment = Alignment.End) {
                    // Stats Row (Level & Power) - only shown in game, not lobby
                    if (showStats) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Level
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = com.munchkin.app.ui.theme.NeonPrimary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Nivel ${player.level}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = com.munchkin.app.ui.theme.NeonPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Power
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = com.munchkin.app.ui.theme.NeonGray500.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Fuerza ${player.combatPower}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = com.munchkin.app.ui.theme.NeonGray100,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                
                    // Custom Actions
                    Row {
                        actions()
                    }

                    // Kick button (host only, disconnected players)
                    if (!player.isConnected && showDisconnectedBadge && onKickPlayer != null) {
                        var showKickConfirm by remember { mutableStateOf(false) }
                        IconButton(onClick = { showKickConfirm = true }) {
                            Icon(
                                Icons.Default.PersonRemove,
                                contentDescription = stringResource(R.string.kick_player),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        if (showKickConfirm) {
                            AlertDialog(
                                onDismissRequest = { showKickConfirm = false },
                                title = { Text(stringResource(R.string.kick_player)) },
                                text = { Text(stringResource(R.string.confirm_kick, player.name)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showKickConfirm = false
                                        onKickPlayer()
                                    }) {
                                        Text(
                                            stringResource(R.string.kick_player),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showKickConfirm = false }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                    }
                }
            }


        }
    }
}

/**
 * Trait chip for race/class display.
 */
@Composable
fun TraitChip(
    name: String,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    InputChip(
        selected = true,
        onClick = { onRemove?.invoke() },
        label = { Text(name) },
        modifier = modifier,
        trailingIcon = if (onRemove != null) {
            {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Quitar",
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null
    )
}

/**
 * Combat result banner with animation.
 */
@Composable
fun CombatResultBanner(
    isWin: Boolean,
    difference: Int,
    marginToWin: Int = difference, // Default to diff if not provided (for backward compat)
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isWin) 
            MaterialTheme.colorScheme.tertiary
        else 
            MaterialTheme.colorScheme.error,
        animationSpec = tween(300),
        label = "bannerColor"
    )
    
    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isWin) "¡VICTORIA!" else "DERROTA",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isWin) 
                    "Ganas por $difference" 
                else 
                    "Te faltan $marginToWin para ganar",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun QuickModifierButtons(
    onModify: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = listOf(1, 2, 3, 5, 10)
    
    Column(modifier = modifier.fillMaxWidth()) {
        // Negative Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            values.asReversed().forEach { amount ->
                CompactButton(
                    text = "-$amount",
                    onClick = { onModify(-amount) },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Positive Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            values.forEach { amount ->
                CompactButton(
                    text = "+$amount",
                    onClick = { onModify(amount) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun CompactButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color
) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        modifier = Modifier
            .widthIn(min = 40.dp)
            .height(36.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
