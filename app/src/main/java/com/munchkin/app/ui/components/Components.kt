package com.munchkin.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.core.PlayerState
import com.munchkin.app.ui.theme.AvatarResources
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.DungeonDangerFill
import com.munchkin.app.ui.theme.DungeonSuccessFill
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import com.munchkin.app.ui.theme.NeonWarning
import com.munchkin.app.ui.theme.getAvatarColor

/**
 * Animated counter button for Level / Gear / Modifiers with >=48dp touch targets
 * and deferred graphicsLayer animations.
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

    var shouldShake by remember { mutableStateOf(false) }
    val shakeOffsetState = animateFloatAsState(
        targetValue = if (shouldShake) 10f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        finishedListener = { shouldShake = false },
        label = "counterShake"
    )

    var lastValue by remember { mutableIntStateOf(value) }
    val scaleState = animateFloatAsState(
        targetValue = if (value != lastValue) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "counterScale"
    )

    LaunchedEffect(value) {
        lastValue = value
    }

    val displayValue = if (showSign && value >= 0) "+$value" else value.toString()
    val canDecrement = enabled && (minValue == null || value > minValue)
    val canIncrement = enabled && (maxValue == null || value < maxValue)

    Column(
        modifier = modifier.graphicsLayer { translationX = shakeOffsetState.value },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NeonGray300
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = {
                    if (canDecrement) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDecrement()
                    } else {
                        shouldShake = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (canDecrement) NeonSurfaceVariant else NeonSurface,
                    contentColor = if (canDecrement) NeonGray100 else NeonGray300.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.cd_decrement, label)
                )
            }

            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                    .graphicsLayer {
                        val s = scaleState.value
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeonSurfaceVariant)
                    .border(1.dp, DungeonBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = NeonGray100
                )
            }

            FilledTonalIconButton(
                onClick = {
                    if (canIncrement) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onIncrement()
                    } else {
                        shouldShake = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (canIncrement) NeonSurfaceVariant else NeonSurface,
                    contentColor = if (canIncrement) NeonGray100 else NeonGray300.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_increment, label)
                )
            }
        }
    }
}

/**
 * Circular player avatar rendered from canonical class/race drawable resources.
 */
@Composable
fun PlayerAvatar(
    player: PlayerState,
    modifier: Modifier = Modifier,
    size: Int = 48,
    showBorder: Boolean = false
) {
    val backgroundColor = getAvatarColor(player.avatarId)
    val drawableResId: Int = remember(player.avatarId, player.gender) {
        AvatarResources.getAvatarDrawable(player.avatarId, player.gender == Gender.F)
    }

    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
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
                    if (showBorder) Modifier.border(2.dp, NeonPrimaryLight.copy(alpha = 0.85f), CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = drawableResId),
                contentDescription = player.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * Player summary card used across Table, Board, and Lobby screens.
 * Turn glow animation is strictly deferred to drawBehind with zero Paint shadow allocations.
 */
@Composable
fun PlayerCard(
    player: PlayerState,
    isMe: Boolean,
    isHost: Boolean,
    modifier: Modifier = Modifier,
    isTurn: Boolean = false,
    showDisconnectedBadge: Boolean = true,
    showStats: Boolean = true,
    onToggleGender: (() -> Unit)? = null,
    onKickPlayer: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {}
) {
    val accentColor = when {
        isTurn -> NeonWarning
        isMe -> NeonPrimaryLight
        isHost -> NeonSecondary
        else -> DungeonBorder
    }
    val borderColor by animateColorAsState(
        targetValue = accentColor,
        label = "playerCardBorderColor"
    )

    val glowAlphaState = if (isTurn) {
        val transition = rememberInfiniteTransition(label = "turnGlow")
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "turnGlowAlpha"
        )
    } else {
        null
    }

    val containerBg = when {
        isTurn -> NeonSurfaceVariant
        isMe -> NeonSurface
        else -> NeonSurface.copy(alpha = 0.92f)
    }
    val borderWidth = if (isTurn || isMe) 1.5.dp else 1.dp
    val shape = RoundedCornerShape(16.dp)
    val hostDescription = stringResource(R.string.host)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowAlphaState != null) {
                    Modifier.drawBehind {
                        val a = glowAlphaState.value
                        val r = 16.dp.toPx()
                        drawRoundRect(
                            color = NeonWarning.copy(alpha = a * 0.22f),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = 8.dp.toPx())
                        )
                        drawRoundRect(
                            color = NeonWarning.copy(alpha = a * 0.35f),
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(containerBg)
            .border(
                width = borderWidth,
                color = if (isTurn) borderColor.copy(alpha = 0.85f) else borderColor,
                shape = shape
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!player.isConnected && showDisconnectedBadge) {
                        Modifier.graphicsLayer { alpha = 0.6f }
                    } else {
                        Modifier
                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAvatar(
                player = player,
                size = 48,
                showBorder = isMe
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = player.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Medium,
                        color = NeonGray100,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isHost) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "👑",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { contentDescription = hostDescription }
                        )
                    }
                    if (isMe) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonPrimary
                        ) {
                            Text(
                                text = stringResource(R.string.you),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonGray100,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val genderSymbol = when (player.gender) {
                        Gender.M -> "♂"
                        Gender.F -> "♀"
                        Gender.NA -> "Ø"
                    }
                    val genderLabel = when (player.gender) {
                        Gender.M -> stringResource(R.string.gender_male)
                        Gender.F -> stringResource(R.string.gender_female)
                        Gender.NA -> stringResource(R.string.gender_na)
                    }
                    val genderColor = when (player.gender) {
                        Gender.M -> Color(0xFF64B5F6)
                        Gender.F -> Color(0xFFF48FB1)
                        Gender.NA -> NeonGray300
                    }
                    val canToggleGender = isMe && onToggleGender != null
                    val genderCd = stringResource(R.string.cd_toggle_gender, genderLabel)

                    Box(
                        modifier = Modifier
                            .then(
                                if (canToggleGender) {
                                    Modifier
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            role = Role.Button,
                                            onClick = { onToggleGender?.invoke() }
                                        )
                                } else {
                                    Modifier
                                }
                            )
                            .semantics { contentDescription = genderCd },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = genderColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = genderSymbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = genderColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (isTurn || (!player.isConnected && showDisconnectedBadge)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isTurn) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = NeonSecondary
                            ) {
                                Text(
                                    text = stringResource(R.string.turn_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonSurface,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (!player.isConnected && showDisconnectedBadge) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.player_disconnected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (showStats) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonSurfaceVariant,
                            border = BorderStroke(1.dp, DungeonBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.player_level_format, player.level),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = NeonPrimaryLight,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonSurfaceVariant,
                            border = BorderStroke(1.dp, DungeonBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.player_power_format, player.combatPower),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = NeonGray100,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row {
                    actions()
                }

                if (!player.isConnected && showDisconnectedBadge && onKickPlayer != null) {
                    var showKickConfirm by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showKickConfirm = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonRemove,
                            contentDescription = stringResource(R.string.kick_player),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    if (showKickConfirm) {
                        AlertDialog(
                            onDismissRequest = { showKickConfirm = false },
                            containerColor = NeonSurface,
                            titleContentColor = NeonGray100,
                            textContentColor = NeonGray200,
                            title = { Text(stringResource(R.string.kick_player)) },
                            text = { Text(stringResource(R.string.confirm_kick, player.name)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showKickConfirm = false
                                        onKickPlayer()
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.kick_player),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showKickConfirm = false },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.cancel),
                                        color = NeonGray300
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Trait chip for race/class display with localized removal contentDescription.
 */
@Composable
fun TraitChip(
    name: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null
) {
    InputChip(
        selected = true,
        onClick = { onRemove?.invoke() },
        label = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.then(if (onRemove != null) Modifier.heightIn(min = 48.dp) else Modifier),
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = NeonSurfaceVariant,
            selectedLabelColor = NeonGray100,
            selectedTrailingIconColor = NeonGray300
        ),
        border = InputChipDefaults.inputChipBorder(
            enabled = true,
            selected = true,
            selectedBorderColor = DungeonBorder
        ),
        trailingIcon = if (onRemove != null) {
            {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove),
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        }
    )
}

/**
 * Combat result banner indicating current hero/monster margin with WCAG AA contrast.
 */
@Composable
fun CombatResultBanner(
    isWin: Boolean,
    difference: Int,
    modifier: Modifier = Modifier,
    marginToWin: Int = difference
) {
    val containerColor by animateColorAsState(
        targetValue = if (isWin) DungeonSuccessFill else DungeonDangerFill,
        label = "combatBannerBg"
    )
    val contentColor = NeonGray100

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isWin) {
                    stringResource(R.string.result_win)
                } else {
                    stringResource(R.string.result_lose)
                },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = contentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isWin) {
                    stringResource(R.string.win_by, kotlin.math.abs(difference))
                } else {
                    stringResource(R.string.combat_margin_to_win, kotlin.math.abs(marginToWin))
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = contentColor
            )
        }
    }
}

/**
 * Quick modifier +/- button strip with >=48dp touch height and localized accessibility labels.
 */
@Composable
fun QuickModifierButtons(
    onModify: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = listOf(1, 2, 3, 5, 10)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            values.reversed().forEach { v ->
                CompactModifierButton(
                    text = "-$v",
                    delta = -v,
                    onClick = { onModify(-v) },
                    containerColor = DungeonDangerFill,
                    contentColor = NeonGray100,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            values.forEach { v ->
                CompactModifierButton(
                    text = "+$v",
                    delta = v,
                    onClick = { onModify(v) },
                    containerColor = DungeonSuccessFill,
                    contentColor = NeonGray100,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CompactModifierButton(
    text: String,
    delta: Int,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val cd = stringResource(R.string.cd_modify_modifier, delta)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = cd },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1
        )
    }
}
