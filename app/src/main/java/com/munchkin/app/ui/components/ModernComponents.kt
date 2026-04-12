package com.munchkin.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// GLASS CARD — core glass-morphism surface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Glass-morphism card with optional neon glow, gradient border, and inner highlight.
 * Simulates glass without blur (compatible with all API levels).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = GlassBorder,
    containerColor: Color = GlassBase,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    // Animated glow intensity (only computed when glowColor != null)
    val glowAlpha = if (glowColor != null) {
        val transition = rememberInfiniteTransition(label = "glow")
        transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        ).value
    } else 0f

    // Press interaction for click feedback
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "pressScale"
    )

    Box(
        modifier = modifier
            .scale(pressScale)
            .then(
                if (glowColor != null) Modifier.drawBehind {
                    drawIntoCanvas { canvas ->
                        val glowPaint = Paint().also { p ->
                            p.asFrameworkPaint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.TRANSPARENT
                                setShadowLayer(
                                    20.dp.toPx(), 0f, 0f,
                                    glowColor.copy(alpha = glowAlpha).toArgb()
                                )
                            }
                        }
                        canvas.drawRoundRect(
                            left = 0f, top = 0f,
                            right = size.width, bottom = size.height,
                            radiusX = cornerRadius.toPx(),
                            radiusY = cornerRadius.toPx(),
                            paint = glowPaint
                        )
                    }
                } else Modifier
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick() }
                    else Modifier
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        0f to borderColor.copy(alpha = 0.75f),
                        0.5f to borderColor.copy(alpha = 0.35f),
                        1f to borderColor.copy(alpha = 0.15f)
                    ),
                    shape = shape
                ),
            shape = shape,
            color = containerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box {
                // Inner glass highlight — simulates light catching glass at top-left
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.07f),
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.06f)
                            )
                        )
                )
                Column(
                    modifier = Modifier.padding(16.dp),
                    content = content
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NEON GLOW CARD — pulsing colored border (for active/turn states)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeonGlowCard(
    modifier: Modifier = Modifier,
    glowColor: Color = NeonWarning,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = cornerRadius,
        borderColor = glowColor.copy(alpha = 0.7f),
        containerColor = glowColor.copy(alpha = 0.06f),
        glowColor = glowColor,
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ENTRANCE ANIMATION — fade + scale in, optionally staggered by index
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EntranceAnimation(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(350, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.95f)
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GRADIENT BUTTON — with press scale and animated shimmer
// ─────────────────────────────────────────────────────────────────────────────

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
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "btnScale"
    )
    val alpha = if (enabled) 1f else 0.45f

    // Shimmer offset animation
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing, delayMillis = 600),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                // Subtle outer glow on button
                drawIntoCanvas { canvas ->
                    if (enabled) {
                        val paint = Paint().also { p ->
                            p.asFrameworkPaint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.TRANSPARENT
                                setShadowLayer(
                                    12.dp.toPx(), 0f, 2.dp.toPx(),
                                    gradientColors.first().copy(alpha = 0.4f).toArgb()
                                )
                            }
                        }
                        canvas.drawRoundRect(0f, 0f, size.width, size.height,
                            16.dp.toPx(), 16.dp.toPx(), paint)
                    }
                }
            }
            .background(
                brush = Brush.horizontalGradient(gradientColors),
                alpha = alpha
            )
            .then(
                // Shimmer overlay
                Modifier.background(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        (shimmerOffset - 0.2f).coerceIn(0f, 1f) to Color.Transparent,
                        shimmerOffset.coerceIn(0f, 1f) to Color.White.copy(alpha = 0.18f),
                        (shimmerOffset + 0.2f).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent
                    ),
                    alpha = if (enabled) 1f else 0f
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLASS OUTLINED BUTTON
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = NeonGray100
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "outlinedBtnScale"
    )

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .height(56.dp)
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) color.copy(alpha = 0.45f) else color.copy(alpha = 0.2f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            containerColor = Color.White.copy(alpha = 0.04f)
        )
    ) {
        icon?.let {
            Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLASS TEXT FIELD
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = NeonGray500) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = NeonGray400) } },
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonPrimary,
            unfocusedBorderColor = GlassBorder,
            focusedLabelColor = NeonPrimary,
            unfocusedLabelColor = NeonGray400,
            cursorColor = NeonPrimary,
            focusedContainerColor = NeonPrimary.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContainerColor = Color.White.copy(alpha = 0.02f),
            disabledBorderColor = NeonGray500.copy(alpha = 0.3f),
            focusedTextColor = NeonGray100,
            unfocusedTextColor = NeonGray200
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// STAT COUNTER — animated stat display with glass card
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun StatCounter(
    value: Int,
    label: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = NeonPrimary,
    containerColor: Color = GlassBase,
    showSign: Boolean = false,
    minValue: Int = Int.MIN_VALUE,
    maxValue: Int = Int.MAX_VALUE
) {
    GlassCard(
        modifier = modifier,
        containerColor = containerColor,
        borderColor = accentColor.copy(alpha = 0.4f),
        glowColor = null
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = accentColor.copy(alpha = 0.8f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Decrement
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (value > minValue) Color.White.copy(alpha = 0.08f)
                            else Color.White.copy(alpha = 0.03f)
                        )
                        .border(1.dp, GlassBorderDim, CircleShape)
                        .clickable(enabled = value > minValue, onClick = onDecrement),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = null,
                        tint = if (value > minValue) NeonGray100 else NeonGray500
                    )
                }

                // Value
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInVertically { -it } + fadeIn() togetherWith
                                    slideOutVertically { it } + fadeOut()
                        } else {
                            slideInVertically { it } + fadeIn() togetherWith
                                    slideOutVertically { -it } + fadeOut()
                        }.using(SizeTransform(clip = false))
                    },
                    label = "value"
                ) { v ->
                    val display = if (showSign && v > 0) "+$v" else v.toString()
                    Text(
                        text = display,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }

                // Increment
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(listOf(accentColor, accentColor.copy(alpha = 0.7f)))
                        )
                        .clickable(enabled = value < maxValue, onClick = onIncrement),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = NeonGray100
        )
        action?.invoke()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TRAIT CHIP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TraitChip(
    name: String,
    onRemove: () -> Unit
) {
    Surface(
        color = NeonPrimary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonPrimary.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .clickable { onRemove() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, color = NeonGray100)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonGray300)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLASS TOP APP BAR
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                title,
                color = NeonGray100,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        navigationIcon = {
            if (navigationIcon != null && onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(navigationIcon, contentDescription = null, tint = NeonGray100)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = NeonGray100,
            actionIconContentColor = NeonGray400,
            navigationIconContentColor = NeonGray100
        ),
        modifier = Modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    NeonBackground.copy(alpha = 0.98f),
                    NeonBackground.copy(alpha = 0.6f),
                    Color.Transparent
                )
            )
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NEON DIVIDER — glowing horizontal divider
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeonDivider(
    modifier: Modifier = Modifier,
    color: Color = NeonPrimary
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.5f), Color.Transparent)
                )
            )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// AMBIENT ORB — decorative background glow orb
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AmbientOrb(
    modifier: Modifier = Modifier,
    color: Color = NeonPrimary,
    size: Dp = 300.dp,
    alpha: Float = 0.12f
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent)
                )
            )
    )
}
