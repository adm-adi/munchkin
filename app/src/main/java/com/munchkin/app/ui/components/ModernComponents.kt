package com.munchkin.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.DungeonDangerFill
import com.munchkin.app.ui.theme.GlassBase
import com.munchkin.app.ui.theme.GlassBorder
import com.munchkin.app.ui.theme.GradientNeonPurple
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import com.munchkin.app.ui.theme.NeonWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Dungeon Edition dark stone surface card with optional subtle ambient border glow.
 * Glow halos are rendered with GPU strokes rather than per-frame Paint shadow allocations.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = GlassBorder,
    containerColor: Color = GlassBase,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val resolvedContainer = if (containerColor == GlassBase) NeonSurface.copy(alpha = 0.92f) else containerColor
    val resolvedBorder = if (borderColor == GlassBorder) DungeonBorder else borderColor

    val glowModifier = if (glowColor != null) {
        Modifier.drawBehind {
            val radiusPx = cornerRadius.toPx()
            drawRoundRect(
                color = glowColor.copy(alpha = 0.14f),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = 6.dp.toPx())
            )
        }
    } else {
        Modifier
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .then(glowModifier)
            .clip(shape)
            .then(clickModifier),
        shape = shape,
        color = resolvedContainer,
        border = BorderStroke(1.dp, resolvedBorder)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * Entrance animation wrapper with staggered delay.
 */
@Composable
fun EntranceAnimation(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 360)) +
            slideInVertically(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                initialOffsetY = { it / 6 }
            )
    ) {
        content()
    }
}

/**
 * Primary Dungeon Edition gradient button with deferred press-scale animation.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    gradientColors: List<Color> = GradientNeonPurple
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "gradientButtonScale"
    )

    val shape = RoundedCornerShape(16.dp)
    val brush = if (enabled) {
        Brush.horizontalGradient(colors = gradientColors)
    } else {
        Brush.horizontalGradient(colors = listOf(NeonSurfaceVariant, NeonSurfaceVariant))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(brush = brush, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) NeonGray100 else NeonGray300,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) NeonGray100 else NeonGray300,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Outlined secondary action button styled for Dungeon Edition stone surfaces.
 */
@Composable
fun GlassOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = NeonGray100
) {
    val resolvedColor = if (enabled) color else NeonGray300
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) resolvedColor.copy(alpha = 0.55f) else DungeonBorder
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = NeonSurfaceVariant.copy(alpha = 0.65f),
            contentColor = resolvedColor,
            disabledContainerColor = NeonSurfaceVariant.copy(alpha = 0.35f),
            disabledContentColor = NeonGray300
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Styled text input field using Dungeon Edition surface & high-contrast parchment ink tokens.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = NeonGray100),
        label = label?.let { { Text(text = it, color = NeonGray300) } },
        placeholder = placeholder?.let { { Text(text = it, color = NeonGray300.copy(alpha = 0.8f)) } },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = NeonGray300
                )
            }
        },
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = NeonSurfaceVariant,
            unfocusedContainerColor = NeonSurfaceVariant,
            disabledContainerColor = NeonSurface,
            focusedBorderColor = NeonPrimaryLight,
            unfocusedBorderColor = DungeonBorder,
            disabledBorderColor = DungeonBorder.copy(alpha = 0.5f),
            focusedTextColor = NeonGray100,
            unfocusedTextColor = NeonGray100,
            disabledTextColor = NeonGray300,
            cursorColor = NeonPrimaryLight
        )
    )
}

/**
 * Top bar for sub-screens with accessible >=48dp navigation touch target.
 */
@Composable
fun GlassTopAppBar(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = NeonSurface,
        border = BorderStroke(1.dp, DungeonBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (navigationIcon != null && onNavigationClick != null) {
                IconButton(
                    onClick = onNavigationClick,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = navigationIcon,
                        contentDescription = stringResource(R.string.back),
                        tint = NeonGray100
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NeonGray100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/**
 * Subtle dungeon divider accent line.
 */
@Composable
fun NeonDivider(
    modifier: Modifier = Modifier,
    color: Color = NeonPrimary
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = color.copy(alpha = 0.28f)
    )
}

/**
 * Subtle radial torch/dungeon background glow. Animated scale is deferred strictly
 * to graphicsLayer so parent layouts never recompose on frame ticks.
 */
@Composable
fun AmbientOrb(
    modifier: Modifier = Modifier,
    color: Color = NeonPrimary,
    size: Dp = 300.dp,
    alpha: Float = 0.12f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambientOrb")
    val pulseState = infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientOrbPulse"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = pulseState.value
                scaleX = scale
                scaleY = scale
            }
            .drawWithCache {
                val brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent)
                )
                onDrawBehind {
                    drawRect(brush = brush)
                }
            }
    )
}

/**
 * Isolated turn countdown badge. Keeps its 1-second timer tick strictly inside this
 * composable so parent Board/Table screens do not recompose every second.
 */
@Composable
fun TurnCountdownBadge(
    turnStartedAt: Long?,
    timerSeconds: Int,
    serverTimeOffsetMs: Long,
    modifier: Modifier = Modifier,
    turnEndsAt: Long? = null
) {
    if (timerSeconds <= 0) return
    val deadlineMs = turnEndsAt ?: turnStartedAt?.plus(timerSeconds * 1000L) ?: return

    var remainingSeconds by remember(deadlineMs, serverTimeOffsetMs) {
        mutableIntStateOf(computeRemainingSeconds(deadlineMs, serverTimeOffsetMs))
    }

    LaunchedEffect(turnStartedAt, timerSeconds, serverTimeOffsetMs, turnEndsAt) {
        while (isActive) {
            val next = computeRemainingSeconds(deadlineMs, serverTimeOffsetMs)
            if (next != remainingSeconds) {
                remainingSeconds = next
            }
            if (next <= 0) break
            delay(250L)
        }
    }

    val isExpired = remainingSeconds == 0
    val isLow = remainingSeconds in 1..15
    val containerColor = when {
        isExpired -> DungeonDangerFill
        isLow -> NeonWarning.copy(alpha = 0.18f)
        else -> NeonSurfaceVariant
    }
    val contentColor = when {
        isExpired -> NeonGray100
        isLow -> NeonWarning
        else -> NeonGray200
    }
    val borderColor = when {
        isExpired -> NeonError
        isLow -> NeonWarning
        else -> DungeonBorder
    }

    val mins = remainingSeconds / 60
    val secs = remainingSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    val badgeDescription = stringResource(R.string.cd_turn_timer, formattedTime)

    Surface(
        modifier = modifier.semantics { contentDescription = badgeDescription },
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = contentColor
            )
        }
    }
}

private fun computeRemainingSeconds(deadlineMs: Long, serverTimeOffsetMs: Long): Int {
    val now = System.currentTimeMillis() + serverTimeOffsetMs
    val diffMs = deadlineMs - now
    return if (diffMs <= 0L) 0 else ((diffMs + 999L) / 1000L).toInt()
}
